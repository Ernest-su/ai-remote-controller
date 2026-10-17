use super::*;
use axum::{
    body::{Body, Bytes},
    extract::Path,
    http::{header, StatusCode},
};
use futures_util::StreamExt;
use serde_json::json;
#[tokio::test]
async fn large_streams_validate_digest_commit_without_overwrite_and_clean_failures() {
    let root = std::env::temp_dir().join(format!("airc-stream-{}", uuid::Uuid::new_v4()));
    std::fs::create_dir(&root).unwrap();
    static ZERO: [u8; CHUNK] = [0; CHUNK];
    let size = 17 * 1024 * 1024u64;
    let mut hash = Sha256::new();
    for _ in 0..size / CHUNK as u64 {
        hash.update(ZERO);
    }
    let digest = format!("{:x}", hash.finalize());
    let task = create_at(
        &json!({"direction":"upload","path":"large.bin","sizeBytes":size,"sha256":digest}),
        &root,
    )
    .unwrap();
    tasks()
        .lock()
        .unwrap()
        .insert(task.id.clone(), task.clone());
    let body = Body::from_stream(futures_util::stream::iter(
        (0..size / CHUNK as u64).map(|_| Ok::<_, std::io::Error>(Bytes::from_static(&ZERO))),
    ));
    let result = http::upload(Path(task.id.clone()), body).await;
    assert_eq!(result.status(), StatusCode::OK);
    assert_eq!(
        std::fs::metadata(root.join("large.bin")).unwrap().len(),
        size
    );
    assert_eq!(task.phase.lock().unwrap().as_str(), "completed");
    let download = create_at(&json!({"direction":"download","path":"large.bin"}), &root).unwrap();
    *download.digest.lock().unwrap() = Some(digest.clone());
    download.phase("ready");
    tasks()
        .lock()
        .unwrap()
        .insert(download.id.clone(), download.clone());
    let response = http::download(Path(download.id.clone())).await;
    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(response.headers()[header::CONTENT_LENGTH], size.to_string());
    let mut data = response.into_body().into_data_stream();
    let mut received = 0u64;
    let mut actual = Sha256::new();
    while let Some(chunk) = data.next().await {
        let chunk = chunk.unwrap();
        assert!(chunk.len() <= CHUNK);
        received += chunk.len() as u64;
        actual.update(&chunk);
    }
    assert_eq!(received, size);
    assert_eq!(format!("{:x}", actual.finalize()), digest);
    let wrong = create_at(
        &json!({"direction":"upload","path":"wrong.bin","sizeBytes":1,"sha256":"0".repeat(64)}),
        &root,
    )
    .unwrap();
    tasks()
        .lock()
        .unwrap()
        .insert(wrong.id.clone(), wrong.clone());
    assert_eq!(
        http::upload(Path(wrong.id.clone()), Body::from("x"))
            .await
            .status(),
        StatusCode::BAD_REQUEST
    );
    assert!(!root.join("wrong.bin").exists());
    assert!(wrong.destination.lock().unwrap().is_none());
    let existing=create_at(&json!({"direction":"upload","path":"large.bin","sizeBytes":0,"sha256":format!("{:x}",Sha256::digest([]))}),&root).unwrap();
    tasks()
        .lock()
        .unwrap()
        .insert(existing.id.clone(), existing.clone());
    assert_eq!(
        http::upload(Path(existing.id.clone()), Body::empty())
            .await
            .status(),
        StatusCode::BAD_REQUEST
    );
    assert_eq!(
        std::fs::metadata(root.join("large.bin")).unwrap().len(),
        size
    );
    let pending = create_at(
        &json!({"direction":"upload","path":"cancelled.bin","sizeBytes":1,"sha256":"0".repeat(64)}),
        &root,
    )
    .unwrap();
    tasks()
        .lock()
        .unwrap()
        .insert(pending.id.clone(), pending.clone());
    let worker = tokio::spawn(http::upload(
        Path(pending.id.clone()),
        Body::from_stream(futures_util::stream::pending::<Result<Bytes, std::io::Error>>()),
    ));
    for _ in 0..100 {
        if pending.phase.lock().unwrap().as_str() == "uploading" {
            break;
        }
        tokio::time::sleep(Duration::from_millis(1)).await;
    }
    execute("task.cancel", &json!({"id":pending.id}))
        .await
        .unwrap();
    assert_eq!(
        tokio::time::timeout(Duration::from_secs(2), worker)
            .await
            .unwrap()
            .unwrap()
            .status(),
        StatusCode::BAD_REQUEST
    );
    assert_eq!(pending.phase.lock().unwrap().as_str(), "cancelled");
    assert!(pending.destination.lock().unwrap().is_none());
    for id in [&task.id, &download.id, &wrong.id, &existing.id, &pending.id] {
        tasks().lock().unwrap().remove(id);
    }
    std::fs::remove_dir_all(root).unwrap();
}

#[tokio::test]
async fn app_control_progress_uses_items_and_stays_committing_until_completion() {
    // Hold a real background job so the approval snapshot and progress/cancel
    // responses can be checked before any Android operation finishes.
    let (release, wait) = std::sync::mpsc::channel();
    let initial = start_background(
        "apps".into(),
        "fixture.app".into(),
        "app_control",
        0,
        move |cancel, count| {
            wait.recv_timeout(Duration::from_secs(5))
                .context("test job release missing")?;
            if cancel.load(Ordering::Relaxed) {
                bail!("committed app task was incorrectly cancelled");
            }
            count.store(1, Ordering::Relaxed);
            Ok(json!({"completed":true}))
        },
    )
    .unwrap();
    let id = initial["id"].as_str().unwrap().to_owned();
    // Always release the worker before asserting, including on the old bug.
    let progress: Value =
        serde_json::from_str(&execute("task.progress", &json!({"id":id})).await.unwrap()).unwrap();
    let cancelled: Value =
        serde_json::from_str(&execute("task.cancel", &json!({"id":id})).await.unwrap()).unwrap();
    release.send(()).unwrap();
    let completed = tokio::time::timeout(Duration::from_secs(5), async {
        loop {
            let state = lookup(&id).unwrap().progress();
            if matches!(
                state["phase"].as_str(),
                Some("completed" | "failed" | "cancelled")
            ) {
                break state;
            }
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
    })
    .await
    .unwrap();
    tasks().lock().unwrap().remove(&id);
    for state in [&initial, &progress, &cancelled, &completed] {
        assert_eq!(state["id"], id);
        assert_eq!(state["direction"], "app_control");
        assert_eq!(state["root"], "apps");
        assert_eq!(state["name"], "fixture.app");
        assert_eq!(state["unit"], "items");
    }
    for state in [&initial, &progress, &cancelled] {
        assert_eq!(state["phase"], "committing");
    }
    assert_eq!(completed["phase"], "completed");
    assert_eq!(completed["bytesTransferred"], 1);
}
