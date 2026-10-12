use anyhow::{bail, Context, Result};
use serde_json::{json, Value};
const FIELDS: [&str; 4] = [
    "interactive",
    "keyguardLocked",
    "deviceLocked",
    "deviceSecure",
];
fn parse(uid: u32, value: Value) -> Result<Value> {
    if value["uid"].as_u64() != Some(uid.into()) {
        bail!("system state caller identity mismatch");
    }
    if value["userId"] != 0 || value["elapsedRealtimeMs"].as_u64().is_none() {
        bail!("invalid system state identity or clock");
    }
    for name in FIELDS {
        if !value
            .get(name)
            .is_some_and(|v| v.is_boolean() || v.is_null())
        {
            bail!("invalid system state field: {name}");
        }
    }
    let mut result = value;
    result["runtimeUid"] = uid.into();
    result["readOnly"] = true.into();
    result["available"] = FIELDS.iter().any(|name| result[*name].is_boolean()).into();
    result["source"] = "android_framework".into();
    Ok(result)
}
pub async fn execute(args: &Value) -> Result<String> {
    if args.as_object().is_none_or(|args| !args.is_empty()) {
        bail!("system state query requires empty arguments");
    }
    let uid = unsafe { libc::geteuid() };
    let result = match crate::platform_bridge::system_state().await {
        Ok(payload) => parse(
            uid,
            serde_json::from_str(&payload).context("invalid system state response")?,
        )?,
        Err(error) => {
            json!({"runtimeUid":uid,"readOnly":true,"available":false,"source":"android_framework","interactive":null,"keyguardLocked":null,"deviceLocked":null,"deviceSecure":null,"reason":error.to_string().chars().take(160).collect::<String>()})
        }
    };
    Ok(result.to_string())
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn unknown_fields_are_not_false_and_identity_is_checked() {
        let value = json!({"uid":2000,"userId":0,"elapsedRealtimeMs":123,"interactive":true,"keyguardLocked":false,"deviceLocked":null,"deviceSecure":null});
        let parsed = parse(2000, value.clone()).unwrap();
        assert_eq!(parsed["deviceLocked"], Value::Null);
        assert_eq!(parsed["available"], true);
        assert!(parse(0, value.clone()).is_err());
        let mut invalid = value;
        invalid["interactive"] = json!("true");
        assert!(parse(2000, invalid).is_err());
    }
    #[tokio::test]
    async fn state_tool_rejects_action_arguments() {
        assert!(execute(&json!({"action":"lock"})).await.is_err());
    }
}
