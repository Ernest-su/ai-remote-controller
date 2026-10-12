use anyhow::{bail, Context, Result};
use serde_json::{json, Value};
use std::{
    collections::HashMap,
    fs::{File, Metadata},
    os::fd::AsRawFd,
    sync::{
        atomic::{AtomicBool, AtomicU64},
        Mutex, OnceLock,
    },
    time::{Duration, Instant},
};
mod copy;
mod journal;
pub use journal::initialize;
mod storage;
#[cfg(test)]
mod tests;
struct Publication {
    id: String,
    parent: File,
    folder: File,
    folder_name: std::ffi::CString,
    folder_meta: Metadata,
    files: Vec<(std::ffi::CString, Metadata)>,
    _permit: tokio::sync::OwnedSemaphorePermit,
    created: Instant,
    created_at: u64,
    journal: Option<std::path::PathBuf>,
    result: Option<Value>,
}
impl Drop for Publication {
    fn drop(&mut self) {
        for (name, expected) in &self.files {
            if crate::file_actions::metadata(&self.folder, name)
                .ok()
                .flatten()
                .is_some_and(|actual| {
                    use std::os::unix::fs::MetadataExt;
                    actual.dev() == expected.dev() && actual.ino() == expected.ino()
                })
            {
                unsafe {
                    libc::unlinkat(self.folder.as_raw_fd(), name.as_ptr(), 0);
                }
            }
        }
        if crate::file_actions::metadata(&self.parent, &self.folder_name)
            .ok()
            .flatten()
            .is_some_and(|meta| {
                use std::os::unix::fs::MetadataExt;
                meta.dev() == self.folder_meta.dev() && meta.ino() == self.folder_meta.ino()
            })
        {
            unsafe {
                libc::unlinkat(
                    self.parent.as_raw_fd(),
                    self.folder_name.as_ptr(),
                    libc::AT_REMOVEDIR,
                );
            }
        }
        if let Some(path) = &self.journal {
            // Keep the journal if an owned item remains, so a later restart can retry cleanup.
            let remains = self.files.iter().any(|(name, expected)| {
                crate::file_actions::metadata(&self.folder, name)
                    .ok()
                    .flatten()
                    .is_some_and(|actual| {
                        use std::os::unix::fs::MetadataExt;
                        actual.dev() == expected.dev() && actual.ino() == expected.ino()
                    })
            });
            if !remains {
                let _ = std::fs::remove_file(path);
            }
        }
    }
}
static PUBLICATIONS: OnceLock<Mutex<HashMap<String, Publication>>> = OnceLock::new();
fn records() -> &'static Mutex<HashMap<String, Publication>> {
    PUBLICATIONS.get_or_init(|| Mutex::new(HashMap::new()))
}
pub fn release(id: &str) -> Result<()> {
    let record = records()
        .lock()
        .unwrap()
        .remove(id)
        .context("media copy not found")?;
    drop(record);
    Ok(())
}
pub fn start(args: &Value) -> Result<String> {
    let object = args.as_object().context("arguments must be object")?;
    if object
        .keys()
        .any(|key| !["root", "path"].contains(&key.as_str()))
    {
        bail!("unknown publication parameter");
    }
    let root = args
        .get("root")
        .map(|v| v.as_str().context("root must be string"))
        .transpose()?
        .unwrap_or("inbox");
    let path = args["path"].as_str().context("path required")?;
    let source = crate::tools::files::open(&crate::tools::files::root(root)?, path)?;
    let size = source.metadata()?.len();
    if !source.metadata()?.is_file() || size > 1024 * 1024 * 1024 * 1024 {
        bail!("media copy requires a regular file up to 1 TiB");
    }
    super::tools::media_open::mime(path, None)?;
    records()
        .lock()
        .unwrap()
        .retain(|_, record| record.created.elapsed() < Duration::from_secs(86400));
    let destination = crate::tools::files::root("downloads")?;
    let name = path
        .rsplit('/')
        .next()
        .context("filename required")?
        .to_string();
    Ok(crate::transfers::start_background(
        root.into(),
        path.into(),
        "media_publish",
        size,
        move |cancel, progress| publish(source, &destination, &name, cancel, progress),
    )?
    .to_string())
}
fn publish(
    source: File,
    destination: &std::path::Path,
    name: &str,
    cancel: &AtomicBool,
    progress: &AtomicU64,
) -> Result<Value> {
    let (mut record, output) = storage::create(destination, source.metadata()?.len(), name)?;
    let digest = copy::stream(source, output, cancel, progress)?;
    copy::verify(&record, name, &digest, cancel)?;
    if cancel.load(std::sync::atomic::Ordering::Relaxed) {
        bail!("media publication cancelled");
    }
    let current = crate::file_actions::metadata(&record.parent, &record.folder_name)?
        .context("playback folder moved")?;
    {
        use std::os::unix::fs::MetadataExt;
        if current.dev() != record.folder_meta.dev() || current.ino() != record.folder_meta.ino() {
            bail!("playback folder changed");
        }
    }
    let path = format!("{}/{}", record.folder_name.to_str()?, name);
    let result = json!({"id":record.id,"root":"downloads","path":path,"sizeBytes":progress.load(std::sync::atomic::Ordering::Relaxed),"sha256":digest});
    record.result = Some(result.clone());
    journal::save(&record)?;
    let mut registry = records().lock().unwrap();
    registry.retain(|_, record| record.created.elapsed() < Duration::from_secs(86400));
    if registry.len() >= 4 {
        bail!("four playback copies already exist; release one first");
    }
    registry.insert(record.id.clone(), record);
    Ok(result)
}

pub fn list(args: &Value) -> Result<String> {
    if args.as_object().is_none_or(|v| !v.is_empty()) {
        bail!("publication list accepts no parameters");
    }
    let registry = records().lock().unwrap();
    let mut copies: Vec<_> = registry.values().filter_map(|r| r.result.clone()).collect();
    copies.sort_by_key(|v| v["id"].as_str().unwrap_or("").to_string());
    Ok(serde_json::to_string(&copies)?)
}
