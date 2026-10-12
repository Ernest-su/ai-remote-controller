use axum::{
    extract::Path,
    http::{header, StatusCode},
    response::{IntoResponse, Response},
};
use serde_json::{json, Value};
use std::{
    collections::VecDeque,
    sync::{Mutex, OnceLock},
    time::{Duration, Instant},
};

pub const MAX_BYTES: usize = 16 * 1024 * 1024;
const TTL: Duration = Duration::from_secs(60);
struct Artifact {
    id: String,
    bytes: axum::body::Bytes,
    created: Instant,
    mime: &'static str,
    disposition: &'static str,
}
static STORE: OnceLock<Mutex<VecDeque<Artifact>>> = OnceLock::new();

fn store() -> &'static Mutex<VecDeque<Artifact>> {
    STORE.get_or_init(|| Mutex::new(VecDeque::new()))
}
fn prune(items: &mut VecDeque<Artifact>) {
    items.retain(|item| item.created.elapsed() < TTL);
}

pub fn insert(bytes: Vec<u8>, width: u32, height: u32) -> Value {
    let mut result = insert_resource(bytes, "image/png", "attachment; filename=screenshot.png");
    result["width"] = json!(width);
    result["height"] = json!(height);
    result
}

pub fn insert_file(bytes: Vec<u8>, name: &str) -> Value {
    use sha2::{Digest, Sha256};
    let digest = format!("{:x}", Sha256::digest(&bytes));
    let mut result = insert_resource(
        bytes,
        "application/octet-stream",
        "attachment; filename=file.bin",
    );
    result["name"] = json!(name);
    result["sha256"] = json!(digest);
    result
}

fn insert_resource(bytes: Vec<u8>, mime: &'static str, disposition: &'static str) -> Value {
    let id = uuid::Uuid::new_v4().to_string();
    let size = bytes.len();
    let mut items = store().lock().unwrap_or_else(|e| e.into_inner());
    prune(&mut items);
    while items.len() >= 4 {
        items.pop_front();
    }
    items.push_back(Artifact {
        id: id.clone(),
        bytes: bytes.into(),
        created: Instant::now(),
        mime,
        disposition,
    });
    json!({"id":id,"path":format!("/artifacts/{id}"),"mimeType":mime,"sizeBytes":size,"expiresInSeconds":60})
}

pub async fn download(Path(id): Path<String>) -> Response {
    let mut items = store().lock().unwrap_or_else(|e| e.into_inner());
    prune(&mut items);
    match items.iter().find(|item| item.id == id) {
        Some(item) => (
            [
                (header::CONTENT_TYPE, item.mime),
                (header::CACHE_CONTROL, "no-store"),
                (header::CONTENT_DISPOSITION, item.disposition),
                (header::X_CONTENT_TYPE_OPTIONS, "nosniff"),
            ],
            item.bytes.clone(),
        )
            .into_response(),
        None => StatusCode::NOT_FOUND.into_response(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[tokio::test]
    async fn artifacts_have_opaque_ids_and_bounded_retention() {
        let first = insert(vec![1], 1, 1);
        for _ in 0..4 {
            insert(vec![2], 1, 1);
        }
        assert_eq!(
            download(Path(first["id"].as_str().unwrap().into()))
                .await
                .status(),
            StatusCode::NOT_FOUND
        );
        assert_eq!(
            download(Path("../state/model.json".into())).await.status(),
            StatusCode::NOT_FOUND
        );
        let latest = insert(vec![3], 1, 1);
        assert_eq!(
            download(Path(latest["id"].as_str().unwrap().into()))
                .await
                .status(),
            StatusCode::OK
        );
        let descriptor = insert_file(b"fixture".to_vec(), "fixture.txt");
        let response = download(Path(descriptor["id"].as_str().unwrap().into())).await;
        assert_eq!(
            response.headers()[header::CONTENT_TYPE],
            "application/octet-stream"
        );
        assert_eq!(descriptor["sha256"].as_str().unwrap().len(), 64);
        let latest = descriptor;
        let mut items = store().lock().unwrap();
        items.back_mut().unwrap().created = Instant::now() - TTL;
        prune(&mut items);
        assert!(!items
            .iter()
            .any(|item| item.id == latest["id"].as_str().unwrap()));
    }
}
