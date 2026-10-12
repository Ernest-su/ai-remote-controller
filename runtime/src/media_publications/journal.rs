use super::*;
use serde::{Deserialize, Serialize};
use std::{
    os::unix::fs::MetadataExt,
    path::{Path, PathBuf},
};
static DIRECTORY: OnceLock<PathBuf> = OnceLock::new();
#[derive(Serialize, Deserialize)]
struct Entry {
    name: String,
    dev: u64,
    ino: u64,
}
#[derive(Serialize, Deserialize)]
struct Saved {
    id: String,
    dev: u64,
    ino: u64,
    created: u64,
    files: Vec<Entry>,
    result: Option<Value>,
}
pub(super) fn path(id: &str) -> Option<PathBuf> {
    DIRECTORY.get().map(|dir| dir.join(format!("{id}.json")))
}
pub(super) fn save(record: &Publication) -> Result<()> {
    let Some(path) = &record.journal else {
        return Ok(());
    };
    let value = Saved {
        id: record.id.clone(),
        dev: record.folder_meta.dev(),
        ino: record.folder_meta.ino(),
        created: record.created_at,
        files: record
            .files
            .iter()
            .map(|(name, meta)| Entry {
                name: name.to_string_lossy().into(),
                dev: meta.dev(),
                ino: meta.ino(),
            })
            .collect(),
        result: record.result.clone(),
    };
    crate::config::private_write(path, &serde_json::to_vec(&value)?)?;
    File::open(path)?.sync_all()?;
    File::open(path.parent().unwrap())?.sync_all()?;
    Ok(())
}
fn valid_name(name: &str) -> bool {
    !name.is_empty()
        && name != "."
        && name != ".."
        && name.len() <= 255
        && !name.contains('/')
        && !name.chars().any(char::is_control)
}
pub(super) fn load(path: &Path, destination: &Path) -> Result<Publication> {
    use std::os::fd::FromRawFd;
    let meta = std::fs::symlink_metadata(path)?;
    if !meta.is_file() || meta.len() > 16384 {
        bail!("invalid publication journal");
    }
    let saved: Saved = serde_json::from_slice(&std::fs::read(path)?)?;
    let id = uuid::Uuid::parse_str(&saved.id)?.to_string();
    if id != saved.id
        || path.file_name().and_then(|s| s.to_str()) != Some(&format!("{id}.json"))
        || saved.files.len() > 2
        || saved.files.iter().any(|f| !valid_name(&f.name))
    {
        bail!("invalid publication journal identity");
    }
    let parent = crate::tools::files::open(destination, "")?;
    let folder_name = std::ffi::CString::new(format!(".airc-media-{id}"))?;
    let folder_meta =
        crate::file_actions::metadata(&parent, &folder_name)?.context("playback folder missing")?;
    if !folder_meta.is_dir() || folder_meta.dev() != saved.dev || folder_meta.ino() != saved.ino {
        bail!("playback folder identity changed; refusing cleanup");
    }
    let fd = unsafe {
        libc::openat(
            parent.as_raw_fd(),
            folder_name.as_ptr(),
            libc::O_DIRECTORY | libc::O_NOFOLLOW | libc::O_RDONLY | libc::O_CLOEXEC,
        )
    };
    if fd < 0 {
        return Err(std::io::Error::last_os_error().into());
    }
    let folder = unsafe { File::from_raw_fd(fd) };
    let opened = folder.metadata()?;
    if opened.dev() != saved.dev || opened.ino() != saved.ino {
        bail!("playback folder changed during recovery");
    }
    let mut files = Vec::new();
    for item in saved.files {
        let name = std::ffi::CString::new(item.name)?;
        if let Some(meta) = crate::file_actions::metadata(&folder, &name)? {
            if meta.dev() == item.dev && meta.ino() == item.ino {
                files.push((name, meta));
            }
        }
    }
    let result = if let Some(value) = saved.result {
        let relative = value["path"]
            .as_str()
            .context("journal copy path missing")?;
        let prefix = format!(".airc-media-{id}/");
        let name = relative
            .strip_prefix(&prefix)
            .context("journal copy path escaped its folder")?;
        if !valid_name(name)
            || name == ".nomedia"
            || value["root"] != "downloads"
            || value["id"] != id
            || value["sizeBytes"].as_u64().is_none()
            || !value["sha256"]
                .as_str()
                .is_some_and(|s| s.len() == 64 && s.bytes().all(|b| b.is_ascii_hexdigit()))
        {
            bail!("invalid journal copy result");
        }
        if files.iter().any(|(entry, meta)| {
            entry.to_str().ok() == Some(name)
                && meta.is_file()
                && Some(meta.len()) == value["sizeBytes"].as_u64()
        }) {
            Some(value)
        } else {
            None
        }
    } else {
        None
    };
    let age = Duration::from_secs(now().saturating_sub(saved.created));
    Ok(Publication {
        id,
        parent,
        folder,
        folder_name,
        folder_meta,
        files,
        _permit: super::storage::permit()?,
        created: Instant::now().checked_sub(age).unwrap_or_else(Instant::now),
        created_at: saved.created,
        journal: Some(path.into()),
        result,
    })
}
pub fn initialize(state: &Path) -> Result<()> {
    let directory = state.join("media-publications");
    crate::config::prepare_directory(&directory)?;
    DIRECTORY
        .set(directory.clone())
        .map_err(|_| anyhow::anyhow!("publication journal already initialized"))?;
    let destination = crate::tools::files::root("downloads")?;
    let mut registry = records().lock().unwrap();
    for entry in std::fs::read_dir(&directory)?.take(32) {
        let entry = entry?;
        if entry.path().extension().and_then(|s| s.to_str()) != Some("json") {
            continue;
        }
        match load(&entry.path(), &destination) {
            Ok(record)
                if record.result.is_some()
                    && record.created.elapsed() < Duration::from_secs(86400) =>
            {
                registry.insert(record.id.clone(), record);
            }
            Ok(record) => drop(record),
            Err(error) => eprintln!(
                "publication recovery skipped {}: {error}",
                entry.file_name().to_string_lossy()
            ),
        }
    }
    Ok(())
}
pub(super) fn now() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs()
}
