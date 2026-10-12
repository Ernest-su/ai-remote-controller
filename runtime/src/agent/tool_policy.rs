use crate::tools;
use anyhow::{bail, Result};
use serde_json::Value;
use std::collections::BTreeSet;

/// A caller-selected subset may reduce the tool set, never add tools or confirmation APIs.
pub(super) struct ToolPolicy {
    allowed: Option<BTreeSet<String>>,
}
impl ToolPolicy {
    pub(super) fn parse(value: Option<&Value>) -> Result<Self> {
        let Some(value) = value else {
            return Ok(Self { allowed: None });
        };
        let values = value
            .as_array()
            .ok_or_else(|| anyhow::anyhow!("allowedTools must be an array"))?;
        if values.is_empty() || values.len() > tools::TOOLS.len() {
            bail!("allowedTools must contain 1..{} tools", tools::TOOLS.len());
        }
        let mut allowed = BTreeSet::new();
        for value in values {
            let name = value
                .as_str()
                .ok_or_else(|| anyhow::anyhow!("allowedTools entries must be strings"))?;
            if !tools::TOOLS.contains(&name) || !allowed.insert(name.to_string()) {
                bail!("allowedTools contains an unknown or duplicate tool");
            }
        }
        Ok(Self {
            allowed: Some(allowed),
        })
    }
    pub(super) fn restricted(&self) -> bool {
        self.allowed.is_some()
    }
    pub(super) fn permits(&self, name: &str) -> bool {
        tools::TOOLS.contains(&name)
            && self
                .allowed
                .as_ref()
                .is_none_or(|allowed| allowed.contains(name))
    }
    pub(super) fn schemas(&self) -> Value {
        let mut schemas = super::tool_schemas();
        schemas.as_array_mut().unwrap().retain(|schema| {
            let alias = schema
                .pointer("/function/name")
                .and_then(Value::as_str)
                .unwrap_or("");
            tools::TOOLS
                .iter()
                .any(|name| self.permits(name) && name.replace('.', "_") == alias)
        });
        schemas
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    #[test]
    fn scope_filters_schemas_and_blocks_shell_and_confirmation_aliases() {
        let policy = ToolPolicy::parse(Some(&json!(["device.info"]))).unwrap();
        assert!(policy.permits("device.info"));
        for name in [
            "device.shell",
            "files.delete",
            "approve_system",
            "device_info",
        ] {
            assert!(!policy.permits(name));
        }
        assert_eq!(policy.schemas().as_array().unwrap().len(), 1);
        assert_eq!(policy.schemas()[0]["function"]["name"], "device_info");
    }
    #[test]
    fn malformed_scopes_fail_closed() {
        for value in [
            json!(null),
            json!([]),
            json!([1]),
            json!(["unknown"]),
            json!(["device_info"]),
            json!(["device.info", "device.info"]),
        ] {
            assert!(ToolPolicy::parse(Some(&value)).is_err());
        }
        let policy = ToolPolicy::parse(None).unwrap();
        assert!(!policy.restricted());
        assert_eq!(
            policy.schemas().as_array().unwrap().len(),
            tools::TOOLS.len()
        );
    }
}
