use super::*;
use std::{io::Write, sync::atomic::Ordering};
#[test]
fn copies_stream_without_overwriting_and_cancel_removes_partial_files() {
    let root = std::env::temp_dir().join(format!("airc-publish-{}", uuid::Uuid::new_v4()));
    std::fs::create_dir(&root).unwrap();
    let source_path = root.join("source.png");
    let mut file = File::create(&source_path).unwrap();
    let chunk = [42u8; 64 * 1024];
    for _ in 0..272 {
        file.write_all(&chunk).unwrap();
    }
    drop(file);
    let cancelled = AtomicBool::new(false);
    let count = AtomicU64::new(0);
    let result = publish(
        File::open(&source_path).unwrap(),
        &root,
        "media.png",
        &cancelled,
        &count,
    )
    .unwrap();
    assert_eq!(count.load(Ordering::Relaxed), 17 * 1024 * 1024);
    let copied = root.join(result["path"].as_str().unwrap());
    assert_eq!(copied.metadata().unwrap().len(), 17 * 1024 * 1024);
    assert_eq!(source_path.metadata().unwrap().len(), 17 * 1024 * 1024);
    release(result["id"].as_str().unwrap()).unwrap();
    assert!(!copied.exists());
    assert!(source_path.exists());
    let (record, mut output) = storage::create(&root, 100, "partial.png").unwrap();
    output.write_all(b"partial contents").unwrap();
    assert!(copy::verify(
        &record,
        "partial.png",
        &"0".repeat(64),
        &AtomicBool::new(false)
    )
    .is_err());
    let partial = root.join(record.folder_name.to_str().unwrap());
    drop(output);
    drop(record);
    assert!(!partial.exists());
    cancelled.store(true, Ordering::Relaxed);
    assert!(publish(
        File::open(&source_path).unwrap(),
        &root,
        "cancel.png",
        &cancelled,
        &count
    )
    .is_err());
    assert_eq!(std::fs::read_dir(&root).unwrap().count(), 1);
    // If someone replaces an owned copy with another inode, release preserves that entry.
    let result = publish(
        File::open(&source_path).unwrap(),
        &root,
        "media.png",
        &AtomicBool::new(false),
        &count,
    )
    .unwrap();
    let copied = root.join(result["path"].as_str().unwrap());
    let replacement = root.join("replacement");
    std::fs::write(&replacement, b"keep user replacement").unwrap();
    std::fs::rename(replacement, &copied).unwrap();
    release(result["id"].as_str().unwrap()).unwrap();
    assert_eq!(std::fs::read(&copied).unwrap(), b"keep user replacement");
    std::fs::remove_dir_all(root).unwrap();
}

#[test]
fn journal_restores_completed_copy_and_cleans_interrupted_copy() {
    use std::os::unix::fs::PermissionsExt;
    let root = std::env::temp_dir().join(format!("airc-journal-{}", uuid::Uuid::new_v4()));
    std::fs::create_dir(&root).unwrap();
    let (mut record, mut output) = storage::create(&root, 4, "image.png").unwrap();
    output.write_all(b"test").unwrap();
    output.sync_all().unwrap();
    drop(output);
    let id = record.id.clone();
    let path = format!(".airc-media-{id}/image.png");
    let journal = root.join(format!("{id}.json"));
    record.journal = Some(journal.clone());
    record.result =
        Some(json!({"id":id,"root":"downloads","path":path,"sizeBytes":4,"sha256":"0".repeat(64)}));
    journal::save(&record).unwrap();
    assert_eq!(
        std::fs::metadata(&journal).unwrap().permissions().mode() & 0o777,
        0o600
    );
    // Close descriptors as a process exit would, without executing the normal release cleanup.
    record.files.clear();
    record.folder_name = std::ffi::CString::new("never-created").unwrap();
    record.journal = None;
    drop(record);
    let restored = journal::load(&journal, &root).unwrap();
    assert!(restored.result.is_some());
    assert!(root.join(&path).exists());
    drop(restored);
    assert!(!root.join(&path).exists());
    assert!(!journal.exists());
    let (mut pending, mut output) = storage::create(&root, 100, "partial.png").unwrap();
    output.write_all(b"unfinished").unwrap();
    drop(output);
    let journal = root.join(format!("{}.json", pending.id));
    let folder = root.join(pending.folder_name.to_str().unwrap());
    pending.journal = Some(journal.clone());
    journal::save(&pending).unwrap();
    pending.files.clear();
    pending.folder_name = std::ffi::CString::new("never-created").unwrap();
    pending.journal = None;
    drop(pending);
    let restored = journal::load(&journal, &root).unwrap();
    assert!(restored.result.is_none());
    drop(restored);
    assert!(!folder.exists());
    assert!(!journal.exists());
    std::fs::remove_dir_all(root).unwrap();
}
