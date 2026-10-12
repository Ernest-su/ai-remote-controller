use super::run_command;
use anyhow::Result;
use serde_json::Value;

// Fixed commands only: missing platform capabilities remain explicit null fields.
async fn probe(program: &str, argv: &[&str]) -> Option<String> {
    let argv: Vec<String> = argv.iter().map(|arg| (*arg).into()).collect();
    let output = run_command(program, &argv).await.ok()?;
    let output = output.trim();
    (!output.is_empty()).then(|| output.to_owned())
}

pub(super) fn screen_size(output: &str) -> Option<Value> {
    // Android reports physical size first and an optional effective override last.
    output
        .lines()
        .filter_map(|line| {
            let size = line
                .strip_prefix("Physical size: ")
                .or_else(|| line.strip_prefix("Override size: "))?;
            let (width, height) = size.trim().split_once('x')?;
            let width: u32 = width.parse().ok()?;
            let height: u32 = height.parse().ok()?;
            (width > 0 && height > 0).then(|| serde_json::json!({"width":width,"height":height}))
        })
        .next_back()
}

fn screen_summary(viewport: Option<&str>, natural: Option<&str>) -> Option<Value> {
    if let Some((width, height)) = viewport.and_then(super::gestures::display_dimensions) {
        return Some(serde_json::json!({"width":width,"height":height,"source":"active_viewport"}));
    }
    natural.and_then(screen_size).map(|mut value| {
        value["source"] = "wm_size".into();
        value
    })
}

fn storage_summary(output: &str) -> Option<Value> {
    // POSIX df -k: filesystem, total, used, available, capacity, mount point.
    let columns: Vec<&str> = output.lines().last()?.split_whitespace().collect();
    if columns.len() < 6 || !columns.last().copied()?.starts_with('/') {
        return None;
    }
    let bytes = |index: usize| columns.get(index)?.parse::<u64>().ok()?.checked_mul(1024);
    Some(
        serde_json::json!({"path":"/data", "mountPoint":columns.last()?, "totalBytes":bytes(1)?,
        "usedBytes":bytes(2)?, "availableBytes":bytes(3)?}),
    )
}

pub(super) async fn execute() -> Result<String> {
    let (manufacturer, model, release, sdk, abi, screen, storage, viewport) = tokio::join!(
        probe("getprop", &["ro.product.manufacturer"]),
        probe("getprop", &["ro.product.model"]),
        probe("getprop", &["ro.build.version.release"]),
        probe("getprop", &["ro.build.version.sdk"]),
        probe("getprop", &["ro.product.cpu.abilist"]),
        probe("wm", &["size"]),
        probe("df", &["-k", "/data"]),
        probe("dumpsys", &["input"]),
    );
    // Older Android releases may expose only the primary ABI.
    let abi = match abi {
        Some(value) => Some(value),
        None => probe("getprop", &["ro.product.cpu.abi"]).await,
    };
    Ok(serde_json::json!({
        "manufacturer":manufacturer, "model":model, "androidRelease":release,
        "sdk":sdk.and_then(|value| value.parse::<u32>().ok()),
        "abis":abi.map(|value| value.split(',').map(str::trim).filter(|abi| !abi.is_empty())
            .map(str::to_owned).collect::<Vec<_>>()),
        "screen":screen_summary(viewport.as_deref(), screen.as_deref()),
        "storage":storage.as_deref().and_then(storage_summary),
    })
    .to_string())
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    #[test]
    fn logical_viewport_takes_precedence_and_fallback_is_identified() {
        let viewport =
            "Viewport INTERNAL: displayId=0, logicalFrame=[0, 0, 1080, 1920], isActive=[1]";
        assert_eq!(
            screen_summary(Some(viewport), Some("Physical size: 1920x1080")),
            Some(serde_json::json!({"width":1080,"height":1920,"source":"active_viewport"}))
        );
        assert_eq!(
            screen_summary(Some("unavailable"), Some("Physical size: 1920x1080")),
            Some(serde_json::json!({"width":1920,"height":1080,"source":"wm_size"}))
        );
        assert_eq!(screen_summary(None, None), None);
    }
    #[test]
    fn device_info_parses_platform_output() {
        assert_eq!(
            screen_size("Physical size: 1920x1080\nOverride size: 1280x720\n"),
            Some(json!({"width":1280,"height":720}))
        );
        assert_eq!(screen_size("Physical size: 0x1080"), None);
        assert_eq!(screen_size("permission denied"), None);
        assert_eq!(storage_summary("Filesystem 1K-blocks Used Available Use% Mounted on\n/dev/block/dm-0 1000 400 600 40% /data\n"),
            Some(json!({"path":"/data","mountPoint":"/data","totalBytes":1024000,"usedBytes":409600,"availableBytes":614400})));
        assert_eq!(
            storage_summary("/dev/block/mmcblk0p30 1000 400 600 40% /data/user/0"),
            Some(
                json!({"path":"/data","mountPoint":"/data/user/0","totalBytes":1024000,"usedBytes":409600,"availableBytes":614400})
            )
        );
        assert_eq!(
            storage_summary("/dev/block 18446744073709551615 1 2 0% /data"),
            None
        );
        assert_eq!(storage_summary("permission denied"), None);
    }
}
