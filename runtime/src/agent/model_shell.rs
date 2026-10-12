use anyhow::{bail, Context, Result};
use serde_json::Value;

/// Model diagnostics never enter a shell interpreter. Direct expert calls are separate.
fn command(args: &Value) -> Result<(&'static str, Vec<String>)> {
    let object = args
        .as_object()
        .context("diagnostic arguments must be object")?;
    if object.len() != 1 || !object.contains_key("command") {
        bail!("only command accepted");
    }
    let text = args["command"].as_str().context("command must be string")?;
    if text.is_empty()
        || text.len() > 4096
        || text
            .chars()
            .any(|c| c.is_control() || ";|&$`<>\\\"'()".contains(c))
    {
        bail!("model diagnostics reject shell syntax; use structured tools and App confirmation");
    }
    let words: Vec<&str> = text.split_ascii_whitespace().collect();
    let (program, values): (&str, Vec<String>) = match words.as_slice() {
        ["getprop"] => ("getprop", vec![]),
        ["getprop", property]
            if property
                .bytes()
                .all(|c| c.is_ascii_alphanumeric() || b"._-".contains(&c))
                && !property.starts_with('-') =>
        {
            ("getprop", vec![property.to_string()])
        }
        ["pwd"] => ("pwd", vec![]),
        ["wm", value @ ("size" | "density")] => ("wm", vec![value.to_string()]),
        ["dumpsys", "window", "windows"] => ("dumpsys", vec!["window".into(), "windows".into()]),
        ["dumpsys", "activity", "activities"] => {
            ("dumpsys", vec!["activity".into(), "activities".into()])
        }
        ["dumpsys", value @ ("power" | "media_session" | "package")] => {
            ("dumpsys", vec![value.to_string()])
        }
        ["printf" | "echo", values @ ..] => ("printf", vec!["%s".into(), values.join(" ")]),
        _ => bail!(
            "model shell is diagnostic-only; use structured tools for actions and App confirmation"
        ),
    };
    Ok((program, values))
}

pub(super) async fn execute(args: &Value) -> Result<String> {
    let (program, argv) = command(args)?;
    crate::tools::run_command(program, &argv).await
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn fixed_diagnostics_preserve_arguments_without_shell() {
        for value in [
            "getprop",
            "getprop ro.build.version.sdk",
            "pwd",
            "wm size",
            "wm density",
            "dumpsys window windows",
            "dumpsys activity activities",
            "dumpsys power",
            "dumpsys media_session",
            "dumpsys package",
        ] {
            let (program, args) = command(&json!({"command":value})).unwrap();
            assert_ne!(program, "sh");
            assert!(!args.iter().any(|arg| arg == "-c"));
        }
    }
    #[test]
    fn destructive_commands_and_mutating_diagnostics_are_rejected() {
        for value in [
            "reboot",
            "reboot -p",
            "rm -rf /sdcard",
            "pm clear fixture.app",
            "pm uninstall fixture.app",
            "am force-stop fixture.app",
            "wm size 640x480",
            "dumpsys battery set level 1",
            "sh -c reboot",
            "su 0 reboot",
            "input keyevent POWER",
        ] {
            assert!(command(&json!({"command":value})).is_err(), "{value}");
        }
    }
    #[test]
    fn substitutions_pipelines_and_redirects_are_rejected() {
        for value in [
            "pwd;reboot",
            "pwd && reboot",
            "echo $(reboot)",
            "echo `reboot`",
            "pwd > /data/file",
            "pwd | sh",
            "getprop\nreboot",
            "echo 'reboot'",
            "echo \\reboot",
        ] {
            assert!(command(&json!({"command":value})).is_err(), "{value}");
        }
    }
    #[test]
    fn malformed_arguments_are_rejected() {
        for value in [
            json!(null),
            json!({}),
            json!({"command":1}),
            json!({"command":"pwd","extra":true}),
            json!({"command":""}),
        ] {
            assert!(command(&value).is_err());
        }
    }
    #[tokio::test]
    async fn literal_output_does_not_interpret_formatting() {
        assert_eq!(
            execute(&json!({"command":"printf %s%n"})).await.unwrap(),
            "%s%n"
        );
    }
}
