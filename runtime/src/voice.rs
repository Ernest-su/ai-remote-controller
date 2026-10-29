//! A single connection-owned, bounded virtual microphone stream; no audio is persisted.
use anyhow::{bail, Context, Result};
use axum::{
    extract::ws::{Message, WebSocket, WebSocketUpgrade},
    response::IntoResponse,
};
use futures_util::SinkExt;
use serde::Deserialize;
use serde_json::json;
use std::{
    process::Stdio,
    time::{Duration, Instant},
};
use tokio::{
    io::{AsyncBufReadExt, AsyncWriteExt, BufReader},
    process::Command,
    sync::Mutex,
};

static ACTIVE: Mutex<()> = Mutex::const_new(());
#[derive(Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct Settings {
    package_name: String,
    key_code: u16,
}
impl Settings {
    fn validate(&self) -> Result<()> {
        let parts: Vec<_> = self.package_name.split('.').collect();
        if self.package_name.len() > 255
            || parts.len() < 2
            || parts.iter().any(|p| {
                p.is_empty()
                    || !p.as_bytes()[0].is_ascii_alphabetic()
                    || !p.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_')
            })
        {
            bail!("Invalid receiving application package");
        }
        Ok(())
    }
}
// Refill only up to 200ms, so a pause cannot accumulate permission for a later burst.
fn pcm_credit(previous: f64, elapsed: Duration, bytes: usize) -> Result<f64> {
    let credit = (previous + elapsed.as_secs_f64() * 32000.0).min(6400.0) - bytes as f64;
    if credit < 0.0 {
        bail!("Voice stream exceeds real-time rate");
    }
    Ok(credit)
}
pub(crate) async fn websocket(upgrade: WebSocketUpgrade) -> impl IntoResponse {
    upgrade
        .max_message_size(4096)
        .max_frame_size(4096)
        .on_upgrade(connection)
}
async fn connection(mut socket: WebSocket) {
    let result = session(&mut socket).await;
    let message = match result {
        Ok(()) => json!({"stopped":true}),
        Err(error) => json!({"error":format!("{error:#}")}),
    };
    let _ = tokio::time::timeout(
        Duration::from_secs(1),
        socket.send(Message::Text(message.to_string().into())),
    )
    .await;
    let _ = tokio::time::timeout(Duration::from_secs(1), socket.close()).await;
}
async fn session(socket: &mut WebSocket) -> Result<()> {
    let _active = ACTIVE.try_lock().context("Microphone is already in use")?;
    let first = tokio::time::timeout(Duration::from_secs(5), socket.recv())
        .await?
        .context("Voice connection closed")??;
    let Message::Text(text) = first else {
        bail!("Expected voice settings");
    };
    let settings: Settings = serde_json::from_str(&text)?;
    settings.validate()?;
    let dex = crate::platform_bridge::input_bridge_path()?;
    // Dropping the task must close stdin, not SIGKILL the bridge before its key-UP/finally.
    // Its independent watchdog bounds cleanup even if the runtime exits unexpectedly.
    let mut child = Command::new("app_process")
        .args([
            "/system/bin",
            "ernest.airc.bridge.VoiceBridge",
            &settings.package_name,
            &settings.key_code.to_string(),
        ])
        .env("CLASSPATH", dex)
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .spawn()?;
    let mut input = child.stdin.take().context("Voice input pipe missing")?;
    let mut output = BufReader::new(child.stdout.take().context("Voice output pipe missing")?);
    let result: Result<()> = async {
        let mut line = String::new();
        tokio::time::timeout(Duration::from_secs(5), output.read_line(&mut line)).await??;
        let ready: serde_json::Value =
            serde_json::from_str(&line).context("Virtual microphone did not initialize")?;
        if ready["ready"] != true {
            bail!(
                "{}",
                ready["error"]
                    .as_str()
                    .unwrap_or("Virtual microphone unavailable")
            );
        }
        tokio::time::timeout(
            Duration::from_secs(1),
            socket.send(Message::Text(ready.to_string().into())),
        )
        .await??;
        let started = Instant::now();
        let mut credit = 6400.0;
        let mut last_frame = Instant::now();
        loop {
            let next = tokio::time::timeout(Duration::from_millis(1500), socket.recv())
                .await?
                .context("Voice connection closed")??;
            if started.elapsed() >= Duration::from_secs(60) {
                bail!("Voice session reached 60 seconds");
            }
            match next {
                Message::Binary(pcm) => {
                    if pcm.is_empty() || pcm.len() > 3200 || pcm.len() % 2 != 0 {
                        bail!("Invalid PCM frame");
                    }
                    credit = pcm_credit(credit, last_frame.elapsed(), pcm.len())?;
                    last_frame = Instant::now();
                    tokio::time::timeout(Duration::from_secs(1), async {
                        input.write_all(&(pcm.len() as u16).to_be_bytes()).await?;
                        input.write_all(&pcm).await?;
                        input.flush().await
                    })
                    .await??;
                }
                Message::Text(text) if text == "stop" => break,
                Message::Close(_) => break,
                Message::Ping(data) => {
                    tokio::time::timeout(Duration::from_secs(1), socket.send(Message::Pong(data)))
                        .await??;
                }
                Message::Pong(_) => {}
                _ => bail!("Unsupported voice message"),
            }
            if last_frame.elapsed() > Duration::from_millis(1500) {
                bail!("Voice stream idle");
            }
        }
        Ok(())
    }
    .await;
    // EOF performs key UP and unregisters the policy even for invalid frames or a broken socket.
    drop(input);
    let exited = tokio::time::timeout(Duration::from_secs(3), child.wait()).await;
    match exited {
        Ok(status) => {
            if !status?.success() && result.is_ok() {
                bail!("Virtual microphone stopped with an error");
            }
        }
        Err(_) => {
            let _ = child.kill().await;
            let _ = child.wait().await;
            bail!("Virtual microphone cleanup timed out");
        }
    }
    result
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn pcm_limit_refills_at_live_rate_but_never_accumulates_a_recording() {
        let remaining = pcm_credit(6400.0, Duration::ZERO, 6400).unwrap();
        assert!(pcm_credit(remaining, Duration::ZERO, 2).is_err());
        assert_eq!(
            pcm_credit(remaining, Duration::from_millis(20), 640).unwrap(),
            0.0
        );
        assert!(pcm_credit(0.0, Duration::from_secs(1), 6402).is_err());
        assert_eq!(pcm_credit(0.0, Duration::from_secs(1), 6400).unwrap(), 0.0);
    }
    #[test]
    fn settings_are_strict_and_do_not_accept_shell_arguments() {
        for package in ["com.konka.bestlauncher", "com.example_voice.assistant"] {
            Settings {
                package_name: package.into(),
                key_code: 1017,
            }
            .validate()
            .unwrap();
        }
        for package in [
            "",
            "android",
            "com..app",
            "com.app;reboot",
            "com.app\n",
            "com.1app",
        ] {
            assert!(Settings {
                package_name: package.into(),
                key_code: 0
            }
            .validate()
            .is_err());
        }
        for value in [
            r#"{"packageName":"com.a","keyCode":65536}"#,
            r#"{"packageName":"com.a","keyCode":-1}"#,
            r#"{"packageName":"com.a","keyCode":1,"other":1}"#,
        ] {
            assert!(serde_json::from_str::<Settings>(value).is_err());
        }
    }
}
