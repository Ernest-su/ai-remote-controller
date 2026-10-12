use anyhow::{bail, Context, Result};
use serde_json::{json, Value};
use std::{
    fs::{DirBuilder, File, OpenOptions},
    io::Read,
    os::unix::fs::{DirBuilderExt, OpenOptionsExt},
    path::{Path, PathBuf},
};

const MAX_XML: u64 = 128 * 1024;
struct Scratch(PathBuf);
impl Drop for Scratch {
    fn drop(&mut self) {
        let _ = std::fs::remove_file(self.0.join("hierarchy.xml"));
        let _ = std::fs::remove_dir(&self.0);
    }
}

fn read_xml(path: &Path) -> Result<String> {
    let file: File = OpenOptions::new()
        .read(true)
        .custom_flags(libc::O_NOFOLLOW | libc::O_NONBLOCK)
        .open(path)?;
    if !file.metadata()?.is_file() {
        bail!("UI hierarchy must be a regular file");
    }
    let mut bytes = Vec::new();
    file.take(MAX_XML + 1).read_to_end(&mut bytes)?;
    if bytes.len() as u64 > MAX_XML {
        bail!("UI hierarchy exceeds 128 KiB; use screenshot or narrower inspection");
    }
    let xml = String::from_utf8(bytes).context("UI hierarchy is not UTF-8")?;
    if !xml.contains("<hierarchy") || !xml.trim_end().ends_with("</hierarchy>") {
        bail!("UI hierarchy capture is incomplete");
    }
    Ok(xml)
}

pub(super) async fn execute(args: &Value) -> Result<String> {
    if args.as_object().is_none_or(|object| !object.is_empty()) {
        bail!("UI hierarchy accepts an empty object only");
    }
    let directory =
        PathBuf::from("/data/local/tmp").join(format!("airc-ui-{}", uuid::Uuid::new_v4()));
    DirBuilder::new().mode(0o700).create(&directory)?;
    let scratch = Scratch(directory);
    let path = scratch.0.join("hierarchy.xml");
    super::run_command(
        "uiautomator",
        &[
            "dump".into(),
            "--compressed".into(),
            path.to_string_lossy().into_owned(),
        ],
    )
    .await?;
    let xml = read_xml(&path)?;
    Ok(
        json!({"readOnly":true,"source":"android_uiautomator","atomicSnapshot":false,"xml":xml})
            .to_string(),
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn capture_limits_and_incomplete_output_are_rejected() {
        let path = std::env::temp_dir().join(format!("airc-ui-test-{}", uuid::Uuid::new_v4()));
        std::fs::write(&path, "<hierarchy><node /></hierarchy>").unwrap();
        assert!(read_xml(&path).is_ok());
        std::fs::write(&path, "<hierarchy>").unwrap();
        assert!(read_xml(&path).is_err());
        std::fs::write(&path, vec![b'x'; MAX_XML as usize + 1]).unwrap();
        assert!(read_xml(&path).is_err());
        std::fs::remove_file(path).unwrap();
    }
    #[test]
    fn capture_does_not_follow_links() {
        let path = std::env::temp_dir().join(format!("airc-ui-link-{}", uuid::Uuid::new_v4()));
        std::os::unix::fs::symlink("/dev/null", &path).unwrap();
        assert!(read_xml(&path).is_err());
        std::fs::remove_file(path).unwrap();
    }
    #[test]
    fn fifo_output_is_rejected_without_waiting_for_a_writer() {
        use std::os::unix::ffi::OsStrExt;
        let path = std::env::temp_dir().join(format!("airc-ui-fifo-{}", uuid::Uuid::new_v4()));
        let name = std::ffi::CString::new(path.as_os_str().as_bytes()).unwrap();
        assert_eq!(unsafe { libc::mkfifo(name.as_ptr(), 0o600) }, 0);
        assert!(read_xml(&path).is_err());
        std::fs::remove_file(path).unwrap();
    }
}
