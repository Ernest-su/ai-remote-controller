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
        })
        .collect();
    let result = serde_json::to_string(
        &serde_json::json!({"apps": apps,"source":"pm","labelsAvailable":false}),
    )?;
    if result.len() > 256 * 1024 {
        bail!("app list exceeds 256 KiB");
    }
    Ok(result)
}

fn validate_standard(output: &str) -> Result<String> {
    if output.len() > 256 * 1024 {
        bail!("app list exceeds 256 KiB");
    }
    let value: Value = serde_json::from_str(output)?;
    let apps: Vec<InstalledApp> = serde_json::from_value(value["apps"].clone())?;
    if apps.len() > 4096 {
        bail!("installed application count exceeds 4096");
    }
    let mut seen = BTreeSet::new();
    for app in &apps {
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
        let value=validate_standard(r#"{"apps":[{"package":"com.test.app","label":"应用名称","system":false}],"source":"package_manager"}"#).unwrap();
        let value: Value = serde_json::from_str(&value).unwrap();
        assert_eq!(value["apps"][0]["label"], "应用名称");
        assert_eq!(value["labelsAvailable"], true);
        assert!(validate_standard(r#"{"apps":[{"package":"com.test.app","label":null,"system":false},{"package":"com.test.app","label":null,"system":false}]}"#).is_err());
        assert!(validate_standard(&serde_json::json!({"apps":[{"package":"com.test.app","label":"a".repeat(257),"system":false}]}).to_string()).is_err());
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
