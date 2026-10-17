use anyhow::{bail, Context, Result};
use serde_json::{json, Value};
use std::{
    collections::HashMap,
    fs::File,
    os::fd::AsRawFd,
    sync::{
        atomic::{AtomicBool, AtomicU64, Ordering},
        Arc, Mutex, OnceLock,
    },
    time::Instant,
};
pub(super) const CHUNK: usize = 64 * 1024;
pub(super) const MAX_FILE: u64 = 1024 * 1024 * 1024 * 1024; // Protocol quota, not an in-memory allocation.
pub(super) struct UploadTarget {
    pub(super) parent: File,
    pub(super) temporary: std::ffi::CString,
    pub(super) destination: std::ffi::CString,
    pub(super) committed: bool,
    pub(super) overwrite: bool,
    pub(super) copy_commit: bool,
    pub(super) expected: Option<std::fs::Metadata>,
}
impl Drop for UploadTarget {
    fn drop(&mut self) {
        if !self.committed {
            unsafe {
                libc::unlinkat(self.parent.as_raw_fd(), self.temporary.as_ptr(), 0);
            }
        }
    }
}
pub(super) struct Transfer {
    pub(super) id: String,
    pub(super) direction: String,
    pub(super) root: String,
    pub(super) name: String,
    pub(super) size: u64,
    pub(super) bytes: AtomicU64,
    pub(super) cancelled: AtomicBool,
    pub(super) busy: AtomicBool,
    pub(super) phase: Mutex<String>,
    pub(super) digest: Mutex<Option<String>>,
    pub(super) error: Mutex<Option<String>>,
    pub(super) result: Mutex<Option<Value>>,
    pub(super) file: Mutex<Option<File>>,
    pub(super) destination: Mutex<Option<UploadTarget>>,
    pub(super) touched: Mutex<Instant>,
    pub(super) wake: tokio::sync::Notify,
}
impl Transfer {
    pub(super) fn phase(&self, value: &str) {
        *self.phase.lock().unwrap() = value.into();
        *self.touched.lock().unwrap() = Instant::now();
    }
    pub(super) fn progress(&self) -> Value {
        *self.touched.lock().unwrap() = Instant::now();
        json!({"id":self.id,"direction":self.direction,"root":self.root,"name":self.name,"sizeBytes":self.size,"bytesTransferred":self.bytes.load(Ordering::Relaxed),"phase":self.phase.lock().unwrap().clone(),"sha256":self.digest.lock().unwrap().clone(),"error":self.error.lock().unwrap().clone(),"result":self.result.lock().unwrap().clone(),"unit":if self.direction=="delete" || self.direction=="app_uninstall" || self.direction=="app_cache_clear" || self.direction=="app_control" || self.direction=="storage_scan" {"items"}else{"bytes"},"dataPath":format!("/transfers/{}/data",self.id)})
    }
    pub(super) fn failed(&self, message: String) {
        *self.error.lock().unwrap() = Some(message);
        self.phase(if self.cancelled.load(Ordering::Relaxed) {
            "cancelled"
        } else {
            "failed"
        });
        self.destination.lock().unwrap().take();
    }
}
pub(super) struct Busy(Arc<Transfer>);
impl Drop for Busy {
    fn drop(&mut self) {
        let phase = self.0.phase.lock().unwrap().clone();
        if phase == "uploading" {
            self.0.failed("upload interrupted before commit".into());
        }
        if phase == "downloading" {
            if self.0.cancelled.load(Ordering::Relaxed) {
                self.0.failed("download cancelled".into());
            } else {
                self.0.phase("ready");
            }
        }
        self.0.busy.store(false, Ordering::Release);
    }
}
static TASKS: OnceLock<Mutex<HashMap<String, Arc<Transfer>>>> = OnceLock::new();
pub(super) fn tasks() -> &'static Mutex<HashMap<String, Arc<Transfer>>> {
    TASKS.get_or_init(|| Mutex::new(HashMap::new()))
}
pub(super) fn lookup(id: &str) -> Result<Arc<Transfer>> {
    tasks()
        .lock()
        .unwrap()
        .get(id)
        .cloned()
        .context("transfer task not found")
}
pub(super) fn claim(task: Arc<Transfer>) -> Result<Busy> {
    if task.busy.swap(true, Ordering::AcqRel) {
        bail!("transfer already in progress");
    }
    Ok(Busy(task))
}
pub(super) fn cancelled(task: &Transfer) -> Result<()> {
    if task.cancelled.load(Ordering::Relaxed) {
        bail!("transfer cancelled");
    }
    Ok(())
}
