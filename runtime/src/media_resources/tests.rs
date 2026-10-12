use super::*;
use axum::{
    body::to_bytes,
    extract::Path,
    http::{header, HeaderMap, Method, StatusCode},
};
#[tokio::test]
async fn media_streams_are_seekable_pinned_and_revocable() {
    initialize(9876);
    let path = std::env::temp_dir().join(format!("airc-media-{}", uuid::Uuid::new_v4()));
    std::fs::write(&path, b"0123456789").unwrap();
    let (id, _) = register(File::open(&path).unwrap(), "video/mp4").unwrap();
    let mut headers = HeaderMap::new();
    headers.insert(header::RANGE, "bytes=3-6".parse().unwrap());
    let response = download(Path(id.clone()), Method::GET, headers).await;
    assert_eq!(response.status(), StatusCode::PARTIAL_CONTENT);
    assert_eq!(response.headers()[header::CONTENT_RANGE], "bytes 3-6/10");
    assert_eq!(
        &to_bytes(response.into_body(), 100).await.unwrap()[..],
        b"3456"
    );
    let head = download(Path(id.clone()), Method::HEAD, HeaderMap::new()).await;
    assert_eq!(head.headers()[header::CONTENT_LENGTH], "10");
    assert!(to_bytes(head.into_body(), 100).await.unwrap().is_empty());
    let mut headers = HeaderMap::new();
    headers.insert(header::RANGE, "bytes=11-".parse().unwrap());
    assert_eq!(
        download(Path(id.clone()), Method::GET, headers)
            .await
            .status(),
        StatusCode::RANGE_NOT_SATISFIABLE
    );
    std::fs::write(&path, b"changed").unwrap();
    assert_eq!(
        download(Path(id.clone()), Method::GET, HeaderMap::new())
            .await
            .status(),
        StatusCode::CONFLICT
    );
    revoke(&id).unwrap();
    assert_eq!(
        download(Path(id), Method::GET, HeaderMap::new())
            .await
            .status(),
        StatusCode::NOT_FOUND
    );
    std::fs::remove_file(path).unwrap();
}
