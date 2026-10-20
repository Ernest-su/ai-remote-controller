use anyhow::{bail, Context, Result};
use serde_json::{json, Value};
use std::{
    collections::HashMap,
    fs::{File, Metadata},
    os::{
        fd::{AsRawFd, FromRawFd},
        unix::fs::MetadataExt,
    },
    sync::{
        atomic::{AtomicBool, AtomicU64, Ordering},
        Mutex, OnceLock,
    },
    time::{Duration, Instant},
};

struct Proposal {
    id: String,
    kind: String,
    root: String,
    path: String,
    parent: File,
    name: std::ffi::CString,
    expected: Option<Metadata>,
    recursive: bool,
    approved: bool,
    created: Instant,
}
static PROPOSALS: OnceLock<Mutex<HashMap<String, Proposal>>> = OnceLock::new();
fn proposals() -> &'static Mutex<HashMap<String, Proposal>> {
    PROPOSALS.get_or_init(|| Mutex::new(HashMap::new()))
}
pub(crate) fn metadata(parent: &File, name: &std::ffi::CStr) -> Result<Option<Metadata>> {
    let path = format!("/proc/self/fd/{}/{}", parent.as_raw_fd(), name.to_str()?);
    match std::fs::symlink_metadata(path) {
        Ok(meta) => Ok(Some(meta)),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(None),
        Err(error) => Err(error.into()),
    }
}
pub(crate) fn same(a: &Metadata, b: &Metadata) -> bool {
    a.dev() == b.dev()
        && a.ino() == b.ino()
        && a.len() == b.len()
        && a.mtime() == b.mtime()
        && a.mtime_nsec() == b.mtime_nsec()
        && a.ctime() == b.ctime()
        && a.ctime_nsec() == b.ctime_nsec()
}
impl Proposal {
    fn preview(&self) -> Value {
        json!({"id":self.id,"kind":self.kind,"root":self.root,"path":self.path,"recursive":self.recursive,"exists":self.expected.is_some(),"sizeBytes":self.expected.as_ref().filter(|m|m.is_file()).map(Metadata::len),"directory":self.expected.as_ref().is_some_and(Metadata::is_dir),"symlink":self.expected.as_ref().is_some_and(|m|m.is_symlink()),"approvalRequired":true})
    }
}
pub fn prepare(tool: &str, args: &Value) -> Result<String> {
    let object = args.as_object().context("arguments must be an object")?;
    if object
        .keys()
        .any(|key| !["root", "path", "recursive"].contains(&key.as_str()))
    {
        bail!("unknown file action parameter");
    }
    let root = args
        .get("root")
        .map(|v| v.as_str().context("root must be string"))
        .transpose()?
        .unwrap_or("downloads");
    let path = args["path"].as_str().context("path required")?;
    let (parent, name) = crate::tools::files::parent(&crate::tools::files::root(root)?, path)?;
    let expected = metadata(&parent, &name)?;
    let kind = if tool == "files.delete" {
        "delete"
    } else {
        "overwrite"
    };
    if kind == "delete" && expected.is_none() {
        bail!("target does not exist");
    }
    if kind == "overwrite" && expected.as_ref().is_some_and(Metadata::is_dir) {
        bail!("cannot overwrite directory with file");
    }
    if kind == "overwrite" && expected.as_ref().is_some_and(|m| m.is_symlink()) {
        bail!("cannot overwrite a symbolic link");
    }
    let recursive = args
        .get("recursive")
        .map(|v| v.as_bool().context("recursive must be boolean"))
        .transpose()?
        .unwrap_or(false);
    if kind == "overwrite" && object.contains_key("recursive") {
        bail!("overwrite does not accept recursive");
    }
    let proposal = Proposal {
        id: uuid::Uuid::new_v4().to_string(),
        kind: kind.into(),
        root: root.into(),
        path: path.into(),
        parent,
        name,
        expected,
        recursive,
        approved: false,
        created: Instant::now(),
    };
    let preview = proposal.preview();
    let mut registry = proposals().lock().unwrap();
    registry.retain(|_, p| p.created.elapsed() < Duration::from_secs(900));
    if registry.len() >= 32 {
        bail!("too many pending file approvals");
    }
    registry.insert(proposal.id.clone(), proposal);
    Ok(preview.to_string())
}
pub fn consume_overwrite(
    id: &str,
    root: &str,
    path: &str,
    parent: &File,
) -> Result<Option<Metadata>> {
    let mut registry = proposals().lock().unwrap();
    let proposal = registry.get(id).context("overwrite approval not found")?;
    if !proposal.approved
        || proposal.kind != "overwrite"
        || proposal.root != root
        || proposal.path != path
        || proposal.created.elapsed() > Duration::from_secs(900)
    {
        bail!("overwrite approval invalid or expired");
    }
    let expected = proposal.parent.metadata()?;
    let actual = parent.metadata()?;
    if expected.dev() != actual.dev() || expected.ino() != actual.ino() {
        bail!("target directory changed after approval");
    }
    let current = metadata(parent, &proposal.name)?;
    if !same_optional(proposal.expected.as_ref(), current.as_ref()) {
        bail!("target changed; request a new overwrite approval");
    }
    Ok(registry.remove(id).unwrap().expected)
}
pub(crate) fn same_optional(a: Option<&Metadata>, b: Option<&Metadata>) -> bool {
    match (a, b) {
        (None, None) => true,
        (Some(a), Some(b)) => same(a, b),
        _ => false,
    }
}
pub fn pending(args: &Value) -> Result<String> {
    if args.as_object().is_none_or(|v| !v.is_empty()) {
        bail!("pending does not accept parameters");
    }
    let mut registry = proposals().lock().unwrap();
    registry.retain(|_, p| p.created.elapsed() < Duration::from_secs(900));
    let mut values: Vec<_> = registry.values().filter(|p| !p.approved).collect();
    values.sort_by_key(|p| p.created);
    Ok(serde_json::to_string(
        &values.iter().map(|p| p.preview()).collect::<Vec<_>>(),
    )?)
}
pub async fn approve(args: &Value) -> Result<String> {
    if args.as_object().is_none_or(|object| {
        !object.contains_key("id")
            || object
                .keys()
                .any(|key| !["id", "accepted"].contains(&key.as_str()))
    }) {
        bail!("only approval id accepted");
    }
    let id = args["id"].as_str().context("approval id required")?;
    let mut registry = proposals().lock().unwrap();
    if let Some(accepted) = args.get("accepted") {
        if !accepted.as_bool().context("accepted must be boolean")? {
            registry.remove(id);
            return Ok(json!({"id":id,"rejected":true}).to_string());
        }
    }
    let proposal = registry.get_mut(id).context("approval not found")?;
    if proposal.created.elapsed() > Duration::from_secs(900) {
        bail!("approval expired");
    }
    if proposal.kind == "overwrite" {
        proposal.approved = true;
        return Ok(json!({"approved":true,"id":id}).to_string());
    }
    let proposal = registry.remove(id).unwrap();
    drop(registry);
    let current = metadata(&proposal.parent, &proposal.name)?.context("target disappeared")?;
    if !same(&current, proposal.expected.as_ref().unwrap()) {
        bail!("target changed; request a new deletion approval");
    }
    let root = proposal.root.clone();
    let name = proposal.path.clone();
    Ok(
        crate::transfers::start_job(root, name, move |cancel, count| {
            remove(
                &proposal.parent,
                &proposal.name,
                proposal.recursive,
                cancel,
                count,
                0,
                proposal.expected.as_ref(),
            )
        })?
        .to_string(),
    )
}
fn remove(
    parent: &File,
    name: &std::ffi::CStr,
    recursive: bool,
    cancel: &AtomicBool,
    count: &AtomicU64,
    depth: u32,
    expected: Option<&Metadata>,
) -> Result<()> {
    if cancel.load(Ordering::Relaxed) {
        bail!("deletion cancelled; removed entries cannot be restored");
    }
    if depth > 128 {
        bail!("directory nesting exceeds 128");
    }
    let original = metadata(parent, name)?.context("entry disappeared")?;
    check_recursive_filesystem(depth, parent.metadata()?.dev(), original.dev())?;
    if expected.is_some_and(|expected| !same(expected, &original)) {
        bail!("target changed before deletion started");
    }
    let directory = original.is_dir();
    if directory && recursive {
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
        let folder = unsafe { File::from_raw_fd(fd) };
        let meta = folder.metadata()?;
        if meta.dev() != original.dev() || meta.ino() != original.ino() {
            bail!("directory changed while deleting");
        }
        for entry in std::fs::read_dir(format!("/proc/self/fd/{}", folder.as_raw_fd()))? {
            let entry = entry?;
            let child = std::ffi::CString::new(entry.file_name().as_encoded_bytes())?;
            if entry.path().symlink_metadata()?.dev() != meta.dev() {
                bail!("will not recursively cross filesystem mounts");
            }
            remove(&folder, &child, true, cancel, count, depth + 1, None)?;
        }
    }
    if cancel.load(Ordering::Relaxed) {
        bail!("deletion cancelled; removed entries cannot be restored");
    }
    let current = metadata(parent, name)?.context("entry disappeared")?;
    if current.dev() != original.dev() || current.ino() != original.ino() {
        bail!("entry replaced during deletion");
    }
    let flags = if directory { libc::AT_REMOVEDIR } else { 0 };
    if unsafe { libc::unlinkat(parent.as_raw_fd(), name.as_ptr(), flags) } != 0 {
        return Err(std::io::Error::last_os_error().into());
    }
    count.fetch_add(1, Ordering::Relaxed);
    Ok(())
}

fn check_recursive_filesystem(depth: u32, parent_device: u64, child_device: u64) -> Result<()> {
    if depth > 0 && parent_device != child_device {
        bail!("will not recursively cross filesystem mounts");
    }
    Ok(())
}

#[cfg(test)]
#[path = "file_actions_tests.rs"]
mod tests;
