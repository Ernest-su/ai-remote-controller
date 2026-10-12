use anyhow::{bail, Context, Result};
use serde::Serialize;
use serde_json::Value;
use sha2::{Digest, Sha256};
use std::{
    fs::File,
    io::{Read, Seek, SeekFrom},
    os::unix::fs::MetadataExt,
    sync::atomic::{AtomicBool, AtomicU64, Ordering},
};
mod manifest;
mod signing;
#[cfg(test)]
mod tests;
static INSPECTIONS: tokio::sync::Semaphore = tokio::sync::Semaphore::const_new(1);
const MAX_APK: u64 = 4 * 1024 * 1024 * 1024;
const MAX_MANIFEST: u64 = 8 * 1024 * 1024;
#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct ApkInfo {
    pub(super) root: String,
    pub(super) path: String,
    pub(super) package: String,
    pub(super) version_code: u64,
    pub(super) version_name: Option<String>,
    pub(super) split: Option<String>,
    pub(super) size_bytes: u64,
    pub(super) sha256: String,
    pub(super) signatures: Vec<signing::Signature>,
    pub(super) signature_verified: bool,
}
pub(super) fn identity(meta: &std::fs::Metadata) -> (u64, u64, u64, i64, i64, i64, i64) {
    (
        meta.dev(),
        meta.ino(),
        meta.len(),
        meta.mtime(),
        meta.mtime_nsec(),
        meta.ctime(),
        meta.ctime_nsec(),
    )
}
pub(super) fn inspect(
    mut file: File,
    root: String,
    path: String,
    cancelled: &AtomicBool,
    progress: &AtomicU64,
    progress_base: u64,
) -> Result<ApkInfo> {
    if cancelled.load(Ordering::Relaxed) {
        bail!("APK inspection cancelled");
    }
    let before = file.metadata()?;
    if !before.is_file() || before.len() == 0 || before.len() > MAX_APK {
        bail!("APK must be a regular file of at most 4 GiB");
    }
    let directory = signing::directory(&mut file, before.len())?;
    let mut signatures = signing::inspect(&mut file, &directory)?;
    let manifest = {
        file.seek(SeekFrom::Start(0))?;
        let mut archive = zip::ZipArchive::new(&mut file)?;
        if archive.len() != directory.entries || directory.size == 0 {
            bail!("APK archive entry count mismatch");
        }
        let mut manifests = 0;
        let mut v1 = false;
        for index in 0..archive.len() {
            let entry = archive.by_index(index)?;
            let name = entry.name()?;
            if name == "AndroidManifest.xml" {
                manifests += 1;
            }
            let name = name.to_ascii_uppercase();
            if name.starts_with("META-INF/")
                && [".RSA", ".DSA", ".EC"]
                    .iter()
                    .any(|ext| name.ends_with(ext))
            {
                v1 = true;
            }
        }
        if manifests != 1 {
            bail!("APK must contain exactly one AndroidManifest.xml");
        }
        if v1 {
            signatures.insert(
                0,
                signing::Signature {
                    scheme: "v1".into(),
                    certificate_sha256: vec![],
                    certificate_extraction_supported: false,
                },
            );
        }
        let entry = archive.by_name("AndroidManifest.xml")?;
        if entry.size() > MAX_MANIFEST {
            bail!("APK manifest exceeds 8 MiB");
        }
        let expected = entry.size();
        let mut bytes = Vec::new();
        entry.take(MAX_MANIFEST + 1).read_to_end(&mut bytes)?;
        if bytes.len() as u64 != expected || bytes.len() as u64 > MAX_MANIFEST {
            bail!("APK manifest length mismatch");
        }
        manifest::parse(&bytes)?
    };
    file.seek(SeekFrom::Start(0))?;
    let mut hash = Sha256::new();
    let mut buffer = [0; 64 * 1024];
    let mut total = 0u64;
    loop {
        if cancelled.load(Ordering::Relaxed) {
            bail!("APK inspection cancelled");
        }
        let count = file.read(&mut buffer)?;
        if count == 0 {
            break;
        }
        total += count as u64;
        if total > MAX_APK {
            bail!("APK grew beyond size limit");
        }
        hash.update(&buffer[..count]);
        progress.store(progress_base + total, Ordering::Relaxed);
    }
    if total != before.len() || identity(&before) != identity(&file.metadata()?) {
        bail!("APK changed during inspection; retry");
    }
    Ok(ApkInfo {
        root,
        path,
        package: manifest.package,
        version_code: manifest.version_code,
        version_name: manifest.version_name,
        split: manifest.split,
        size_bytes: total,
        sha256: format!("{:x}", hash.finalize()),
        signatures,
        signature_verified: false,
    })
}
pub(super) async fn execute(args: &Value) -> Result<String> {
    let object = args.as_object().context("APK arguments must be object")?;
    if object
        .keys()
        .any(|key| !["root", "path"].contains(&key.as_str()))
    {
        bail!("only root and path accepted");
    }
    let root = args
        .get("root")
        .map(|v| v.as_str().context("root must be string"))
        .transpose()?
        .unwrap_or("downloads")
        .to_owned();
    let path = args["path"]
        .as_str()
        .context("APK path required")?
        .to_owned();
    let directory = crate::tools::files::root(&root)?;
    let file = crate::tools::files::open(&directory, &path)?;
    let permit = INSPECTIONS
        .try_acquire()
        .context("another APK inspection is in progress")?;
    let metadata = file.metadata()?;
    if !metadata.is_file() || metadata.len() == 0 || metadata.len() > MAX_APK {
        bail!("APK must be a regular file of at most 4 GiB");
    }
    let result = crate::transfers::start_background(
        root.clone(),
        path.clone(),
        "apk_inspect",
        metadata.len(),
        move |cancelled, progress| {
            // A cancelled client request does not release the worker's inspection permit early.
            let _permit = permit;
            let info = inspect(file, root, path, cancelled, progress, 0)?;
            Ok(serde_json::to_value(info)?)
        },
    )?;
    Ok(result.to_string())
}
