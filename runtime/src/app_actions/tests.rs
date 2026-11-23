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

#[test]
fn builtin_version_uses_hidden_package_section_not_version_order() {
    let dump = "Packages:\n  Package [com.test.app] (a):\n    appId=10042\n    codePath=/data/app/current\n    versionCode=10000 minSdk=28\n    versionName=1.0\n    lastUpdateTime=now\nHidden system packages:\n  Package [com.other] (b):\n    versionCode=99999\n  Package [com.test.app] (c):\n    codePath=/system/app/Test\n    versionCode=1927 minSdk=19\n    versionName=5.1.1927\n";
    let app = info::parse("com.test.app", 0, true, dump).unwrap();
    assert_eq!(app.version_code, 10000);
    let builtin = app.built_in_version.unwrap();
    assert_eq!(builtin.version_code, 1927);
    assert_eq!(builtin.code_path, "/system/app/Test");
    assert!(info::parse("com.test.app", 0, false, dump).unwrap().built_in_version.is_none());
    let reversed = dump.replace("versionCode=10000", "versionCode=1");
    assert_eq!(info::parse("com.test.app", 0, true, &reversed).unwrap().built_in_version.unwrap().version_code, 1927);
}
#[test]
fn builtin_version_for_unupdated_system_app_and_unavailable_updated_app() {
    let dump = "Package [com.test.app] (a):\n appId=10042\n codePath=/product/app/Test\n versionCode=7\n versionName=null\n lastUpdateTime=now";
    let app = info::parse("com.test.app", 0, true, dump).unwrap();
    assert_eq!(app.built_in_version.unwrap().version_code, 7);
    assert!(info::parse("com.test.app", 0, true, &dump.replace("/product/app/Test", "/data/app/Test")).unwrap().built_in_version.is_none());
}
