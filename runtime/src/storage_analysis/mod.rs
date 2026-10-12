use anyhow::{bail, Context, Result};
use serde_json::Value;
use std::{
    fs::File,
    os::unix::fs::MetadataExt,
    sync::atomic::{AtomicBool, AtomicU64},
};
mod report;
#[cfg(test)]
mod tests;
mod walk;
static SCANS: tokio::sync::Semaphore = tokio::sync::Semaphore::const_new(1);
fn scan(
    folder: File,
    root: String,
    path: String,
    limit: u64,
    cancel: &AtomicBool,
    progress: &AtomicU64,
) -> Result<Value> {
    let meta = folder.metadata()?;
    if !meta.is_dir() {
        bail!("storage analysis requires a directory");
    }
    let mut scanner = walk::Scanner::new(
        report::Report::new(root, path.clone()),
        meta.dev(),
        limit,
        cancel,
        progress,
    );
    scanner.walk(folder, path, 0)?;
    scanner.report.complete = !scanner.report.truncated
        && scanner.report.error_count == 0
        && scanner.report.changed_directories == 0;
    Ok(serde_json::to_value(scanner.report)?)
}
pub(crate) fn start(args: &Value) -> Result<String> {
    let object = args
        .as_object()
        .context("storage arguments must be object")?;
    if object
        .keys()
        .any(|key| !["root", "path", "maxEntries"].contains(&key.as_str()))
    {
        bail!("only root, path and maxEntries allowed");
    }
    let root = args
        .get("root")
        .map(|v| v.as_str().context("root must be string"))
        .transpose()?
        .unwrap_or("downloads")
        .to_owned();
    let path = args
        .get("path")
        .map(|v| v.as_str().context("path must be string"))
        .transpose()?
        .unwrap_or("")
        .to_owned();
    let limit = args
        .get("maxEntries")
        .map(|v| v.as_u64().context("maxEntries must be integer"))
        .transpose()?
        .unwrap_or(100000);
    if !(1..=100000).contains(&limit) {
        bail!("maxEntries must be between 1 and 100000");
    }
    let folder = crate::tools::files::open(&crate::tools::files::root(&root)?, &path)?;
    if !folder.metadata()?.is_dir() {
        bail!("storage analysis requires a directory");
    }
    let permit = SCANS
        .try_acquire()
        .context("another storage analysis is in progress")?;
    Ok(crate::transfers::start_background(
        root.clone(),
        path.clone(),
        "storage_scan",
        limit,
        move |cancel, count| {
            let _permit = permit;
            scan(folder, root, path, limit, cancel, count)
        },
    )?
    .to_string())
}
