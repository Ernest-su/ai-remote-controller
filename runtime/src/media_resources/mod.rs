use anyhow::{bail, Context, Result};
use std::{
    collections::HashMap,
    fs::{File, Metadata},
    sync::{
        atomic::{AtomicBool, AtomicU16, AtomicUsize},
        Arc, Mutex, OnceLock,
    },
    time::{Duration, Instant},
};
mod http;
pub use http::download;
static PORT: AtomicU16 = AtomicU16::new(0);
static RESOURCES: OnceLock<Mutex<HashMap<String, Arc<Resource>>>> = OnceLock::new();
fn resources() -> &'static Mutex<HashMap<String, Arc<Resource>>> {
    RESOURCES.get_or_init(|| Mutex::new(HashMap::new()))
}
struct Resource {
    file: File,
    metadata: Metadata,
    mime: String,
    created: Instant,
    touched: Mutex<Instant>,
    readers: AtomicUsize,
    revoked: AtomicBool,
}
pub fn initialize(port: u16) {
    PORT.store(port, std::sync::atomic::Ordering::Relaxed);
}
pub fn base_url() -> Result<String> {
    let port = PORT.load(std::sync::atomic::Ordering::Relaxed);
    if port == 0 {
        bail!("media endpoint unavailable");
    }
    Ok(format!("http://127.0.0.1:{port}"))
}
pub fn register(file: File, mime: &str) -> Result<(String, String)> {
    let metadata = file.metadata()?;
    if !metadata.is_file() || metadata.len() > 1024 * 1024 * 1024 * 1024 {
        bail!("media requires a regular file up to 1 TiB");
    }
    let mut registry = resources().lock().unwrap();
    registry.retain(|_, r| r.touched.lock().unwrap().elapsed() < Duration::from_secs(86400));
    if registry.len() >= 4 {
        let oldest = registry
            .iter()
            .filter(|(_, r)| r.readers.load(std::sync::atomic::Ordering::Relaxed) == 0)
            .min_by_key(|(_, r)| r.created)
            .map(|(id, _)| id.clone())
            .context("all media resources are streaming; release one first")?;
        registry.remove(&oldest);
    }
    let id = uuid::Uuid::new_v4().to_string();
    let url = format!("{}/media/{id}", base_url()?);
    registry.insert(
        id.clone(),
        Arc::new(Resource {
            file,
            metadata,
            mime: mime.into(),
            created: Instant::now(),
            touched: Mutex::new(Instant::now()),
            readers: AtomicUsize::new(0),
            revoked: AtomicBool::new(false),
        }),
    );
    Ok((id, url))
}
pub fn revoke(id: &str) -> Result<()> {
    resources()
        .lock()
        .unwrap()
        .remove(id)
        .context("media resource not found")?
        .revoked
        .store(true, std::sync::atomic::Ordering::Relaxed);
    Ok(())
}
fn lookup(id: &str) -> Result<Arc<Resource>> {
    let registry = resources().lock().unwrap();
    let resource = registry
        .get(id)
        .context("media resource not found")?
        .clone();
    if resource.touched.lock().unwrap().elapsed() >= Duration::from_secs(86400) {
        bail!("media resource expired");
    }
    *resource.touched.lock().unwrap() = Instant::now();
    Ok(resource)
}
#[cfg(test)]
mod tests;
