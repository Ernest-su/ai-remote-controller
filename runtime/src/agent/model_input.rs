use anyhow::{bail, Result};
use serde_json::Value;

pub(super) fn validate(tool: &str, args: &Value) -> Result<()> {
    if tool == "remote.key" && args["key"].as_str() == Some("power") {
        bail!("power key requires direct manual control; use system action proposals and App confirmation");
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn model_power_is_rejected_but_direct_mapping_remains_available() {
        let args = json!({"key":"power"});
        assert!(validate("remote.key", &args).is_err());
        assert_eq!(crate::tools::remote_key_code("power").unwrap(), 26);
        assert!(validate("remote.key", &json!({"key":"home"})).is_ok());
        assert!(validate("device.system_action", &json!({"action":"lock"})).is_ok());
    }
}
