use anyhow::{Context, Result};
use serde::Serialize;
#[derive(Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct Totals {
    pub files: u64,
    pub logical_bytes: u64,
    pub allocated_bytes: u64,
}
impl Totals {
    pub fn add(&mut self, logical: u64, allocated: u64) -> Result<()> {
        let logical_bytes = self
            .logical_bytes
            .checked_add(logical)
            .filter(|v| *v <= i64::MAX as u64)
            .context("storage logical total exceeds protocol range")?;
        let allocated_bytes = self
            .allocated_bytes
            .checked_add(allocated)
            .filter(|v| *v <= i64::MAX as u64)
            .context("storage allocated total exceeds protocol range")?;
        self.files += 1;
        self.logical_bytes = logical_bytes;
        self.allocated_bytes = allocated_bytes;
        Ok(())
    }
}
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct LargeFile {
    pub path: String,
    pub category: String,
    pub logical_bytes: u64,
    pub allocated_bytes: u64,
}
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct ScanError {
    pub path: String,
    pub message: String,
}
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct Report {
    pub root: String,
    pub path: String,
    pub totals: Totals,
    pub categories: std::collections::BTreeMap<String, Totals>,
    pub largest_files: Vec<LargeFile>,
    pub entries_visited: u64,
    pub directories: u64,
    pub duplicate_files: u64,
    pub repeated_directories: u64,
    pub skipped_links: u64,
    pub skipped_special: u64,
    pub skipped_filesystems: u64,
    pub error_count: u64,
    pub errors: Vec<ScanError>,
    pub changed_directories: u64,
    pub truncated: bool,
    pub complete: bool,
    pub snapshot: bool,
}
impl Report {
    pub fn new(root: String, path: String) -> Self {
        Self {
            root,
            path,
            totals: Totals::default(),
            categories: Default::default(),
            largest_files: vec![],
            entries_visited: 0,
            directories: 0,
            duplicate_files: 0,
            repeated_directories: 0,
            skipped_links: 0,
            skipped_special: 0,
            skipped_filesystems: 0,
            error_count: 0,
            errors: vec![],
            changed_directories: 0,
            truncated: false,
            complete: false,
            snapshot: false,
        }
    }
    pub fn error(&mut self, path: &str, message: impl std::fmt::Display) {
        self.error_count += 1;
        if self.errors.len() < 8 {
            self.errors.push(ScanError {
                path: path.into(),
                message: message.to_string().chars().take(160).collect(),
            });
        }
    }
    pub fn file(&mut self, path: String, logical: u64, allocated: u64) -> Result<()> {
        let category = category(&path).to_owned();
        self.totals.add(logical, allocated)?;
        self.categories
            .entry(category.clone())
            .or_default()
            .add(logical, allocated)?;
        self.largest_files.push(LargeFile {
            path,
            category,
            logical_bytes: logical,
            allocated_bytes: allocated,
        });
        self.largest_files.sort_by(|a, b| {
            b.logical_bytes
                .cmp(&a.logical_bytes)
                .then(a.path.cmp(&b.path))
        });
        self.largest_files.truncate(20);
        Ok(())
    }
}
pub(super) fn category(path: &str) -> &'static str {
    let name = path.rsplit('/').next().unwrap_or("");
    let extension = name
        .rsplit_once('.')
        .map(|(_, ext)| ext.to_ascii_lowercase())
        .unwrap_or_default();
    match extension.as_str() {
        "apk" => "apk",
        "jpg" | "jpeg" | "png" | "gif" | "webp" | "heic" | "bmp" | "avif" => "images",
        "mp4" | "mkv" | "avi" | "webm" | "mov" | "m4v" | "ts" => "video",
        "mp3" | "wav" | "flac" | "ogg" | "aac" | "m4a" | "opus" => "audio",
        "zip" | "gz" | "xz" | "tar" | "7z" | "rar" => "archives",
        "txt" | "pdf" | "md" | "json" | "xml" | "csv" | "doc" | "docx" | "xls" | "xlsx" => {
            "documents"
        }
        "log" => "logs",
        "tmp" | "temp" | "part" => "temporary",
        _ => "other",
    }
}
