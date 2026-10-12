use super::*;
use sha2::{Digest, Sha256};
use std::{
    fs::{File, OpenOptions},
    io::{Read, Seek, SeekFrom, Write},
    os::{fd::AsRawFd, unix::fs::OpenOptionsExt},
    path::Path,
    sync::atomic::{AtomicBool, AtomicU64, Ordering},
};

pub(super) fn unnamed(directory: &Path, size: u64) -> Result<File> {
    let file = OpenOptions::new()
        .read(true)
        .write(true)
        .mode(0o600)
        .custom_flags(libc::O_TMPFILE | libc::O_CLOEXEC | libc::O_NOFOLLOW | libc::O_LARGEFILE)
        .open(directory)
        .context("private filesystem does not support unnamed APK staging")?;
    let mut stat = std::mem::MaybeUninit::<libc::statvfs>::uninit();
    if unsafe { libc::fstatvfs(file.as_raw_fd(), stat.as_mut_ptr()) } != 0 {
        return Err(std::io::Error::last_os_error().into());
    }
    let stat = unsafe { stat.assume_init() };
    #[allow(clippy::unnecessary_cast)]
    let free = (stat.f_bavail as u64).saturating_mul(stat.f_frsize as u64);
    if free < size.saturating_add(16 * 1024 * 1024) {
        bail!("insufficient private APK staging space");
    }
    Ok(file)
}
pub(super) fn copy(
    mut source: File,
    mut target: File,
    expected: &std::fs::Metadata,
    digest: &str,
    cancel: &AtomicBool,
    count: &AtomicU64,
    progress_base: u64,
) -> Result<File> {
    if super::super::apk::identity(&source.metadata()?) != super::super::apk::identity(expected) {
        bail!("source APK changed before preparation");
    }
    let mut buffer = [0; 64 * 1024];
    let mut copied = 0u64;
    let mut hash = Sha256::new();
    loop {
        if cancel.load(Ordering::Relaxed) {
            bail!("APK preparation cancelled");
        }
        let n = source.read(&mut buffer)?;
        if n == 0 {
            break;
        }
        copied += n as u64;
        if copied > expected.len() {
            bail!("source APK grew during preparation");
        }
        target.write_all(&buffer[..n])?;
        hash.update(&buffer[..n]);
        count.store(progress_base + copied, Ordering::Relaxed);
    }
    if copied != expected.len()
        || format!("{:x}", hash.finalize()) != digest
        || super::super::apk::identity(&source.metadata()?) != super::super::apk::identity(expected)
    {
        bail!("source APK changed; inspect it again before installation");
    }
    target.sync_all()?;
    let mut readonly = File::open(format!("/proc/self/fd/{}", target.as_raw_fd()))?;
    if super::super::apk::identity(&target.metadata()?)
        != super::super::apk::identity(&readonly.metadata()?)
    {
        bail!("APK staging descriptor identity mismatch");
    }
    readonly.seek(SeekFrom::Start(0))?;
    Ok(readonly)
}
