//! A single connection-owned, bounded virtual microphone stream; no audio is persisted.
use anyhow::{bail, Context, Result};
use axum::{
    extract::ws::{CloseFrame, Message, WebSocket, WebSocketUpgrade},
    response::IntoResponse,
};
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
const WINDOW_BYTES: u64 = 128000; // Four seconds; independent of the PCM playback clock.
const MAX_AUDIO_BYTES: u64 = 16000 * 2 * 60;
#[derive(Default)]
struct Outcome {
    received: u64,
    injected: u64,
    cancelled: bool,
}
fn accept_pcm(received: u64, injected: u64, bytes: usize) -> Result<u64> {
    if bytes == 0 || bytes > 3200 || !bytes.is_multiple_of(2) {
        bail!("Invalid PCM frame");
    }
    let next = received + bytes as u64;
    if next > MAX_AUDIO_BYTES || next - injected > WINDOW_BYTES {
        bail!("Voice receive buffer limit exceeded");
    }
    Ok(next)
}
pub(crate) async fn websocket(upgrade: WebSocketUpgrade) -> impl IntoResponse {
    upgrade
        .max_message_size(4096)
        .max_frame_size(4096)
        .on_upgrade(connection)
}
async fn send(socket: &mut WebSocket, value: serde_json::Value) -> Result<()> {
    tokio::time::timeout(
        Duration::from_secs(1),
        socket.send(Message::Text(value.to_string().into())),
    )
    .await??;
    Ok(())
}
async fn connection(mut socket: WebSocket) {
    let message = match session(&mut socket).await {
        Ok(done) => {
            json!({"stopped":true,"cancelled":done.cancelled,"receivedBytes":done.received,"injectedBytes":done.injected})
        }
        Err(error) => json!({"error":format!("{error:#}")}),
    };
    let _ = send(&mut socket, message).await;
    let _ = tokio::time::timeout(
        Duration::from_secs(1),
        socket.send(Message::Close(Some(CloseFrame {
            code: 1000,
            reason: "voice finished".into(),
        }))),
    )
    .await;
}
async fn packet(input: &mut tokio::process::ChildStdin, kind: u16, pcm: &[u8]) -> Result<()> {
    tokio::time::timeout(Duration::from_secs(1), async {
        input.write_all(&kind.to_be_bytes()).await?;
        input.write_all(pcm).await?;
        input.flush().await
    })
    .await??;
    Ok(())
}
async fn session(socket: &mut WebSocket) -> Result<Outcome> {
    let first = tokio::time::timeout(Duration::from_secs(5), socket.recv())
        .await?
        .context("Voice connection closed")??;
    let Message::Text(text) = first else {
        bail!("Expected voice settings");
    };
    let settings: Settings = serde_json::from_str(&text)?;
    settings.validate()?;
    let _active = tokio::select! {
        result = tokio::time::timeout(Duration::from_secs(4), ACTIVE.lock()) => result.context("Microphone is already in use")?,
        _ = socket.recv() => return Ok(Outcome { cancelled:true, ..Outcome::default() }),
    };
    let dex = crate::platform_bridge::input_bridge_path()?;
    // EOF cancels the bridge; never SIGKILL it before its key-UP/finally on task drop.
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
    let mut output =
        BufReader::new(child.stdout.take().context("Voice output pipe missing")?).lines();
    let result: Result<Outcome> = async {
        let began = Instant::now();
        let mut last_network = began;
        let mut prepared = false;
        let mut starting: Option<Instant> = None;
        let mut ready = false;
        let mut ending: Option<Instant> = None;
        let mut received = 0;
        let mut injected = 0;
        let mut timer = tokio::time::interval(Duration::from_millis(100));
        loop {
            tokio::select! {
                _ = timer.tick() => {
                    if began.elapsed() > Duration::from_secs(75) { bail!("Voice session reached its time limit"); }
                    if !prepared && began.elapsed() > Duration::from_secs(5) { bail!("Virtual microphone initialization timed out"); }
                    if prepared && starting.is_none() && last_network.elapsed() > Duration::from_secs(5) { bail!("Voice start timed out"); }
                    if !ready && starting.is_some_and(|at| at.elapsed() > Duration::from_secs(6)) { bail!("Target recorder did not become ready"); }
                    if ready && ending.is_none() && last_network.elapsed() > Duration::from_millis(1500) { bail!("Voice stream idle"); }
                    if ending.is_some_and(|at| at.elapsed() > Duration::from_secs(6)) { bail!("Voice drain timed out"); }
                },
                line = output.next_line() => {
                    let line = line?.context("Virtual microphone exited before completion")?;
                    if line.len() > 4096 { bail!("Invalid microphone reply"); }
                    let event: serde_json::Value = serde_json::from_str(&line)?;
                    if let Some(error) = event["error"].as_str() { bail!("{error}"); }
                    if event["prepared"] == true && !prepared {
                        prepared = true; last_network = Instant::now();
                        send(socket, json!({"prepared":true,"windowBytes":WINDOW_BYTES})).await?;
                    } else if event["ready"] == true && starting.is_some() && !ready {
                        ready = true; last_network = Instant::now();
                        send(socket, event).await?;
                    } else if let Some(next) = event["consumedBytes"].as_u64() {
                        if next < injected || next > received { bail!("Invalid microphone consumption counter"); }
                        injected = next;
                        send(socket, event).await?;
                    } else if event["drained"] == true && ending.is_some() && event["injectedBytes"].as_u64() == Some(received) && injected == received {
                        return Ok(Outcome { received, injected, cancelled:false });
                    } else { bail!("Unexpected microphone reply: {event}"); }
                },
                message = socket.recv() => {
                    match message.context("Voice connection closed")?? {
                        Message::Text(text) if text == "cancel" => return Ok(Outcome { received, injected, cancelled:true }),
                        Message::Close(_) => return Ok(Outcome { received, injected, cancelled:true }),
                        Message::Text(text) if text == "start" && prepared && starting.is_none() => {
                            packet(&mut input, 0, &[]).await?;
                            starting = Some(Instant::now());
                        },
                        Message::Text(text) if text == "end" && ready && ending.is_none() => {
                            packet(&mut input, u16::MAX, &[]).await?;
                            ending = Some(Instant::now());
                        },
                        Message::Binary(pcm) if ready && ending.is_none() => {
                            let next = accept_pcm(received, injected, pcm.len())?;
                            packet(&mut input, pcm.len() as u16, &pcm).await?;
                            received = next; last_network = Instant::now();
                        },
                        Message::Ping(data) => { tokio::time::timeout(Duration::from_secs(1), socket.send(Message::Pong(data))).await??; },
                        Message::Pong(_) => {},
                        _ => bail!("Unsupported voice message or phase"),
                    }
                },
            }
        }
    }.await;
    drop(input); // EOF cancels queued PCM, except after an explicitly acknowledged drain.
    match tokio::time::timeout(Duration::from_secs(3), child.wait()).await {
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
    fn receive_window_accepts_startup_bursts_and_requires_consumption_for_more() {
        let mut received = 0;
        for _ in 0..40 {
            received = accept_pcm(received, 0, 3200).unwrap();
        }
        assert_eq!(received, WINDOW_BYTES);
        assert!(accept_pcm(received, 0, 2).is_err());
        assert_eq!(accept_pcm(received, 640, 640).unwrap(), received + 640);
        assert!(accept_pcm(MAX_AUDIO_BYTES, MAX_AUDIO_BYTES, 2).is_err());
        for bytes in [0, 3, 3202] {
            assert!(accept_pcm(0, 0, bytes).is_err());
        }
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
