mod device;
mod state;
use anyhow::{bail, Context, Result};
use serde_json::{json, Value};
use std::{
    sync::{Mutex, Once},
    time::{Duration, Instant},
};
struct Session {
    id: String,
    owner: String,
    name: String,
    device: device::Device,
    last: Instant,
    created: Instant,
    sequence: u64,
}
static CURRENT: Mutex<Option<Session>> = Mutex::new(None);
static WATCH: Once = Once::new();
fn empty(args: &Value) -> Result<()> {
    if args.as_object().is_none_or(|v| !v.is_empty()) {
        bail!("empty argument object required");
    }
    Ok(())
}
fn retire(mut session: Session) {
    let _ = session.device.set(state::State::default());
    tokio::spawn(async move {
        tokio::time::sleep(Duration::from_millis(100)).await;
        drop(session);
    });
}
pub(crate) fn disconnect(owner: &str) {
    let session = {
        let mut current = CURRENT.lock().unwrap();
        if current.as_ref().is_some_and(|v| v.owner == owner) {
            current.take()
        } else {
            None
        }
    };
    if let Some(v) = session {
        retire(v);
    }
}
pub(crate) fn initialize() {
    WATCH.call_once(|| {
        tokio::spawn(async {
            loop {
                tokio::time::sleep(Duration::from_millis(100)).await;
                let expired = {
                    let mut current = CURRENT.lock().unwrap();
                    if let Some(session) = current.as_mut() {
                        if session.last.elapsed() >= Duration::from_secs(2)
                            && session.device.state != state::State::default()
                        {
                            if session.device.set(state::State::default()).is_err() {
                                current.take()
                            } else {
                                None
                            }
                        } else if session.last.elapsed() >= Duration::from_secs(30)
                            || session.created.elapsed() >= Duration::from_secs(3600)
                        {
                            current.take()
                        } else {
                            None
                        }
                    } else {
                        None
                    }
                };
                if let Some(v) = expired {
                    retire(v);
                }
            }
        });
    });
}
pub(crate) fn shutdown() {
    if let Some(session) = CURRENT.lock().unwrap().take() {
        drop(session);
    }
}
pub(crate) fn execute(tool: &str, args: &Value, owner: &str) -> Result<String> {
    if owner.is_empty() {
        bail!("connection owner required");
    }
    if !matches!(
        tool,
        "gamepad.virtual_capabilities"
            | "gamepad.virtual_open"
            | "gamepad.virtual_update"
            | "gamepad.virtual_close"
    ) {
        bail!("unknown virtual gamepad tool");
    }
    if tool == "gamepad.virtual_capabilities" {
        empty(args)?;
        return Ok(match device::open(){Ok((_,version))=>json!({"supported":true,"version":version,"mode":"uinput","runtimeUid":unsafe{libc::geteuid()}}),Err(error)=>json!({"supported":false,"mode":"uinput","runtimeUid":unsafe{libc::geteuid()},"reason":error.to_string()})}.to_string());
    }
    if tool == "gamepad.virtual_open" {
        empty(args)?;
        let mut current = CURRENT.lock().unwrap();
        if current.is_some() {
            bail!("virtual gamepad already active");
        }
        let id = uuid::Uuid::new_v4().to_string();
        let name = format!("AIRC virtual gamepad {id}");
        let device = device::Device::create(&name)?;
        *current = Some(Session {
            id: id.clone(),
            owner: owner.into(),
            name: name.clone(),
            device,
            last: Instant::now(),
            created: Instant::now(),
            sequence: 0,
        });
        return Ok(json!({"id":id,"deviceName":name,"mode":"uinput","deadmanMs":2000,"idleCloseSeconds":30,"maxSessionSeconds":3600,"axisMap":{"leftX":"X","leftY":"Y","rightX":"Z","rightY":"RZ","hatX":"HAT_X","hatY":"HAT_Y"},"registered":true,"gameVerified":false}).to_string());
    }
    let object = args
        .as_object()
        .context("virtual gamepad arguments must be object")?;
    let allowed = if tool == "gamepad.virtual_close" {
        vec!["id"]
    } else {
        vec!["id", "sequence", "state"]
    };
    if object.keys().any(|key| !allowed.contains(&key.as_str())) {
        bail!("unsupported virtual gamepad arguments");
    }
    let id = args["id"].as_str().context("id required")?;
    let mut current = CURRENT.lock().unwrap();
    let session = current
        .as_mut()
        .filter(|v| v.id == id && v.owner == owner)
        .context("virtual session missing or owned by another connection")?;
    if tool == "gamepad.virtual_close" {
        let session = current.take().unwrap();
        drop(current);
        retire(session);
        return Ok(json!({"id":id,"closed":true}).to_string());
    }
    let sequence = args["sequence"]
        .as_u64()
        .filter(|v| *v > session.sequence && *v <= u32::MAX as u64)
        .context("sequence must strictly increase")?;
    let state = state::parse(&args["state"])?;
    if let Err(error) = session.device.set(state) {
        let failed = current.take().unwrap();
        drop(current);
        retire(failed);
        return Err(error);
    }
    session.sequence = sequence;
    session.last = Instant::now();
    Ok(json!({"id":id,"sequence":sequence,"deviceName":session.name,"submitted":true,"gameVerified":false}).to_string())
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn session_tools_require_an_owner_and_known_operation() {
        assert!(execute("gamepad.virtual_open", &json!({}), "").is_err());
        assert!(execute("gamepad.virtual_unknown", &json!({}), "test-owner").is_err());
        assert!(execute(
            "gamepad.virtual_close",
            &json!({"id":"missing"}),
            "test-owner"
        )
        .is_err());
    }
    #[test]
    fn snapshots_emit_key_edges_axes_and_one_sync() {
        let old = state::parse(&json!({"buttons":["a"],"leftX":0.5})).unwrap();
        let next = state::parse(&json!({"buttons":["b"],"rightY":-1})).unwrap();
        let delta = state::delta(&old, &next);
        assert_eq!(
            delta,
            vec![
                (1, 304, 0),
                (1, 305, 1),
                (3, 0, 0),
                (3, 5, -32767),
                (0, 0, 0)
            ]
        );
        assert!(state::delta(&next, &next).is_empty());
        for args in [
            json!({"buttons":["power"]}),
            json!({"leftX":2}),
            json!({"hatY":0.5}),
            json!({"device":"/dev/input/event0"}),
        ] {
            assert!(state::parse(&args).is_err());
        }
    }
}
