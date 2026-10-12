use super::run_command;
use anyhow::{bail, Context, Result};
use serde_json::Value;

pub(super) async fn execute(args: &Value) -> Result<String> {
    let object = args.as_object().context("icon arguments must be object")?;
    if object.len() != 1 {
        bail!("only package accepted");
    }
    let package = args["package"].as_str().context("package required")?;
    if package.len() > 255
        || !package.contains('.')
        || !package
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'.')
    {
        bail!("invalid package");
    }
    let user = run_command("am", &["get-current-user".into()])
        .await?
        .trim()
        .parse::<u32>()?;
    let encoded = crate::platform_bridge::app_icon(user, package).await?;
    let encoded = encoded.trim();
    if encoded.len() > 256 * 1024 || encoded.len() % 2 != 0 {
        bail!("invalid icon length");
    }
    let bytes = (0..encoded.len())
        .step_by(2)
        .map(|i| {
            u8::from_str_radix(encoded.get(i..i + 2).context("invalid icon encoding")?, 16)
                .context("invalid icon encoding")
        })
        .collect::<Result<Vec<_>>>()?;
    if !bytes.starts_with(b"\x89PNG\r\n\x1a\n") {
        bail!("invalid icon PNG");
    }
    let mut descriptor = crate::artifacts::insert(bytes, 96, 96);
    descriptor["package"] = serde_json::json!(package);
    descriptor["userId"] = serde_json::json!(user);
    Ok(descriptor.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[tokio::test]
    async fn rejects_invalid_args_before_bridge_execution() {
        for args in [
            Value::Null,
            serde_json::json!({}),
            serde_json::json!({"package":"test.app","path":"/tmp"}),
            serde_json::json!({"package":"test.app;reboot"}),
        ] {
            assert!(execute(&args).await.is_err());
        }
    }
}
