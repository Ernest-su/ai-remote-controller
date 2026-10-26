use anyhow::{bail, Context, Result};
use serde_json::{json, Value};
use std::{os::fd::AsRawFd, path::Path};
#[path = "file_rename_copy.rs"]
mod copy;

pub fn execute(args: &Value) -> Result<String> {
    let root = args
        .get("root")
        .map(|v| v.as_str().context("root must be string"))
        .transpose()?
        .unwrap_or("downloads");
    rename_at(&super::files::root(root)?, root, args)
}

fn rename_at(root: &Path, root_name: &str, args: &Value) -> Result<String> {
    let object = args.as_object().context("arguments must be object")?;
    if object
        .keys()
        .any(|key| !["root", "path", "name"].contains(&key.as_str()))
    {
        bail!("unsupported rename arguments");
    }
    let path = args["path"].as_str().context("path required")?;
    let name = args["name"].as_str().context("name required")?;
    if name.is_empty()
        || name == "."
        || name == ".."
        || name.len() > 255
        || name.contains('/')
        || name.chars().any(char::is_control)
    {
        bail!("invalid new filename");
    }
    let (parent, source) = super::files::parent(root, path)?;
    if source.to_str()? == name {
        bail!("new name must differ");
    }
    let metadata =
        crate::file_actions::metadata(&parent, &source)?.context("source does not exist")?;
    if !metadata.is_file() && !metadata.is_dir() && !metadata.is_symlink() {
        bail!("unsupported source type");
    }
    let destination = std::ffi::CString::new(name)?;
    let result = unsafe {
        libc::syscall(
            libc::SYS_renameat2,
            parent.as_raw_fd(),
            source.as_ptr(),
            parent.as_raw_fd(),
            destination.as_ptr(),
            libc::RENAME_NOREPLACE,
        )
    };
    if result != 0 {
        let error = std::io::Error::last_os_error();
        if error.kind() == std::io::ErrorKind::AlreadyExists {
            bail!("同名文件或目录已存在，请更换名称");
        }
        if matches!(
            error.raw_os_error(),
            Some(libc::EINVAL | libc::ENOSYS | libc::EOPNOTSUPP)
        ) {
            copy::execute(&parent, &source, &destination)?;
        } else {
            return Err(error).context("重命名失败，原文件保留");
        }
    }
    let destination_path = path
        .rsplit_once('/')
        .map(|(parent, _)| format!("{parent}/{name}"))
        .unwrap_or_else(|| name.to_owned());
    Ok(
        json!({"root":root_name,"path":path,"destination":destination_path,"renamed":true})
            .to_string(),
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn renames_files_directories_and_links_without_overwrite_or_traversal() {
        let root = std::env::temp_dir().join(format!("airc-rename-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir(&root).unwrap();
        std::fs::write(root.join("old.apk"), b"original").unwrap();
        std::fs::write(root.join("exists.apk"), b"other").unwrap();
        let run =
            |path: &str, name: &str| rename_at(&root, "inbox", &json!({"path":path,"name":name}));
        assert!(run("old.apk", "exists.apk").is_err());
        assert_eq!(std::fs::read(root.join("old.apk")).unwrap(), b"original");
        assert_eq!(std::fs::read(root.join("exists.apk")).unwrap(), b"other");
        for name in [
            "",
            ".",
            "..",
            "../escape",
            "/absolute",
            "bad\nname",
            "old.apk",
        ] {
            assert!(run("old.apk", name).is_err());
        }
        for path in ["", "../old.apk", "/old.apk", "missing/file"] {
            assert!(run(path, "new").is_err());
        }
        assert!(run("old.apk", "电视.apk").is_ok());
        assert_eq!(std::fs::read(root.join("电视.apk")).unwrap(), b"original");
        std::fs::create_dir(root.join("folder")).unwrap();
        std::fs::write(root.join("folder/child"), b"child").unwrap();
        assert!(run("folder", "renamed").is_ok());
        assert_eq!(std::fs::read(root.join("renamed/child")).unwrap(), b"child");
        std::os::unix::fs::symlink(root.join("renamed"), root.join("link")).unwrap();
        assert!(run("link/child", "escape").is_err());
        assert!(run("link", "new-link").is_ok());
        assert!(root.join("new-link").is_symlink());
        assert!(root.join("renamed/child").exists());
        assert!(rename_at(
            &root,
            "inbox",
            &json!({"path":"电视.apk","name":"x","overwrite":true})
        )
        .is_err());
        std::fs::remove_dir_all(root).unwrap();
    }
}
