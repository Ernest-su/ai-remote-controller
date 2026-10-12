use anyhow::{bail, Context, Result};
use axum::{
    body::{Body, Bytes},
    extract::Path,
    http::{header, StatusCode},
    response::{IntoResponse, Response},
};
use futures_util::stream;
mod producer;
use producer::buffered_stream;
use serde_json::{json, Value};
use std::{
    process::Stdio,
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc, Mutex, OnceLock,
    },
    time::{Duration, Instant},
};
use tokio::{
    io::AsyncReadExt,
    process::{Child, ChildStdout, Command},
    sync::{Notify, OwnedSemaphorePermit, Semaphore, SemaphorePermit},
};
const MAGIC: &[u8; 8] = b"AIRCMIR1";
#[derive(Clone)]
struct Settings {
    width: u32,
    height: u32,
    bitrate: u32,
    duration: u32,
}
fn settings(args: &Value) -> Result<Settings> {
    let object = args
        .as_object()
        .context("mirror arguments must be object")?;
    if object
        .keys()
        .any(|key| !["width", "height", "bitrate", "durationSeconds"].contains(&key.as_str()))
    {
        bail!("only width, height, bitrate and durationSeconds accepted");
    }
    let number = |key, default| {
        args.get(key)
            .map(|v| {
                v.as_u64()
                    .context("mirror values must be positive integers")
            })
            .transpose()
            .map(|v| v.unwrap_or(default))
    };
    let width = number("width", 640)?;
    let height = number("height", 360)?;
    let bitrate = number("bitrate", 2000000)?;
    let duration = number("durationSeconds", 60)?;
    if !(160..=1280).contains(&width)
        || !(90..=1280).contains(&height)
        || width % 2 != 0
        || height % 2 != 0
        || width * height > 921600
        || !(250000..=8000000).contains(&bitrate)
        || !(10..=120).contains(&duration)
    {
        bail!("mirror settings outside limits");
    }
    Ok(Settings {
        width: width as u32,
        height: height as u32,
        bitrate: bitrate as u32,
        duration: duration as u32,
    })
}
struct Lease {
    id: String,
    owner: String,
    created: Instant,
    settings: Settings,
    claimed: AtomicBool,
    canceled: AtomicBool,
    notify: Notify,
    _slot: OwnedSemaphorePermit,
}
static SLOT: OnceLock<Arc<Semaphore>> = OnceLock::new();
static CURRENT: Mutex<Option<Arc<Lease>>> = Mutex::new(None);
fn cancel(lease: &Lease) {
    lease.canceled.store(true, Ordering::SeqCst);
    lease.notify.notify_one();
}
pub fn disconnect(owner: &str) {
    let mut current = CURRENT.lock().unwrap();
    if current.as_ref().is_some_and(|lease| lease.owner == owner) {
        cancel(current.as_ref().unwrap());
        current.take();
    }
}
pub fn execute(tool: &str, args: &Value, owner: &str) -> Result<String> {
    if owner.is_empty() {
        bail!("mirror connection owner required");
    }
    if !matches!(tool, "screen.mirror_start" | "screen.mirror_stop") {
        bail!("unknown mirror tool");
    }
    if tool == "screen.mirror_stop" {
        let object = args
            .as_object()
            .context("mirror stop arguments must be object")?;
        if object.len() != 1 {
            bail!("only id accepted");
        }
        let id = args["id"].as_str().context("id required")?;
        uuid::Uuid::parse_str(id)?;
        let mut current = CURRENT.lock().unwrap();
        if current
            .as_ref()
            .is_some_and(|v| v.id == id && v.owner != owner)
        {
            bail!("mirror stream belongs to another connection");
        }
        let stopped = current
            .as_ref()
            .is_some_and(|v| v.id == id && v.owner == owner);
        if stopped {
            cancel(current.as_ref().unwrap());
            current.take();
        }
        return Ok(json!({"id":id,"stopped":stopped}).to_string());
    }
    let config = settings(args)?;
    let mut current = CURRENT.lock().unwrap();
    if current.as_ref().is_some_and(|v| {
        v.created.elapsed() >= Duration::from_secs(30 + v.settings.duration as u64)
            || (!v.claimed.load(Ordering::SeqCst) && v.created.elapsed() >= Duration::from_secs(30))
    }) {
        cancel(current.as_ref().unwrap());
        current.take();
    }
    let slot = SLOT
        .get_or_init(|| Arc::new(Semaphore::new(1)))
        .clone()
        .try_acquire_owned()
        .context("mirror stream already reserved or active")?;
    let id = uuid::Uuid::new_v4().to_string();
    let value = json!({"id":id,"path":format!("/mirror/{id}"),"mimeType":"application/x-airc-avc","format":"airc-avc-v1","width":config.width,"height":config.height,"bitrate":config.bitrate,"durationSeconds":config.duration,"claimWithinSeconds":30,"maxChunkBytes":65536,"maxStreamBytes":134217728,"audio":false});
    *current = Some(Arc::new(Lease {
        id,
        owner: owner.into(),
        created: Instant::now(),
        settings: config,
        claimed: AtomicBool::new(false),
        canceled: AtomicBool::new(false),
        notify: Notify::new(),
        _slot: slot,
    }));
    Ok(value.to_string())
}
struct Reader {
    lease: Arc<Lease>,
    child: Child,
    stdout: ChildStdout,
    stderr: Option<tokio::task::JoinHandle<std::io::Result<Vec<u8>>>>,
    _group: crate::tools::ProcessGroup,
    _capture: SemaphorePermit<'static>,
    started: Instant,
    bytes: u64,
    header: bool,
    ended: bool,
}
impl Drop for Reader {
    fn drop(&mut self) {
        cancel(&self.lease);
        if let Some(task) = &self.stderr {
            task.abort();
        }
        let mut current = CURRENT.lock().unwrap();
        if current.as_ref().is_some_and(|v| v.id == self.lease.id) {
            current.take();
        }
    }
}
fn record(kind: u32, payload: &[u8]) -> Bytes {
    let mut output = Vec::with_capacity(8 + payload.len());
    output.extend_from_slice(&kind.to_be_bytes());
    output.extend_from_slice(&(payload.len() as u32).to_be_bytes());
    output.extend_from_slice(payload);
    output.into()
}
pub async fn download(Path(id): Path<String>) -> Response {
    let capture = match crate::screen_capture::reserve() {
        Ok(v) => v,
        Err(_) => return StatusCode::CONFLICT.into_response(),
    };
    let lease = {
        let current = CURRENT.lock().unwrap();
        match current
            .as_ref()
            .filter(|v| v.id == id && !v.canceled.load(Ordering::SeqCst))
        {
            Some(v)
                if v.created.elapsed() < Duration::from_secs(30)
                    && !v.claimed.swap(true, Ordering::SeqCst) =>
            {
                v.clone()
            }
            Some(_) => return StatusCode::CONFLICT.into_response(),
            None => return StatusCode::NOT_FOUND.into_response(),
        }
    };
    let config = &lease.settings;
    let spawned = Command::new("screenrecord")
        .args([
            "--output-format=h264",
            "--size",
            &format!("{}x{}", config.width, config.height),
            "--bit-rate",
            &config.bitrate.to_string(),
            "--time-limit",
            &config.duration.to_string(),
            "-",
        ])
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .process_group(0)
        .kill_on_drop(true)
        .spawn();
    let mut child = match spawned {
        Ok(v) => v,
        Err(_) => {
            cancel(&lease);
            CURRENT.lock().unwrap().take();
            return StatusCode::SERVICE_UNAVAILABLE.into_response();
        }
    };
    let group = crate::tools::ProcessGroup(child.id().unwrap() as i32);
    let stdout = child.stdout.take().unwrap();
    let mut stderr = child.stderr.take().unwrap();
    let error = tokio::spawn(async move {
        let mut block = vec![0u8; 1024];
        let mut output = vec![];
        loop {
            let count = stderr.read(&mut block).await?;
            if count == 0 {
                break;
            }
            let keep = count.min(8192usize.saturating_sub(output.len()));
            output.extend_from_slice(&block[..keep]);
        }
        Ok::<_, std::io::Error>(output)
    });
    let reader = Reader {
        lease,
        child,
        stdout,
        stderr: Some(error),
        _group: group,
        _capture: capture,
        started: Instant::now(),
        bytes: 0,
        header: false,
        ended: false,
    };
    let producer_lease = reader.lease.clone();
    let chunks = stream::unfold(reader, |mut reader| async move {
        if reader.ended {
            return None;
        }
        if !reader.header {
            reader.header = true;
            return Some((Ok::<_, std::io::Error>(Bytes::from_static(MAGIC)), reader));
        }
        let mut block = vec![0u8; 65536];
        let deadline = reader.started
            + if reader.bytes == 0 {
                Duration::from_secs(10)
            } else {
                Duration::from_secs(reader.lease.settings.duration as u64 + 5)
            };
        let read = tokio::select! {_ = reader.lease.notify.notified()=>None,_ = tokio::time::sleep_until(deadline.into())=>Some(Err(std::io::Error::new(std::io::ErrorKind::TimedOut,"screen encoder timed out"))),value=reader.stdout.read(&mut block)=>Some(value)};
        let output = match read {
            None => {
                reader.ended = true;
                record(0, &[])
            }
            Some(Ok(0)) => {
                reader.ended = true;
                let status =
                    tokio::time::timeout(Duration::from_secs(2), reader.child.wait()).await;
                if matches!(status,Ok(Ok(status)) if status.success()) && reader.bytes > 0 {
                    record(0, &[])
                } else {
                    let error = if let Some(task) = reader.stderr.take() {
                        match tokio::time::timeout(Duration::from_secs(1), task).await {
                            Ok(Ok(Ok(v))) => String::from_utf8_lossy(&v)
                                .chars()
                                .take(256)
                                .collect::<String>(),
                            _ => String::new(),
                        }
                    } else {
                        String::new()
                    };
                    record(2, format!("screen encoder failed: {error}").as_bytes())
                }
            }
            Some(Ok(count)) => {
                reader.bytes += count as u64;
                if reader.bytes > 134217728 {
                    reader.ended = true;
                    record(2, b"encoded stream exceeds limit")
                } else {
                    record(1, &block[..count])
                }
            }
            Some(Err(error)) => {
                reader.ended = true;
                record(
                    2,
                    error
                        .to_string()
                        .chars()
                        .take(256)
                        .collect::<String>()
                        .as_bytes(),
                )
            }
        };
        Some((Ok(output), reader))
    });
    let chunks = buffered_stream(chunks, producer_lease);
    (
        [
            (header::CONTENT_TYPE, "application/x-airc-avc"),
            (header::CACHE_CONTROL, "no-store"),
            (header::X_CONTENT_TYPE_OPTIONS, "nosniff"),
        ],
        Body::from_stream(chunks),
    )
        .into_response()
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn connection_ownership_disconnect_and_reader_retention_are_enforced() {
        assert!(execute("screen.mirror_start", &json!({}), "").is_err());
        let ticket: Value =
            serde_json::from_str(&execute("screen.mirror_start", &json!({}), "first").unwrap())
                .unwrap();
        let args = json!({"id": ticket["id"]});
        assert!(execute("screen.mirror_stop", &args, "other").is_err());
        disconnect("other");
        let retained = CURRENT.lock().unwrap().as_ref().unwrap().clone();
        assert!(!retained.canceled.load(Ordering::SeqCst));
        disconnect("first");
        assert!(retained.canceled.load(Ordering::SeqCst));
        assert!(execute("screen.mirror_start", &json!({}), "second").is_err());
        drop(retained);
        let next: Value =
            serde_json::from_str(&execute("screen.mirror_start", &json!({}), "second").unwrap())
                .unwrap();
        assert_ne!(ticket["id"], next["id"]);
        assert!(
            execute("screen.mirror_stop", &json!({"id": next["id"]}), "second")
                .unwrap()
                .contains("true")
        );
    }
    #[test]
    fn encoder_parameters_are_bounded_and_never_shell_strings() {
        assert!(settings(&json!({})).is_ok());
        for value in [
            json!({"width":641}),
            json!({"width":1280,"height":1280}),
            json!({"durationSeconds":0}),
            json!({"output":"/sdcard/user.mp4"}),
            json!({"bitrate":8000001}),
        ] {
            assert!(settings(&value).is_err());
        }
        assert_eq!(&record(1, b"abc")[..8], &[0, 0, 0, 1, 0, 0, 0, 3]);
    }
}
