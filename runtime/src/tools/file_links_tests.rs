use super::*;
use std::{
    io::Read,
    os::unix::fs::{symlink, PermissionsExt},
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc,
    },
};
struct Fixture(PathBuf);
impl Fixture {
    fn new() -> Self {
        let path = std::env::temp_dir().join(format!("airc-links-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir(&path).unwrap();
        Self(path)
    }
    fn read(&self, path: &str) -> Result<String> {
        let mut file = open_read(&self.0, path)?.file;
        let mut result = String::new();
        file.read_to_string(&mut result)?;
        Ok(result)
    }
}
impl Drop for Fixture {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.0);
    }
}

#[test]
fn relative_absolute_chains_and_parent_targets_stay_in_root() {
    let f = Fixture::new();
    std::fs::create_dir_all(f.0.join("dir/nested")).unwrap();
    std::fs::write(f.0.join("dir/file.txt"), "fixture中文").unwrap();
    symlink("dir", f.0.join("folder")).unwrap();
    symlink("folder/file.txt", f.0.join("relative.txt")).unwrap();
    symlink(f.0.join("dir/file.txt"), f.0.join("absolute.txt")).unwrap();
    symlink("../file.txt", f.0.join("dir/nested/up.txt")).unwrap();
    symlink(".", f.0.join("self")).unwrap();
    for path in [
        "relative.txt",
        "absolute.txt",
        "folder/nested/up.txt",
        "self/dir/file.txt",
    ] {
        assert_eq!(f.read(path).unwrap(), "fixture中文");
        assert!(open_read(&f.0, path).unwrap().followed);
        assert!(
            super::super::open(&f.0, path).is_err(),
            "mutation resolver must reject {path}"
        );
    }
    let listing: serde_json::Value = serde_json::from_str(
        &super::super::execute_at(&f.0, "files.list", &serde_json::json!({"path":"folder"}))
            .unwrap(),
    )
    .unwrap();
    assert_eq!(listing["readOnly"], true);
    let stat = describe(&f.0, "relative.txt", "relative.txt", true);
    assert_eq!(stat["kind"], "file");
    assert_eq!(stat["linkTarget"], "folder/file.txt");
    assert_eq!(stat["symlink"], true);
    let root: serde_json::Value = serde_json::from_str(
        &super::super::execute_at(&f.0, "files.list", &serde_json::json!({})).unwrap(),
    )
    .unwrap();
    assert_eq!(root["readOnly"], false);
    assert!(root["entries"]
        .as_array()
        .unwrap()
        .iter()
        .any(|e| e["name"] == "folder" && e["kind"] == "directory" && e["symlink"] == true));
}

#[test]
fn escapes_broken_cycles_proc_magic_and_special_files_are_distinct() {
    let f = Fixture::new();
    symlink("/etc/passwd", f.0.join("outside")).unwrap();
    symlink("../outside", f.0.join("parent")).unwrap();
    symlink("missing", f.0.join("broken")).unwrap();
    symlink("cycle", f.0.join("cycle")).unwrap();
    symlink("second", f.0.join("first")).unwrap();
    symlink("first", f.0.join("second")).unwrap();
    std::fs::write(f.0.join("file"), "x").unwrap();
    symlink("file/../file", f.0.join("not-dir")).unwrap();
    for (path, code) in [
        ("outside", "link_outside_root"),
        ("parent", "link_outside_root"),
        ("broken", "link_missing"),
        ("cycle", "link_loop"),
        ("first", "link_loop"),
        ("not-dir", "link_not_directory"),
    ] {
        assert!(open_read(&f.0, path).is_err());
        assert_eq!(
            describe(&f.0, path, path, true)["linkError"],
            code,
            "{path}"
        );
    }
    let fifo = CString::new(f.0.join("fifo").to_str().unwrap()).unwrap();
    assert_eq!(unsafe { libc::mkfifo(fifo.as_ptr(), 0o600) }, 0);
    symlink("fifo", f.0.join("fifo-link")).unwrap();
    assert_eq!(
        describe(&f.0, "fifo-link", "fifo-link", true)["linkError"],
        "link_special"
    );
    let pinned = File::open(f.0.join("file")).unwrap();
    let magic = format!("proc/{}/fd/{}", std::process::id(), pinned.as_raw_fd());
    assert_eq!(
        error_code(&open_read(Path::new("/"), &magic).err().unwrap()),
        "link_special"
    );
    // Permissions are meaningful only for an unprivileged host test process.
    if unsafe { libc::geteuid() } != 0 {
        std::fs::set_permissions(f.0.join("file"), std::fs::Permissions::from_mode(0o000)).unwrap();
        symlink("file", f.0.join("denied")).unwrap();
        assert_eq!(
            describe(&f.0, "denied", "denied", true)["linkError"],
            "link_permission"
        );
    }
}

#[test]
fn replacement_race_never_reads_outside_bytes_and_open_stream_is_pinned() {
    let f = Fixture::new();
    let outside = Fixture::new();
    std::fs::write(f.0.join("inside"), "inside").unwrap();
    std::fs::write(outside.0.join("secret"), "outside").unwrap();
    symlink("inside", f.0.join("alias")).unwrap();
    let mut pinned = open_read(&f.0, "alias").unwrap().file;
    std::fs::remove_file(f.0.join("alias")).unwrap();
    symlink(outside.0.join("secret"), f.0.join("alias")).unwrap();
    let mut text = String::new();
    pinned.read_to_string(&mut text).unwrap();
    assert_eq!(text, "inside");
    let stopped = Arc::new(AtomicBool::new(false));
    let signal = stopped.clone();
    let base = f.0.clone();
    let secret = outside.0.join("secret");
    let writer = std::thread::spawn(move || {
        while !signal.load(Ordering::Relaxed) {
            for target in [Path::new("inside"), secret.as_path()] {
                symlink(target, base.join("next")).unwrap();
                std::fs::rename(base.join("next"), base.join("alias")).unwrap();
            }
        }
    });
    let mut invalid = false;
    for _ in 0..1000 {
        if let Ok(bytes) = f.read("alias") {
            invalid |= bytes != "inside";
        }
    }
    stopped.store(true, Ordering::Relaxed);
    writer.join().unwrap();
    assert!(
        !invalid,
        "changing a link must never escape the selected root"
    );
}
