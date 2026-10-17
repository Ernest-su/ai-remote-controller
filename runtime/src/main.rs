mod agent;
mod app_actions;
mod artifacts;
mod config;
mod discovery;
mod file_actions;
mod i18n;
mod media_publications;
mod media_resources;
mod platform_bridge;
mod remote_input;
mod screen_capture;
mod screen_mirror;
mod screen_preview;
mod service;
mod storage_analysis;
mod system_actions;
mod system_capabilities;
mod system_state;
mod tools;
mod transfers;
mod virtual_gamepad;

use anyhow::{bail, Context, Result};
use axum::{
    extract::{
        ws::{Message, WebSocket, WebSocketUpgrade},
        State,
    },
    http::HeaderMap,
    response::IntoResponse,
    routing::get,
    serve::ListenerExt,
    Json, Router,
};
use config::ModelConfig;
use futures_util::{SinkExt, StreamExt};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::{path::PathBuf, sync::Arc};
use tokio::sync::{Mutex, RwLock};

struct AppState {
    id: String,
    name: String,
    directory: PathBuf,
    binary_sha256: String,
    config: RwLock<ModelConfig>,
}

#[derive(Deserialize)]
struct Request {
    id: String,
    protocol: u32,
    #[serde(rename = "type")]
    kind: String,
    tool: Option<String>,
    text: Option<String>,
    #[serde(default)]
    progress: bool,
    #[serde(default)]
    arguments: Value,
}

#[derive(Serialize)]
struct Response {
    id: String,
    protocol: u32,
    ok: bool,
    result: Option<String>,
    error: Option<String>,
}

async fn dispatch(
    request: &Request,
    state: &AppState,
    history: &Mutex<Vec<Value>>,
    output: &tokio::sync::mpsc::Sender<Value>,
    owner: &str,
) -> Result<String> {
    if request.protocol != 1 {
        bail!("unsupported protocol");
    }
    let progress = |phase: &str, text: &str| {
        if !request.progress {
            return;
        }
        let mut event = json!({"id":request.id,"protocol":1,"type":"assistant.state","phase":phase,"text":if phase=="tool" {""} else {text}});
        if phase == "tool" && tools::TOOLS.contains(&text) {
            event["tool"] = json!(text);
        }
        let _ = output.try_send(event);
    };
    match request.kind.as_str() {
        "tool" => {
            tools::execute_owned(
                request.tool.as_deref().unwrap_or(""),
                &request.arguments,
                owner,
            )
            .await
        }
        "text" => {
            let mut history = history.lock().await;
            let config = state.config.read().await.clone();
            let result = agent::run(
                &config,
                &mut history,
                request.text.as_deref().unwrap_or(""),
                &progress,
                owner,
                request.arguments.get("allowedTools"),
            )
            .await;
            if result.is_err() {
                history.clear();
            }
            result
        }
        "approve_system" => crate::system_actions::approve(&request.arguments, owner).await,
        "approve_cache" => crate::app_actions::cache::approve(&request.arguments).await,
        "approve_install" => crate::app_actions::install::approve(&request.arguments).await,
        "approve_app_control" => crate::app_actions::control::approve(&request.arguments).await,
        "approve_app" => crate::app_actions::approve(&request.arguments).await,
        "approve" => crate::file_actions::approve(&request.arguments).await,
        "configure" => {
            let mut history = history.lock().await;
            let config: ModelConfig = serde_json::from_value(request.arguments.clone())?;
            config.save(&state.directory)?;
            *state.config.write().await = config;
            history.clear();
            Ok("模型配置已保存到目标设备".into())
        }
        _ => bail!("unsupported request type: {}", request.kind),
    }
}

async fn websocket(
    State(state): State<Arc<AppState>>,
    upgrade: WebSocketUpgrade,
    headers: HeaderMap,
) -> impl IntoResponse {
    let language = i18n::Language::from_accept_language(
        headers
            .get("accept-language")
            .and_then(|value| value.to_str().ok())
            .unwrap_or(""),
    );
    upgrade
        .max_message_size(1024 * 1024)
        .on_upgrade(move |socket| connection(socket, state, language))
}
async fn connection(socket: WebSocket, state: Arc<AppState>, language: i18n::Language) {
    // Keep reading control frames while a model/tool call is running, so ping/pong
    // does not time out during a long decision. All queues have bounded memory.
    let owner = uuid::Uuid::new_v4().to_string();
    let (mut writer, mut reader) = socket.split();
    let (requests, incoming) = tokio::sync::mpsc::channel(16);
    let history = Arc::new(Mutex::new(Vec::new()));
    let (controls, control_incoming) = tokio::sync::mpsc::channel(16);
    let (keys, key_incoming) = tokio::sync::mpsc::channel(16);
    let (output, mut responses) = tokio::sync::mpsc::channel(16);
    // Direct controls retain their own FIFO order and never wait for model execution.
    let control_worker = tokio::spawn(process_messages(
        control_incoming,
        output.clone(),
        state.clone(),
        history.clone(),
        owner.clone(),
        language,
    ));
    let worker = tokio::spawn(process_messages(
        incoming,
        output.clone(),
        state.clone(),
        history.clone(),
        owner.clone(),
        language,
    ));
    let key_worker = tokio::spawn(process_messages(
        key_incoming,
        output,
        state,
        history,
        owner.clone(),
        language,
    ));
    loop {
        tokio::select! {
            reply = responses.recv() => {
                let Some(reply) = reply else { break };
                if writer.send(Message::Text(serde_json::to_string(&reply).unwrap().into())).await.is_err() { break; }
            }
            frame = reader.next() => {
                match frame {
                    Some(Ok(Message::Ping(bytes))) => {
                        if writer.send(Message::Pong(bytes)).await.is_err() { break; }
                    }
                    Some(Ok(frame @ Message::Text(_))) => {
                        let (kind, id, tool) = match &frame {
                            Message::Text(text) => serde_json::from_str::<Request>(text).map(|request| (request.kind, request.id, request.tool)).unwrap_or_default(),
                            _ => (String::new(), String::new(), None),
                        };
                        let queue = if kind == "tool" && tool.as_deref() == Some("remote.key") { &keys }
                            else if kind == "tool" || kind == "approve" || kind == "approve_app" || kind == "approve_app_control" || kind == "approve_install" || kind == "approve_cache" || kind == "approve_system" { &controls } else { &requests };
                        match queue.try_send(frame) {
                            Ok(()) => {},
                            Err(tokio::sync::mpsc::error::TrySendError::Full(_)) => {
                                // Keep the bounded FIFO and control-frame reader alive.
                                // Reject only this request; never retry device actions automatically.
                                let reply = Response { id, protocol: 1, ok: false, result: None,
                                    error: Some(language.error("request queue full; wait for pending operations and retry")) };
                                if writer.send(Message::Text(serde_json::to_string(&reply).unwrap().into())).await.is_err() { break; }
                            },
                            Err(tokio::sync::mpsc::error::TrySendError::Closed(_)) => break,
                        }
                    }
                    Some(Ok(Message::Pong(_))) => {},
                    _ => break,
                }
            }
        }
    }
    worker.abort();
    control_worker.abort();
    key_worker.abort();
    let _ = worker.await;
    let _ = control_worker.await;
    let _ = key_worker.await;
    remote_input::disconnect(&owner).await;
    virtual_gamepad::disconnect(&owner);
    screen_mirror::disconnect(&owner);
    system_actions::disconnect(&owner);
}

async fn process_messages(
    mut incoming: tokio::sync::mpsc::Receiver<Message>,
    output: tokio::sync::mpsc::Sender<Value>,
    state: Arc<AppState>,
    history: Arc<Mutex<Vec<Value>>>,
    owner: String,
    language: i18n::Language,
) {
    while let Some(message) = incoming.recv().await {
        let response = match message {
            Message::Text(text) => match serde_json::from_str::<Request>(&text) {
                Ok(request) => {
                    let result = tokio::time::timeout(
                        std::time::Duration::from_secs(170),
                        dispatch(&request, &state, &history, &output, &owner),
                    )
                    .await
                    .unwrap_or_else(|_| Err(anyhow::anyhow!("request execution timed out")));
                    if result.is_err() && request.kind == "text" {
                        history.lock().await.clear();
                    }
                    match result {
                        Ok(result) => Response {
                            id: request.id,
                            protocol: 1,
                            ok: true,
                            result: Some(if request.kind == "tool" {
                                language.tool_result(request.tool.as_deref().unwrap_or(""), result)
                            } else {
                                result
                            }),
                            error: None,
                        },
                        Err(error) => Response {
                            id: request.id,
                            protocol: 1,
                            ok: false,
                            result: None,
                            error: Some(language.error(&error.to_string())),
                        },
                    }
                }
                Err(error) => Response {
                    id: "".into(),
                    protocol: 1,
                    ok: false,
                    result: None,
                    error: Some(language.error(&format!("invalid request: {error}"))),
                },
            },
            Message::Close(_) => break,
            _ => continue,
        };
        if output
            .send(serde_json::to_value(response).unwrap())
            .await
            .is_err()
        {
            break;
        }
    }
}
// Hash the running executable once, rather than a path that may later be replaced.
fn executable_sha256() -> Result<String> {
    use sha2::{Digest, Sha256};
    use std::io::Read;
    let mut file = std::fs::File::open("/proc/self/exe")?;
    let mut hash = Sha256::new();
    let mut buffer = [0u8; 64 * 1024];
    loop {
        let count = file.read(&mut buffer)?;
        if count == 0 {
            break;
        }
        hash.update(&buffer[..count]);
    }
    Ok(format!("{:x}", hash.finalize()))
}

async fn status(State(state): State<Arc<AppState>>) -> Json<Value> {
    let configured = state.config.read().await.validate().is_ok();
    Json(
        json!({"version":env!("CARGO_PKG_VERSION"),"binarySha256":state.binary_sha256,"protocol":1,"deviceId":state.id,"name":state.name,"tools":tools::TOOLS,"modelConfigured":configured,"textToolScope":true,"uid":unsafe { libc::geteuid() }}),
    )
}

async fn serve(directory: PathBuf, port: u16, enable_mdns: bool) -> Result<()> {
    let _running = service::Lock::acquire(&directory.join("running.lock"))?;
    let id = config::device_id(&directory)?;
    let name = std::process::Command::new("getprop")
        .arg("ro.product.model")
        .output()
        .ok()
        .filter(|output| output.status.success())
        .map(|output| String::from_utf8_lossy(&output.stdout).trim().to_string())
        .filter(|name| !name.is_empty())
        .unwrap_or_else(|| "AI Remote Device".into());
    if let Err(error) = tools::files::initialize(&directory) {
        eprintln!("file inbox unavailable: {error}");
    }
    if let Err(error) = platform_bridge::initialize(&directory) {
        eprintln!("Android framework bridge unavailable: {error}");
    }
    if let Err(error) = remote_input::initialize().await {
        eprintln!("persistent input bridge unavailable: {error:#}");
    }
    if let Err(error) = media_publications::initialize(&directory) {
        eprintln!("media publication recovery unavailable: {error}");
    }
    let state = Arc::new(AppState {
        id: id.clone(),
        binary_sha256: executable_sha256()?,
        name: name.clone(),
        config: RwLock::new(ModelConfig::load(&directory)?),
        directory: directory.clone(),
    });
    let listener = tokio::net::TcpListener::bind(("0.0.0.0", port)).await?;
    let actual_port = listener.local_addr()?.port();
    media_resources::initialize(actual_port);
    let _advertisement = if enable_mdns {
        Some(discovery::Advertisement::publish(&id, &name, actual_port)?)
    } else {
        None
    };
    service::Record::save(&directory, actual_port, id)?;
    virtual_gamepad::initialize();
    let _state_guard = service::StateGuard::new(directory);
    println!("airc-runtime listening on {}", listener.local_addr()?);
    let app = Router::new()
        .route("/status", get(status))
        .route("/artifacts/{id}", get(artifacts::download))
        .route("/preview/{id}", get(screen_preview::download))
        .route("/mirror/{id}", get(screen_mirror::download))
        .route("/media/{id}", get(media_resources::download))
        .route(
            "/transfers/{id}/data",
            get(transfers::download).put(transfers::upload),
        )
        .route("/transfers/{id}", axum::routing::delete(transfers::cancel))
        .route("/ws", get(websocket))
        .with_state(state);
    let listener = listener.tap_io(|stream| {
        if let Err(error) = stream.set_nodelay(true) {
            eprintln!("cannot disable input response coalescing: {error}");
        }
    });
    axum::serve(listener, app)
        .with_graceful_shutdown(async {
            let mut terminate =
                tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate())
                    .expect("SIGTERM handler");
            tokio::select! { _ = tokio::signal::ctrl_c() => {}, _ = terminate.recv() => {} }
            virtual_gamepad::shutdown();
            remote_input::shutdown().await;
        })
        .await?;
    tools::gamepad::drain().await;
    Ok(())
}

fn main() -> Result<()> {
    let mut args = std::env::args().skip(1);
    let mut action = None;
    let mut directory = std::env::current_exe()?
        .parent()
        .context("executable has no parent")?
        .join("state");
    let mut port = 9876u16;
    let mut mdns = true;
    while let Some(arg) = args.next() {
        match arg.as_str() {
            "--version" => {
                println!("{}", env!("CARGO_PKG_VERSION"));
                return Ok(());
            }
            "service" => action = Some(args.next().context("missing service operation")?),
            "serve" => {}
            "--state-dir" => {
                directory = PathBuf::from(args.next().context("missing state directory")?)
            }
            "--port" => port = args.next().context("missing port")?.parse()?,
            "--no-mdns" => mdns = false,
            _ => bail!("unknown argument: {arg}"),
        }
    }
    config::prepare_directory(&directory)?;
    if let Some(action) = action {
        return service::control(&action, &directory, port);
    }
    tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()?
        .block_on(serve(directory, port, mdns))
}
