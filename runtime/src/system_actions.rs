use anyhow::{bail, Context, Result};
use serde_json::{json, Value};
use std::{
    collections::HashMap,
    sync::{Mutex, OnceLock},
    time::{Duration, Instant},
};
#[derive(Clone)]
struct Proposal {
    id: String,
    action: String,
    owner: String,
    created: Instant,
}
static PENDING: OnceLock<Mutex<HashMap<String, Proposal>>> = OnceLock::new();
fn registry() -> &'static Mutex<HashMap<String, Proposal>> {
    PENDING.get_or_init(|| Mutex::new(HashMap::new()))
}
fn preview(p: &Proposal) -> Value {
    let remaining = Duration::from_secs(120).saturating_sub(p.created.elapsed());
    let seconds = remaining.as_secs() + u64::from(remaining.subsec_nanos() != 0);
    json!({"id":p.id,"kind":"system_action","action":p.action,"runtimeUid":0,"approvalRequired":true,"connectionMayClose":p.action!="lock","expiresInSeconds":seconds})
}
fn eligible(cap: &Value, action: &str) -> bool {
    let (permission, interface) = match action {
        "lock" => ("devicePowerGranted", "lockInterfacePresent"),
        "reboot" => ("rebootGranted", "rebootInterfacePresent"),
        "shutdown" => ("rebootGranted", "shutdownInterfacePresent"),
        _ => return false,
    };
    cap["runtimeUid"] == 0
        && cap["permissionProbeAvailable"] == true
        && cap[permission] == true
        && cap[interface] == true
}
async fn check(action: &str) -> Result<()> {
    let cap: Value = serde_json::from_str(&crate::system_capabilities::execute(&json!({})).await?)?;
    if !eligible(&cap, action) {
        bail!("system action requires root and verified framework permission/interface");
    }
    Ok(())
}
pub async fn execute(tool: &str, args: &Value, owner: &str) -> Result<String> {
    if owner.is_empty() || !matches!(tool, "device.system_action" | "device.system_pending") {
        bail!("system tools require a connection owner and known operation");
    }
    let object = args.as_object().context("arguments must be object")?;
    if tool == "device.system_pending" {
        if !object.is_empty() {
            bail!("pending requires empty arguments");
        }
        let mut pending = registry().lock().unwrap();
        pending.retain(|_, p| p.created.elapsed() < Duration::from_secs(120));
        return Ok(serde_json::to_string(
            &pending
                .values()
                .filter(|p| p.owner == owner)
                .map(preview)
                .collect::<Vec<_>>(),
        )?);
    }
    if object.len() != 1 {
        bail!("only action accepted");
    }
    let action = args["action"].as_str().context("action required")?;
    if !["lock", "reboot", "shutdown"].contains(&action) {
        bail!("unsupported system action");
    }
    check(action).await?;
    let proposal = Proposal {
        id: uuid::Uuid::new_v4().to_string(),
        action: action.into(),
        owner: owner.into(),
        created: Instant::now(),
    };
    let value = preview(&proposal);
    let mut pending = registry().lock().unwrap();
    pending.retain(|_, p| p.created.elapsed() < Duration::from_secs(120));
    if pending.len() >= 16 {
        bail!("too many pending system approvals");
    }
    pending.insert(proposal.id.clone(), proposal);
    Ok(value.to_string())
}
pub async fn approve(args: &Value, owner: &str) -> Result<String> {
    let object = args.as_object().context("approval must be object")?;
    if object.len() != 2 || !object.contains_key("id") || !object.contains_key("accepted") {
        bail!("explicit id and accepted required");
    }
    let id = args["id"].as_str().context("id required")?;
    let accepted = args["accepted"]
        .as_bool()
        .context("accepted must be boolean")?;
    let p = take(id, owner)?;
    if !accepted {
        return Ok(json!({"id":id,"rejected":true}).to_string());
    }
    check(&p.action).await?;
    let result = crate::platform_bridge::system_action(&p.action).await?;
    let result: Value = serde_json::from_str(&result)?;
    if result["action"] != p.action || result["submitted"] != true {
        bail!("invalid system action result");
    }
    Ok(json!({"id":id,"action":p.action,"submitted":true,"completed":false,"connectionMayClose":p.action!="lock","note":"Framework accepted the request; final device state requires observation. A lost connection is not proof of success."}).to_string())
}
pub fn disconnect(owner: &str) {
    registry().lock().unwrap().retain(|_, p| p.owner != owner);
}
fn take(id: &str, owner: &str) -> Result<Proposal> {
    let mut pending = registry().lock().unwrap();
    let p = pending
        .get(id)
        .context("system approval missing or already used")?;
    if p.owner != owner {
        bail!("system approval belongs to another connection");
    }
    let p = pending.remove(id).unwrap();
    if p.created.elapsed() >= Duration::from_secs(120) {
        bail!("system approval expired");
    }
    Ok(p)
}
#[cfg(test)]
mod tests {
    use super::*;
    #[tokio::test]
    async fn pending_reports_remaining_lifetime_and_disconnect_is_scoped() {
        let owner = uuid::Uuid::new_v4().to_string();
        let other = uuid::Uuid::new_v4().to_string();
        let make = |owner: &str, age| Proposal {
            id: uuid::Uuid::new_v4().to_string(),
            action: "lock".into(),
            owner: owner.into(),
            created: Instant::now() - Duration::from_secs(age),
        };
        let live = make(&owner, 95);
        let expired = make(&owner, 121);
        let independent = make(&other, 0);
        let expired_id = expired.id.clone();
        let independent_id = independent.id.clone();
        {
            let mut registry = registry().lock().unwrap();
            for p in [live, expired, independent] {
                registry.insert(p.id.clone(), p);
            }
        }
        let pending: Value = serde_json::from_str(
            &execute("device.system_pending", &json!({}), &owner)
                .await
                .unwrap(),
        )
        .unwrap();
        assert_eq!(pending.as_array().unwrap().len(), 1);
        assert!(pending[0]["expiresInSeconds"].as_u64().unwrap() <= 25);
        assert!(take(&expired_id, &owner).is_err());
        disconnect(&owner);
        assert_eq!(
            execute("device.system_pending", &json!({}), &owner)
                .await
                .unwrap(),
            "[]"
        );
        assert!(take(&independent_id, &other).is_ok());
        assert!(execute("device.system_pending", &json!({}), "")
            .await
            .is_err());
        assert!(execute("device.unknown", &json!({}), &owner).await.is_err());
    }
    #[test]
    fn root_permissions_and_matching_interface_are_required() {
        let mut cap = json!({"runtimeUid":0,"permissionProbeAvailable":true,"rebootGranted":true,"shutdownInterfacePresent":true});
        assert!(eligible(&cap, "shutdown"));
        assert!(!eligible(&cap, "reboot"));
        cap["runtimeUid"] = json!(2000);
        assert!(!eligible(&cap, "shutdown"));
    }
    #[tokio::test]
    async fn approval_is_explicit_owned_single_use_and_rejection_does_not_execute() {
        let id = uuid::Uuid::new_v4().to_string();
        registry().lock().unwrap().insert(
            id.clone(),
            Proposal {
                id: id.clone(),
                action: "reboot".into(),
                owner: "test".into(),
                created: Instant::now(),
            },
        );
        assert!(approve(&json!({"id":id}), "test").await.is_err());
        assert!(approve(&json!({"id":id,"accepted":false}), "other")
            .await
            .is_err());
        assert_eq!(
            serde_json::from_str::<Value>(
                &approve(&json!({"id":id,"accepted":false}), "test")
                    .await
                    .unwrap()
            )
            .unwrap()["rejected"],
            true
        );
        assert!(take(&id, "test").is_err());
    }
    #[tokio::test]
    async fn expired_confirmation_is_consumed_before_permission_or_action_calls() {
        let id = uuid::Uuid::new_v4().to_string();
        registry().lock().unwrap().insert(
            id.clone(),
            Proposal {
                id: id.clone(),
                action: "shutdown".into(),
                owner: id.clone(),
                created: Instant::now() - Duration::from_secs(121),
            },
        );
        let error = approve(&json!({"id":id,"accepted":true}), &id)
            .await
            .unwrap_err();
        assert_eq!(error.to_string(), "system approval expired");
        assert!(take(&id, &id).is_err());
    }
}
