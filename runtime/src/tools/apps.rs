use super::run_command;
use anyhow::{bail, Context, Result};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::collections::BTreeSet;

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct InstalledApp {
    package: String,
    label: Option<String>,
    system: bool,
    #[serde(rename = "cacheKey", skip_serializing_if = "Option::is_none")]
    cache_key: Option<String>,
    first_install_time: Option<u64>,
    size_bytes: Option<u64>,
}

fn packages(output: &str) -> Result<BTreeSet<String>> {
    output
        .lines()
        .filter(|line| !line.trim().is_empty())
        .map(|line| {
            let package = line
                .trim()
                .strip_prefix("package:")
                .context("unexpected pm package output")?;
            if package.is_empty()
                || !package
                    .bytes()
                    .all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'.')
            {
                bail!("invalid package in pm output");
            }
            Ok(package.to_owned())
        })
        .collect()
}

pub(super) async fn list(args: &Value) -> Result<String> {
    let object = args.as_object().context("apps.list requires an object")?;
    if object.keys().any(|key| key != "includeSizes") {
        bail!("apps.list accepts only includeSizes");
    }
    let include_sizes = object
        .get("includeSizes")
        .map(|value| value.as_bool().context("includeSizes must be boolean"))
        .transpose()?
        .unwrap_or(false);
    let standard: Result<String> = async {
        let user = run_command("am", &["get-current-user".into()])
            .await?
            .trim()
            .parse::<u32>()?;
        let output = crate::platform_bridge::apps(&[user.to_string()]).await?;
        let output = validate_standard(&output)?;
        if !include_sizes {
            return Ok(output);
        }
        let mut value: Value = serde_json::from_str(&output)?;
        value["sizesRequested"] = serde_json::json!(true);
        value["sizeTask"] = start_sizes(user)?;
        Ok(value.to_string())
    }
    .await;
    if let Ok(output) = standard {
        return Ok(output);
    }
    let all_args = ["list".into(), "packages".into()];
    let system_args = ["list".into(), "packages".into(), "-s".into()];
    let (all, system) = tokio::try_join!(
        run_command("pm", &all_args),
        run_command("pm", &system_args),
    )?;
    let all = packages(&all)?;
    let system = packages(&system)?;
    if !system.is_subset(&all) {
        bail!("package list changed during query; refresh again");
    }
    let apps: Vec<InstalledApp> = all
        .into_iter()
        .map(|package| InstalledApp {
            system: system.contains(&package),
            package,
            label: None,
            cache_key: None,
            first_install_time: None,
            size_bytes: None,
        })
        .collect();
    let result = serde_json::to_string(
        &serde_json::json!({"apps": apps,"source":"pm","labelsAvailable":false,"sizesRequested":include_sizes}),
    )?;
    if result.len() > 256 * 1024 {
        bail!("app list exceeds 1 MiB");
    }
    Ok(result)
}

async fn current_user() -> Result<u32> {
    Ok(run_command("am", &["get-current-user".into()])
        .await?
        .trim()
        .parse()?)
}
type SizeTask = (u32, Value, std::sync::Arc<std::sync::atomic::AtomicBool>);
static SIZE_TASK: std::sync::Mutex<Option<SizeTask>> = std::sync::Mutex::new(None);
fn start_sizes(user: u32) -> Result<Value> {
    use std::sync::{
        atomic::{AtomicBool, Ordering},
        Arc,
    };
    let mut active = SIZE_TASK.lock().unwrap();
    if let Some((owner, task, running)) = active.as_ref() {
        if running.load(Ordering::Acquire) {
            if *owner != user {
                bail!("another user's application storage scan is still running");
            }
            return Ok(task.clone());
        }
    }
    let running = Arc::new(AtomicBool::new(true));
    let flag = running.clone();
    let runtime = tokio::runtime::Handle::current();
    let task = crate::transfers::start_background_reporting(
        "apps".into(),
        "app-sizes".into(),
        "app_sizes",
        0,
        move |_, count, reporter| {
            struct Finish(Arc<AtomicBool>);
            impl Drop for Finish {
                fn drop(&mut self) {
                    self.0.store(false, Ordering::Release);
                }
            }
            let _finish = Finish(flag);
            runtime.block_on(async move {
                if crate::tools::apps::current_user().await? != user {
                    bail!("Android user changed before storage scan");
                }
                let mut final_result=None;
                let mut total=None;
                let mut seen=BTreeSet::new();
                crate::platform_bridge::app_sizes_stream(user,|frame| {
                    if final_result.is_some(){bail!("frame after completed size snapshot");}
                    match frame["kind"].as_str() {
                        Some("start")=>{
                            let expected=frame["total"].as_u64().filter(|n|*n<=4096).context("invalid storage scan total")?;
                            if total.replace(expected).is_some() || frame["userId"]!=user {bail!("invalid storage scan start");}
                            reporter.update(|slot| *slot=Some(serde_json::json!({"apps":[],"sizesRequested":true,"partial":true,"processed":0,"total":expected,"userId":user})));
                        }
                        Some("size")=>{
                            let expected=total.context("size frame before scan start")?;
                            if frame["userId"]!=user || frame["total"]!=expected {bail!("storage scan frame identity mismatch");}
                            let app=frame["app"].clone();
                            validate_standard(&serde_json::json!({"apps":[app.clone()]}).to_string())?;
                            let name=app["package"].as_str().context("missing size package")?.to_owned();
                            if !seen.insert(name) || seen.len() as u64>expected || frame["processed"].as_u64()!=Some(seen.len() as u64) {bail!("duplicate or out-of-order storage result");}
                            reporter.update(|slot|->Result<()> {
                                let snapshot=slot.as_mut().context("storage progress missing")?;
                                snapshot["apps"].as_array_mut().context("storage apps missing")?.push(app);
                                snapshot["processed"]=serde_json::json!(seen.len());
                                Ok(())
                            })?;
                            count.store(seen.len() as u64,Ordering::Relaxed);
                        }
                        Some("complete")=>{
                            if total.is_none(){bail!("completed storage scan without start");}
                            let output=validate_standard(&frame["result"].to_string())?;
                            let value:Value=serde_json::from_str(&output)?;
                            if value["userId"]!=user || value["sizesRequested"]!=true {bail!("completed storage snapshot identity mismatch");}
                            final_result=Some(value);
                        }
                        _=>bail!("unknown storage stream frame"),
                    }
                    Ok(())
                }).await?;
                if current_user().await?!=user {bail!("Android user changed during storage scan");}
                let value=final_result.context("storage stream ended without completed snapshot")?;
                count.store(value["apps"].as_array().context("missing app sizes")?.len() as u64,Ordering::Relaxed);
                Ok(value)
            })
        },
    )?;
    *active = Some((user, task.clone(), running));
    Ok(task)
}

fn validate_standard(output: &str) -> Result<String> {
    if output.len() > 1024 * 1024 {
        bail!("app list exceeds 1 MiB");
    }
    let value: Value = serde_json::from_str(output)?;
    let apps: Vec<InstalledApp> = serde_json::from_value(value["apps"].clone())?;
    if apps.len() > 4096 {
        bail!("installed application count exceeds 4096");
    }
    let mut seen = BTreeSet::new();
    for app in &apps {
        if app.cache_key.as_ref().is_none_or(|key| {
            key.len() != 64
                || !key
                    .bytes()
                    .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
        }) {
            bail!("invalid application cache identity");
        }
        if app.package.len() > 255
            || app.package.is_empty()
            || !app
                .package
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'.')
            || !seen.insert(app.package.clone())
        {
            bail!("invalid or duplicate package in standard app list");
        }
        if app
            .label
            .as_ref()
            .is_some_and(|label| label.chars().count() > 256)
        {
            bail!("application label exceeds limit");
        }
    }
    let mut value = value;
    value["labelsAvailable"] = serde_json::json!(true);
    Ok(value.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn standard_labels_preserve_unicode_and_reject_duplicate_or_oversized_records() {
        let key = "a".repeat(64);
        let entry = serde_json::json!({"package":"com.test.app","label":"应用名称","system":false,"cacheKey":key});
        let output = serde_json::json!({"apps":[entry.clone()],"source":"package_manager"});
        let value: Value =
            serde_json::from_str(&validate_standard(&output.to_string()).unwrap()).unwrap();
        assert_eq!(value["apps"][0]["label"], "应用名称");
        assert_eq!(value["labelsAvailable"], true);
        assert_eq!(value["apps"][0]["cacheKey"], key);
        assert!(validate_standard(
            &serde_json::json!({"apps":[entry.clone(),entry.clone()]}).to_string()
        )
        .is_err());
        let mut oversized = entry.clone();
        oversized["label"] = serde_json::json!("a".repeat(257));
        assert!(validate_standard(&serde_json::json!({"apps":[oversized]}).to_string()).is_err());
        for key in [
            Value::Null,
            serde_json::json!("short"),
            serde_json::json!("Z".repeat(64)),
        ] {
            let mut bad = entry.clone();
            bad["cacheKey"] = key;
            assert!(validate_standard(&serde_json::json!({"apps":[bad]}).to_string()).is_err());
        }
    }
    #[test]
    fn packages_are_sorted_deduplicated_and_validated() {
        assert_eq!(
            packages("package:com.z\npackage:com.a\npackage:com.z\n").unwrap(),
            BTreeSet::from(["com.a".into(), "com.z".into()])
        );
        assert!(packages("Failure [not permitted]").is_err());
        assert!(packages("package:com.a;reboot").is_err());
        assert!(packages("package:").is_err());
        assert!(packages("").unwrap().is_empty());
    }

    #[tokio::test]
    async fn list_rejects_unknown_args_before_running_commands() {
        assert!(list(&serde_json::json!({"command":"reboot"}))
            .await
            .is_err());
        assert!(list(&Value::Null).await.is_err());
    }
}
