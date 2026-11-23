//! Connection-owned real Linux mouse: bounded relative deltas, ordered button snapshots and deadman.
mod device;
use anyhow::{bail, Context, Result};
use serde_json::{json, Value};
use std::{
    sync::{Mutex, Once},
    time::{Duration, Instant},
};

#[derive(Clone, Copy, Debug, Default, PartialEq)]
struct Frame {
    dx: i32,
    dy: i32,
    wheel: i32,
    buttons: u8,
}
fn parse(args: &Value) -> Result<Frame> {
    let object = args.as_object().context("mouse frame must be an object")?;
    if object.len() != 4
        || object
            .keys()
            .any(|key| !["dx", "dy", "wheel", "buttons"].contains(&key.as_str()))
    {
        bail!("mouse frame requires only dx, dy, wheel and buttons");
    }
    let integer = |key: &str, bound: i64| -> Result<i32> {
        Ok(args[key]
            .as_i64()
            .filter(|n| (-bound..=bound).contains(n))
            .context("mouse deltas must be bounded integers")? as i32)
    };
    let buttons = args["buttons"]
        .as_u64()
        .filter(|n| *n <= 7)
        .context("mouse buttons must be a mask 0..7")? as u8;
    Ok(Frame {
        dx: integer("dx", 2048)?,
        dy: integer("dy", 2048)?,
        wheel: integer("wheel", 32)?,
        buttons,
    })
}
fn events(old: u8, next: Frame) -> Vec<(u16, u16, i32)> {
    let mut events = Vec::new();
    for (mask, code) in [(1, 272), (2, 273), (4, 274)] {
        if (old & mask) != (next.buttons & mask) {
            events.push((1, code, i32::from(next.buttons & mask != 0)));
        }
    }
    for (code, value) in [(0, next.dx), (1, next.dy), (8, next.wheel)] {
        if value != 0 {
            events.push((2, code, value));
        }
    }
    if !events.is_empty() {
        events.push((0, 0, 0));
    }
    events
}
struct Session {
    id: String,
    owner: String,
    device: device::Device,
    sequence: u64,
    last: Instant,
    created: Instant,
}
static CURRENT: Mutex<Option<Session>> = Mutex::new(None);
static WATCH: Once = Once::new();
fn retire(mut session: Session) {
    let _ = session.device.send(Frame::default());
    tokio::spawn(async move {
        tokio::time::sleep(Duration::from_millis(100)).await;
        drop(session);
    });
}
pub(crate) fn disconnect(owner: &str) {
    let mut current = CURRENT.lock().unwrap();
    if current.as_ref().is_some_and(|s| s.owner == owner) {
        let session = current.take().unwrap();
        drop(current);
        retire(session);
    }
}
pub(crate) fn initialize() {
    WATCH.call_once(|| {
        tokio::spawn(async {
            loop {
                tokio::time::sleep(Duration::from_millis(100)).await;
                let expired = {
                    let mut current = CURRENT.lock().unwrap();
                    if current.as_ref().is_some_and(|s| {
                        s.last.elapsed() >= Duration::from_secs(2)
                            || s.created.elapsed() >= Duration::from_secs(3600)
                    }) {
                        current.take()
                    } else {
                        None
                    }
                };
                if let Some(s) = expired {
                    retire(s);
                }
            }
        });
    });
}
pub(crate) fn shutdown() {
    CURRENT.lock().unwrap().take();
}
pub(crate) fn execute(tool: &str, args: &Value, owner: &str) -> Result<String> {
    if owner.is_empty() {
        bail!("connection owner required");
    }
    let object = args
        .as_object()
        .context("mouse arguments must be an object")?;
    if matches!(tool, "mouse.capabilities" | "mouse.open") {
        if !object.is_empty() {
            bail!("mouse operation requires empty arguments");
        }
        if tool == "mouse.capabilities" {
            return Ok(match device::open() {
                Ok(_) => json!({"supported":true,"mode":"uinput"}),
                Err(error) => json!({"supported":false,"reason":error.to_string(),"mode":"uinput"}),
            }
            .to_string());
        }
        let mut current = CURRENT.lock().unwrap();
        if current.as_ref().is_some_and(|s| s.owner != owner) {
            bail!("mouse already owned by another connection");
        }
        if let Some(previous) = current.take() {
            retire(previous);
        }
        let id = uuid::Uuid::new_v4().to_string();
        let name = format!("AIRC mouse {id}");
        let device = device::Device::create(&name)?;
        *current = Some(Session {
            id: id.clone(),
            owner: owner.into(),
            device,
            sequence: 0,
            last: Instant::now(),
            created: Instant::now(),
        });
        return Ok(json!({"id":id,"deviceName":name,"deadmanMs":2000,"mode":"uinput"}).to_string());
    }
    let allowed: &[&str] = match tool {
        "mouse.update" => &["id", "sequence", "frame"],
        "mouse.close" => &["id"],
        _ => bail!("unknown mouse operation"),
    };
    if object.len() != allowed.len() || object.keys().any(|key| !allowed.contains(&key.as_str())) {
        bail!("unsupported mouse arguments");
    }
    let id = args["id"].as_str().context("mouse id required")?;
    let mut current = CURRENT.lock().unwrap();
    let session = current
        .as_mut()
        .filter(|s| s.owner == owner && s.id == id)
        .context("mouse session expired or owned by another connection")?;
    if tool == "mouse.close" {
        let session = current.take().unwrap();
        drop(current);
        retire(session);
        return Ok(json!({"id":id,"closed":true}).to_string());
    }
    if session.last.elapsed() >= Duration::from_secs(2)
        || session.created.elapsed() >= Duration::from_secs(3600)
    {
        let session = current.take().unwrap();
        drop(current);
        retire(session);
        bail!("mouse session expired");
    }
    let sequence = args["sequence"]
        .as_u64()
        .filter(|n| *n > session.sequence && *n <= u32::MAX as u64)
        .context("mouse sequence must strictly increase")?;
    let frame = parse(&args["frame"])?;
    if let Err(error) = session.device.send(frame) {
        let session = current.take().unwrap();
        drop(current);
        retire(session);
        return Err(error);
    }
    session.sequence = sequence;
    session.last = Instant::now();
    Ok(json!({"id":id,"sequence":sequence,"submitted":true}).to_string())
}
#[cfg(test)]
mod tests {
    use super::*;
    #[tokio::test]
    async fn session_rejects_foreign_owner_duplicate_deltas_and_expired_buttons() {
        let path = std::env::temp_dir().join(format!("airc-mouse-test-{}", uuid::Uuid::new_v4()));
        let install = |last| {
            *CURRENT.lock().unwrap() = Some(Session {
                id: "owned".into(),
                owner: "first".into(),
                device: device::Device::fixture(std::fs::File::create(&path).unwrap()),
                sequence: 0,
                last,
                created: Instant::now(),
            });
        };
        install(Instant::now());
        let update =
            json!({"id":"owned","sequence":1,"frame":{"dx":12,"dy":0,"wheel":0,"buttons":1}});
        assert!(execute("mouse.update", &update, "other").is_err());
        assert_eq!(std::fs::metadata(&path).unwrap().len(), 0);
        assert!(execute("mouse.update", &update, "first").is_ok());
        let written = std::fs::metadata(&path).unwrap().len();
        assert!(execute("mouse.update", &update, "first").is_err());
        assert_eq!(written, std::fs::metadata(&path).unwrap().len());
        disconnect("other");
        assert!(CURRENT.lock().unwrap().is_some());
        disconnect("first");
        assert!(CURRENT.lock().unwrap().is_none());
        assert!(
            std::fs::metadata(&path).unwrap().len() > written,
            "disconnect must write a real button release"
        );
        install(Instant::now() - Duration::from_secs(3));
        assert!(execute("mouse.update", &update, "first").is_err());
        assert!(
            CURRENT.lock().unwrap().is_none(),
            "late DOWN must never reactivate an expired device"
        );
        std::fs::remove_file(path).unwrap();
    }
    #[test]
    fn genuine_mouse_edges_relative_motion_and_wheel() {
        assert_eq!(
            events(
                1,
                Frame {
                    dx: 12,
                    dy: -6,
                    wheel: 2,
                    buttons: 6
                }
            ),
            vec![
                (1, 272, 0),
                (1, 273, 1),
                (1, 274, 1),
                (2, 0, 12),
                (2, 1, -6),
                (2, 8, 2),
                (0, 0, 0)
            ]
        );
        assert!(events(0, Frame::default()).is_empty());
        assert_eq!(
            events(7, Frame::default()),
            vec![(1, 272, 0), (1, 273, 0), (1, 274, 0), (0, 0, 0)]
        );
    }
    #[test]
    fn rejects_bad_frames_before_device_access() {
        for args in [
            json!({}),
            json!({"dx":2049,"dy":0,"wheel":0,"buttons":0}),
            json!({"dx":0.1,"dy":0,"wheel":0,"buttons":0}),
            json!({"dx":0,"dy":0,"wheel":33,"buttons":0}),
            json!({"dx":0,"dy":0,"wheel":0,"buttons":8}),
        ] {
            assert!(parse(&args).is_err());
        }
        assert!(execute("mouse.open", &json!({}), "").is_err());
        assert!(execute("mouse.close", &json!({"id":"missing"}), "test").is_err());
    }
}
