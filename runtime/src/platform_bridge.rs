use anyhow::{bail, Context, Result};
use sha2::{Digest, Sha256};
use std::{
    fs::{self, OpenOptions},
    io::{Read, Write},
    os::unix::fs::{MetadataExt, OpenOptionsExt, PermissionsExt},
    path::{Path, PathBuf},
    sync::OnceLock,
};
static BRIDGE: OnceLock<PathBuf> = OnceLock::new();
const DEX: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/classes.dex"));
fn verify(path: &Path) -> Result<()> {
    let mut file = OpenOptions::new()
        .read(true)
        .custom_flags(libc::O_NOFOLLOW)
        .open(path)?;
    let meta = file.metadata()?;
    if !meta.is_file()
        || meta.len() != DEX.len() as u64
        || meta.uid() != unsafe { libc::geteuid() }
        || meta.mode() & 0o222 != 0
    {
        bail!("platform bridge asset ownership or mode mismatch");
    }
    let mut bytes = vec![];
    Read::by_ref(&mut file)
        .take(1024 * 1024)
        .read_to_end(&mut bytes)?;
    if bytes != DEX {
        bail!("platform bridge asset content mismatch");
    }
    Ok(())
}
pub(crate) fn initialize(state: &Path) -> Result<()> {
    if DEX.is_empty() {
        return Ok(());
    }
    let directory = state.join("platform");
    fs::create_dir_all(&directory)?;
    let parent = OpenOptions::new()
        .read(true)
        .custom_flags(libc::O_DIRECTORY | libc::O_NOFOLLOW)
        .open(&directory)?;
    if parent.metadata()?.uid() != unsafe { libc::geteuid() } {
        bail!("platform directory has a different owner");
    }
    fs::set_permissions(&directory, fs::Permissions::from_mode(0o700))?;
    let path = directory.join(format!("android-{:x}.dex", Sha256::digest(DEX)));
    if !path.exists() {
        let temporary = directory.join(format!(".media-{}.tmp", uuid::Uuid::new_v4()));
        let outcome = (|| -> Result<()> {
            let mut file = OpenOptions::new()
                .write(true)
                .create_new(true)
                .mode(0o400)
                .custom_flags(libc::O_NOFOLLOW)
                .open(&temporary)?;
            file.write_all(DEX)?;
            file.sync_all()?;
            drop(file);
            match fs::hard_link(&temporary, &path) {
                Ok(()) => {}
                Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => {}
                Err(error) => return Err(error.into()),
            }
            parent.sync_all()?;
            Ok(())
        })();
        let _ = fs::remove_file(&temporary);
        outcome?;
    }
    verify(&path)?;
    let _ = BRIDGE.set(path);
    Ok(())
}
pub(crate) async fn invoke(operation: &str, arguments: &[String]) -> Result<String> {
    invoke_class("ernest.airc.bridge.MediaBridge", operation, arguments).await
}
pub(crate) async fn system_action(action: &str) -> Result<String> {
    invoke_class("ernest.airc.bridge.SystemActionBridge", action, &[]).await
}
pub(crate) async fn system_state() -> Result<String> {
    invoke_class("ernest.airc.bridge.SystemStateBridge", "state", &[]).await
}
pub(crate) async fn system_capabilities() -> Result<String> {
    invoke_class(
        "ernest.airc.bridge.SystemCapabilitiesBridge",
        "capabilities",
        &[],
    )
    .await
}
pub(crate) async fn cache_permission(user: u32) -> Result<String> {
    invoke_class(
        "ernest.airc.bridge.CachePermissionBridge",
        "permission",
        &[user.to_string()],
    )
    .await
}
pub(crate) async fn gamepad(operation: &str, arguments: &[String]) -> Result<String> {
    invoke_class("ernest.airc.bridge.GamepadBridge", operation, arguments).await
}
pub(crate) async fn apps(arguments: &[String]) -> Result<String> {
    invoke_class("ernest.airc.bridge.AppsBridge", "list", arguments).await
}
pub(crate) async fn app_icon(user: u32, package: &str) -> Result<String> {
    invoke_class(
        "ernest.airc.bridge.AppsBridge",
        "icon",
        &[user.to_string(), package.to_owned()],
    )
    .await
}
async fn invoke_class(class: &str, operation: &str, arguments: &[String]) -> Result<String> {
    let path = BRIDGE
        .get()
        .context("Android framework bridge unavailable")?;
    verify(path)?;
    let mut args = vec!["/system/bin".into(), class.into(), operation.into()];
    args.extend_from_slice(arguments);
    crate::tools::run_platform_bridge(&args, path).await
}

pub(crate) fn input_bridge_path() -> Result<PathBuf> {
    let path = BRIDGE
        .get()
        .context("persistent input bridge unavailable")?;
    verify(path)?;
    Ok(path.clone())
}
