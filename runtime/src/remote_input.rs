//! Bounded, runtime-owned input actor. Caller cancellation never interrupts a down/up pair.
use anyhow::{bail, Context, Result};
use serde_json::{json, Value};
use std::{ffi::OsString, path::PathBuf, process::Stdio, sync::OnceLock, time::Duration};
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
        let bytes = format!(
            "{}\n",
            json!({"id":sequence,"operation":operation,"code":code})
        );
        tokio::time::timeout(DEADLINE, async {
            self.stdin.write_all(bytes.as_bytes()).await?;
            self.stdin.flush().await?;
            let reply = self.read_frame().await?;
            if reply["id"].as_u64() != Some(sequence)
                || reply["ok"] != true
                || reply["released"] != true
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

struct Work {
    code: Option<u32>,
    reply: oneshot::Sender<Result<String>>,
}
struct Manager {
    sender: mpsc::Sender<Work>,
    shutdown: watch::Sender<bool>,
    finished: watch::Receiver<bool>,
}
impl Manager {
    fn start(spec: Spec) -> Self {
        let (sender, mut incoming) = mpsc::channel::<Work>(16);
        let (shutdown, mut stop) = watch::channel(false);
        let (finished_sender, finished) = watch::channel(false);
        tokio::spawn(async move {
            let mut bridge: Option<Bridge> = None;
            let mut sequence = 0;
            let mut uncertain = None;
            loop {
                let work = tokio::select! {
                    biased;
                    _ = stop.changed() => break,
                    next = incoming.recv() => match next { Some(work) => work, None => break },
                };
                if work.reply.is_closed() {
                    continue;
                }
                let mut result: Result<String> = async {
                    if bridge.is_none() {
                        bridge = Some(Bridge::start(&spec).await?);
                    }
                    let child = bridge.as_mut().unwrap();
                    if let Some(code) = uncertain {
                        sequence += 1;
                        child.request(sequence, "release", code).await?;
                        uncertain = None;
                    }
                    if let Some(code) = work.code {
                        // Startup/recovery can outlive the caller. Recheck immediately
                        // before accepting a DOWN, not just before starting the helper.
                        if work.reply.is_closed() {
                            return Ok(String::new());
                        }
                        // Record before writing: losing the reply does not prove the key was not sent.
                        uncertain = Some(code);
                        sequence += 1;
                        let started = std::time::Instant::now();
                        child.request(sequence, "key", code).await?;
                        uncertain = None;
                        Ok(json!({"operation":"key","keyCode":code,"submitted":true,
                            "released":true,"source":257,"bridgePid":child.child.id(),
                            "injectionMicros":started.elapsed().as_micros() as u64})
                        .to_string())
                    } else {
                        Ok("ready".into())
                    }
                }
                .await;
                if result.is_err() {
                    if let Err(error) = &result {
                        eprintln!("persistent input bridge failed: {error:#}");
                    }
                    if uncertain.is_some() {
                        result = result.map_err(|error| error.context("input key outcome uncertain; no automatic replay; release recovery attempted"));
                    }
                    if let Some(child) = bridge.take() {
                        child.stop().await;
                    }
                    // Recovery releases only. The failed DOWN is never automatically replayed.
                    if let Some(code) = uncertain {
                        if let Ok(mut child) = Bridge::start(&spec).await {
                            sequence += 1;
                            if child.request(sequence, "release", code).await.is_ok() {
                                uncertain = None;
                                bridge = Some(child);
                            } else {
                                child.stop().await;
                            }
                        }
                    }
                }
                let result = if uncertain.is_some() {
                    Err(anyhow::anyhow!(
                        "input key outcome uncertain; release recovery pending; no automatic retry"
                    ))
                } else {
                    result
                };
                let _ = work.reply.send(result);
            }
            incoming.close();
            // Complete any in-flight transaction above before shutting down the child.
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
    async fn call(&self, code: Option<u32>) -> Result<String> {
        let (reply, result) = oneshot::channel();
        self.sender
            .try_send(Work { code, reply })
            .map_err(|error| match error {
                mpsc::error::TrySendError::Full(_) => anyhow::anyhow!(BUSY),
                mpsc::error::TrySendError::Closed(_) => anyhow::anyhow!("input service stopped"),
            })?;
        result.await.context("input service stopped")?
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
