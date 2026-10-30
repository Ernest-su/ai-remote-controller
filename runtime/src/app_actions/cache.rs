use super::{info, *};
#[derive(Clone)]
struct CacheProposal {
    id: String,
    app: info::AppInfo,
    created: Instant,
}
static CACHE: OnceLock<Mutex<HashMap<String, CacheProposal>>> = OnceLock::new();
fn store() -> &'static Mutex<HashMap<String, CacheProposal>> {
    CACHE.get_or_init(|| Mutex::new(HashMap::new()))
}
fn supported(probe: &Value, uid: u32) -> bool {
    (uid == 0 || uid == 2000)
        && probe["uid"].as_u64() == Some(uid as u64)
        && probe["cacheDeleteGranted"] == true
        && probe["cacheOnlyApiAvailable"] == true
}
pub(super) async fn capabilities() -> Result<Value> {
    let uid = unsafe { libc::geteuid() };
    let probe = match crate::platform_bridge::cache_capabilities(info::current_user().await?).await
    {
        Ok(payload) => serde_json::from_str::<Value>(&payload).unwrap_or(Value::Null),
        Err(_) => Value::Null,
    };
    Ok(
        json!({"supported":supported(&probe,uid),"cacheOnlyApiAvailable":probe["cacheOnlyApiAvailable"]==true,
        "cacheDeleteGranted":probe["cacheDeleteGranted"]==true,"runtimeUid":uid,
        "cacheDeletePermission":probe["cacheDeletePermission"],"mode":"package_manager_cache_only","appDataClearAllowed":false}),
    )
}
async fn require_support() -> Result<()> {
    if capabilities().await?["supported"] != true {
        bail!("package-manager cache-only operation unavailable; application data will not be cleared");
    }
    Ok(())
}
fn preview(p: &CacheProposal) -> Value {
    json!({"id":p.id,"kind":"app_cache_clear","app":p.app,"approvalRequired":true,"cacheOnly":true,"mode":"package_manager_cache_only"})
}
fn empty(args: &Value) -> Result<()> {
    if args.as_object().is_none_or(|object| !object.is_empty()) {
        bail!("empty argument object required");
    }
    Ok(())
}
pub(super) async fn execute(tool: &str, args: &Value) -> Result<String> {
    if tool == "apps.cache_capabilities" {
        empty(args)?;
        return Ok(capabilities().await?.to_string());
    }
    if tool == "apps.cache_pending" {
        empty(args)?;
        let mut items = store().lock().unwrap();
        items.retain(|_, p| p.created.elapsed() < Duration::from_secs(900));
        return Ok(serde_json::to_string(
            &items.values().map(preview).collect::<Vec<_>>(),
        )?);
    }
    let object = args.as_object().context("cache arguments must be object")?;
    if object.len() != 1 || !object.contains_key("package") {
        bail!("only package accepted");
    }
    require_support().await?;
    let app = info::read(args["package"].as_str().context("package required")?).await?;
    if app.system || app.app_id < 10000 {
        bail!("system and privileged app cache cleanup is not allowed");
    }
    let proposal = CacheProposal {
        id: uuid::Uuid::new_v4().to_string(),
        app,
        created: Instant::now(),
    };
    let value = preview(&proposal);
    let mut items = store().lock().unwrap();
    items.retain(|_, p| p.created.elapsed() < Duration::from_secs(900));
    if items.len() >= 32 {
        bail!("too many cache approvals");
    }
    items.insert(proposal.id.clone(), proposal);
    Ok(value.to_string())
}
pub(crate) async fn approve(args: &Value) -> Result<String> {
    let object = args.as_object().context("cache approval must be object")?;
    if object
        .keys()
        .any(|key| !["id", "accepted"].contains(&key.as_str()))
    {
        bail!("only id and accepted allowed");
    }
    let id = args["id"].as_str().context("id required")?;
    let accepted = args
        .get("accepted")
        .map(|v| v.as_bool().context("accepted must be boolean"))
        .transpose()?
        .unwrap_or(true);
    let proposal = store()
        .lock()
        .unwrap()
        .remove(id)
        .context("cache proposal missing or already used")?;
    if !accepted {
        return Ok(json!({"id":id,"rejected":true}).to_string());
    }
    if proposal.created.elapsed() >= Duration::from_secs(900) {
        bail!("cache proposal expired");
    }
    require_support().await?;
    let current = info::read(&proposal.app.package).await?;
    if current != proposal.app {
        bail!("application or user changed; request new cache confirmation");
    }
    let runtime = tokio::runtime::Handle::current();
    let name = current.package.clone();
    Ok(crate::transfers::start_background("apps".into(),name,"app_cache_clear",0,move|_,count|runtime.block_on(async move{
        // Recheck inside the task immediately before the fixed cache-only command.
        require_support().await?;if info::read(&current.package).await?!=current {bail!("application changed before cache cleanup");}
        let output=crate::platform_bridge::clear_app_cache(current.user_id,&current.package).await?;
        let result:Value=serde_json::from_str(&output)?;
        if result["package"]!=current.package || result["userId"]!=current.user_id || result["cacheOnly"]!=true || result["completed"]!=true {bail!("cache-only package-manager result mismatch");}
        if info::read(&current.package).await?!=current {bail!("package manager completed cache cleanup but application identity changed; verify target state");}
        count.store(1,std::sync::atomic::Ordering::Relaxed);
        Ok(json!({"package":current.package,"userId":current.user_id,"cacheOnly":true,"completed":true,"verifiedBy":"package_manager","reclaimedBytes":null,"appDataVerified":false}))
    }))?.to_string())
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn support_requires_the_api_permission_and_actual_privileged_identity() {
        let probe = json!({"uid":0,"cacheOnlyApiAvailable":true,"cacheDeleteGranted":true});
        assert!(supported(&probe, 0));
        assert!(!supported(&probe, 2000));
        for field in ["cacheOnlyApiAvailable", "cacheDeleteGranted"] {
            let mut missing = probe.clone();
            missing[field] = json!(false);
            assert!(!supported(&missing, 0));
        }
        assert!(!supported(
            &json!({"uid":10001,"cacheOnlyApiAvailable":true,"cacheDeleteGranted":true}),
            10001
        ));
        assert!(!supported(&Value::Null, 0));
    }
}
