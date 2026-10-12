use anyhow::{bail, Context, Result};
use serde_json::{json, Value};
use std::{
    collections::HashMap,
    fs::File,
    sync::{Mutex, OnceLock},
    time::{Duration, Instant},
};
mod bundle;
mod commit;
mod snapshot;
#[cfg(test)]
mod tests;
const TTL: Duration = Duration::from_secs(900);
static SLOTS: tokio::sync::Semaphore = tokio::sync::Semaphore::const_new(4);
static PROPOSALS: OnceLock<Mutex<HashMap<String, Prepared>>> = OnceLock::new();
struct Prepared {
    id: String,
    apk: super::apk::ApkInfo,
    current: Option<super::info::AppInfo>,
    user_id: u32,
    update: bool,
    files: Vec<StagedApk>,
    created: Instant,
    _slot: tokio::sync::SemaphorePermit<'static>,
}
struct StagedApk {
    apk: super::apk::ApkInfo,
    file: File,
    metadata: std::fs::Metadata,
}
fn registry() -> &'static Mutex<HashMap<String, Prepared>> {
    PROPOSALS.get_or_init(|| Mutex::new(HashMap::new()))
}
fn prune(values: &mut HashMap<String, Prepared>) {
    values.retain(|_, p| p.created.elapsed() < TTL);
}
fn preview(p: &Prepared) -> Value {
    json!({"id":p.id,"kind":"app_install","apk":p.apk,"apks":p.files.iter().map(|f| &f.apk).collect::<Vec<_>>(),"currentApp":p.current,"userId":p.user_id,"update":p.update,"approvalRequired":true,"codeMayAffectAllUsers":p.update})
}
pub(crate) fn discard(id: &str) {
    registry().lock().unwrap().remove(id);
}
pub(crate) fn pending(args: &Value) -> Result<String> {
    if args.as_object().is_none_or(|v| !v.is_empty()) {
        bail!("install pending does not accept arguments");
    }
    let mut values = registry().lock().unwrap();
    prune(&mut values);
    Ok(serde_json::to_string(
        &values.values().map(preview).collect::<Vec<_>>(),
    )?)
}
pub(crate) fn start(args: &Value) -> Result<String> {
    let (root, inputs, update) = bundle::arguments(args)?;
    let mut sources = Vec::new();
    let mut total = 0u64;
    for (path, digest) in inputs {
        let source = crate::tools::files::open(&crate::tools::files::root(&root)?, &path)?;
        let metadata = source.metadata()?;
        if !metadata.is_file() || metadata.len() == 0 {
            bail!("APK must be a nonempty regular file");
        }
        total = total
            .checked_add(metadata.len())
            .context("APK size overflow")?;
        if total > 4 * 1024 * 1024 * 1024 {
            bail!("APK set exceeds 4 GiB");
        }
        sources.push((path, digest, source, metadata));
    }
    let path = sources[0].0.clone();
    prune(&mut registry().lock().unwrap());
    let slot = SLOTS
        .try_acquire()
        .context("four APK proposals are already preparing or pending")?;
    let inbox = crate::tools::files::root("inbox")?;
    let first_copy = snapshot::unnamed(&inbox, total)?;
    let runtime = tokio::runtime::Handle::current();
    Ok(crate::transfers::start_background(
        root.clone(),
        path.clone(),
        "apk_prepare",
        total * 2,
        move |cancel, count| {
            let mut files = Vec::new();
            let mut offset = 0;
            let mut first_copy = Some(first_copy);
            for (path, digest, source, metadata) in sources {
                let copy = match first_copy.take() {
                    Some(file) => file,
                    None => snapshot::unnamed(&inbox, metadata.len())?,
                };
                let file = snapshot::copy(source, copy, &metadata, &digest, cancel, count, offset)?;
                let apk = super::apk::inspect(
                    file.try_clone()?,
                    root.clone(),
                    path,
                    cancel,
                    count,
                    offset + metadata.len(),
                )?;
                if apk.sha256 != digest {
                    bail!("staged APK digest mismatch");
                }
                offset += metadata.len() * 2;
                files.push(StagedApk {
                    metadata: file.metadata()?,
                    file,
                    apk,
                });
            }
            bundle::validate(&files.iter().map(|file| &file.apk).collect::<Vec<_>>())?;
            files.sort_by_key(|file| file.apk.split.as_ref().is_some_and(|name| !name.is_empty()));
            let apk = &files[0].apk;
            let (user_id, current) = runtime.block_on(async {
                let user = super::info::current_user().await?;
                let current = super::info::global(&apk.package, user).await?;
                Ok::<_, anyhow::Error>((user, current))
            })?;
            if let Some(current) = &current {
                if current.system || current.app_id < 10000 {
                    bail!("system and privileged application replacement is not supported");
                }
                if !update {
                    bail!("package already exists; explicitly prepare replacement installation");
                }
            }
            let prepared = Prepared {
                id: uuid::Uuid::new_v4().to_string(),
                apk: apk.clone(),
                current,
                user_id,
                update,
                files,
                created: Instant::now(),
                _slot: slot,
            };
            let value = preview(&prepared);
            if cancel.load(std::sync::atomic::Ordering::Relaxed) {
                bail!("APK preparation cancelled");
            }
            registry()
                .lock()
                .unwrap()
                .insert(prepared.id.clone(), prepared);
            Ok(value)
        },
    )?
    .to_string())
}
pub(crate) async fn approve(args: &Value) -> Result<String> {
    let object = args
        .as_object()
        .context("installation approval must be object")?;
    if object
        .keys()
        .any(|key| !["id", "accepted"].contains(&key.as_str()))
    {
        bail!("only id and accepted allowed");
    }
    let id = args["id"]
        .as_str()
        .context("installation proposal id required")?;
    let accepted = args
        .get("accepted")
        .map(|v| v.as_bool().context("accepted must be boolean"))
        .transpose()?
        .unwrap_or(true);
    let prepared = registry()
        .lock()
        .unwrap()
        .remove(id)
        .context("installation proposal expired or already used")?;
    if !accepted {
        return Ok(json!({"id":id,"rejected":true}).to_string());
    }
    if prepared.created.elapsed() >= TTL {
        bail!("installation proposal expired");
    }
    let user = super::info::current_user().await?;
    if user != prepared.user_id
        || super::info::global(&prepared.apk.package, user).await? != prepared.current
    {
        bail!("installed package or Android user changed; prepare a new confirmation");
    }
    if prepared.files.iter().any(|f| {
        f.file
            .metadata()
            .map(|m| super::apk::identity(&m) != super::apk::identity(&f.metadata))
            .unwrap_or(true)
    }) {
        bail!("staged APK changed; prepare again");
    }
    let runtime = tokio::runtime::Handle::current();
    Ok(crate::transfers::start_background(
        "apps".into(),
        prepared.apk.package.clone(),
        "app_install",
        prepared.files.iter().map(|f| f.apk.size_bytes).sum(),
        move |_, count| runtime.block_on(commit::execute(prepared, count)),
    )?
    .to_string())
}
