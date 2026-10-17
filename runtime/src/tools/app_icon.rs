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
    let bytes = decode_icon(&encoded)?;
    let mut descriptor = crate::artifacts::insert(bytes, 96, 96);
    descriptor["package"] = serde_json::json!(package);
    descriptor["userId"] = serde_json::json!(user);
    Ok(descriptor.to_string())
}

fn decode_icon(encoded: &str) -> Result<Vec<u8>> {
    let encoded = encoded.trim();
    if encoded.len() > 256 * 1024 || !encoded.len().is_multiple_of(2) {
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
    Ok(bytes)
}

pub(super) async fn apk(args: &Value) -> Result<String> {
    use std::os::fd::AsRawFd;
    static GATE: tokio::sync::Semaphore = tokio::sync::Semaphore::const_new(2);
    let object = args
        .as_object()
        .context("APK icon arguments must be object")?;
    if object
        .keys()
        .any(|key| !["root", "path"].contains(&key.as_str()))
    {
        bail!("only root and path accepted");
    }
    let root = args
        .get("root")
        .map(|v| v.as_str().context("root must be string"))
        .transpose()?
        .unwrap_or("downloads");
    let path = args["path"].as_str().context("path required")?;
    if !path.to_ascii_lowercase().ends_with(".apk") {
        bail!("APK file required");
    }
    let file = super::files::open(&super::files::root(root)?, path)?;
    let metadata = file.metadata()?;
    if !metadata.is_file() || metadata.len() > 4 * 1024 * 1024 * 1024 {
        bail!("APK requires a regular file up to 4 GiB");
    }
    let _permit = GATE.try_acquire().context("APK icon requests busy")?;
    let user = run_command("am", &["get-current-user".into()])
        .await?
        .trim()
        .parse::<u32>()?;
    let pinned = format!("/proc/{}/fd/{}", std::process::id(), file.as_raw_fd());
    let bytes = decode_icon(&crate::platform_bridge::apk_icon(user, &pinned).await?)?;
    if !crate::file_actions::same(&metadata, &file.metadata()?) {
        bail!("APK changed while reading icon");
    }
    let mut descriptor = crate::artifacts::insert(bytes, 96, 96);
    descriptor["root"] = serde_json::json!(root);
    descriptor["filePath"] = serde_json::json!(path);
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
