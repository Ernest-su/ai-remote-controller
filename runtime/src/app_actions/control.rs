use super::*;

#[derive(Clone)]
struct ControlProposal {
    id: String,
    app: info::AppInfo,
    action: String,
    permission: Option<String>,
    before: Value,
    created: Instant,
}
static CONTROLS: OnceLock<Mutex<HashMap<String, ControlProposal>>> = OnceLock::new();
fn store() -> &'static Mutex<HashMap<String, ControlProposal>> {
    CONTROLS.get_or_init(|| Mutex::new(HashMap::new()))
}
const ACTIONS: &[&str] = &[
    "force_stop",
    "enable",
    "disable",
    "clear_data",
    "grant",
    "revoke",
];

async fn details(app: &info::AppInfo) -> Result<Value> {
    let state: Value = serde_json::from_str(
        &crate::platform_bridge::app_control(app.user_id, &app.package).await?,
    )?;
    if state["package"] != app.package || state["userId"] != app.user_id {
        bail!("app control state identity mismatch");
    }
    if state["permissions"]
        .as_array()
        .is_none_or(|items| items.len() > 1024)
        || state["enabled"].as_bool().is_none()
        || state["stopped"].as_bool().is_none()
        || state["enabledSetting"].as_u64().is_none()
    {
        bail!("invalid app control state");
    }
    Ok(state)
}
fn permission<'a>(state: &'a Value, name: &str) -> Result<&'a Value> {
    state["permissions"]
        .as_array()
        .context("permission state missing")?
        .iter()
        .find(|p| p["name"] == name)
        .context("permission is not requested by this application")
}
fn validate_action(action: &str, name: Option<&str>) -> Result<()> {
    if !ACTIONS.contains(&action) {
        bail!("unsupported app control action");
    }
    if matches!(action, "grant" | "revoke") {
        let name = name.context("permission required")?;
        if name.len() > 255
            || !name.contains('.')
            || !name
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'.')
        {
            bail!("invalid permission name");
        }
    } else if name.is_some() {
        bail!("permission only allowed for grant/revoke");
    }
    Ok(())
}
fn require_action(
    app: &info::AppInfo,
    state: &Value,
    action: &str,
    name: Option<&str>,
) -> Result<()> {
    validate_action(action, name)?;
    if app.system || app.app_id < 10000 {
        bail!("system and privileged applications are read-only");
    }
    let capability = match action {
        "force_stop" => "forceStop",
        "enable" | "disable" => "setEnabled",
        "clear_data" => "clearData",
        "grant" | "revoke" => {
            let p = permission(state, name.unwrap())?;
            if p["runtime"] != true || p["mutable"] != true {
                bail!("permission cannot be changed: {}", p["reason"]);
            }
            if p["granted"] == (action == "grant") {
                bail!("permission state already changed; refresh application details");
            }
            return Ok(());
        }
        _ => unreachable!(),
    };
    if state["capabilities"][capability] != true {
        bail!("app action unavailable for the current service identity");
    }
    Ok(())
}
fn preview(p: &ControlProposal) -> Value {
    json!({"id":p.id,"kind":"app_control","app":p.app,"action":p.action,"permission":p.permission,
        "approvalRequired":true,"dataLoss":p.action=="clear_data","before":p.before})
}
pub(super) async fn execute(tool: &str, args: &Value) -> Result<String> {
    let object = args
        .as_object()
        .context("app control arguments must be object")?;
    if object
        .keys()
        .any(|key| !["package", "action", "permission"].contains(&key.as_str()))
    {
        bail!("unexpected app control argument");
    }
    let package = args["package"].as_str().context("package required")?;
    if tool == "apps.control_info" {
        if object.len() != 1 {
            bail!("only package allowed for app control info");
        }
        let app = info::read(package).await?;
        return Ok(json!({"app":app,"state":details(&app).await?}).to_string());
    }
    let action = args["action"].as_str().context("action required")?;
    let name = args
        .get("permission")
        .map(|p| p.as_str().context("permission must be string"))
        .transpose()?;
    validate_action(action, name)?;
    let app = info::read(package).await?;
    let before = details(&app).await?;
    require_action(&app, &before, action, name)?;
    let p = ControlProposal {
        id: uuid::Uuid::new_v4().to_string(),
        app,
        action: action.into(),
        permission: name.map(str::to_owned),
        before,
        created: Instant::now(),
    };
    let value = preview(&p);
    let mut items = store().lock().unwrap();
    items.retain(|_, p| p.created.elapsed() < Duration::from_secs(900));
    if items.len() >= 32 {
        bail!("too many app control approvals");
    }
    items.insert(p.id.clone(), p);
    Ok(value.to_string())
}
fn command(p: &ControlProposal) -> (&'static str, Vec<String>) {
    let user = p.app.user_id.to_string();
    let package = p.app.package.clone();
    match p.action.as_str() {
        "force_stop" => (
            "am",
            vec!["force-stop".into(), "--user".into(), user, package],
        ),
        "enable" | "disable" => (
            "pm",
            vec![
                if p.action == "enable" {
                    "enable"
                } else {
                    "disable-user"
                }
                .into(),
                "--user".into(),
                user,
                package,
            ],
        ),
        "clear_data" => ("pm", vec!["clear".into(), "--user".into(), user, package]),
        "grant" | "revoke" => (
            "pm",
            vec![
                p.action.clone(),
                "--user".into(),
                user,
                package,
                p.permission.clone().unwrap(),
            ],
        ),
        _ => unreachable!(),
    }
}
async fn recheck(p: &ControlProposal) -> Result<()> {
    if p.created.elapsed() >= Duration::from_secs(900) || info::read(&p.app.package).await? != p.app
    {
        bail!("application/user changed or approval expired; request a new confirmation");
    }
    let now = details(&p.app).await?;
    require_action(&p.app, &now, &p.action, p.permission.as_deref())?;
    if matches!(p.action.as_str(), "enable" | "disable")
        && now["enabledSetting"] != p.before["enabledSetting"]
    {
        bail!("application enabled state changed; request a new confirmation");
    }
    Ok(())
}
fn verify_result(p: &ControlProposal, output: &str, state: &Value) -> Result<()> {
    let verified = match p.action.as_str() {
        "force_stop" => state["stopped"] == true,
        "enable" => state["enabled"] == true && state["enabledSetting"] == 1,
        "disable" => state["enabled"] == false && state["enabledSetting"] == 3,
        "grant" | "revoke" => {
            permission(state, p.permission.as_deref().unwrap())?["granted"] == (p.action == "grant")
        }
        "clear_data" => output.lines().any(|line| line.trim() == "Success"),
        _ => false,
    };
    if !verified {
        bail!("app action result could not be verified; refresh target state");
    }
    Ok(())
}
pub(crate) async fn approve(args: &Value) -> Result<String> {
    let object = args
        .as_object()
        .context("app control approval must be object")?;
    if object
        .keys()
        .any(|key| !["id", "accepted"].contains(&key.as_str()))
    {
        bail!("only id and accepted allowed");
    }
    let id = args["id"].as_str().context("approval id required")?;
    let accepted = args
        .get("accepted")
        .map(|v| v.as_bool().context("accepted must be boolean"))
        .transpose()?
        .unwrap_or(true);
    let p = store()
        .lock()
        .unwrap()
        .remove(id)
        .context("app control approval missing or already used")?;
    if !accepted {
        return Ok(json!({"id":id,"rejected":true}).to_string());
    }
    recheck(&p).await?;
    let runtime = tokio::runtime::Handle::current();
    Ok(crate::transfers::start_background("apps".into(), p.app.package.clone(), "app_control", 0, move |_, count| runtime.block_on(async move {
        recheck(&p).await?;
        let (program, args) = command(&p);
        let output = crate::tools::run_command(program, &args).await?;
        if info::read(&p.app.package).await? != p.app { bail!("application identity changed during action; verify target state"); }
        let state = details(&p.app).await?;
        verify_result(&p, &output, &state)?;
        count.store(1, std::sync::atomic::Ordering::Relaxed);
        Ok(json!({"package":p.app.package,"userId":p.app.user_id,"action":p.action,"permission":p.permission,
            "completed":true,"verifiedBy":if p.action=="clear_data" {"package_manager"} else {"package_state"},"state":state}))
    }))?.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;
    fn app() -> info::AppInfo {
        info::AppInfo {
            package: "com.fixture.app".into(),
            user_id: 10,
            app_id: 10001,
            version_code: 1,
            version_name: None,
            code_path: "/data/app/fixture".into(),
            last_update_time: "now".into(),
            system: false,
        }
    }
    fn proposal(action: &str) -> ControlProposal {
        ControlProposal {
            id: "fixture".into(),
            app: app(),
            action: action.into(),
            permission: matches!(action, "grant" | "revoke")
                .then(|| "android.permission.CAMERA".into()),
            before: Value::Null,
            created: Instant::now(),
        }
    }
    #[test]
    fn commands_are_fixed_user_scoped_and_data_clear_is_explicit() {
        assert_eq!(
            command(&proposal("clear_data")),
            (
                "pm",
                vec!["clear", "--user", "10", "com.fixture.app"]
                    .into_iter()
                    .map(str::to_owned)
                    .collect()
            )
        );
        assert_eq!(command(&proposal("force_stop")).0, "am");
        assert_eq!(command(&proposal("disable")).1[0], "disable-user");
        assert_eq!(
            command(&proposal("grant")).1.last().unwrap(),
            "android.permission.CAMERA"
        );
        assert!(validate_action("shell", None).is_err());
        assert!(validate_action("grant", Some("CAMERA;reboot")).is_err());
        assert!(validate_action("clear_data", Some("android.permission.CAMERA")).is_err());
    }
    #[test]
    fn policy_permissions_and_system_apps_cannot_be_modified() {
        let state = json!({"capabilities":{"clearData":true},"permissions":[{"name":"android.permission.CAMERA","runtime":true,"mutable":false,"granted":false}]});
        assert!(
            require_action(&app(), &state, "grant", Some("android.permission.CAMERA")).is_err()
        );
        let mut system = app();
        system.system = true;
        assert!(require_action(&system, &state, "clear_data", None).is_err());
        assert!(require_action(&app(), &json!({}), "force_stop", None).is_err());
    }
    #[test]
    fn command_success_alone_does_not_verify_permission_or_enable_state() {
        assert!(verify_result(
            &proposal("enable"),
            "Success",
            &json!({"enabled":false,"enabledSetting":3})
        )
        .is_err());
        assert!(verify_result(&proposal("force_stop"), "", &json!({"stopped":true})).is_ok());
        assert!(verify_result(&proposal("clear_data"), "Failure", &json!({})).is_err());
    }
    #[tokio::test]
    async fn rejects_unknown_fields_before_touching_android() {
        assert!(execute(
            "apps.prepare_control",
            &json!({"package":"com.fixture.app","action":"clear_data","shell":"reboot"})
        )
        .await
        .is_err());
        assert!(approve(&json!({"id":"fixture","accepted":"yes"}))
            .await
            .is_err());
    }
}
