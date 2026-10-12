use super::*;
#[derive(Clone, Debug, PartialEq, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct AppInfo {
    pub(super) package: String,
    pub(super) user_id: u32,
    pub(super) app_id: u32,
    pub(super) version_code: u64,
    pub(super) version_name: Option<String>,
    pub(super) code_path: String,
    pub(super) last_update_time: String,
    pub(super) system: bool,
}
pub(super) fn valid_package(value: &str) -> bool {
    value.len() <= 255
        && value.contains('.')
        && value.split('.').all(|part| {
            !part.is_empty() && part.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_')
        })
}
pub(super) fn parse(package: &str, user_id: u32, system: bool, dump: &str) -> Result<AppInfo> {
    let start = dump
        .lines()
        .position(|line| line.trim().starts_with(&format!("Package [{package}]")))
        .context("package detail missing")?;
    let lines: Vec<_> = dump
        .lines()
        .skip(start + 1)
        .take_while(|line| !line.trim().starts_with("Package ["))
        .map(str::trim)
        .collect();
    let field = |key: &str| {
        lines
            .iter()
            .find_map(|line| line.strip_prefix(key))
            .map(str::to_owned)
    };
    let app_id = field("appId=")
        .or_else(|| field("userId="))
        .context("app identity unavailable")?
        .parse()?;
    let version_code = field("versionCode=")
        .context("version unavailable")?
        .split_whitespace()
        .next()
        .unwrap_or("")
        .parse()?;
    Ok(AppInfo {
        package: package.into(),
        user_id,
        app_id,
        version_code,
        version_name: field("versionName=").filter(|v| v != "null"),
        code_path: field("codePath=").context("code path unavailable")?,
        last_update_time: field("lastUpdateTime=").context("update identity unavailable")?,
        system,
    })
}
pub(super) async fn current_user() -> Result<u32> {
    Ok(
        crate::tools::run_command("am", &["get-current-user".into()])
            .await?
            .trim()
            .parse()?,
    )
}
pub(super) async fn global(package: &str, user_id: u32) -> Result<Option<AppInfo>> {
    query(package, user_id, "all").await
}
pub(super) async fn read(package: &str) -> Result<AppInfo> {
    let user_id = current_user().await?;
    query(package, user_id, &user_id.to_string())
        .await?
        .context("package not installed for current user")
}
async fn query(package: &str, user_id: u32, scope: &str) -> Result<Option<AppInfo>> {
    if !valid_package(package) {
        bail!("invalid package name");
    }
    let args = vec![
        "list".into(),
        "packages".into(),
        "--user".into(),
        scope.into(),
        package.into(),
    ];
    let installed = crate::tools::run_command("pm", &args).await?;
    if !installed
        .lines()
        .any(|line| line.trim() == format!("package:{package}"))
    {
        return Ok(None);
    }
    let mut system_args = args;
    system_args.insert(2, "-s".into());
    let system = crate::tools::run_command("pm", &system_args)
        .await?
        .lines()
        .any(|line| line.trim() == format!("package:{package}"));
    let dump = crate::tools::run_command("dumpsys", &["package".into(), package.into()]).await?;
    Ok(Some(parse(package, user_id, system, &dump)?))
}
