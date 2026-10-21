//! Explicit manual schedules persist on the target. No model tool can create or enable them.
mod time;
use anyhow::{bail, Context, Result};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::{
    path::{Path, PathBuf},
    sync::Arc,
};
use tokio::sync::Mutex;

#[derive(Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Entry {
    id: String,
    action: String,
    package: Option<String>,
    volume_percent: Option<u8>,
    app_identity: Option<Value>,
    kind: String,
    daily_time: Option<String>,
    next_at: i64,
    enabled: bool,
    last_at: Option<i64>,
    last_status: String,
    last_result: Option<String>,
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Draft {
    action: String,
    package: Option<String>,
    volume_percent: Option<u8>,
    kind: String,
    delay_seconds: Option<i64>,
    local_date_time: Option<String>,
    daily_time: Option<String>,
    confirmed: bool,
}
struct Store {
    path: PathBuf,
    entries: Vec<Entry>,
}
impl Store {
    fn persist(&self) -> Result<()> {
        crate::config::private_write(&self.path, &serde_json::to_vec(&self.entries)?)?;
        std::fs::File::open(self.path.parent().unwrap())?.sync_all()?;
        Ok(())
    }
}
#[derive(Clone)]
pub struct Scheduler(Arc<Mutex<Store>>);
impl Scheduler {
    pub fn load(dir: &Path) -> Result<Self> {
        let path = dir.join("schedules.json");
        let mut entries: Vec<Entry> = if path.exists() {
            let metadata = std::fs::symlink_metadata(&path)?;
            if !metadata.is_file()
                || metadata.file_type().is_symlink()
                || metadata.len() > 256 * 1024
            {
                bail!("invalid schedule storage");
            }
            serde_json::from_slice(&std::fs::read(&path)?)?
        } else {
            Vec::new()
        };
        if entries.len() > 100 {
            bail!("too many stored schedules");
        }
        let now = time::now();
        for entry in &mut entries {
            validate_entry(entry)?;
            if entry.last_status == "running" {
                entry.last_status = "interrupted".into();
                entry.last_result = Some("服务退出，操作结果不确定；不会自动重试。".into());
            }
            if entry.enabled && entry.next_at <= now {
                entry.last_at = Some(entry.next_at);
                entry.last_status = "missed".into();
                entry.last_result = Some("服务未运行时错过执行，已跳过。".into());
                advance(entry, now)?;
            }
        }
        let store = Store { path, entries };
        store.persist()?;
        Ok(Self(Arc::new(Mutex::new(store))))
    }
    pub async fn request(&self, operation: &str, args: &Value) -> Result<String> {
        if operation == "schedule_list" {
            if args.as_object().is_none_or(|o| !o.is_empty()) {
                bail!("list accepts empty arguments");
            }
            let store = self.0.lock().await;
            let now = time::now();
            let local = time::local(now);
            let tasks: Vec<Value> = store
                .entries
                .iter()
                .map(|entry| {
                    let mut value = serde_json::to_value(entry).unwrap();
                    value["nextLocalTime"] = json!(time::formatted(entry.next_at));
                    value
                })
                .collect();
            return Ok(json!({"tasks":tasks,"targetNow":now,"targetLocalTime":time::formatted(now),"utcOffsetSeconds":local.tm_gmtoff,"runningRequired":true}).to_string());
        }
        if operation == "schedule_save" {
            let draft: Draft = serde_json::from_value(args.clone())?;
            if !draft.confirmed {
                bail!("explicit user confirmation required");
            }
            validate_parameters(
                &draft.action,
                draft.package.as_deref(),
                draft.volume_percent,
            )?;
            let identity = validate_action(&draft.action, draft.package.as_deref()).await?;
            let now = time::now();
            let next = match draft.kind.as_str() {
                "delay" if draft.local_date_time.is_none() && draft.daily_time.is_none() => {
                    let delay = draft.delay_seconds.context("delay required")?;
                    if !(60..=7 * 86400).contains(&delay) {
                        bail!("delay must be 1 minute to 7 days");
                    }
                    now + delay
                }
                "once" if draft.delay_seconds.is_none() && draft.daily_time.is_none() => {
                    time::once(
                        draft
                            .local_date_time
                            .as_deref()
                            .context("local date/time required")?,
                        now,
                    )?
                }
                "daily" if draft.delay_seconds.is_none() && draft.local_date_time.is_none() => {
                    time::daily(
                        draft.daily_time.as_deref().context("daily time required")?,
                        now,
                    )?
                }
                _ => bail!("invalid schedule kind or mixed time fields"),
            };
            let entry = Entry {
                id: uuid::Uuid::new_v4().to_string(),
                action: draft.action,
                package: draft.package,
                volume_percent: draft.volume_percent,
                app_identity: identity,
                kind: draft.kind,
                daily_time: draft.daily_time,
                next_at: next,
                enabled: true,
                last_at: None,
                last_status: "pending".into(),
                last_result: None,
            };
            let mut store = self.0.lock().await;
            if store.entries.len() >= 100 {
                bail!("最多保存100个定时任务，请先删除已完成任务");
            }
            store.entries.push(entry.clone());
            if let Err(error) = store.persist() {
                store.entries.pop();
                return Err(error);
            }
            return Ok(serde_json::to_string(&entry)?);
        }
        let object = args.as_object().context("arguments must be object")?;
        if !matches!(operation, "schedule_delete" | "schedule_toggle")
            || object
                .keys()
                .any(|k| !["id", "enabled", "confirmed"].contains(&k.as_str()))
        {
            bail!("invalid schedule operation");
        }
        let id = args["id"].as_str().context("id required")?;
        let mut store = self.0.lock().await;
        let index = store
            .entries
            .iter()
            .position(|e| e.id == id)
            .context("schedule not found")?;
        if store.entries[index].last_status == "running" {
            bail!("任务已提交，不能取消或删除；请等待结果");
        }
        let old = store.entries.clone();
        if operation == "schedule_delete" {
            store.entries.remove(index);
        } else {
            let enabled = args["enabled"].as_bool().context("enabled required")?;
            if enabled && args["confirmed"] != true {
                bail!("enabling requires explicit user confirmation");
            }
            let entry = &mut store.entries[index];
            if enabled && entry.next_at <= time::now() {
                if entry.kind != "daily" {
                    bail!("单次任务已过期，请新建任务");
                }
                entry.next_at = time::daily(
                    entry.daily_time.as_deref().context("daily time missing")?,
                    time::now(),
                )?;
            }
            entry.enabled = enabled;
        }
        if let Err(error) = store.persist() {
            store.entries = old;
            return Err(error);
        }
        Ok(json!({"id":id,"saved":true}).to_string())
    }
    pub async fn run(self, mut stop: tokio::sync::watch::Receiver<bool>) {
        loop {
            tokio::select! { _=stop.changed()=>break, _=tokio::time::sleep(std::time::Duration::from_secs(1))=>{} }
            if *stop.borrow() {
                break;
            }
            if let Err(error) = self.tick().await {
                eprintln!("schedule execution failed: {error:#}");
            }
        }
    }
    async fn tick(&self) -> Result<()> {
        self.tick_with(|entry| async move { perform(&entry).await })
            .await
    }
    async fn tick_with<F, Fut>(&self, execute: F) -> Result<()>
    where
        F: Fn(Entry) -> Fut,
        Fut: std::future::Future<Output = Result<String>>,
    {
        loop {
            let now = time::now();
            let entry = {
                let mut store = self.0.lock().await;
                let Some(index) = store
                    .entries
                    .iter()
                    .position(|e| e.enabled && e.next_at <= now)
                else {
                    return Ok(());
                };
                let old = store.entries[index].clone();
                let entry = &mut store.entries[index];
                entry.last_at = Some(entry.next_at);
                entry.last_status = if now - entry.next_at > 60 {
                    "missed"
                } else {
                    "running"
                }
                .into();
                entry.last_result = None;
                advance(entry, now)?;
                let run = entry.clone();
                if let Err(error) = store.persist() {
                    store.entries[index] = old;
                    return Err(error);
                }
                run
            };
            if entry.last_status == "missed" {
                continue;
            }
            let result =
                tokio::time::timeout(std::time::Duration::from_secs(45), execute(entry.clone()))
                    .await;
            let (status, output) = match result {
                Ok(Ok(value)) => ("submitted", value),
                Ok(Err(error)) => ("failed", error.to_string()),
                Err(_) => ("uncertain", "执行超时，结果不确定，不自动重试。".into()),
            };
            let mut store = self.0.lock().await;
            if let Some(stored) = store.entries.iter_mut().find(|e| e.id == entry.id) {
                stored.last_status = status.into();
                stored.last_result = Some(output.chars().take(4096).collect());
            }
            store.persist()?;
        }
    }
}
fn advance(entry: &mut Entry, now: i64) -> Result<()> {
    if entry.kind == "daily" {
        entry.next_at = time::daily(
            entry.daily_time.as_deref().context("daily time required")?,
            now,
        )?;
    } else {
        entry.enabled = false;
    }
    Ok(())
}
fn validate_entry(e: &Entry) -> Result<()> {
    uuid::Uuid::parse_str(&e.id)?;
    validate_parameters(&e.action, e.package.as_deref(), e.volume_percent)?;
    if ![
        "pause",
        "set_volume",
        "mute",
        "unmute",
        "shutdown",
        "stop_app",
        "open_app",
    ]
    .contains(&e.action.as_str())
        || !["delay", "once", "daily"].contains(&e.kind.as_str())
        || e.next_at < 0
    {
        bail!("invalid stored schedule");
    }
    if e.kind == "daily" {
        time::clock(e.daily_time.as_deref().context("daily time required")?)?;
    }
    if matches!(e.action.as_str(), "stop_app" | "open_app")
        && (e.package.is_none() || e.app_identity.is_none())
    {
        bail!("schedule application identity missing");
    }
    Ok(())
}
fn validate_parameters(action: &str, package: Option<&str>, volume: Option<u8>) -> Result<()> {
    if action == "set_volume" {
        if package.is_some() || volume.is_none_or(|v| v > 100) {
            bail!("媒体音量必须为 0 到 100 的整数，且不能指定应用");
        }
    } else if volume.is_some() {
        bail!("只有调节媒体音量可指定 volumePercent");
    }
    if !matches!(action, "stop_app" | "open_app") && package.is_some() {
        bail!("此操作不能指定应用");
    }
    Ok(())
}
async fn validate_action(action: &str, package: Option<&str>) -> Result<Option<Value>> {
    match action {
        "pause" | "set_volume" | "mute" | "unmute" if package.is_none() => Ok(None),
        "shutdown" if package.is_none() => {
            crate::system_actions::check("shutdown").await?;
            Ok(None)
        }
        "stop_app" | "open_app" => {
            let package = package.context("请选择应用")?;
            let app: Value = serde_json::from_str(
                &crate::tools::execute("apps.info", &json!({"package":package})).await?,
            )?;
            if action == "stop_app"
                && (app["system"] != false || app["appId"].as_u64().is_none_or(|id| id < 10000))
            {
                bail!("不能定时强行停止系统或特权应用");
            }
            let mut identity = serde_json::Map::new();
            for key in [
                "package",
                "userId",
                "appId",
                "versionCode",
                "firstInstallTime",
                "lastUpdateTime",
                "codePath",
                "system",
            ] {
                if let Some(value) = app.get(key) {
                    identity.insert(key.into(), value.clone());
                }
            }
            if identity.get("package").and_then(Value::as_str) != Some(package)
                || !identity.get("userId").is_some_and(Value::is_u64)
                || !identity.get("versionCode").is_some_and(Value::is_u64)
            {
                bail!("invalid target application identity");
            }
            Ok(Some(Value::Object(identity)))
        }
        _ => bail!("unsupported schedule action or unexpected package"),
    }
}
async fn perform(entry: &Entry) -> Result<String> {
    validate_parameters(
        &entry.action,
        entry.package.as_deref(),
        entry.volume_percent,
    )?;
    let identity = validate_action(&entry.action, entry.package.as_deref()).await?;
    if identity != entry.app_identity {
        bail!("应用版本、安装或用户已改变，请删除后重新创建定时任务");
    }
    match entry.action.as_str() {
        "pause" => crate::tools::pause_current_media().await,
        "set_volume" | "mute" | "unmute" => {
            let user = crate::tools::run_command("am", &["get-current-user".into()])
                .await?
                .trim()
                .parse::<u32>()?;
            let mut args = vec![user.to_string()];
            if let Some(volume) = entry.volume_percent {
                args.push(volume.to_string());
            }
            crate::platform_bridge::scheduled_audio(&entry.action, &args).await
        }
        "shutdown" => crate::platform_bridge::system_action("shutdown").await,
        "open_app" => crate::tools::execute("apps.open", &json!({"package":entry.package})).await,
        "stop_app" => {
            let info = entry
                .app_identity
                .as_ref()
                .context("missing app identity")?;
            crate::tools::run_command(
                "am",
                &[
                    "force-stop".into(),
                    "--user".into(),
                    info["userId"].as_u64().context("user missing")?.to_string(),
                    entry.package.clone().unwrap(),
                ],
            )
            .await?;
            let state: Value = serde_json::from_str(
                &crate::tools::execute("apps.control_info", &json!({"package":entry.package}))
                    .await?,
            )?;
            if state["state"]["stopped"] != true {
                bail!("退出应用未验证，请核对目标设备");
            }
            Ok(json!({"stopped":true,"package":entry.package}).to_string())
        }
        _ => bail!("unsupported schedule action"),
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[tokio::test]
    async fn due_tasks_execute_once_without_a_controller_and_daily_tasks_advance() {
        let dir = std::env::temp_dir().join(format!("airc-schedule-due-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir(&dir).unwrap();
        let scheduler = Scheduler::load(&dir).unwrap();
        scheduler
            .request(
                "schedule_save",
                &json!({"kind":"delay","delaySeconds":60,"action":"pause","confirmed":true}),
            )
            .await
            .unwrap();
        {
            let mut store = scheduler.0.lock().await;
            store.entries[0].next_at = time::now() - 1;
        }
        let calls = std::sync::atomic::AtomicUsize::new(0);
        scheduler
            .tick_with(|_| {
                calls.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
                async { Ok("fixture submitted".into()) }
            })
            .await
            .unwrap();
        scheduler
            .tick_with(|_| async { panic!("single job replayed") })
            .await
            .unwrap();
        assert_eq!(calls.load(std::sync::atomic::Ordering::Relaxed), 1);
        {
            let mut store = scheduler.0.lock().await;
            let entry = &mut store.entries[0];
            assert_eq!(entry.last_status, "submitted");
            entry.kind = "daily".into();
            entry.daily_time = Some("12:00".into());
            entry.enabled = true;
            entry.next_at = time::now() - 1;
        }
        scheduler
            .tick_with(|_| async { Err(anyhow::anyhow!("fixture denied")) })
            .await
            .unwrap();
        {
            let store = scheduler.0.lock().await;
            assert!(store.entries[0].next_at > time::now());
            assert_eq!(store.entries[0].last_status, "failed");
        }
        drop(scheduler);
        let recovered = Scheduler::load(&dir).unwrap();
        assert!(recovered.0.lock().await.entries[0].enabled);
        std::fs::remove_dir_all(dir).unwrap();
    }
    #[tokio::test]
    async fn audio_parameters_persist_and_invalid_combinations_are_rejected() {
        let dir =
            std::env::temp_dir().join(format!("airc-schedule-audio-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir(&dir).unwrap();
        let scheduler = Scheduler::load(&dir).unwrap();
        for (action, volume) in [
            ("set_volume", Some(0)),
            ("set_volume", Some(100)),
            ("mute", None),
            ("unmute", None),
        ] {
            let mut draft =
                json!({"action":action,"kind":"daily","dailyTime":"20:00","confirmed":true});
            if let Some(v) = volume {
                draft["volumePercent"] = json!(v);
            }
            scheduler.request("schedule_save", &draft).await.unwrap();
        }
        for fields in [
            json!({"action":"set_volume"}),
            json!({"action":"set_volume","volumePercent":-1}),
            json!({"action":"set_volume","volumePercent":101}),
            json!({"action":"set_volume","volumePercent":1.5}),
            json!({"action":"set_volume","volumePercent":"50"}),
            json!({"action":"set_volume","volumePercent":50,"package":"test.player"}),
            json!({"action":"mute","volumePercent":50}),
            json!({"action":"unmute","package":"test.player"}),
            json!({"action":"pause","volumePercent":50}),
        ] {
            let mut draft = json!({"kind":"delay","delaySeconds":60,"confirmed":true});
            draft
                .as_object_mut()
                .unwrap()
                .extend(fields.as_object().unwrap().clone());
            assert!(
                scheduler.request("schedule_save", &draft).await.is_err(),
                "{draft}"
            );
        }
        drop(scheduler);
        let recovered = Scheduler::load(&dir).unwrap();
        let mut store = recovered.0.lock().await;
        assert_eq!(store.entries.len(), 4);
        assert_eq!(store.entries[0].volume_percent, Some(0));
        assert_eq!(store.entries[1].volume_percent, Some(100));
        store.entries[0].volume_percent = Some(101);
        assert!(validate_entry(&store.entries[0]).is_err());
        drop(store);
        std::fs::remove_dir_all(dir).unwrap();
    }
    #[tokio::test]
    async fn persistence_confirmation_and_missed_jobs() {
        let dir = std::env::temp_dir().join(format!("airc-schedule-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir(&dir).unwrap();
        let scheduler = Scheduler::load(&dir).unwrap();
        assert!(scheduler
            .request(
                "schedule_save",
                &json!({"kind":"delay","delaySeconds":60,"action":"pause","confirmed":false})
            )
            .await
            .is_err());
        let reply = scheduler
            .request(
                "schedule_save",
                &json!({"kind":"delay","delaySeconds":60,"action":"pause","confirmed":true}),
            )
            .await
            .unwrap();
        let task: Value = serde_json::from_str(&reply).unwrap();
        assert!(scheduler
            .request("schedule_toggle", &json!({"id":task["id"],"enabled":true}))
            .await
            .is_err());
        assert!(scheduler
            .request(
                "schedule_save",
                &json!({"kind":"delay","delaySeconds":60,"action":"device.shell","confirmed":true})
            )
            .await
            .is_err());
        {
            let mut s = scheduler.0.lock().await;
            s.entries[0].next_at = time::now() - 120;
            s.persist().unwrap();
        }
        drop(scheduler);
        let recovered = Scheduler::load(&dir).unwrap();
        let s = recovered.0.lock().await;
        assert!(!s.entries[0].enabled);
        assert_eq!(s.entries[0].last_status, "missed");
        std::fs::remove_dir_all(dir).unwrap();
    }
}
