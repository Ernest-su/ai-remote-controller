//! Bounded input actor with connection-owned holds and independent bridge deadman release.
use anyhow::{bail, Context, Result};
use serde_json::{json, Value};
use std::{
    collections::{BTreeMap, BTreeSet},
    ffi::OsString,
    path::PathBuf,
    process::Stdio,
    sync::OnceLock,
    time::{Duration, Instant},
};
use tokio::{
    io::{AsyncBufReadExt, AsyncWriteExt, BufReader},
    process::{Child, ChildStdin, ChildStdout, Command},
    sync::{mpsc, oneshot, watch},
};

const BUSY: &str = "request queue full; wait for pending operations and retry";
const DEADLINE: Duration = Duration::from_secs(3);
static INPUT: OnceLock<Manager> = OnceLock::new();

#[derive(Clone)]
struct Spec {
    program: OsString,
    args: Vec<OsString>,
    classpath: Option<PathBuf>,
}
struct Bridge {
    child: Child,
    stdin: ChildStdin,
    stdout: BufReader<ChildStdout>,
    _group: crate::tools::ProcessGroup,
}
impl Bridge {
    async fn start(spec: &Spec) -> Result<Self> {
        let mut command = Command::new(&spec.program);
        command.args(&spec.args);
        if let Some(path) = &spec.classpath {
            command.env("CLASSPATH", path);
        }
        let mut child = command
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::null())
            .process_group(0)
            .kill_on_drop(true)
            .spawn()
            .context("cannot start persistent input bridge")?;
        let pid = child.id().context("input bridge missing PID")?;
        let mut bridge = Self {
            stdin: child.stdin.take().context("input bridge missing stdin")?,
            stdout: BufReader::new(child.stdout.take().context("input bridge missing stdout")?),
            child,
            _group: crate::tools::ProcessGroup(pid as i32),
        };
        let ready = tokio::time::timeout(DEADLINE, bridge.read_frame())
            .await
            .context("input bridge startup timed out")??;
        if ready["ready"] != true
            || ready["pid"].as_u64() != Some(u64::from(pid))
            || ready["uid"].as_u64() != Some(u64::from(unsafe { libc::geteuid() }))
        {
            bail!("input bridge identity/readiness mismatch: {ready}");
        }
        Ok(bridge)
    }
    async fn read_frame(&mut self) -> Result<Value> {
        let mut bytes = Vec::new();
        loop {
            let available = self.stdout.fill_buf().await?;
            if available.is_empty() {
                bail!("input bridge exited before acknowledgement");
            }
            let count = available
                .iter()
                .position(|byte| *byte == b'\n')
                .map_or(available.len(), |position| position + 1);
            if bytes.len() + count > 2048 {
                bail!("input bridge acknowledgement too large");
            }
            bytes.extend_from_slice(&available[..count]);
            self.stdout.consume(count);
            if bytes.last() == Some(&b'\n') {
                return serde_json::from_slice(&bytes)
                    .context("invalid input bridge acknowledgement");
            }
        }
    }
    async fn request(&mut self, sequence: u64, operation: &str, code: u32) -> Result<()> {
        self.request_edge(sequence, operation, code, None).await
    }
    async fn request_edge(
        &mut self,
        sequence: u64,
        operation: &str,
        code: u32,
        press_id: Option<&str>,
    ) -> Result<()> {
        let bytes = format!(
            "{}\n",
            json!({"id":sequence,"operation":operation,"code":code,"pressId":press_id})
        );
        tokio::time::timeout(DEADLINE, async {
            self.stdin.write_all(bytes.as_bytes()).await?;
            self.stdin.flush().await?;
            let reply = self.read_frame().await?;
            if reply["id"].as_u64() != Some(sequence)
                || reply["ok"] != true
                || reply["released"] != matches!(operation, "key" | "release" | "up" | "cancel")
            {
                bail!("input bridge rejected or did not release key: {reply}");
            }
            Ok(())
        })
        .await
        .context("input bridge acknowledgement timed out; key outcome uncertain")?
    }
    async fn stop(self) {
        // EOF lets Java release owned keys. Hard termination is only the bounded fallback.
        let Self {
            mut child,
            stdin,
            stdout: _stdout,
            _group,
        } = self;
        drop(stdin);
        if tokio::time::timeout(Duration::from_millis(500), child.wait())
            .await
            .is_err()
        {
            let _ = child.kill().await;
            let _ = child.wait().await;
        }
    }
}

#[derive(Clone, Debug)]
struct Hold {
    owner: String,
    press_id: String,
    code: u32,
    action: String,
}
enum WorkKind {
    Warmup,
    Tap(u32),
    Hold(Hold),
    Disconnect(String),
}
struct Work {
    kind: WorkKind,
    reply: oneshot::Sender<Result<String>>,
}
struct Lease {
    owner: String,
    press_id: String,
    touched: Instant,
}
struct Manager {
    sender: mpsc::Sender<Work>,
    shutdown: watch::Sender<bool>,
    finished: watch::Receiver<bool>,
}
fn hold_reply(hold: &Hold) -> String {
    json!({"operation":hold.action,"keyCode":hold.code,"pressId":hold.press_id,
        "submitted":true,"released":matches!(hold.action.as_str(), "up" | "cancel"),"source":257})
    .to_string()
}
impl Manager {
    fn start(spec: Spec) -> Self {
        let (sender, mut incoming) = mpsc::channel::<Work>(16);
        let (shutdown, mut stop) = watch::channel(false);
        let (finished_sender, finished) = watch::channel(false);
        tokio::spawn(async move {
            let mut bridge: Option<Bridge> = None;
            let mut sequence = 0;
            let mut uncertain = BTreeSet::new();
            let mut leases: BTreeMap<u32, Lease> = BTreeMap::new();
            let mut timer = tokio::time::interval(Duration::from_millis(100));
            loop {
                let work = tokio::select! {
                    biased;
                    _ = stop.changed() => break,
                    _ = timer.tick() => {
                        let expired: Vec<_> = leases.iter().filter(|(_, lease)| lease.touched.elapsed() >= Duration::from_millis(1500))
                            .map(|(code, _)| *code).collect();
                        for code in expired {
                            let lease = leases.remove(&code).unwrap();
                            if let Some(child) = bridge.as_mut() {
                                sequence += 1;
                                if child.request_edge(sequence, "cancel", code, Some(&lease.press_id)).await.is_err() {
                                    uncertain.insert(code);
                                }
                            }
                        }
                        continue;
                    },
                    next = incoming.recv() => match next { Some(work) => work, None => break },
                };
                // Releases are accepted even when their caller goes away; DOWN is not.
                let terminal = match &work.kind {
                    WorkKind::Disconnect(_) => true,
                    WorkKind::Hold(hold) => matches!(hold.action.as_str(), "up" | "cancel"),
                    _ => false,
                };
                if work.reply.is_closed() && !terminal {
                    continue;
                }
                if let WorkKind::Tap(code) = &work.kind {
                    if leases.contains_key(code) {
                        let _ = work
                            .reply
                            .send(Err(anyhow::anyhow!("remote key already held")));
                        continue;
                    }
                }
                if let WorkKind::Hold(hold) = &work.kind {
                    let active = leases.get(&hold.code);
                    let valid = if hold.action == "down" {
                        active.is_none()
                    } else {
                        active.is_some_and(|lease| {
                            lease.owner == hold.owner && lease.press_id == hold.press_id
                        })
                    };
                    if !valid {
                        // Duplicate terminal edges are harmless, but never release a newer gesture.
                        let result = if active.is_none()
                            && matches!(hold.action.as_str(), "up" | "cancel")
                        {
                            Ok(hold_reply(hold))
                        } else {
                            Err(anyhow::anyhow!("remote key press expired, already held or belongs to another gesture"))
                        };
                        let _ = work.reply.send(result);
                        continue;
                    }
                }
                let mut result: Result<String> = async {
                    if bridge.is_none() {
                        bridge = Some(Bridge::start(&spec).await?);
                    }
                    let child = bridge.as_mut().unwrap();
                    for code in uncertain.clone() {
                        sequence += 1;
                        child.request(sequence, "release", code).await?;
                        uncertain.remove(&code);
                    }
                    match &work.kind {
                        WorkKind::Warmup => Ok("ready".into()),
                        WorkKind::Tap(code) => {
                            if work.reply.is_closed() {
                                return Ok(String::new());
                            }
                            uncertain.insert(*code);
                            sequence += 1;
                            let started = Instant::now();
                            child.request(sequence, "key", *code).await?;
                            uncertain.remove(code);
                            Ok(json!({"operation":"key","keyCode":code,"submitted":true,
                                "released":true,"source":257,"bridgePid":child.child.id(),
                                "injectionMicros":started.elapsed().as_micros() as u64})
                            .to_string())
                        }
                        WorkKind::Hold(hold) => {
                            if hold.action == "down" && work.reply.is_closed() {
                                return Ok(String::new());
                            }
                            uncertain.insert(hold.code);
                            sequence += 1;
                            child
                                .request_edge(
                                    sequence,
                                    &hold.action,
                                    hold.code,
                                    Some(&hold.press_id),
                                )
                                .await?;
                            uncertain.remove(&hold.code);
                            if matches!(hold.action.as_str(), "up" | "cancel") {
                                leases.remove(&hold.code);
                            } else {
                                leases.insert(
                                    hold.code,
                                    Lease {
                                        owner: hold.owner.clone(),
                                        press_id: hold.press_id.clone(),
                                        touched: Instant::now(),
                                    },
                                );
                            }
                            Ok(hold_reply(hold))
                        }
                        WorkKind::Disconnect(owner) => {
                            let codes: Vec<_> = leases
                                .iter()
                                .filter(|(_, lease)| &lease.owner == owner)
                                .map(|(code, _)| *code)
                                .collect();
                            for code in codes {
                                let lease = leases.remove(&code).unwrap();
                                uncertain.insert(code);
                                sequence += 1;
                                child
                                    .request_edge(sequence, "cancel", code, Some(&lease.press_id))
                                    .await?;
                                uncertain.remove(&code);
                            }
                            Ok("released".into())
                        }
                    }
                }
                .await;
                if result.is_err() {
                    uncertain.extend(leases.keys().copied());
                    leases.clear();
                    if let Err(error) = &result {
                        eprintln!("persistent input bridge failed: {error:#}");
                    }
                    if !uncertain.is_empty() {
                        result = result.map_err(|error| error.context("input key outcome uncertain; no automatic replay; release recovery attempted"));
                    }
                    if let Some(child) = bridge.take() {
                        child.stop().await;
                    }
                    // Recover every possibly held key by sending only UP; never replay a DOWN.
                    if !uncertain.is_empty() {
                        if let Ok(mut child) = Bridge::start(&spec).await {
                            for code in uncertain.clone() {
                                sequence += 1;
                                if child.request(sequence, "release", code).await.is_err() {
                                    break;
                                }
                                uncertain.remove(&code);
                            }
                            if uncertain.is_empty() {
                                bridge = Some(child);
                            } else {
                                child.stop().await;
                            }
                        }
                    }
                }
                let result = if !uncertain.is_empty() {
                    Err(anyhow::anyhow!(
                        "input key outcome uncertain; release recovery pending; no automatic retry"
                    ))
                } else {
                    result
                };
                let _ = work.reply.send(result);
            }
            incoming.close();
            if let Some(child) = bridge {
                child.stop().await;
            }
            let _ = finished_sender.send(true);
        });
        Self {
            sender,
            shutdown,
            finished,
        }
    }
    async fn submit(&self, kind: WorkKind) -> Result<String> {
        let (reply, result) = oneshot::channel();
        self.sender
            .try_send(Work { kind, reply })
            .map_err(|error| match error {
                mpsc::error::TrySendError::Full(_) => anyhow::anyhow!(BUSY),
                mpsc::error::TrySendError::Closed(_) => anyhow::anyhow!("input service stopped"),
            })?;
        result.await.context("input service stopped")?
    }
    async fn call(&self, code: Option<u32>) -> Result<String> {
        self.submit(code.map_or(WorkKind::Warmup, WorkKind::Tap))
            .await
    }
    async fn stop(&self) {
        let _ = self.shutdown.send(true);
        let mut finished = self.finished.clone();
        while !*finished.borrow_and_update() {
            if finished.changed().await.is_err() {
                break;
            }
        }
    }
}

pub(crate) async fn edge(args: &Value, owner: &str) -> Result<String> {
    let fields = args
        .as_object()
        .context("remote key arguments must be object")?;
    if fields.len() != 3
        || fields
            .keys()
            .any(|key| !["key", "action", "pressId"].contains(&key.as_str()))
    {
        bail!("remote key edges require only key, action and pressId");
    }
    let code =
        crate::tools::remote_key_code(args["key"].as_str().context("remote.key requires key")?)?;
    let action = args["action"]
        .as_str()
        .filter(|action| ["down", "keep", "up", "cancel"].contains(action))
        .context("unsupported remote key action")?;
    let press_id = args["pressId"]
        .as_str()
        .context("remote key pressId must be UUID")?;
    uuid::Uuid::parse_str(press_id).context("remote key pressId must be UUID")?;
    INPUT
        .get()
        .context("persistent input bridge unavailable")?
        .submit(WorkKind::Hold(Hold {
            code,
            action: action.into(),
            press_id: press_id.into(),
            owner: owner.into(),
        }))
        .await
}
pub(crate) async fn disconnect(owner: &str) {
    if let Some(input) = INPUT.get() {
        let _ = input.submit(WorkKind::Disconnect(owner.into())).await;
    }
}

pub(crate) async fn initialize() -> Result<()> {
    let path = crate::platform_bridge::input_bridge_path()?;
    let manager = Manager::start(Spec {
        program: "app_process".into(),
        args: vec![
            "/system/bin".into(),
            "ernest.airc.bridge.InputBridge".into(),
        ],
        classpath: Some(path),
    });
    let _ = INPUT.set(manager);
    INPUT.get().unwrap().call(None).await?;
    Ok(())
}
pub(crate) async fn key(code: u32) -> Result<String> {
    INPUT
        .get()
        .context("persistent input bridge unavailable")?
        .call(Some(code))
        .await
}
pub(crate) async fn shutdown() {
    if let Some(input) = INPUT.get() {
        input.stop().await;
    }
}

#[cfg(test)]
mod tests;
