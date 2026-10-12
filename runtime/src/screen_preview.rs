use anyhow::{bail, Context, Result};
use axum::{
    body::{Body, Bytes},
    extract::Path,
    http::{header, StatusCode},
    response::{IntoResponse, Response},
};
use futures_util::stream;
use serde_json::{json, Value};
use std::{
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc, Mutex, OnceLock,
    },
    time::{Duration, Instant},
};
use tokio::sync::{Notify, OwnedSemaphorePermit, Semaphore};

const CLAIM_WINDOW: Duration = Duration::from_secs(30);
pub const MAGIC: &[u8; 8] = b"AIRCPRV1";
struct Lease {
    id: String,
    created: Instant,
    interval: Duration,
    duration: Duration,
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
fn arguments(args: &Value) -> Result<(u64, u64)> {
    let object = args
        .as_object()
        .context("preview arguments must be object")?;
    if object
        .keys()
        .any(|key| !["intervalMs", "durationSeconds"].contains(&key.as_str()))
    {
        bail!("only intervalMs and durationSeconds accepted");
    }
    let interval = args
        .get("intervalMs")
        .map(|v| v.as_u64().context("intervalMs must be integer"))
        .transpose()?
        .unwrap_or(1000);
    let duration = args
        .get("durationSeconds")
        .map(|v| v.as_u64().context("durationSeconds must be integer"))
        .transpose()?
        .unwrap_or(60);
    if !(500..=5000).contains(&interval) || !(10..=120).contains(&duration) {
        bail!("preview interval/duration outside limits");
    }
    Ok((interval, duration))
}
pub fn execute(tool: &str, args: &Value) -> Result<String> {
    if tool == "screen.preview_stop" {
        let object = args.as_object().context("stop arguments must be object")?;
        if object.len() != 1 {
            bail!("only id accepted");
        }
        let id = args["id"].as_str().context("id required")?;
        uuid::Uuid::parse_str(id).context("invalid preview id")?;
        let mut current = CURRENT.lock().unwrap_or_else(|e| e.into_inner());
        let stopped = current.as_ref().is_some_and(|lease| lease.id == id);
        if stopped {
            cancel(current.as_ref().unwrap());
            current.take();
        }
        return Ok(json!({"id":id,"stopped":stopped}).to_string());
    }
    let (interval, duration) = arguments(args)?;
    let mut current = CURRENT.lock().unwrap_or_else(|e| e.into_inner());
    if current.as_ref().is_some_and(|lease| {
        lease.created.elapsed() >= CLAIM_WINDOW + lease.duration
            || (!lease.claimed.load(Ordering::SeqCst) && lease.created.elapsed() >= CLAIM_WINDOW)
    }) {
        cancel(current.as_ref().unwrap());
        current.take();
    }
    let permit = SLOT
        .get_or_init(|| Arc::new(Semaphore::new(1)))
        .clone()
        .try_acquire_owned()
        .context("screen preview already reserved or streaming; close the previous reader")?;
    let id = uuid::Uuid::new_v4().to_string();
    *current = Some(Arc::new(Lease {
        id: id.clone(),
        created: Instant::now(),
        interval: Duration::from_millis(interval),
        duration: Duration::from_secs(duration),
        claimed: AtomicBool::new(false),
        canceled: AtomicBool::new(false),
        notify: Notify::new(),
        _slot: permit,
    }));
    Ok(json!({"id":id,"path":format!("/preview/{id}"),"mimeType":"application/x-airc-preview","format":"airc-png-v1","intervalMs":interval,"durationSeconds":duration,"claimWithinSeconds":30,"maxFrameBytes":crate::artifacts::MAX_BYTES}).to_string())
}
struct Reader {
    lease: Arc<Lease>,
    started: Instant,
    next: Instant,
    sequence: u32,
    magic: bool,
    ended: bool,
}
impl Drop for Reader {
    fn drop(&mut self) {
        cancel(&self.lease);
        let mut current = CURRENT.lock().unwrap_or_else(|e| e.into_inner());
        if current
            .as_ref()
            .is_some_and(|lease| lease.id == self.lease.id)
        {
            current.take();
        }
    }
}
fn record(sequence: u32, elapsed: u64, width: u32, height: u32, payload: &[u8]) -> Bytes {
    let mut bytes = Vec::with_capacity(24 + payload.len());
    for value in [sequence, width, height, payload.len() as u32] {
        bytes.extend_from_slice(&value.to_be_bytes());
    }
    bytes.extend_from_slice(&elapsed.to_be_bytes());
    bytes.extend_from_slice(payload);
    bytes.into()
}
pub async fn download(Path(id): Path<String>) -> Response {
    let lease = {
        let current = CURRENT.lock().unwrap_or_else(|e| e.into_inner());
        match current
            .as_ref()
            .filter(|lease| lease.id == id && !lease.canceled.load(Ordering::SeqCst))
        {
            Some(lease)
                if lease.created.elapsed() < CLAIM_WINDOW
                    && !lease.claimed.swap(true, Ordering::SeqCst) =>
            {
                lease.clone()
            }
            Some(_) => return StatusCode::CONFLICT.into_response(),
            None => return StatusCode::NOT_FOUND.into_response(),
        }
    };
    let now = Instant::now();
    let reader = Reader {
        lease,
        started: now,
        next: now,
        sequence: 0,
        magic: false,
        ended: false,
    };
    let frames = stream::unfold(reader, |mut reader| async move {
        if reader.ended {
            return None;
        }
        if !reader.magic {
            reader.magic = true;
            return Some((Ok::<_, std::io::Error>(Bytes::from_static(MAGIC)), reader));
        }
        let deadline = reader.started + reader.lease.duration;
        tokio::select! {
            _ = reader.lease.notify.notified() => {},
            _ = tokio::time::sleep_until(reader.next.min(deadline).into()) => {},
        }
        let mut width = 0;
        let mut height = 0;
        let mut payload = Vec::new();
        if !reader.lease.canceled.load(Ordering::SeqCst) && Instant::now() < deadline {
            let captured = tokio::select! {
                _ = reader.lease.notify.notified() => None,
                _ = tokio::time::sleep_until(deadline.into()) => None,
                result = crate::screen_capture::capture() => Some(result),
            };
            if let Some(result) = captured {
                match result {
                    Ok((png, w, h)) => {
                        payload = png;
                        width = w;
                        height = h;
                        reader.sequence += 1;
                    }
                    Err(error) => {
                        payload = error
                            .to_string()
                            .chars()
                            .take(256)
                            .collect::<String>()
                            .into_bytes();
                        reader.ended = true;
                    }
                }
            }
        }
        if width == 0 {
            reader.ended = true;
        }
        // No catch-up burst: capture and consumer speed determine actual frame rate.
        reader.next = Instant::now() + reader.lease.interval;
        let bytes = record(
            reader.sequence,
            reader.started.elapsed().as_millis() as u64,
            width,
            height,
            &payload,
        );
        Some((Ok(bytes), reader))
    });
    (
        [
            (header::CONTENT_TYPE, "application/x-airc-preview"),
            (header::CACHE_CONTROL, "no-store"),
            (header::X_CONTENT_TYPE_OPTIONS, "nosniff"),
        ],
        Body::from_stream(frames),
    )
        .into_response()
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn preview_limits_and_binary_records_are_explicit() {
        assert_eq!(arguments(&json!({})).unwrap(), (1000, 60));
        for args in [
            json!({"intervalMs":0}),
            json!({"durationSeconds":121}),
            json!({"intervalMs":500.5}),
            json!({"shell":"screencap"}),
        ] {
            assert!(arguments(&args).is_err());
        }
        let bytes = record(2, 1000, 320, 240, b"png");
        assert_eq!(bytes.len(), 27);
        assert_eq!(&bytes[12..16], &3u32.to_be_bytes());
        assert_eq!(&bytes[16..24], &1000u64.to_be_bytes());
    }
    #[tokio::test]
    async fn tickets_are_single_reader_and_stop_releases_unclaimed_slots() {
        let first: Value =
            serde_json::from_str(&execute("screen.preview_start", &json!({})).unwrap()).unwrap();
        assert!(execute("screen.preview_start", &json!({})).is_err());
        let id = first["id"].as_str().unwrap();
        assert_eq!(download(Path(id.into())).await.status(), StatusCode::OK);
        // The returned response was dropped, which also cancels and releases the reader.
        assert_eq!(
            download(Path(id.into())).await.status(),
            StatusCode::NOT_FOUND
        );
        let second: Value =
            serde_json::from_str(&execute("screen.preview_start", &json!({})).unwrap()).unwrap();
        assert!(execute("screen.preview_stop", &json!({"id":id}))
            .unwrap()
            .contains("false"));
        execute("screen.preview_stop", &json!({"id":second["id"]})).unwrap();
        assert!(CURRENT.lock().unwrap().is_none());
    }
}
