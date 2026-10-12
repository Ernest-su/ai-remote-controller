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
const HELP_COMMAND: &str = r#"pm help; airc_help_status=$?; case "$airc_help_status" in 0|255) exit 0 ;; *) exit "$airc_help_status" ;; esac"#;
fn declares_cache_only(help: &str) -> bool {
    help.lines()
        .any(|line| line.trim() == "clear [--user USER_ID] [--cache-only] PACKAGE")
        && help
            .lines()
            .any(|line| line.trim().starts_with("--cache-only:"))
}
pub(super) async fn capabilities() -> Result<Value> {
    let uid = unsafe { libc::geteuid() };
    // Android ShellCommand help convention may return -1 (shell status 255).
    // Accept it only for this fixed read-only invocation, never for clear.
    let help = crate::tools::run_command("sh", &["-c".into(), HELP_COMMAND.into()]).await?;
    let permission =
        match crate::platform_bridge::cache_permission(info::current_user().await?).await {
            Ok(payload) => serde_json::from_str::<Value>(&payload)
                .ok()
                .filter(|value| value["uid"].as_u64() == Some(uid as u64))
                .is_some_and(|value| value["internalDeleteCacheGranted"] == true),
            Err(_) => false,
        };
    Ok(
        json!({"supported":declares_cache_only(&help)&&permission&&(uid==0||uid==2000),"declaredCacheOnly":declares_cache_only(&help),"internalDeleteCacheGranted":permission,"runtimeUid":uid,"mode":"package_manager_cache_only","appDataClearAllowed":false}),
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
fn command(app: &info::AppInfo) -> Vec<String> {
    vec![
        "clear".into(),
        "--cache-only".into(),
        "--user".into(),
        app.user_id.to_string(),
        app.package.clone(),
    ]
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
        let output=crate::tools::run_command("pm",&command(&current)).await?;
        if !output.lines().any(|line|line.trim()=="Success"){bail!("cache-only package-manager operation failed: {}",output.chars().take(256).collect::<String>());}
        if info::read(&current.package).await?!=current {bail!("package manager completed cache cleanup but application identity changed; verify target state");}
        count.store(1,std::sync::atomic::Ordering::Relaxed);
        Ok(json!({"package":current.package,"userId":current.user_id,"cacheOnly":true,"completed":true,"verifiedBy":"package_manager","reclaimedBytes":null,"appDataVerified":false}))
    }))?.to_string())
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn help_status_255_is_accepted_only_by_the_fixed_read_only_probe() {
        use std::os::unix::fs::PermissionsExt;
        let dir = std::env::temp_dir().join(format!("airc-help-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir(&dir).unwrap();
        let pm = dir.join("pm");
        for (code, success) in [(255, true), (1, false)] {
            std::fs::write(&pm,format!("#!/bin/sh\nprintf '%s\\n' 'clear [--user USER_ID] [--cache-only] PACKAGE' '--cache-only: only cache data'\nexit {code}\n")).unwrap();
            std::fs::set_permissions(&pm, std::fs::Permissions::from_mode(0o700)).unwrap();
            let output = std::process::Command::new("/bin/sh")
                .args(["-c", HELP_COMMAND])
                .env("PATH", &dir)
                .output()
                .unwrap();
            assert_eq!(output.status.success(), success);
            if success {
                assert!(declares_cache_only(&String::from_utf8_lossy(
                    &output.stdout
                )));
            }
        }
        std::fs::remove_dir_all(dir).unwrap();
    }
    #[test]
    fn support_requires_the_explicit_cache_only_command_and_no_data_fallback() {
        assert!(!declares_cache_only(
            "clear [--user USER_ID] PACKAGE\n--cache-only: unavailable"
        ));
        assert!(declares_cache_only(
            " clear [--user USER_ID] [--cache-only] PACKAGE\n --cache-only: only cache data"
        ));
        let app = info::AppInfo {
            package: "com.test.app".into(),
            user_id: 10,
            app_id: 10001,
            version_code: 1,
            version_name: None,
            code_path: "/data/app/test".into(),
            last_update_time: "test".into(),
            system: false,
        };
        assert_eq!(
            command(&app),
            vec!["clear", "--cache-only", "--user", "10", "com.test.app"]
        );
    }
}
