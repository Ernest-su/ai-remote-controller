use super::*;
use serde_json::json;
use std::io::Write;
#[test]
fn selected_mount_is_allowed_but_recursive_mount_boundaries_are_rejected() {
    // A selected directory may itself be a mount. Descendants may never cross it.
    assert!(check_recursive_filesystem(0, 1, 2).is_ok());
    assert!(check_recursive_filesystem(1, 2, 2).is_ok());
    assert!(check_recursive_filesystem(1, 2, 3).is_err());
    assert!(check_recursive_filesystem(128, 2, 3).is_err());
}
#[tokio::test]
async fn confirmation_overwrite_delete_and_path_boundaries() {
    let directory = std::env::temp_dir().join(format!("airc-management-{}", uuid::Uuid::new_v4()));
    std::fs::create_dir(&directory).unwrap();
    let relative = directory.to_str().unwrap().trim_start_matches('/');
    let path = format!("{relative}/file");
    std::fs::write(directory.join("file"), b"original").unwrap();
    let prepare = |tool: &str, path: &str, recursive: bool| -> Value {
        let args = if tool == "files.delete" {
            json!({"root":"full","path":path,"recursive":recursive})
        } else {
            json!({"root":"full","path":path})
        };
        serde_json::from_str(&super::prepare(tool, &args).unwrap()).unwrap()
    };
    assert!(super::prepare("files.delete", &json!({"root":"full","path":""})).is_err());
    assert!(super::prepare("files.delete", &json!({"root":"full","path":"../tmp"})).is_err());
    let proposal = prepare("files.prepare_overwrite", &path, false);
    let (parent, _) = crate::tools::files::parent(std::path::Path::new("/"), &path).unwrap();
    let id = proposal["id"].as_str().unwrap();
    assert!(consume_overwrite(id, "full", &path, &parent).is_err());
    assert_eq!(std::fs::read(directory.join("file")).unwrap(), b"original");
    approve(&json!({"id":id})).await.unwrap();
    std::fs::OpenOptions::new()
        .append(true)
        .open(directory.join("file"))
        .unwrap()
        .write_all(b"changed")
        .unwrap();
    assert!(consume_overwrite(id, "full", &path, &parent).is_err());
    let proposal = prepare("files.prepare_overwrite", &path, false);
    let id = proposal["id"].as_str().unwrap();
    approve(&json!({"id":id})).await.unwrap();
    assert!(consume_overwrite(id, "full", &path, &parent)
        .unwrap()
        .is_some());
    assert!(consume_overwrite(id, "full", &path, &parent).is_err());
    let proposal = prepare("files.delete", &path, false);
    std::fs::write(directory.join("file"), b"different").unwrap();
    assert!(approve(&json!({"id":proposal["id"]})).await.is_err());
    let nested = directory.join("folder");
    std::fs::create_dir(&nested).unwrap();
    std::fs::write(nested.join("child"), b"x").unwrap();
    let outside = directory.join("outside");
    std::fs::write(&outside, b"keep").unwrap();
    std::os::unix::fs::symlink(&outside, nested.join("link")).unwrap();
    let proposal = prepare("files.delete", &format!("{relative}/folder"), true);
    assert!(nested.exists());
    let mut task: Value =
        serde_json::from_str(&approve(&json!({"id":proposal["id"]})).await.unwrap()).unwrap();
    // Allow bounded worker scheduling under the full parallel suite.
    for _ in 0..500 {
        if task["phase"] == "completed" || task["phase"] == "failed" {
            break;
        }
        tokio::time::sleep(Duration::from_millis(10)).await;
        task = serde_json::from_str(
            &crate::transfers::execute("task.progress", &json!({"id":task["id"]}))
                .await
                .unwrap(),
        )
        .unwrap();
    }
    assert_eq!(task["phase"], "completed");
    assert_eq!(task["unit"], "items");
    assert_eq!(task["bytesTransferred"], 3);
    assert!(!nested.exists());
    assert_eq!(std::fs::read(outside).unwrap(), b"keep");
    let cancel = AtomicBool::new(true);
    let count = AtomicU64::new(0);
    let name = std::ffi::CString::new("file").unwrap();
    assert!(remove(&parent, &name, false, &cancel, &count, 0, None).is_err());
    assert!(directory.join("file").exists());
    std::fs::remove_dir_all(directory).unwrap();
}

#[test]
fn deleting_a_link_preserves_target_and_mutations_reject_link_parents() {
    let directory = std::env::temp_dir().join(format!("airc-link-delete-{}", uuid::Uuid::new_v4()));
    std::fs::create_dir_all(directory.join("target")).unwrap();
    std::fs::write(directory.join("target/keep"), b"keep").unwrap();
    std::os::unix::fs::symlink("target", directory.join("alias")).unwrap();
    let path = directory
        .join("alias")
        .to_str()
        .unwrap()
        .trim_start_matches('/')
        .to_owned();
    assert!(prepare(
        "files.delete",
        &json!({"root":"full","path":format!("{path}/keep")})
    )
    .is_err());
    assert!(prepare(
        "files.prepare_overwrite",
        &json!({"root":"full","path":path})
    )
    .is_err());
    let proposal: Value = serde_json::from_str(
        &prepare(
            "files.delete",
            &json!({"root":"full","path":path,"recursive":false}),
        )
        .unwrap(),
    )
    .unwrap();
    assert_eq!(proposal["symlink"], true);
    assert_eq!(proposal["directory"], false);
    let prepared = proposals()
        .lock()
        .unwrap()
        .remove(proposal["id"].as_str().unwrap())
        .unwrap();
    remove(
        &prepared.parent,
        &prepared.name,
        false,
        &AtomicBool::new(false),
        &AtomicU64::new(0),
        0,
        prepared.expected.as_ref(),
    )
    .unwrap();
    assert!(std::fs::symlink_metadata(directory.join("alias")).is_err());
    assert_eq!(
        std::fs::read(directory.join("target/keep")).unwrap(),
        b"keep"
    );
    std::fs::remove_dir_all(directory).unwrap();
}
