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
    if args.as_object().is_none_or(|object| !object.is_empty()) {
        bail!("apps.list accepts an empty argument object");
    }
    let standard = async {
        let user = run_command("am", &["get-current-user".into()])
            .await?
            .trim()
            .parse::<u32>()?;
        let output = crate::platform_bridge::apps(&[user.to_string()]).await?;
        validate_standard(&output)
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
        })
        .collect();
    let result = serde_json::to_string(
        &serde_json::json!({"apps": apps,"source":"pm","labelsAvailable":false}),
    )?;
    if result.len() > 256 * 1024 {
        bail!("app list exceeds 1 MiB");
    }
    Ok(result)
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
