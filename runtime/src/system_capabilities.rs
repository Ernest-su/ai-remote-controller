use anyhow::{bail, Context, Result};
use serde_json::{json, Value};

const PROBE_FIELDS: [&str; 5] = [
    "devicePowerGranted",
    "rebootGranted",
    "lockInterfacePresent",
    "rebootInterfacePresent",
    "shutdownInterfacePresent",
];

fn valid_probe(uid: u32, value: &Value) -> bool {
    value["uid"].as_u64() == Some(uid as u64)
        && PROBE_FIELDS.iter().all(|name| value[*name].is_boolean())
}

fn report(uid: u32, probe: Option<&Value>) -> Value {
    let valid = probe.filter(|value| valid_probe(uid, value));
    let field = |name: &str| {
        valid
            .and_then(|value| value[name].as_bool())
            .unwrap_or(false)
    };
    json!({"runtimeUid":uid,"rootRequired":true,"rootRuntime":uid==0,
        "permissionProbeAvailable":valid.is_some(),"devicePowerGranted":field("devicePowerGranted"),
        "rebootGranted":field("rebootGranted"),"lockInterfacePresent":field("lockInterfacePresent"),
        "rebootInterfacePresent":field("rebootInterfacePresent"),"shutdownInterfacePresent":field("shutdownInterfacePresent"),
        "executionImplemented":true,"approvalRequired":true,"supported":false})
}
pub async fn execute(args: &Value) -> Result<String> {
    if args.as_object().is_none_or(|object| !object.is_empty()) {
        bail!("system capability probe requires empty arguments");
    }
    let uid = unsafe { libc::geteuid() };
    let payload = crate::platform_bridge::system_capabilities().await;
    let mut result = match payload {
        Ok(payload) => {
            let value: Value =
                serde_json::from_str(&payload).context("invalid system permission probe")?;
            if value["uid"].as_u64() != Some(uid as u64) {
                bail!("system permission caller identity mismatch");
            }
            if !valid_probe(uid, &value) {
                bail!("incomplete or invalid system permission probe");
            }
            report(uid, Some(&value))
        }
        Err(error) => {
            let mut value = report(uid, None);
            value["reason"] = error
                .to_string()
                .chars()
                .take(160)
                .collect::<String>()
                .into();
            value
        }
    };
    result["readOnly"] = true.into();
    Ok(result.to_string())
}
#[cfg(test)]
mod tests {
    use super::*;
    #[tokio::test]
    async fn action_arguments_are_rejected_before_platform_probe() {
        for args in [
            json!({"action":"reboot"}),
            json!({"confirmed":true}),
            json!(null),
            json!([]),
        ] {
            let error = execute(&args).await.unwrap_err();
            assert_eq!(
                error.to_string(),
                "system capability probe requires empty arguments"
            );
        }
    }
    #[test]
    fn incomplete_or_mistyped_probe_is_not_available() {
        let complete = json!({"uid":0,"devicePowerGranted":true,"rebootGranted":false,"lockInterfacePresent":true,"rebootInterfacePresent":true,"shutdownInterfacePresent":true});
        assert!(valid_probe(0, &complete));
        for field in PROBE_FIELDS {
            let mut missing = complete.clone();
            missing.as_object_mut().unwrap().remove(field);
            assert!(!valid_probe(0, &missing));
            assert_eq!(report(0, Some(&missing))["permissionProbeAvailable"], false);
            for invalid in [json!(null), json!("true"), json!(1)] {
                let mut mistyped = complete.clone();
                mistyped[field] = invalid;
                assert!(!valid_probe(0, &mistyped));
                assert_eq!(report(0, Some(&mistyped))["devicePowerGranted"], false);
            }
        }
    }
    #[test]
    fn permissions_and_interface_presence_never_claim_execution_support() {
        let probe = json!({"uid":0,"devicePowerGranted":true,"rebootGranted":true,"lockInterfacePresent":true,"rebootInterfacePresent":true,"shutdownInterfacePresent":true});
        let value = report(0, Some(&probe));
        assert_eq!(value["supported"], false);
        assert_eq!(value["executionImplemented"], true);
        assert_eq!(value["approvalRequired"], true);
        assert_eq!(value["permissionProbeAvailable"], true);
        assert_eq!(
            report(2000, Some(&probe))["permissionProbeAvailable"],
            false
        );
        assert_eq!(report(2000, None)["rootRuntime"], false);
    }
}
