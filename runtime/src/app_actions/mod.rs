use anyhow::{bail, Context, Result};
use serde_json::{json, Value};
use std::{
    collections::HashMap,
    sync::{Mutex, OnceLock},
    time::{Duration, Instant},
};
mod apk;
pub(crate) mod cache;
pub(crate) mod control;
mod info;
pub(crate) mod install;
#[cfg(test)]
mod tests;
#[derive(Clone)]
struct Proposal {
    id: String,
    info: info::AppInfo,
    created: Instant,
}
static PROPOSALS: OnceLock<Mutex<HashMap<String, Proposal>>> = OnceLock::new();
fn proposals() -> &'static Mutex<HashMap<String, Proposal>> {
    PROPOSALS.get_or_init(|| Mutex::new(HashMap::new()))
}
fn preview(p: &Proposal) -> Value {
    json!({"id":p.id,"kind":"app_uninstall","app":p.info,"approvalRequired":true,"dataLoss":true})
}
pub async fn execute(tool: &str, args: &Value) -> Result<String> {
    if matches!(tool, "apps.control_info" | "apps.prepare_control") {
        return control::execute(tool, args).await;
    }
    if matches!(
        tool,
        "apps.cache_capabilities" | "apps.cache_pending" | "apps.clear_cache"
    ) {
        return cache::execute(tool, args).await;
    }
    if tool == "apps.install" {
        return install::start(args);
    }
    if tool == "apps.install_pending" {
        return install::pending(args);
    }
    if tool == "apps.apk_info" {
        return apk::execute(args).await;
    }
    if tool == "apps.pending" {
        if args.as_object().is_none_or(|v| !v.is_empty()) {
            bail!("pending does not accept arguments");
        }
        let mut registry = proposals().lock().unwrap();
        registry.retain(|_, p| p.created.elapsed() < Duration::from_secs(900));
        return Ok(serde_json::to_string(
            &registry.values().map(preview).collect::<Vec<_>>(),
        )?);
    }
    let object = args.as_object().context("arguments must be object")?;
    if object.len() != 1 || !object.contains_key("package") {
        bail!("only package name accepted");
    }
    let app = info::read(args["package"].as_str().context("package required")?).await?;
    if tool == "apps.info" {
        return Ok(serde_json::to_string(&app)?);
    }
    if app.system || app.app_id < 10000 {
        bail!("system and privileged applications cannot be uninstalled through this tool");
    }
    let proposal = Proposal {
        id: uuid::Uuid::new_v4().to_string(),
        info: app,
        created: Instant::now(),
    };
    let value = preview(&proposal);
    let mut registry = proposals().lock().unwrap();
    registry.retain(|_, p| p.created.elapsed() < Duration::from_secs(900));
    if registry.len() >= 32 {
        bail!("too many pending app approvals");
    }
    registry.insert(proposal.id.clone(), proposal);
    Ok(value.to_string())
}
pub async fn approve(args: &Value) -> Result<String> {
    let object = args.as_object().context("approval must be object")?;
    if object
        .keys()
        .any(|key| !["id", "accepted"].contains(&key.as_str()))
    {
        bail!("only id and accepted allowed");
    }
    let id = args["id"].as_str().context("approval id required")?;
    let proposal = proposals()
        .lock()
        .unwrap()
        .remove(id)
        .context("app approval missing or already used")?;
    if let Some(accepted) = args.get("accepted") {
        if !accepted.as_bool().context("accepted must be boolean")? {
            return Ok(json!({"id":id,"rejected":true}).to_string());
        }
    }
    if proposal.created.elapsed() > Duration::from_secs(900) {
        bail!("app approval expired");
    }
    let current = info::read(&proposal.info.package).await?;
    if current != proposal.info {
        bail!("application changed; request a new uninstall confirmation");
    }
    let runtime = tokio::runtime::Handle::current();
    let package = current.package.clone();
    let name = package.clone();
    let user_id = current.user_id;
    Ok(crate::transfers::start_background(
        "apps".into(),
        name,
        "app_uninstall",
        0,
        move |_, count| {
            runtime.block_on(async move {
                let result = crate::tools::run_command(
                    "pm",
                    &[
                        "uninstall".into(),
                        "--user".into(),
                        user_id.to_string(),
                        package.clone(),
                    ],
                )
                .await?;
                if !result.lines().any(|line| line.trim() == "Success") {
                    bail!(
                        "package manager uninstall failed: {}",
                        result.chars().take(256).collect::<String>()
                    );
                }
                let installed = crate::tools::run_command(
                    "pm",
                    &[
                        "list".into(),
                        "packages".into(),
                        "--user".into(),
                        user_id.to_string(),
                        package.clone(),
                    ],
                )
                .await?;
                if installed
                    .lines()
                    .any(|line| line.trim() == format!("package:{package}"))
                {
                    bail!("package manager reported success but package remains installed");
                }
                count.store(1, std::sync::atomic::Ordering::Relaxed);
                Ok(json!({"package":package,"userId":user_id,"removed":true,"dataRemoved":true}))
            })
        },
    )?
    .to_string())
}
