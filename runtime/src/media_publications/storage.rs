use super::*;
use std::os::fd::FromRawFd;
pub(super) fn create(
    destination: &std::path::Path,
    size: u64,
    name: &str,
) -> Result<(Publication, File)> {
    let permit = permit()?;
    let parent = crate::tools::files::open(destination, "")?;
    let mut space = std::mem::MaybeUninit::<libc::statvfs>::uninit();
    if unsafe { libc::fstatvfs(parent.as_raw_fd(), space.as_mut_ptr()) } != 0 {
        return Err(std::io::Error::last_os_error().into());
    }
    let space = unsafe { space.assume_init() };
    #[allow(clippy::unnecessary_cast)]
    let available = (space.f_bavail as u64)
        .saturating_mul(space.f_frsize as u64)
        .saturating_sub(16 * 1024 * 1024);
    if size > available {
        bail!("insufficient Downloads space for playback copy");
    }
    let id = uuid::Uuid::new_v4().to_string();
    let folder_name = std::ffi::CString::new(format!(".airc-media-{id}"))?;
    if unsafe { libc::mkdirat(parent.as_raw_fd(), folder_name.as_ptr(), 0o755) } != 0 {
        return Err(std::io::Error::last_os_error().into());
    }
    let fd = unsafe {
        libc::openat(
            parent.as_raw_fd(),
            folder_name.as_ptr(),
            libc::O_DIRECTORY | libc::O_NOFOLLOW | libc::O_RDONLY | libc::O_CLOEXEC,
        )
    };
    if fd < 0 {
        unsafe {
            libc::unlinkat(parent.as_raw_fd(), folder_name.as_ptr(), libc::AT_REMOVEDIR);
        }
        return Err(std::io::Error::last_os_error().into());
    }
    let folder = unsafe { File::from_raw_fd(fd) };
    let folder_meta = folder.metadata()?;
    let mut record = Publication {
        id: id.clone(),
        parent,
        folder,
        folder_name,
        folder_meta,
        files: vec![],
        _permit: permit,
        created: Instant::now(),
        created_at: super::journal::now(),
        journal: super::journal::path(&id),
        result: None,
    };
    super::journal::save(&record)?;
    let mut create_file = |name: &str| -> Result<(File, std::ffi::CString)> {
        let name = std::ffi::CString::new(name)?;
        let fd = unsafe {
            libc::openat(
                record.folder.as_raw_fd(),
                name.as_ptr(),
                libc::O_CREAT
                    | libc::O_EXCL
                    | libc::O_WRONLY
                    | libc::O_NOFOLLOW
                    | libc::O_LARGEFILE
                    | libc::O_CLOEXEC,
                0o644,
            )
        };
        if fd < 0 {
            return Err(std::io::Error::last_os_error().into());
        }
        let file = unsafe { File::from_raw_fd(fd) };
        record.files.push((name.clone(), file.metadata()?));
        super::journal::save(&record)?;
        Ok((file, name))
    };
    let (marker, _) = create_file(".nomedia")?;
    marker.sync_all()?;
    let (output, _) = create_file(name)?;
    Ok((record, output))
}

pub(super) fn permit() -> Result<tokio::sync::OwnedSemaphorePermit> {
    static SLOTS: std::sync::OnceLock<std::sync::Arc<tokio::sync::Semaphore>> =
        std::sync::OnceLock::new();
    let permit = SLOTS
        .get_or_init(|| std::sync::Arc::new(tokio::sync::Semaphore::new(4)))
        .clone()
        .try_acquire_owned()
        .context("four playback copies are active; release one first")?;
    Ok(permit)
}
