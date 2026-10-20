use anyhow::{bail, Context, Result};
use serde_json::{json, Value};
use std::{
    fs::{File, OpenOptions},
    os::{
        fd::{AsRawFd, FromRawFd},
        unix::fs::OpenOptionsExt,
    },
    path::Path,
};

#[path = "file_links.rs"]
mod links;
pub(crate) use links::open_read;

static INBOX: std::sync::OnceLock<std::path::PathBuf> = std::sync::OnceLock::new();
pub(crate) fn initialize(directory: &Path) -> Result<()> {
    use std::os::unix::fs::PermissionsExt;
    let inbox = directory.join("files");
    std::fs::create_dir_all(&inbox)?;
    std::fs::set_permissions(&inbox, std::fs::Permissions::from_mode(0o700))?;
    let _ = INBOX.set(inbox);
    Ok(())
}
pub(crate) fn root(name: &str) -> Result<std::path::PathBuf> {
    match name {
        "downloads" => Ok(ROOT.into()),
        "full" => Ok("/".into()),
        "inbox" => INBOX.get().cloned().context("service inbox unavailable"),
        _ => bail!("root must be downloads, inbox or full"),
    }
}

static DOWNLOAD: tokio::sync::Semaphore = tokio::sync::Semaphore::const_new(1);

pub(crate) const ROOT: &str = "/storage/emulated/0/Download";

fn components(path: &str) -> Result<Vec<&str>> {
    if path.len() > 2048 || path.starts_with('/') {
        bail!("path must be relative to Downloads");
    }
    if path.is_empty() {
        return Ok(vec![]);
    }
    let parts: Vec<_> = path.split('/').collect();
    if parts.iter().any(|part| {
        part.is_empty()
            || *part == "."
            || *part == ".."
            || part.len() > 255
            || part.chars().any(char::is_control)
    }) {
        bail!("invalid relative path");
    }
    Ok(parts)
}

pub(crate) fn open(root: &Path, relative: &str) -> Result<File> {
    let parts = components(relative)?;
    let mut file = OpenOptions::new()
        .read(true)
        .custom_flags(libc::O_DIRECTORY | libc::O_NOFOLLOW | libc::O_CLOEXEC)
        .open(root)?;
    for (index, part) in parts.iter().enumerate() {
        let name = std::ffi::CString::new(*part)?;
        let flags = libc::O_RDONLY
            | libc::O_LARGEFILE
            | libc::O_NOFOLLOW
            | libc::O_CLOEXEC
            | libc::O_NONBLOCK
            | if index + 1 < parts.len() {
                libc::O_DIRECTORY
            } else {
                0
            };
        let fd = unsafe { libc::openat(file.as_raw_fd(), name.as_ptr(), flags) };
        if fd < 0 {
            return Err(std::io::Error::last_os_error().into());
        }
        file = unsafe { File::from_raw_fd(fd) };
        let metadata = file.metadata()?;
        if !metadata.is_file() && !metadata.is_dir() {
            bail!("unsupported file type");
        }
    }
    Ok(file)
}

fn execute_at(root: &Path, tool: &str, args: &Value) -> Result<String> {
    let object = args.as_object().context("arguments must be an object")?;
    if object.keys().any(|key| {
        !["root", "path", "cursor", "limit"].contains(&key.as_str())
            || (tool != "files.list" && key != "path" && key != "root")
    }) {
        bail!("unsupported file arguments");
    }
    let root_name = object
        .get("root")
        .map(|value| value.as_str().context("root must be string"))
        .transpose()?
        .unwrap_or("downloads");
    if !["downloads", "inbox", "full"].contains(&root_name) {
        bail!("invalid file root");
    }
    let relative = object
        .get("path")
        .map(|value| value.as_str().context("path must be a string"))
        .transpose()?
        .unwrap_or("");
    components(relative)?;
    if tool == "files.stat" {
        let (parent_path, name) = relative.rsplit_once('/').unwrap_or(("", relative));
        let parent = open_read(root, parent_path)?;
        let entry = std::fs::symlink_metadata(format!(
            "/proc/self/fd/{}/{}",
            parent.file.as_raw_fd(),
            name
        ))?;
        let mut value = if entry.is_symlink() {
            links::describe(root, relative, name, true)
        } else {
            let resolved = open_read(root, relative)?;
            let meta = resolved.file.metadata()?;
            json!({"kind":if meta.is_dir(){"directory"}else{"file"},"sizeBytes":if meta.is_file(){Some(meta.len())}else{None},"symlink":false})
        };
        value["root"] = json!(root_name);
        value["path"] = json!(relative);
        return Ok(value.to_string());
    }
    let resolved = open_read(root, relative)?;
    let read_only = resolved.followed;
    let file = resolved.file;
    let metadata = file.metadata()?;
    if tool == "files.preview" {
        if !metadata.is_file() {
            bail!("preview requires a regular file");
        }
        let size = metadata.len();
        let (id, _) = crate::media_resources::register_preview(file)?;
        return Ok(json!({"id":id,"root":root_name,"path":relative,"sizeBytes":size,"dataPath":format!("/media/{id}")}).to_string());
    }
    if tool == "files.download" {
        use std::io::Read;
        if !metadata.is_file() {
            bail!("files.download requires a regular file");
        }
        if metadata.len() > crate::artifacts::MAX_BYTES as u64 {
            bail!("file exceeds 16 MiB snapshot limit; use task.start with direction=download for large-file streaming");
        }
        let mut bytes = Vec::new();
        file.take(crate::artifacts::MAX_BYTES as u64 + 1)
            .read_to_end(&mut bytes)?;
        if bytes.len() > crate::artifacts::MAX_BYTES || bytes.len() as u64 != metadata.len() {
            bail!("file changed size during snapshot capture; retry");
        }
        return Ok(
            crate::artifacts::insert_file(bytes, relative.rsplit('/').next().unwrap()).to_string(),
        );
    }
    if !metadata.is_dir() {
        bail!("files.list requires a directory");
    }
    let limit = object
        .get("limit")
        .map(|value| value.as_u64().context("limit must be an integer"))
        .transpose()?
        .unwrap_or(50);
    if !(1..=100).contains(&limit) {
        bail!("limit must be 1..100");
    }
    let cursor = object
        .get("cursor")
        .map(|value| value.as_str().context("cursor must be a string"))
        .transpose()?
        .unwrap_or("");
    if !cursor.is_empty() && (components(cursor)?.len() != 1) {
        bail!("invalid cursor");
    }
    let mut entries = Vec::new();
    for entry in std::fs::read_dir(format!("/proc/self/fd/{}", file.as_raw_fd()))? {
        if entries.len() >= 4096 {
            bail!("directory exceeds 4096 entries; choose a narrower directory");
        }
        let entry = entry?;
        let name = entry
            .file_name()
            .into_string()
            .map_err(|_| anyhow::anyhow!("non-UTF-8 filename unsupported"))?;
        if name
            .strip_prefix(".airc-transfer-")
            .and_then(|name| name.strip_suffix(".part"))
            .is_some_and(|id| uuid::Uuid::parse_str(id).is_ok())
        {
            continue;
        }
        let meta = entry.path().symlink_metadata()?;
        entries.push((name, meta));
    }
    entries.sort_by(|a, b| a.0.cmp(&b.0));
    let remaining: Vec<_> = entries
        .into_iter()
        .filter(|(name, _)| name.as_str() > cursor)
        .collect();
    let page: Vec<_> = remaining.iter().take(limit as usize).map(|(name,meta)| {
        if meta.is_symlink() {
            let path = if relative.is_empty() { name.clone() } else { format!("{relative}/{name}") };
            links::describe(root, &path, name, true)
        } else {
            json!({"name":name,"kind":if meta.is_dir(){"directory"}else if meta.is_file(){"file"}else{"other"},"sizeBytes":if meta.is_file(){Some(meta.len())}else{None},"symlink":false})
        }
    }).collect();
    let next =
        (remaining.len() > limit as usize).then(|| page.last().unwrap()["name"].as_str().unwrap());
    Ok(json!({"root":root_name,"path":relative,"entries":page,"nextCursor":next,"readOnly":read_only}).to_string())
}

pub(super) async fn execute(tool: &str, args: &Value) -> Result<String> {
    if tool == "files.preview_close" {
        let object = args.as_object().context("arguments must be object")?;
        if object.len() != 1 {
            bail!("only preview id accepted");
        }
        crate::media_resources::revoke_preview(args["id"].as_str().context("id required")?)?;
        return Ok(json!({"released":true}).to_string());
    }
    let _permit = if tool == "files.download" {
        Some(
            DOWNLOAD
                .try_acquire()
                .context("file snapshot already in progress")?,
        )
    } else {
        None
    };
    let tool = tool.to_owned();
    let args = args.clone();
    tokio::task::spawn_blocking(move || {
        let _permit = _permit;
        let root_name = args
            .get("root")
            .and_then(Value::as_str)
            .unwrap_or("downloads");
        execute_at(&root(root_name)?, &tool, &args)
    })
    .await?
}

pub(crate) fn parent(root: &Path, relative: &str) -> Result<(File, std::ffi::CString)> {
    let parts = components(relative)?;
    let name = parts.last().context("root directory cannot be changed")?;
    let prefix = relative
        .rsplit_once('/')
        .map(|(parent, _)| parent)
        .unwrap_or("");
    let directory = open(root, prefix)?;
    if !directory.metadata()?.is_dir() {
        bail!("parent must be a directory");
    }
    Ok((directory, std::ffi::CString::new(*name)?))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn previews_pin_regular_files_without_snapshot_size_limit() {
        crate::media_resources::initialize(9876);
        let root = std::env::temp_dir().join(format!("airc-preview-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir(&root).unwrap();
        let file = File::create(root.join("large.mp4")).unwrap();
        file.set_len(100 * 1024 * 1024).unwrap();
        std::os::unix::fs::symlink("large.mp4", root.join("link.mp4")).unwrap();
        let result: Value = serde_json::from_str(
            &execute_at(
                &root,
                "files.preview",
                &json!({"root":"inbox","path":"large.mp4"}),
            )
            .unwrap(),
        )
        .unwrap();
        assert_eq!(result["sizeBytes"], 100 * 1024 * 1024);
        assert_eq!(result["root"], "inbox");
        assert_eq!(result["path"], "large.mp4");
        assert_eq!(
            result["dataPath"],
            format!("/media/{}", result["id"].as_str().unwrap())
        );
        crate::media_resources::revoke_preview(result["id"].as_str().unwrap()).unwrap();
        for path in ["../escape", ""] {
            assert!(execute_at(&root, "files.preview", &json!({"path":path})).is_err());
        }
        assert!(execute_at(
            &root,
            "files.preview",
            &json!({"path":"large.mp4","limit":1})
        )
        .is_err());
        std::fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn paging_and_symlink_escape_are_controlled() {
        let root = std::env::temp_dir().join(format!("airc-files-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir(&root).unwrap();
        std::fs::write(root.join("a"), b"fixture").unwrap();
        std::fs::write(root.join("b"), b"x").unwrap();
        std::os::unix::fs::symlink("/", root.join("escape")).unwrap();
        let result: Value =
            serde_json::from_str(&execute_at(&root, "files.list", &json!({"limit":1})).unwrap())
                .unwrap();
        assert_eq!(result["nextCursor"], "a");
        assert!(execute_at(&root, "files.stat", &json!({"path":"escape/etc/passwd"})).is_err());
        assert!(execute_at(&root, "files.stat", &json!({"path":"../etc/passwd"})).is_err());
        assert!(execute_at(&root, "files.list", &json!({"limit":101})).is_err());
        assert_eq!(
            serde_json::from_str::<Value>(
                &execute_at(&root, "files.stat", &json!({"path":"a"})).unwrap()
            )
            .unwrap()["sizeBytes"],
            7
        );
        assert!(execute_at(
            &root,
            "files.download",
            &json!({"path":"escape/etc/passwd"})
        )
        .is_err());
        assert!(execute_at(&root, "files.download", &json!({})).is_err());
        assert!(execute_at(&root, "files.download", &json!({"path":"a","limit":1})).is_err());
        let huge = File::create(root.join("huge")).unwrap();
        huge.set_len(crate::artifacts::MAX_BYTES as u64 + 1)
            .unwrap();
        assert!(execute_at(&root, "files.download", &json!({"path":"huge"})).is_err());
        std::fs::remove_dir_all(root).unwrap();
    }
}
