use super::*;
use std::{
    io::{Read, Write},
    sync::atomic::{AtomicBool, AtomicU64},
};
#[test]
fn unnamed_snapshot_keeps_source_and_closes_without_path_or_overwrite() {
    let directory =
        std::env::temp_dir().join(format!("airc-apk-snapshot-{}", uuid::Uuid::new_v4()));
    std::fs::create_dir(&directory).unwrap();
    let path = directory.join("source.apk");
    let bytes = b"fixture bytes";
    std::fs::write(&path, bytes).unwrap();
    let source = File::open(&path).unwrap();
    let meta = source.metadata().unwrap();
    use sha2::{Digest, Sha256};
    let digest = format!("{:x}", Sha256::digest(bytes));
    let stage = snapshot::unnamed(&directory, bytes.len() as u64).unwrap();
    let mut frozen = snapshot::copy(
        source,
        stage,
        &meta,
        &digest,
        &AtomicBool::new(false),
        &AtomicU64::new(0),
        0,
    )
    .unwrap();
    assert_eq!(std::fs::read_dir(&directory).unwrap().count(), 1);
    std::fs::OpenOptions::new()
        .write(true)
        .truncate(true)
        .open(&path)
        .unwrap()
        .write_all(b"changed")
        .unwrap();
    let mut received = vec![];
    frozen.read_to_end(&mut received).unwrap();
    assert_eq!(received, bytes);
    assert_eq!(std::fs::read(&path).unwrap(), b"changed");
    drop(frozen);
    std::fs::remove_dir_all(directory).unwrap();
}
