//! Bounded no-clobber fallback for Android FUSE without RENAME_NOREPLACE.
use anyhow::{bail, Context, Result};
use std::{
    ffi::{CStr, CString},
    fs::{File, Metadata},
    io::Read,
    os::fd::{AsRawFd, FromRawFd},
    os::unix::fs::MetadataExt,
};
struct Snapshot {
    metadata: Metadata,
    children: Vec<(CString, Snapshot)>,
}
struct Budget {
    bytes: u64,
    entries: usize,
    device: Option<u64>,
}
fn directory(parent: &File, name: &CStr) -> Result<File> {
    let fd = unsafe {
        libc::openat(
            parent.as_raw_fd(),
            name.as_ptr(),
            libc::O_RDONLY | libc::O_DIRECTORY | libc::O_NOFOLLOW | libc::O_CLOEXEC,
        )
    };
    if fd < 0 {
        return Err(std::io::Error::last_os_error().into());
    }
    Ok(unsafe { File::from_raw_fd(fd) })
}
fn copy(
    parent: &File,
    name: &CStr,
    output: &File,
    new: &CStr,
    depth: usize,
    budget: &mut Budget,
) -> Result<Snapshot> {
    if depth > 64 || budget.entries == 0 {
        bail!("此文件系统的安全重命名上限为2000项、64层，请缩小目录后重试");
    }
    budget.entries -= 1;
    let metadata = crate::file_actions::metadata(parent, name)?.context("source disappeared")?;
    if let Some(device) = budget.device {
        if metadata.dev() != device {
            bail!("安全重命名不跨越挂载点");
        }
    } else {
        budget.device = Some(metadata.dev());
    }
    let mut children = Vec::new();
    if metadata.is_symlink() {
        let target = std::fs::read_link(format!(
            "/proc/self/fd/{}/{}",
            parent.as_raw_fd(),
            name.to_str()?
        ))?;
        use std::os::unix::ffi::OsStrExt;
        let target = CString::new(target.as_os_str().as_bytes())?;
        if unsafe { libc::symlinkat(target.as_ptr(), output.as_raw_fd(), new.as_ptr()) } != 0 {
            return Err(std::io::Error::last_os_error().into());
        }
    } else if metadata.is_dir() {
        let source = directory(parent, name)?;
        if unsafe { libc::mkdirat(output.as_raw_fd(), new.as_ptr(), 0o700) } != 0 {
            return Err(std::io::Error::last_os_error().into());
        }
        let destination = directory(output, new)?;
        for entry in std::fs::read_dir(format!("/proc/self/fd/{}", source.as_raw_fd()))? {
            let entry = entry?;
            let name = CString::new(entry.file_name().as_encoded_bytes())?;
            children.push((
                name.clone(),
                copy(&source, &name, &destination, &name, depth + 1, budget)?,
            ));
        }
    } else if metadata.is_file() {
        if metadata.len() > budget.bytes {
            bail!("此文件系统的安全重命名复制上限为128 MiB，原文件保留");
        }
        budget.bytes -= metadata.len();
        let input = unsafe {
            libc::openat(
                parent.as_raw_fd(),
                name.as_ptr(),
                libc::O_RDONLY | libc::O_NOFOLLOW | libc::O_CLOEXEC | libc::O_NONBLOCK,
            )
        };
        if input < 0 {
            return Err(std::io::Error::last_os_error().into());
        }
        let input = unsafe { File::from_raw_fd(input) };
        if !crate::file_actions::same(&metadata, &input.metadata()?) {
            bail!("source changed");
        }
        let fd = unsafe {
            libc::openat(
                output.as_raw_fd(),
                new.as_ptr(),
                libc::O_WRONLY | libc::O_CREAT | libc::O_EXCL | libc::O_NOFOLLOW | libc::O_CLOEXEC,
                0o600,
            )
        };
        if fd < 0 {
            return Err(std::io::Error::last_os_error().into());
        }
        let mut destination = unsafe { File::from_raw_fd(fd) };
        let count = std::io::copy(&mut input.take(metadata.len() + 1), &mut destination)?;
        destination.sync_all()?;
        if count != metadata.len() {
            bail!("source changed while copying");
        }
    } else {
        bail!("unsupported file type");
    }
    let now = crate::file_actions::metadata(parent, name)?.context("source disappeared")?;
    if !crate::file_actions::same(&metadata, &now) {
        bail!("source changed while copying");
    }
    Ok(Snapshot { metadata, children })
}
fn validate(parent: &File, name: &CStr, snapshot: &Snapshot) -> Result<()> {
    let current = crate::file_actions::metadata(parent, name)?.context("source disappeared")?;
    if !crate::file_actions::same(&current, &snapshot.metadata) {
        bail!("source changed; complete new copy retained");
    }
    if current.is_dir() {
        let source = directory(parent, name)?;
        for (name, child) in &snapshot.children {
            validate(&source, name, child)?;
        }
    }
    Ok(())
}
fn remove(parent: &File, name: &CStr, snapshot: &Snapshot) -> Result<()> {
    let current = crate::file_actions::metadata(parent, name)?.context("source disappeared")?;
    if !crate::file_actions::same(&current, &snapshot.metadata) {
        bail!("source changed; complete new copy retained");
    }
    if current.is_dir() {
        let source = directory(parent, name)?;
        for (name, child) in &snapshot.children {
            remove(&source, name, child)?;
        }
        let current = crate::file_actions::metadata(parent, name)?.context("source disappeared")?;
        if current.dev() != snapshot.metadata.dev() || current.ino() != snapshot.metadata.ino() {
            bail!("source directory changed");
        }
    }
    if unsafe {
        libc::unlinkat(
            parent.as_raw_fd(),
            name.as_ptr(),
            if snapshot.metadata.is_dir() {
                libc::AT_REMOVEDIR
            } else {
                0
            },
        )
    } != 0
    {
        return Err(std::io::Error::last_os_error().into());
    }
    Ok(())
}
pub(super) fn execute(parent: &File, source: &CStr, destination: &CStr) -> Result<()> {
    let snapshot = copy(
        parent,
        source,
        parent,
        destination,
        0,
        &mut Budget {
            bytes: 128 * 1024 * 1024,
            entries: 2000,
            device: None,
        },
    )
    .context("安全复制未完成，原文件保留；新名称下可能留有部分副本")?;
    validate(parent, source, &snapshot)?;
    remove(parent, source, &snapshot)
        .context("新名称下已保留完整副本，原名称下有变动或残留，请刷新查看")
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn copies_then_removes_without_following_links_or_overwriting_existing_files() {
        let root = std::env::temp_dir().join(format!("airc-rename-copy-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(root.join("old/child")).unwrap();
        std::fs::write(root.join("old/child/data"), b"original").unwrap();
        std::os::unix::fs::symlink("child/data", root.join("old/link")).unwrap();
        let parent = File::open(&root).unwrap();
        let old = CString::new("old").unwrap();
        let new = CString::new("new").unwrap();
        execute(&parent, &old, &new).unwrap();
        assert!(!root.join("old").exists());
        assert_eq!(
            std::fs::read(root.join("new/child/data")).unwrap(),
            b"original"
        );
        assert!(root.join("new/link").is_symlink());
        std::fs::create_dir(root.join("old")).unwrap();
        assert!(execute(&parent, &old, &new).is_err());
        assert!(root.join("old").exists());
        assert_eq!(
            std::fs::read(root.join("new/child/data")).unwrap(),
            b"original"
        );
        std::fs::remove_dir_all(root).unwrap();
    }
}
