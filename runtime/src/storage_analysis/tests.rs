use super::*;
use serde_json::json;
use std::os::unix::fs::symlink;
#[test]
fn scan_is_bounded_readonly_sparse_aware_and_does_not_follow_links() {
    let base = std::env::temp_dir().join(format!("airc-storage-{}", uuid::Uuid::new_v4()));
    std::fs::create_dir(&base).unwrap();
    std::fs::create_dir(base.join("folder")).unwrap();
    std::fs::write(base.join("folder/a.JPG"), b"fixture").unwrap();
    std::fs::hard_link(base.join("folder/a.JPG"), base.join("folder/alias.JPG")).unwrap();
    let sparse = File::create(base.join("large.mp4")).unwrap();
    sparse.set_len(17 * 1024 * 1024).unwrap();
    drop(sparse);
    symlink("/", base.join("escape")).unwrap();
    let progress = AtomicU64::new(0);
    let result = scan(
        File::open(&base).unwrap(),
        "inbox".into(),
        "fixture".into(),
        100,
        &AtomicBool::new(false),
        &progress,
    )
    .unwrap();
    assert_eq!(result["totals"]["files"], 2);
    assert_eq!(result["totals"]["logicalBytes"], 17 * 1024 * 1024 + 7);
    assert_eq!(result["duplicateFiles"], 1);
    assert_eq!(result["skippedLinks"], 1);
    assert_eq!(result["complete"], true);
    assert_eq!(result["snapshot"], false);
    assert_eq!(result["largestFiles"][0]["path"], "fixture/large.mp4");
    assert_eq!(result["categories"]["images"]["files"], 1);
    assert!(
        result["totals"]["allocatedBytes"].as_u64().unwrap()
            < result["totals"]["logicalBytes"].as_u64().unwrap()
    );
    let limited = scan(
        File::open(&base).unwrap(),
        "inbox".into(),
        "fixture".into(),
        1,
        &AtomicBool::new(false),
        &AtomicU64::new(0),
    )
    .unwrap();
    assert_eq!(limited["entriesVisited"], 1);
    assert_eq!(limited["truncated"], true);
    assert_eq!(limited["complete"], false);
    assert!(scan(
        File::open(&base).unwrap(),
        "inbox".into(),
        "fixture".into(),
        100,
        &AtomicBool::new(true),
        &AtomicU64::new(0)
    )
    .is_err());
    assert_eq!(
        std::fs::read(base.join("folder/a.JPG")).unwrap(),
        b"fixture"
    );
    assert!(start(&json!({"maxEntries":0})).is_err());
    assert!(start(&json!({"path":"../outside"})).is_err());
    std::fs::remove_dir_all(base).unwrap();
}
#[test]
fn suffix_categories_do_not_claim_cache_or_reclaimable_space() {
    assert_eq!(report::category("app/cache/video.mp4"), "video");
    assert_eq!(report::category("data.log"), "logs");
    assert_eq!(report::category("file.part"), "temporary");
    assert_eq!(report::category("Cache"), "other");
}

#[test]
fn deep_and_unrepresentable_entries_are_reported_as_partial() {
    use std::os::unix::ffi::OsStringExt;
    let base = std::env::temp_dir().join(format!("airc-storage-bounds-{}", uuid::Uuid::new_v4()));
    std::fs::create_dir(&base).unwrap();
    let mut directory = base.clone();
    for _ in 0..66 {
        directory.push("a");
        std::fs::create_dir(&directory).unwrap();
    }
    std::fs::write(directory.join("deep.txt"), b"deep").unwrap();
    std::fs::write(base.join(std::ffi::OsString::from_vec(vec![0xff])), b"name").unwrap();
    let result = scan(
        File::open(&base).unwrap(),
        "inbox".into(),
        "".into(),
        1000,
        &AtomicBool::new(false),
        &AtomicU64::new(0),
    )
    .unwrap();
    assert_eq!(result["complete"], false);
    assert_eq!(result["truncated"], true);
    assert_eq!(result["errorCount"], 2);
    assert_eq!(result["totals"]["files"], 0);
    assert_eq!(std::fs::read(directory.join("deep.txt")).unwrap(), b"deep");
    std::fs::remove_dir_all(base).unwrap();
}
