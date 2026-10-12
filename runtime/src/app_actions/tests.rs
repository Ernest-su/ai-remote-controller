use super::*;
#[test]
fn app_details_are_scoped_and_missing_identity_is_rejected() {
    let dump="Package [com.test.app] (hash):\n appId=10042\n codePath=/data/app/one\n versionCode=4 minSdk=26 targetSdk=35\n versionName=4.0\n lastUpdateTime=2026-10-13 12:00:00\nPackage [com.other] (hash):\n appId=1000";
    let info = info::parse("com.test.app", 10, false, dump).unwrap();
    assert_eq!(info.app_id, 10042);
    assert_eq!(info.version_code, 4);
    assert_eq!(info.user_id, 10);
    assert!(!info.system);
    assert!(info::parse("com.missing", 0, false, dump).is_err());
    assert!(info::parse(
        "com.test.app",
        0,
        false,
        "Package [com.test.app]\n appId=10042"
    )
    .is_err());
    assert!(!info::valid_package("com.test;reboot"));
    assert!(!info::valid_package("com..test"));
    assert!(info::valid_package("com.test_app"));
}

#[tokio::test]
async fn rejected_app_proposal_is_consumed_without_package_commands() {
    let app = info::parse("com.test.app", 0, false,
        "Package [com.test.app] (hash):\n appId=10042\n codePath=/data/app/one\n versionCode=4 minSdk=26\n versionName=4.0\n lastUpdateTime=fixture").unwrap();
    let id = uuid::Uuid::new_v4().to_string();
    proposals().lock().unwrap().insert(
        id.clone(),
        Proposal {
            id: id.clone(),
            info: app,
            created: Instant::now(),
        },
    );
    let rejected = approve(&json!({"id":id,"accepted":false})).await.unwrap();
    assert_eq!(
        serde_json::from_str::<Value>(&rejected).unwrap()["rejected"],
        true
    );
    assert!(approve(&json!({"id":id})).await.is_err());
}
