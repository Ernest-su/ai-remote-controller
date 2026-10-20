//! Read-only traversal anchored to directory descriptors. Mutations use files::open instead.
use anyhow::{bail, Context, Result};
use std::{
    collections::VecDeque,
    ffi::CString,
    fs::{File, OpenOptions},
    os::{
        fd::{AsRawFd, FromRawFd},
        unix::fs::{MetadataExt, OpenOptionsExt},
    },
    path::{Path, PathBuf},
};

pub(crate) struct Resolved {
    pub file: File,
    pub followed: bool,
    pub link_target: Option<String>,
}

fn fd_path(file: &File) -> PathBuf {
    PathBuf::from(format!("/proc/self/fd/{}", file.as_raw_fd()))
}

fn checked_path(root: &File, file: &File) -> Result<()> {
    let base = std::fs::read_link(fd_path(root))?;
    let target = std::fs::read_link(fd_path(file))?;
    if root.metadata()?.nlink() == 0 || file.metadata()?.nlink() == 0 || !target.starts_with(&base)
    {
        bail!("link_outside_root");
    }
    Ok(())
}

fn link_text(file: &File) -> Result<String> {
    let mut fs = std::mem::MaybeUninit::<libc::statfs>::uninit();
    if unsafe { libc::fstatfs(file.as_raw_fd(), fs.as_mut_ptr()) } != 0 {
        return Err(std::io::Error::last_os_error().into());
    }
    // Reject procfs links, including fd/root/cwd magic links, before reading their targets.
    if unsafe { fs.assume_init() }.f_type as u64 == 0x9fa0 {
        bail!("link_special");
    }
    let mut bytes = vec![0u8; 4097];
    let length = unsafe {
        libc::readlinkat(
            file.as_raw_fd(),
            c"".as_ptr(),
            bytes.as_mut_ptr().cast(),
            bytes.len(),
        )
    };
    if length < 0 {
        return Err(std::io::Error::last_os_error().into());
    }
    if length == 0 || length as usize >= bytes.len() {
        bail!("link_invalid");
    }
    bytes.truncate(length as usize);
    let value = String::from_utf8(bytes).context("link_invalid")?;
    if value.chars().any(char::is_control) {
        bail!("link_invalid");
    }
    Ok(value)
}

fn open_component(parent: &File, name: &str) -> Result<File> {
    let name = CString::new(name)?;
    let fd = unsafe {
        libc::openat(
            parent.as_raw_fd(),
            name.as_ptr(),
            libc::O_PATH | libc::O_NOFOLLOW | libc::O_CLOEXEC,
        )
    };
    if fd < 0 {
        return Err(std::io::Error::last_os_error().into());
    }
    Ok(unsafe { File::from_raw_fd(fd) })
}

pub(super) fn resolve(root: &Path, relative: &str) -> Result<Resolved> {
    let parts = super::components(relative)?;
    let base = OpenOptions::new()
        .read(true)
        .custom_flags(libc::O_PATH | libc::O_DIRECTORY | libc::O_NOFOLLOW | libc::O_CLOEXEC)
        .open(root)?;
    let real_root = std::fs::read_link(fd_path(&base))?;
    let full = real_root == Path::new("/");
    let mut stack = vec![base];
    let mut pending: VecDeque<String> = parts.into_iter().map(str::to_owned).collect();
    let mut followed = false;
    let mut link_target = None;
    let mut links = 0;
    let mut steps = 0;
    while let Some(part) = pending.pop_front() {
        steps += 1;
        if steps > 4096 || stack.len() > 256 {
            bail!("link_invalid");
        }
        if part == "." || part.is_empty() {
            continue;
        }
        if part == ".." {
            if stack.len() > 1 {
                stack.pop();
            } else if !full {
                bail!("link_outside_root");
            }
            continue;
        }
        let file = open_component(stack.last().unwrap(), &part)?;
        checked_path(&stack[0], &file)?;
        let metadata = file.metadata()?;
        if metadata.is_symlink() {
            links += 1;
            if links > 40 {
                bail!("link_loop");
            }
            let target = link_text(&file)?;
            // Keep the selected leaf link's text for properties; parent aliases are not the leaf.
            if pending.is_empty() && link_target.is_none() {
                link_target = Some(target.clone());
            }
            followed = true;
            let path = Path::new(&target);
            let suffix = if path.is_absolute() {
                let suffix = path
                    .strip_prefix(&real_root)
                    .or_else(|_| path.strip_prefix(root))
                    .map_err(|_| anyhow::anyhow!("link_outside_root"))?;
                stack.truncate(1);
                suffix.to_str().context("link_invalid")?
            } else {
                &target
            };
            let mut expanded: VecDeque<String> = suffix
                .split('/')
                .filter(|s| !s.is_empty())
                .map(str::to_owned)
                .collect();
            if target.ends_with('/') {
                expanded.push_back(".".into());
            }
            expanded.append(&mut pending);
            if expanded.len() > 4096 {
                bail!("link_invalid");
            }
            pending = expanded;
        } else {
            if !metadata.is_dir() && !metadata.is_file() {
                bail!("link_special");
            }
            if !pending.is_empty() && !metadata.is_dir() {
                bail!("link_not_directory");
            }
            stack.push(file);
        }
    }
    let file = stack.pop().unwrap();
    if let Some(base) = stack.first() {
        checked_path(base, &file)?;
    }
    Ok(Resolved {
        file,
        followed,
        link_target,
    })
}

pub(crate) fn open_read(root: &Path, relative: &str) -> Result<Resolved> {
    let resolved = resolve(root, relative)?;
    // Reopen our own pinned, type-checked descriptor, never a mutable external pathname.
    let file = OpenOptions::new()
        .read(true)
        .custom_flags(libc::O_CLOEXEC | libc::O_NONBLOCK | libc::O_LARGEFILE)
        .open(fd_path(&resolved.file))?;
    let before = resolved.file.metadata()?;
    let after = file.metadata()?;
    if before.dev() != after.dev() || before.ino() != after.ino() {
        bail!("link_changed");
    }
    Ok(Resolved { file, ..resolved })
}

pub(super) fn error_code(error: &anyhow::Error) -> &'static str {
    if let Some(error) = error.downcast_ref::<std::io::Error>() {
        return match error.raw_os_error() {
            Some(libc::ENOENT) => "link_missing",
            Some(libc::EACCES | libc::EPERM) => "link_permission",
            Some(libc::ELOOP) => "link_loop",
            Some(libc::ENOTDIR) => "link_not_directory",
            _ => "link_unavailable",
        };
    }
    match error.to_string().as_str() {
        "link_outside_root" => "link_outside_root",
        "link_loop" => "link_loop",
        "link_special" => "link_special",
        "link_invalid" => "link_invalid",
        "link_not_directory" => "link_not_directory",
        "link_changed" => "link_changed",
        _ => "link_unavailable",
    }
}

pub(super) fn describe(
    root: &Path,
    relative: &str,
    name: &str,
    symlink: bool,
) -> serde_json::Value {
    match open_read(root, relative).and_then(|resolved| {
        let metadata = resolved.file.metadata()?;
        Ok((resolved, metadata))
    }) {
        Ok((resolved, metadata)) => {
            serde_json::json!({"name":name,"kind":if metadata.is_dir(){"directory"}else{"file"},
                "sizeBytes":if metadata.is_file(){Some(metadata.len())}else{None},
                "symlink":symlink,"linkTarget":if symlink { resolved.link_target } else { None },
                "targetName":if symlink { std::fs::read_link(fd_path(&resolved.file)).ok().and_then(|p| p.file_name().and_then(|n|n.to_str()).map(str::to_owned)) } else {None}})
        }
        Err(error) => {
            // Read the leaf's own text even when its destination is missing/outside the scope.
            let (parent_path, leaf) = relative.rsplit_once('/').unwrap_or(("", relative));
            let target = open_read(root, parent_path)
                .ok()
                .and_then(|parent| open_component(&parent.file, leaf).ok())
                .and_then(|file| link_text(&file).ok());
            serde_json::json!({"name":name,"kind":"symlink","symlink":true,"linkTarget":target,"linkError":error_code(&error)})
        }
    }
}

#[cfg(test)]
#[path = "file_links_tests.rs"]
mod tests;
