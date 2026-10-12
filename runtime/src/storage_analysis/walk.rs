use super::report::Report;
use anyhow::{bail, Context, Result};
use std::{
    collections::HashSet,
    ffi::CString,
    fs::File,
    os::{
        fd::{AsRawFd, FromRawFd},
        unix::fs::MetadataExt,
    },
    sync::atomic::{AtomicBool, AtomicU64, Ordering},
    time::{Duration, Instant},
};
pub(super) struct Scanner<'a> {
    pub report: Report,
    pub limit: u64,
    pub cancel: &'a AtomicBool,
    pub progress: &'a AtomicU64,
    device: u64,
    files: HashSet<(u64, u64)>,
    directories: HashSet<(u64, u64)>,
    started: Instant,
}
impl<'a> Scanner<'a> {
    pub fn new(
        report: Report,
        device: u64,
        limit: u64,
        cancel: &'a AtomicBool,
        progress: &'a AtomicU64,
    ) -> Self {
        Self {
            report,
            device,
            limit,
            cancel,
            progress,
            files: HashSet::new(),
            directories: HashSet::new(),
            started: Instant::now(),
        }
    }
    fn check(&self) -> Result<()> {
        if self.cancel.load(Ordering::Relaxed) {
            bail!("storage analysis cancelled");
        }
        Ok(())
    }
    pub fn walk(&mut self, folder: File, prefix: String, depth: usize) -> Result<()> {
        self.check()?;
        let before = folder.metadata()?;
        if !self.directories.insert((before.dev(), before.ino())) {
            self.report.repeated_directories += 1;
            return Ok(());
        }
        self.report.directories += 1;
        let entries = match std::fs::read_dir(format!("/proc/self/fd/{}", folder.as_raw_fd())) {
            Ok(entries) => entries,
            Err(error) => {
                self.report.error(&prefix, error);
                return Ok(());
            }
        };
        for entry in entries {
            self.check()?;
            if self.report.entries_visited >= self.limit
                || self.started.elapsed() >= Duration::from_secs(120)
            {
                self.report.truncated = true;
                break;
            }
            self.report.entries_visited += 1;
            self.progress
                .store(self.report.entries_visited, Ordering::Relaxed);
            let entry = match entry {
                Ok(entry) => entry,
                Err(error) => {
                    self.report.error(&prefix, error);
                    continue;
                }
            };
            let name = entry.file_name();
            let Some(name) = name
                .to_str()
                .filter(|name| !name.chars().any(char::is_control))
            else {
                self.report
                    .error(&prefix, "unrepresentable entry name skipped");
                continue;
            };
            let path = if prefix.is_empty() {
                name.to_owned()
            } else {
                format!("{prefix}/{name}")
            };
            if path.len() > 2048 {
                self.report.truncated = true;
                self.report
                    .error(&prefix, "relative path exceeds 2048 bytes");
                continue;
            }
            let meta = match entry.path().symlink_metadata() {
                Ok(meta) => meta,
                Err(error) => {
                    self.report.error(&path, error);
                    continue;
                }
            };
            if meta.file_type().is_symlink() {
                self.report.skipped_links += 1;
                continue;
            }
            if meta.dev() != self.device {
                self.report.skipped_filesystems += 1;
                continue;
            }
            if meta.is_file() {
                if !self.files.insert((meta.dev(), meta.ino())) {
                    self.report.duplicate_files += 1;
                    continue;
                }
                let Some(allocated) = meta.blocks().checked_mul(512) else {
                    self.report.error(&path, "allocated block count overflow");
                    continue;
                };
                self.report.file(path, meta.len(), allocated)?;
            } else if meta.is_dir() {
                if depth >= 64 {
                    self.report.truncated = true;
                    self.report.error(&path, "directory depth exceeds 64");
                    continue;
                }
                let name = CString::new(name)?;
                let fd = unsafe {
                    libc::openat(
                        folder.as_raw_fd(),
                        name.as_ptr(),
                        libc::O_RDONLY
                            | libc::O_DIRECTORY
                            | libc::O_NOFOLLOW
                            | libc::O_CLOEXEC
                            | libc::O_LARGEFILE,
                    )
                };
                if fd < 0 {
                    self.report.error(&path, std::io::Error::last_os_error());
                    continue;
                }
                let child = unsafe { File::from_raw_fd(fd) };
                let current = child.metadata()?;
                if current.dev() != meta.dev() || current.ino() != meta.ino() {
                    self.report
                        .error(&path, "directory identity changed during scan");
                    continue;
                }
                self.walk(child, path, depth + 1)?;
                if self.report.truncated
                    && (self.report.entries_visited >= self.limit
                        || self.started.elapsed() >= Duration::from_secs(120))
                {
                    break;
                }
            } else {
                self.report.skipped_special += 1;
            }
        }
        let after = folder
            .metadata()
            .context("cannot recheck scanned directory")?;
        if (
            before.mtime(),
            before.mtime_nsec(),
            before.ctime(),
            before.ctime_nsec(),
        ) != (
            after.mtime(),
            after.mtime_nsec(),
            after.ctime(),
            after.ctime_nsec(),
        ) {
            self.report.changed_directories += 1;
        }
        Ok(())
    }
}
