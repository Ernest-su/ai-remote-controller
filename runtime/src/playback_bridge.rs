//! Runtime-owned media/audio worker. Finish an in-flight frame even if its caller cancels;
//! never replay a mutating request after a timeout or a lost acknowledgement.
use anyhow::{bail, Context, Result};
use serde_json::{json, Value};
use std::{
    path::PathBuf,
    process::Stdio,
    sync::{Arc, OnceLock},
    time::Duration,
};
use tokio::{
    io::{AsyncBufReadExt, AsyncWriteExt, BufReader},
    process::{Child, ChildStdin, ChildStdout, Command},
    sync::Mutex,
};

struct Worker {
    _child: Child,
    input: ChildStdin,
    output: BufReader<ChildStdout>,
    sequence: u64,
}
type Slot = Arc<Mutex<Option<Worker>>>;
static WORKER: OnceLock<Slot> = OnceLock::new();
const LIMIT: usize = 256 * 1024;

impl Worker {
    async fn start(mut command: Command) -> Result<Self> {
        let mut child = command
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::inherit())
            .kill_on_drop(true)
            .spawn()?;
        let input = child
            .stdin
            .take()
            .context("playback worker stdin missing")?;
        let output = BufReader::new(
            child
                .stdout
                .take()
                .context("playback worker stdout missing")?,
        );
        let mut worker = Self {
            _child: child,
            input,
            output,
            sequence: 0,
        };
        if worker.read().await?["ready"] != true {
            bail!("playback worker did not become ready");
        }
        Ok(worker)
    }
    async fn read(&mut self) -> Result<Value> {
        let mut line = Vec::new();
        loop {
            let (count, done) = {
                let bytes = self.output.fill_buf().await?;
                if bytes.is_empty() {
                    bail!("playback worker closed before acknowledgement");
                }
                let count = bytes
                    .iter()
                    .position(|b| *b == b'\n')
                    .map(|n| n + 1)
                    .unwrap_or(bytes.len());
                if line.len() + count > LIMIT {
                    bail!("playback worker acknowledgement too large");
                }
                line.extend_from_slice(&bytes[..count]);
                (count, bytes[count - 1] == b'\n')
            };
            self.output.consume(count);
            if done {
                return Ok(serde_json::from_slice(&line)?);
            }
        }
    }
    async fn exchange(
        &mut self,
        kind: &str,
        operation: &str,
        arguments: &[String],
    ) -> Result<Value> {
        self.sequence += 1;
        let mut bytes = serde_json::to_vec(
            &json!({"id":self.sequence,"kind":kind,"operation":operation,"arguments":arguments}),
        )?;
        bytes.push(b'\n');
        self.input.write_all(&bytes).await?;
        self.input.flush().await?;
        let reply = self.read().await?;
        if reply["id"].as_u64() != Some(self.sequence) || !reply["ok"].is_boolean() {
            bail!("invalid playback worker acknowledgement");
        }
        Ok(reply)
    }
}

async fn call(
    slot: Slot,
    path: PathBuf,
    kind: String,
    operation: String,
    arguments: Vec<String>,
    budget: Duration,
) -> Result<String> {
    // Cancellation while queued must not submit a late operation. Only a frame that has
    // acquired the worker is protected, preserving the response boundary for the next caller.
    let mut worker = slot.lock_owned().await;
    tokio::spawn(async move {
        let response = tokio::time::timeout(budget, async {
            if worker.is_none() {
                let mut command = Command::new("app_process");
                command
                    .env("CLASSPATH", path)
                    .args(["/system/bin", "ernest.airc.bridge.PlaybackBridge"]);
                *worker = Some(Worker::start(command).await?);
            }
            worker
                .as_mut()
                .unwrap()
                .exchange(&kind, &operation, &arguments)
                .await
        })
        .await;
        let reply = match response {
            Ok(Ok(reply)) => reply,
            Ok(Err(error)) => {
                *worker = None;
                return Err(error.context("播放桥接响应丢失，请刷新核对操作结果"));
            }
            Err(_) => {
                *worker = None;
                bail!("播放桥接响应超时，请刷新核对操作结果");
            }
        };
        if reply["ok"] != true {
            bail!("{}", reply["error"].as_str().unwrap_or("播放操作失败"));
        }
        Ok(reply["result"].to_string())
    })
    .await
    .context("playback worker task failed")?
}

pub(crate) async fn invoke(kind: &str, operation: &str, arguments: &[String]) -> Result<String> {
    let path = crate::platform_bridge::input_bridge_path()?;
    call(
        WORKER.get_or_init(|| Arc::new(Mutex::new(None))).clone(),
        path,
        kind.into(),
        operation.into(),
        arguments.to_vec(),
        Duration::from_secs(10),
    )
    .await
}

#[cfg(test)]
mod tests {
    use super::*;
    async fn slot() -> Slot {
        let script = r#"import sys,json,time,os
print('{"ready":true}',flush=True)
for line in sys.stdin:
 q=json.loads(line)
 if q['operation']=='slow':time.sleep(.15)
 print(json.dumps(dict(id=q['id'],ok=q['operation']!='fail',error='fixture error',result=dict(pid=os.getpid(),operation=q['operation'],sequence=q['id']))),flush=True)
"#;
        let mut command = Command::new("python3");
        command.args(["-u", "-c", script]);
        Arc::new(Mutex::new(Some(Worker::start(command).await.unwrap())))
    }
    async fn request(slot: Slot, op: &str) -> Result<Value> {
        Ok(serde_json::from_str(
            &call(
                slot,
                PathBuf::new(),
                "media".into(),
                op.into(),
                vec![],
                Duration::from_secs(2),
            )
            .await?,
        )?)
    }
    #[tokio::test]
    async fn reuses_one_process_and_application_errors_do_not_break_framing() {
        let slot = slot().await;
        let first = request(slot.clone(), "status").await.unwrap();
        assert!(request(slot.clone(), "fail").await.is_err());
        let second = request(slot, "status").await.unwrap();
        assert_eq!(first["pid"], second["pid"]);
    }
    #[tokio::test]
    async fn canceled_inflight_and_queued_requests_cannot_corrupt_or_replay_frames() {
        let slot = slot().await;
        let slow = tokio::spawn(request(slot.clone(), "slow"));
        tokio::time::sleep(Duration::from_millis(30)).await;
        slow.abort();
        let queued = tokio::spawn(request(slot.clone(), "must-not-run"));
        tokio::time::sleep(Duration::from_millis(20)).await;
        queued.abort();
        let next = request(slot, "next").await.unwrap();
        assert_eq!(next["operation"], "next");
        assert_eq!(next["sequence"], 2);
    }
    #[tokio::test]
    async fn uncertain_timeouts_retire_worker_without_replaying_the_operation() {
        let slot = slot().await;
        assert!(call(
            slot.clone(),
            PathBuf::new(),
            "media".into(),
            "slow".into(),
            vec![],
            Duration::from_millis(20)
        )
        .await
        .is_err());
        assert!(slot.lock().await.is_none());
    }
}
