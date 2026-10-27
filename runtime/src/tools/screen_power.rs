use anyhow::{bail, Result};
use serde_json::{json, Value};
static STATE: tokio::sync::Mutex<bool> = tokio::sync::Mutex::const_new(true);
// This is the last successfully requested display mode, not Android interactive/lock state.
pub async fn execute(args: &Value) -> Result<String> {
    let Some(object) = args.as_object() else {
        bail!("screen.power requires an object")
    };
    if object.len() > 1
        || object.keys().any(|key| key != "on")
        || object.get("on").is_some_and(|v| !v.is_boolean())
    {
        bail!("screen.power accepts only boolean on");
    }
    let mut state = STATE.lock().await;
    if let Some(on) = object.get("on").and_then(Value::as_bool) {
        crate::platform_bridge::screen_power(on).await?;
        *state = on;
    }
    Ok(json!({"on":*state}).to_string())
}
#[cfg(test)]
mod tests {
    use super::*;
    #[tokio::test]
    async fn rejects_invalid_requests_without_changing_state() {
        for args in [json!(null), json!({"on":0}), json!({"on":true,"extra":1})] {
            assert!(execute(&args).await.is_err());
        }
        assert_eq!(execute(&json!({})).await.unwrap(), "{\"on\":true}");
    }
}
