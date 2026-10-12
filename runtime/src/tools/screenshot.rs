use anyhow::{bail, Result};
use serde_json::Value;

pub(super) async fn execute(args: &Value) -> Result<String> {
    if !args.is_object() || args.as_object().is_some_and(|object| !object.is_empty()) {
        bail!("device.screenshot accepts an empty argument object");
    }
    let (png, width, height) = crate::screen_capture::capture().await?;
    Ok(crate::artifacts::insert(png, width, height).to_string())
}
