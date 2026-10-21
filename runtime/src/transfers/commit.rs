//! Exclusive-copy fallback for filesystems without atomic no-replace rename.
use super::state::*;
use anyhow::{bail, Result};
use sha2::{Digest, Sha256};
use std::{
    fs::File,
    os::fd::{AsRawFd, FromRawFd},
};
use tokio::io::AsyncWriteExt;

// Android shared-storage FUSE can reject renameat2(RENAME_NOREPLACE). Finish fetching
// into the private temporary file, then copy to an exclusively created destination.
// The destination descriptor never overwrites an existing entry, even under races.
pub(super) async fn copy_to_exclusive(
    target: UploadTarget,
    size: u64,
    expected_digest: &str,
) -> Result<()> {
    use std::os::unix::fs::MetadataExt;
    use tokio::io::AsyncReadExt;
    let input_fd = unsafe {
        libc::openat(
            target.parent.as_raw_fd(),
            target.temporary.as_ptr(),
            libc::O_RDONLY
                | libc::O_LARGEFILE
                | libc::O_NOFOLLOW
                | libc::O_NONBLOCK
                | libc::O_CLOEXEC,
        )
    };
    if input_fd < 0 {
        return Err(std::io::Error::last_os_error().into());
    }
    let input = unsafe { File::from_raw_fd(input_fd) };
    if !input.metadata()?.is_file() {
        bail!("transfer temporary file is no longer a regular file");
    }
    ensure_space(input.as_raw_fd(), size)?;
    let parent = target.parent.try_clone()?;
    let fd = unsafe {
        libc::openat(
            target.parent.as_raw_fd(),
            target.destination.as_ptr(),
            libc::O_WRONLY
                | libc::O_LARGEFILE
                | libc::O_CREAT
                | libc::O_EXCL
                | libc::O_NOFOLLOW
                | libc::O_CLOEXEC,
            0o600,
        )
    };
    if fd < 0 {
        return Err(std::io::Error::last_os_error().into());
    }
    let output = unsafe { File::from_raw_fd(fd) };
    let metadata = output.metadata()?;
    let mut guard = ExclusiveCopy {
        parent,
        name: target.destination.clone(),
        device: metadata.dev(),
        inode: metadata.ino(),
        keep: false,
    };
    let mut input = tokio::fs::File::from_std(input);
    let mut output = tokio::fs::File::from_std(output);
    let mut buffer = vec![0; CHUNK];
    let mut count = 0;
    let mut digest = Sha256::new();
    loop {
        let read = input.read(&mut buffer).await?;
        if read == 0 {
            break;
        }
        count += read as u64;
        if count > size {
            bail!("transfer temporary file changed before saving");
        }
        output.write_all(&buffer[..read]).await?;
        digest.update(&buffer[..read]);
    }
    if count != size || format!("{:x}", digest.finalize()) != expected_digest {
        bail!("transfer temporary file changed before saving");
    }
    output.flush().await?;
    output.sync_all().await?;
    if !guard.owned() {
        bail!("transfer destination changed while saving");
    }
    guard.keep = true;
    // UploadTarget drops here and removes only the temporary file.
    Ok(())
}

struct ExclusiveCopy {
    parent: File,
    name: std::ffi::CString,
    device: u64,
    inode: u64,
    keep: bool,
}
impl ExclusiveCopy {
    fn owned(&self) -> bool {
        use std::os::unix::fs::MetadataExt;
        crate::file_actions::metadata(&self.parent, &self.name)
            .ok()
            .flatten()
            .is_some_and(|m| m.dev() == self.device && m.ino() == self.inode && m.is_file())
    }
}
impl Drop for ExclusiveCopy {
    fn drop(&mut self) {
        if !self.keep && self.owned() {
            unsafe {
                libc::unlinkat(self.parent.as_raw_fd(), self.name.as_ptr(), 0);
            }
        }
    }
}

pub(super) fn ensure_space(fd: std::os::fd::RawFd, required: u64) -> Result<()> {
    let mut stat = std::mem::MaybeUninit::<libc::statvfs>::uninit();
    if unsafe { libc::fstatvfs(fd, stat.as_mut_ptr()) } != 0 {
        return Err(std::io::Error::last_os_error().into());
    }
    let stat = unsafe { stat.assume_init() };
    // statvfs fields differ between 32-bit Android and the host.
    #[allow(clippy::unnecessary_cast)]
    let available = (stat.f_bavail as u64).saturating_mul(stat.f_frsize as u64);
    if available < required.saturating_add(16 * 1024 * 1024) {
        bail!("insufficient target storage space; 16 MiB reserve required");
    }
    Ok(())
}
