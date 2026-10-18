use anyhow::{bail, Context, Result};
use serde_json::Value;
use sha2::{Digest, Sha256};
use std::{
    fs::File,
    io::{Read, Seek, SeekFrom},
    os::fd::{AsRawFd, FromRawFd},
    sync::{
        atomic::{AtomicBool, AtomicU64, Ordering},
        Arc, Mutex,
    },
    time::{Duration, Instant},
};
mod http;
mod url_download;
mod url_metadata;
pub(crate) use url_download::start as download_url;
pub(crate) use url_metadata::inspect as url_info;
mod state;
#[cfg(test)]
mod tests;
pub use http::{cancel, download, upload};
use state::*;
fn create(args: &Value) -> Result<Arc<Transfer>> {
    let name = args
        .get("root")
        .and_then(Value::as_str)
        .unwrap_or("downloads");
    create_at(args, &crate::tools::files::root(name)?)
}

fn create_at(args: &Value, root: &std::path::Path) -> Result<Arc<Transfer>> {
    create_at_impl(args, root, false)
}

fn create_at_impl(
    args: &Value,
    root: &std::path::Path,
    allow_copy_commit: bool,
) -> Result<Arc<Transfer>> {
    let object = args
        .as_object()
        .context("task arguments must be an object")?;
    if object.keys().any(|key| {
        ![
            "root",
            "direction",
            "path",
            "sizeBytes",
            "sha256",
            "overwriteToken",
        ]
        .contains(&key.as_str())
    }) {
        bail!("unknown task argument");
    }
    let root_name = args
        .get("root")
        .map(|value| value.as_str().context("root must be a string"))
        .transpose()?
        .unwrap_or("downloads");
    if !["downloads", "inbox", "full"].contains(&root_name) {
        bail!("invalid transfer root");
    }
    let direction = args["direction"].as_str().context("direction required")?;
    let relative = args["path"].as_str().context("relative path required")?;
    let id = uuid::Uuid::new_v4().to_string();
    let (file, destination, size, digest, phase) = match direction {
        "download" => {
            if object.contains_key("sizeBytes") || object.contains_key("sha256") {
                bail!("download size and digest are determined by target");
            }
            let file = crate::tools::files::open(root, relative)?;
            let meta = file.metadata()?;
            if !meta.is_file() || meta.len() > MAX_FILE {
                bail!("invalid file or exceeds 1 TiB protocol quota");
            }
            (file, None, meta.len(), None, "hashing")
        }
        "upload" => {
            let size = args["sizeBytes"]
                .as_u64()
                .filter(|size| *size <= MAX_FILE)
                .context("upload size must be 0..1 TiB")?;
            let digest = args["sha256"]
                .as_str()
                .filter(|value| {
                    value.len() == 64
                        && value
                            .bytes()
                            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
                })
                .context("SHA-256 required")?;
            let (parent_path, name) = relative.rsplit_once('/').unwrap_or(("", relative));
            if name.is_empty()
                || name == "."
                || name == ".."
                || name.len() > 255
                || name.chars().any(char::is_control)
            {
                bail!("invalid upload filename");
            }
            let parent = crate::tools::files::open(root, parent_path)?;
            if !parent.metadata()?.is_dir() {
                bail!("upload parent must be a directory");
            }
            let target = std::ffi::CString::new(name)?;
            let mut stat = std::mem::MaybeUninit::<libc::statvfs>::uninit();
            if unsafe { libc::fstatvfs(parent.as_raw_fd(), stat.as_mut_ptr()) } != 0 {
                return Err(std::io::Error::last_os_error().into());
            }
            let stat = unsafe { stat.assume_init() };
            // statvfs fields vary between 32-bit Android and the 64-bit host.
            #[allow(clippy::unnecessary_cast)]
            let available = (stat.f_bavail as u64)
                .saturating_mul(stat.f_frsize as u64)
                .saturating_sub(16 * 1024 * 1024);
            if size > available {
                bail!("insufficient target storage space");
            }
            let expected = if let Some(token) = args.get("overwriteToken") {
                crate::file_actions::consume_overwrite(
                    token.as_str().context("overwriteToken must be string")?,
                    root_name,
                    relative,
                    &parent,
                )?
            } else {
                None
            };
            let overwrite = args.get("overwriteToken").is_some();
            let temporary = std::ffi::CString::new(format!(".airc-transfer-{id}.part"))?;
            let fd = unsafe {
                libc::openat(
                    parent.as_raw_fd(),
                    temporary.as_ptr(),
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
            let file = unsafe { File::from_raw_fd(fd) };
            let probe = std::ffi::CString::new(format!(".airc-transfer-{id}.probe"))?;
            let renamed = if overwrite {
                0
            } else {
                unsafe {
                    libc::syscall(
                        libc::SYS_renameat2,
                        parent.as_raw_fd(),
                        temporary.as_ptr(),
                        parent.as_raw_fd(),
                        probe.as_ptr(),
                        libc::RENAME_NOREPLACE,
                    )
                }
            };
            let rename_error = std::io::Error::last_os_error();
            let copy_commit = renamed != 0
                && allow_copy_commit
                && matches!(
                    rename_error.raw_os_error(),
                    Some(libc::EINVAL | libc::ENOSYS | libc::EOPNOTSUPP)
                );
            if renamed != 0 && !copy_commit {
                let error = rename_error;
                unsafe {
                    libc::unlinkat(parent.as_raw_fd(), temporary.as_ptr(), 0);
                }
                bail!("directory does not support safe atomic no-replace commit; choose service inbox: {error}");
            }
            let restored = if overwrite || copy_commit {
                0
            } else {
                unsafe {
                    libc::syscall(
                        libc::SYS_renameat2,
                        parent.as_raw_fd(),
                        probe.as_ptr(),
                        parent.as_raw_fd(),
                        temporary.as_ptr(),
                        libc::RENAME_NOREPLACE,
                    )
                }
            };
            if restored != 0 {
                let error = std::io::Error::last_os_error();
                unsafe {
                    libc::unlinkat(parent.as_raw_fd(), probe.as_ptr(), 0);
                }
                return Err(error.into());
            }
            (
                file,
                Some(UploadTarget {
                    parent,
                    temporary,
                    destination: target,
                    committed: false,
                    overwrite,
                    copy_commit,
                    expected,
                }),
                size,
                Some(digest.to_owned()),
                "awaiting_upload",
            )
        }
        _ => bail!("direction must be download or upload"),
    };
    Ok(Arc::new(Transfer {
        id,
        direction: direction.into(),
        root: root_name.into(),
        name: relative.rsplit('/').next().unwrap_or("").into(),
        size,
        bytes: AtomicU64::new(0),
        cancelled: AtomicBool::new(false),
        busy: AtomicBool::new(false),
        phase: Mutex::new(phase.into()),
        digest: Mutex::new(digest),
        error: Mutex::new(None),
        result: Mutex::new(None),
        file: Mutex::new(Some(file)),
        destination: Mutex::new(destination),
        touched: Mutex::new(Instant::now()),
        wake: tokio::sync::Notify::new(),
    }))
}

pub async fn execute(tool: &str, args: &Value) -> Result<String> {
    if tool == "task.start" {
        let mut registry = tasks().lock().unwrap();
        reserve(&mut registry)?;
        let task = create(args)?;
        registry.insert(task.id.clone(), task.clone());
        drop(registry);
        if task.direction == "download" {
            let worker = task.clone();
            tokio::task::spawn_blocking(move || {
                let result = (|| -> Result<()> {
                    cancelled(&worker)?;
                    let mut file = worker
                        .file
                        .lock()
                        .unwrap()
                        .as_ref()
                        .context("file unavailable")?
                        .try_clone()?;
                    let mut digest = Sha256::new();
                    let mut buffer = vec![0; CHUNK];
                    let mut count = 0;
                    loop {
                        cancelled(&worker)?;
                        let read = file.read(&mut buffer)?;
                        if read == 0 {
                            break;
                        }
                        count += read as u64;
                        if count > worker.size {
                            bail!("file changed while hashing");
                        }
                        digest.update(&buffer[..read]);
                        worker.bytes.store(count, Ordering::Relaxed);
                    }
                    if count != worker.size {
                        bail!("file changed while hashing");
                    }
                    file.seek(SeekFrom::Start(0))?;
                    *worker.digest.lock().unwrap() = Some(format!("{:x}", digest.finalize()));
                    let mut phase = worker.phase.lock().unwrap();
                    cancelled(&worker)?;
                    *phase = "ready".into();
                    Ok(())
                })();
                if let Err(error) = result {
                    worker.failed(error.to_string());
                }
            });
        }
        return Ok(task.progress().to_string());
    }
    let object = args.as_object().context("arguments must be object")?;
    if object.len() != 1 || !object.contains_key("id") {
        bail!("only task id is accepted");
    }
    let task = lookup(args["id"].as_str().context("task id required")?)?;
    if tool == "task.cancel" {
        let mut phase = task.phase.lock().unwrap();
        if task.direction == "apk_prepare" && phase.as_str() == "completed" {
            let id = task
                .result
                .lock()
                .unwrap()
                .as_ref()
                .and_then(|result| result["id"].as_str().map(str::to_owned));
            if let Some(id) = id {
                crate::app_actions::install::discard(&id);
            }
            task.result.lock().unwrap().take();
            *phase = "cancelled".into();
        }
        if !matches!(
            phase.as_str(),
            "completed" | "failed" | "cancelled" | "committing"
        ) {
            task.cancelled.store(true, Ordering::Relaxed);
            task.wake.notify_one();
            if !task.busy.load(Ordering::Acquire) {
                task.file.lock().unwrap().take();
                task.destination.lock().unwrap().take();
                *phase = "cancelled".into();
            }
        }
    }

    Ok(task.progress().to_string())
}

pub(crate) fn start_job(
    root: String,
    name: String,
    operation: impl FnOnce(&AtomicBool, &AtomicU64) -> Result<()> + Send + 'static,
) -> Result<Value> {
    start_background(root, name, "delete", 0, move |cancel, count| {
        operation(cancel, count)?;
        Ok(Value::Null)
    })
}
pub(crate) fn start_background(
    root: String,
    name: String,
    direction: &str,
    size: u64,
    operation: impl FnOnce(&AtomicBool, &AtomicU64) -> Result<Value> + Send + 'static,
) -> Result<Value> {
    let mut registry = tasks().lock().unwrap();
    reserve(&mut registry)?;
    let task = Arc::new(Transfer {
        id: uuid::Uuid::new_v4().to_string(),
        direction: direction.into(),
        root,
        name,
        size,
        bytes: AtomicU64::new(0),
        cancelled: AtomicBool::new(false),
        busy: AtomicBool::new(true),
        phase: Mutex::new(
            if direction == "delete" {
                "deleting"
            } else if direction == "storage_scan" {
                "scanning"
            } else if direction == "apk_prepare" {
                "preparing"
            } else if direction == "apk_inspect" {
                "inspecting"
            } else if matches!(
                direction,
                "app_uninstall" | "app_install" | "app_cache_clear" | "app_control"
            ) {
                "committing"
            } else {
                "publishing"
            }
            .into(),
        ),
        digest: Mutex::new(None),
        error: Mutex::new(None),
        result: Mutex::new(None),
        file: Mutex::new(None),
        destination: Mutex::new(None),
        touched: Mutex::new(Instant::now()),
        wake: tokio::sync::Notify::new(),
    });
    let result = task.progress();
    registry.insert(task.id.clone(), task.clone());
    drop(registry);
    tokio::task::spawn_blocking(move || {
        match operation(&task.cancelled, &task.bytes) {
            Ok(result) => {
                if matches!(
                    task.direction.as_str(),
                    "media_publish" | "apk_inspect" | "apk_prepare" | "storage_scan"
                ) && task.cancelled.load(Ordering::Relaxed)
                {
                    if let Some(id) = result["id"].as_str() {
                        if task.direction == "apk_prepare" {
                            crate::app_actions::install::discard(id);
                        } else {
                            let _ = crate::media_publications::release(id);
                        }
                    }
                    task.phase("cancelled");
                } else {
                    *task.result.lock().unwrap() = Some(result);
                    task.phase("completed");
                }
            }
            Err(error) => task.failed(error.to_string()),
        };
        task.busy.store(false, Ordering::Release);
    });
    Ok(result)
}

fn reserve(registry: &mut std::collections::HashMap<String, Arc<Transfer>>) -> Result<()> {
    registry.retain(|_, task| {
        task.busy.load(Ordering::Relaxed)
            || task.phase.lock().unwrap().as_str() == "hashing"
            || task.touched.lock().unwrap().elapsed() < Duration::from_secs(900)
    });
    if registry.len() >= 16 {
        let oldest = registry
            .iter()
            .filter(|(_, task)| {
                !task.busy.load(Ordering::Relaxed)
                    && matches!(
                        task.phase.lock().unwrap().as_str(),
                        "completed" | "failed" | "cancelled"
                    )
            })
            .min_by_key(|(_, task)| *task.touched.lock().unwrap())
            .map(|(id, _)| id.clone());
        if let Some(id) = oldest {
            registry.remove(&id);
        }
    }
    if registry.len() >= 16
        || registry
            .values()
            .filter(|task| {
                task.busy.load(Ordering::Relaxed)
                    || !matches!(
                        task.phase.lock().unwrap().as_str(),
                        "completed" | "failed" | "cancelled" | "ready"
                    )
            })
            .count()
            >= 2
    {
        bail!("transfer quota reached; cancel or finish active tasks");
    }
    Ok(())
}
