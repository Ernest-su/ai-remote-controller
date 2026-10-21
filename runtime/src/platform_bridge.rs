use anyhow::{bail, Context, Result};
use sha2::{Digest, Sha256};
use std::{
    fs::{self, OpenOptions},
    io::{Read, Write},
    os::{
        fd::AsRawFd,
        unix::{
            ffi::OsStrExt,
            fs::{MetadataExt, OpenOptionsExt, PermissionsExt},
        },
    },
    path::{Path, PathBuf},
    sync::OnceLock,
};
static BRIDGE: OnceLock<PathBuf> = OnceLock::new();
static APP_CACHE: OnceLock<PathBuf> = OnceLock::new();
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
// SELinux shell domains may rename private files but forbid hard links.
// Publish atomically without replacing an existing hash-addressed bridge.
fn publish_noreplace(parent: &fs::File, temporary: &Path, path: &Path) -> std::io::Result<()> {
    let from = std::ffi::CString::new(temporary.file_name().unwrap().as_bytes())?;
    let to = std::ffi::CString::new(path.file_name().unwrap().as_bytes())?;
    let result = unsafe {
        libc::syscall(
            libc::SYS_renameat2,
            parent.as_raw_fd(),
            from.as_ptr(),
            parent.as_raw_fd(),
            to.as_ptr(),
            libc::RENAME_NOREPLACE,
        )
    };
    if result != 0 {
        return Err(std::io::Error::last_os_error());
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
            match publish_noreplace(&parent, &temporary, &path) {
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
    let cache = directory.join("apps-cache-v1");
    fs::create_dir_all(&cache)?;
    let cache_dir = OpenOptions::new()
        .read(true)
        .custom_flags(libc::O_DIRECTORY | libc::O_NOFOLLOW)
        .open(&cache)?;
    if cache_dir.metadata()?.uid() != unsafe { libc::geteuid() } {
        bail!("application cache directory has a different owner");
    }
    cache_dir.set_permissions(fs::Permissions::from_mode(0o700))?;
    let _ = APP_CACHE.set(cache);
    Ok(())
}
pub(crate) async fn invoke(operation: &str, arguments: &[String]) -> Result<String> {
    invoke_class("ernest.airc.bridge.MediaBridge", operation, arguments).await
}
pub(crate) async fn scheduled_audio(operation: &str, arguments: &[String]) -> Result<String> {
    invoke_class("ernest.airc.bridge.AudioBridge", operation, arguments).await
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
    let mut arguments = arguments.to_vec();
    arguments.push(
        APP_CACHE
            .get()
            .context("application cache unavailable")?
            .to_string_lossy()
            .into_owned(),
    );
    invoke_class("ernest.airc.bridge.AppsBridge", "list", &arguments).await
}
pub(crate) async fn app_control(user: u32, package: &str) -> Result<String> {
    invoke_class(
        "ernest.airc.bridge.AppControlBridge",
        "details",
        &[user.to_string(), package.to_owned()],
    )
    .await
}
pub(crate) async fn app_icon(user: u32, package: &str) -> Result<String> {
    invoke_class(
        "ernest.airc.bridge.AppsBridge",
        "icon",
        &[
            user.to_string(),
            package.to_owned(),
            APP_CACHE
                .get()
                .context("application cache unavailable")?
                .to_string_lossy()
                .into_owned(),
        ],
    )
    .await
}
pub(crate) async fn app_info(user: u32, package: &str) -> Result<String> {
    invoke_class(
        "ernest.airc.bridge.AppsBridge",
        "info",
        &[
            user.to_string(),
            package.to_owned(),
            APP_CACHE
                .get()
                .context("application cache unavailable")?
                .to_string_lossy()
                .into_owned(),
        ],
    )
    .await
}
pub(crate) async fn apk_icon(user: u32, path: &str) -> Result<String> {
    invoke_class(
        "ernest.airc.bridge.AppsBridge",
        "archive_icon",
        &[user.to_string(), path.to_owned()],
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

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn bridge_publication_is_atomic_and_never_overwrites_or_follows_a_link() {
        let directory =
            std::env::temp_dir().join(format!("airc-bridge-publish-{}", uuid::Uuid::new_v4()));
        fs::create_dir(&directory).unwrap();
        let parent = fs::File::open(&directory).unwrap();
        let temporary = directory.join("temporary");
        let target = directory.join("bridge.dex");
        fs::write(&temporary, b"bridge").unwrap();
        fs::set_permissions(&temporary, fs::Permissions::from_mode(0o400)).unwrap();
        publish_noreplace(&parent, &temporary, &target).unwrap();
        assert!(!temporary.exists());
        assert_eq!(fs::read(&target).unwrap(), b"bridge");
        fs::write(&temporary, b"replacement").unwrap();
        assert_eq!(
            publish_noreplace(&parent, &temporary, &target)
                .unwrap_err()
                .kind(),
            std::io::ErrorKind::AlreadyExists
        );
        assert_eq!(fs::read(&target).unwrap(), b"bridge");
        let link = directory.join("link.dex");
        std::os::unix::fs::symlink(&target, &link).unwrap();
        assert_eq!(
            publish_noreplace(&parent, &temporary, &link)
                .unwrap_err()
                .kind(),
            std::io::ErrorKind::AlreadyExists
        );
        assert_eq!(fs::read(&target).unwrap(), b"bridge");
        fs::remove_dir_all(directory).unwrap();
    }
}
