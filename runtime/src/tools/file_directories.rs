use anyhow::{bail, Context, Result};
use serde_json::{json, Value};
use std::{os::fd::AsRawFd, path::Path};

pub fn execute(args: &Value) -> Result<String> {
    let root = args
        .get("root")
        .map(|v| v.as_str().context("root must be string"))
        .transpose()?
        .unwrap_or("downloads");
    create_at(&super::files::root(root)?, root, args)
}

fn create_at(root: &Path, root_name: &str, args: &Value) -> Result<String> {
    let object = args.as_object().context("arguments must be an object")?;
    if object
        .keys()
        .any(|key| !["root", "path"].contains(&key.as_str()))
    {
        bail!("unsupported directory arguments");
    }
    let path = args["path"].as_str().context("path required")?;
    let (parent, name) = super::files::parent(root, path)?;
    if unsafe { libc::mkdirat(parent.as_raw_fd(), name.as_ptr(), 0o700) } != 0 {
        return Err(std::io::Error::last_os_error().into());
    }
    Ok(json!({"root":root_name,"path":path,"created":true}).to_string())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn creates_only_new_directory_and_preserves_existing_entries() {
        let root = std::env::temp_dir().join(format!("airc-mkdir-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir(&root).unwrap();
        {
            assert!(create_at(&root, "inbox", &json!({"path":"new"})).is_ok());
            std::fs::write(root.join("file"), b"original").unwrap();
            assert!(create_at(&root, "inbox", &json!({"path":"new"})).is_err());
            assert!(create_at(&root, "inbox", &json!({"path":"file"})).is_err());
            assert_eq!(std::fs::read(root.join("file")).unwrap(), b"original");
            for path in ["", "../outside", "/absolute", "missing/child"] {
                assert!(create_at(&root, "inbox", &json!({"path":path})).is_err());
            }
            std::os::unix::fs::symlink(&root, root.join("link")).unwrap();
            assert!(create_at(&root, "inbox", &json!({"path":"link/escaped"})).is_err());
            assert!(!root.join("escaped").exists());
            assert!(create_at(&root, "inbox", &json!({"path":"bad","recursive":true})).is_err());
        }
        std::fs::remove_dir_all(root).unwrap();
    }
}
