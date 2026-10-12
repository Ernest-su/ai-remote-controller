use super::*;
use sha2::{Digest, Sha256};
use std::{
    io::{Read, Write},
    sync::atomic::Ordering,
};
pub(super) fn stream(
    mut source: File,
    mut output: File,
    cancel: &AtomicBool,
    progress: &AtomicU64,
) -> Result<String> {
    let original = source.metadata()?;
    let mut hash = Sha256::new();
    let mut buffer = vec![0; 64 * 1024];
    let mut count = 0u64;
    loop {
        if cancel.load(Ordering::Relaxed) {
            bail!("media publication cancelled");
        }
        let n = source.read(&mut buffer)?;
        if n == 0 {
            break;
        }
        count += n as u64;
        if count > original.len() {
            bail!("source grew during media copy");
        }
        output.write_all(&buffer[..n])?;
        hash.update(&buffer[..n]);
        progress.store(count, Ordering::Relaxed);
    }
    if count != original.len() || !crate::file_actions::same(&original, &source.metadata()?) {
        bail!("source changed during media copy");
    }
    output.sync_all()?;
    if cancel.load(Ordering::Relaxed) {
        bail!("media publication cancelled");
    }
    Ok(format!("{:x}", hash.finalize()))
}

pub(super) fn verify(
    record: &Publication,
    name: &str,
    expected: &str,
    cancel: &AtomicBool,
) -> Result<()> {
    use std::os::fd::FromRawFd;
    use std::os::unix::fs::MetadataExt;
    let name = std::ffi::CString::new(name)?;
    let original = record
        .files
        .iter()
        .find(|(file, _)| file == &name)
        .context("copy identity unavailable")?
        .1
        .clone();
    let fd = unsafe {
        libc::openat(
            record.folder.as_raw_fd(),
            name.as_ptr(),
            libc::O_RDONLY | libc::O_NOFOLLOW | libc::O_CLOEXEC | libc::O_LARGEFILE,
        )
    };
    if fd < 0 {
        return Err(std::io::Error::last_os_error().into());
    }
    let mut file = unsafe { File::from_raw_fd(fd) };
    use std::io::{Seek, SeekFrom};
    // Shared storage can update metadata after a just-written file is closed.
    // Require a stable complete read with matching digest; retry metadata-only movement.
    for _ in 0..3 {
        file.seek(SeekFrom::Start(0))?;
        let before = file.metadata()?;
        if before.ino() != original.ino() || before.dev() != original.dev() {
            bail!("playback copy replaced during preparation");
        }
        let mut hash = Sha256::new();
        let mut buffer = vec![0; 64 * 1024];
        loop {
            if cancel.load(Ordering::Relaxed) {
                bail!("media publication cancelled");
            }
            let n = file.read(&mut buffer)?;
            if n == 0 {
                break;
            }
            hash.update(&buffer[..n]);
        }
        if format!("{:x}", hash.finalize()) != expected {
            bail!("playback copy SHA-256 mismatch");
        }
        let after = file.metadata()?;
        let current = crate::file_actions::metadata(&record.folder, &name)?
            .context("playback copy disappeared")?;
        if current.ino() != original.ino() || current.dev() != original.dev() {
            bail!("playback copy replaced during verification");
        }
        if crate::file_actions::same(&before, &after) && crate::file_actions::same(&after, &current)
        {
            return Ok(());
        }
    }
    bail!("playback copy metadata remains unstable; retry publication")
}
