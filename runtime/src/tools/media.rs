use super::run_command;
use anyhow::{bail, Context, Result};
use serde_json::{json, Value};

fn number(line: &str, field: &str) -> Option<i64> {
    line.split(',')
        .find_map(|part| part.trim().strip_prefix(field))?
        .split('}')
        .next()?
        .trim()
        .parse()
        .ok()
}

pub(super) fn parse(dump: &str) -> Result<Value> {
    if dump.to_ascii_lowercase().contains("permission denial")
        || dump.to_ascii_lowercase().contains("permission denied")
    {
        bail!("permission denied reading MediaSession");
    }
    let mut sessions = Vec::new();
    let mut package: Option<String> = None;
    let mut active = false;
    let mut current: Option<Value> = None;
    for line in dump.lines().map(str::trim) {
        if let Some(value) = line.strip_prefix("package=") {
            if let Some(value) = current.take() {
                sessions.push(value);
            }
            package = Some(value.to_owned());
            active = false;
        } else if let Some(value) = line.strip_prefix("active=") {
            active = value == "true";
        } else if let Some(value) = line.strip_prefix("state=PlaybackState {state=") {
            let state = value
                .split([',', '('])
                .next()
                .and_then(|n| n.parse::<u32>().ok())
                .or_else(|| value.split_once('(')?.1.split(')').next()?.parse().ok());
            let actions = number(line, "actions=").filter(|n| *n >= 0);
            current = Some(json!({"packageName":package,"active":active,"state":state,
                "positionMs":number(line,"position=").filter(|n| *n >= 0),
                "durationMs":null,"description":null,"updatedAtElapsedMs":number(line,"updated=").filter(|n| *n >= 0),
                "seekSupported":actions.map(|n| n & 256 != 0)}));
        } else if line.starts_with("metadata:") {
            if let Some(ref mut session) = current {
                // Some platform dumps expose the standard duration key; never infer duration from titles.
                session["description"] = json!(line
                    .split_once("description=")
                    .map(|(_, value)| value.trim())
                    .filter(|value| !value.is_empty() && *value != "null"));
                session["durationMs"] =
                    json!(number(line, "android.media.metadata.DURATION=").filter(|n| *n >= 0));
            }
        }
    }
    if let Some(value) = current {
        sessions.push(value);
    }
    Ok(json!({"sessions":sessions}))
}

pub(super) async fn execute(args: &Value) -> Result<String> {
    if !args.is_object() || args.as_object().is_some_and(|args| !args.is_empty()) {
        bail!("media.status accepts an empty argument object");
    }
    let standard = async {
        let user = current_user().await?;
        let payload = crate::platform_bridge::invoke("status", &[user]).await?;
        let mut value: Value = serde_json::from_str(&payload)?;
        if !value["sessions"].is_array() {
            bail!("invalid standard media status");
        }
        value["seekAvailable"] = json!(true);
        Ok::<_, anyhow::Error>(value.to_string())
    }
    .await;
    match standard {
        Ok(payload) => Ok(payload),
        Err(error) => {
            let dump = run_command("dumpsys", &["media_session".into()]).await?;
            let mut value = parse(&dump)?;
            value["seekAvailable"] = json!(false);
            value["bridgeError"] = json!(error.to_string().chars().take(256).collect::<String>());
            Ok(value.to_string())
        }
    }
}

pub(super) async fn pause_current() -> Result<String> {
    let result = crate::platform_bridge::invoke("pause_current", &[current_user().await?]).await?;
    let value: Value = serde_json::from_str(&result)?;
    if value["operation"] != "pause"
        || value["submitted"] != true
        || value["packageName"].as_str().is_none_or(str::is_empty)
    {
        bail!("invalid media pause acknowledgement");
    }
    Ok(result)
}

async fn current_user() -> Result<String> {
    let user = run_command("am", &["get-current-user".into()])
        .await?
        .trim()
        .parse::<u32>()?;
    Ok(user.to_string())
}
fn seek_arguments(args: &Value) -> Result<Vec<String>> {
    let object = args.as_object().context("seek arguments must be object")?;
    if object
        .keys()
        .any(|key| !["package", "positionMs", "sessionTag"].contains(&key.as_str()))
    {
        bail!("only package, positionMs and sessionTag accepted");
    }
    let package = args["package"].as_str().context("package required")?;
    if package.len() > 255
        || !package.contains('.')
        || package.split('.').any(|part| {
            part.is_empty() || !part.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_')
        })
    {
        bail!("invalid media package");
    }
    let position = args["positionMs"]
        .as_i64()
        .filter(|position| *position >= 0)
        .context("positionMs must be a nonnegative integer")?;
    let tag = args
        .get("sessionTag")
        .map(|value| value.as_str().context("sessionTag must be string"))
        .transpose()?
        .unwrap_or("");
    if tag.len() > 512 || tag.chars().any(char::is_control) {
        bail!("invalid media session tag");
    }
    Ok(vec![package.into(), tag.into(), position.to_string()])
}
fn custom_action_arguments(args: &Value) -> Result<Vec<String>> {
    let object = args
        .as_object()
        .context("custom action arguments must be object")?;
    if object
        .keys()
        .any(|key| !["package", "sessionTag", "action"].contains(&key.as_str()))
    {
        bail!("only package, sessionTag and action accepted; no custom extras schema");
    }
    // Share package and session validation with seek without accepting seek parameters.
    let mut selection = args.clone();
    selection.as_object_mut().unwrap().remove("action");
    selection["positionMs"] = json!(0);
    let mut parameters = seek_arguments(&selection)?;
    let action = args["action"].as_str().context("action required")?;
    if action.is_empty() || action.len() > 512 || action.chars().any(char::is_control) {
        bail!("invalid custom action identifier");
    }
    parameters[2] = action.into();
    Ok(parameters)
}
pub(super) async fn custom_action(args: &Value) -> Result<String> {
    let arguments = custom_action_arguments(args)?;
    let mut parameters = vec![current_user().await?];
    parameters.extend(arguments);
    crate::platform_bridge::invoke("custom_action", &parameters).await
}

pub(super) async fn seek(args: &Value) -> Result<String> {
    let arguments = seek_arguments(args)?;
    let mut parameters = vec![current_user().await?];
    parameters.extend(arguments);
    crate::platform_bridge::invoke("seek", &parameters).await
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn seek_input_is_absolute_and_cannot_inject_shell_arguments() {
        assert!(
            seek_arguments(&json!({"package":"com.test.player;reboot","positionMs":1})).is_err()
        );
        assert!(seek_arguments(&json!({"package":"com.test.player","positionMs":-1})).is_err());
        assert!(seek_arguments(
            &json!({"package":"com.test.player","positionMs":1,"relative":true})
        )
        .is_err());
        assert_eq!(
            seek_arguments(
                &json!({"package":"com.test.player","positionMs":7000,"sessionTag":"fixture"})
            )
            .unwrap(),
            vec!["com.test.player", "fixture", "7000"]
        );
    }
    #[test]
    fn custom_actions_require_bounded_identity_and_no_guessed_extras() {
        assert_eq!(
            custom_action_arguments(
                &json!({"package":"com.test.player","action":"quality.hd","sessionTag":"test"})
            )
            .unwrap(),
            vec!["com.test.player", "test", "quality.hd"]
        );
        for action in ["".to_owned(), "a".repeat(513), "a\n".to_owned()] {
            assert!(
                custom_action_arguments(&json!({"package":"com.test.player","action":action}))
                    .is_err()
            );
        }
        assert!(custom_action_arguments(
            &json!({"package":"com.test.player","action":"hd","extras":{"episode":1}})
        )
        .is_err());
        assert!(custom_action_arguments(
            &json!({"package":"com.test.player","action":"hd","positionMs":1})
        )
        .is_err());
        assert!(custom_action_arguments(
            &json!({"package":"com.test.player;reboot","action":"hd"})
        )
        .is_err());
    }
    #[test]
    fn buffered_position_does_not_replace_unknown_position() {
        let result = parse("package=com.player\nactive=true\nstate=PlaybackState {state=3, buffered position=100, actions=-1}").unwrap();
        assert!(result["sessions"][0]["positionMs"].is_null());
        assert!(result["sessions"][0]["seekSupported"].is_null());
    }
    #[test]
    fn structured_sessions_preserve_unknown_fields_and_capabilities() {
        let result = parse("package=com.player\nactive=true\nstate=PlaybackState {state=PLAYING(3), position=4200, buffered position=0, speed=1.0, updated=123, actions=256, custom actions=[]}\nmetadata: null\npackage=com.other\nactive=false\nstate=PlaybackState {state=2, position=-1, updated=200, actions=0}").unwrap();
        assert_eq!(result["sessions"][0]["state"], 3);
        assert_eq!(result["sessions"][0]["positionMs"], 4200);
        assert_eq!(result["sessions"][0]["seekSupported"], true);
        assert!(result["sessions"][0]["durationMs"].is_null());
        assert!(result["sessions"][1]["positionMs"].is_null());
        assert_eq!(result["sessions"][1]["seekSupported"], false);
        assert_eq!(
            parse("Sessions Stack - have 0 sessions:").unwrap()["sessions"],
            json!([])
        );
        assert!(parse("Permission Denial").is_err());
    }
}

fn volume_percent(args: &Value) -> Result<Option<u64>> {
    let object = args
        .as_object()
        .context("media.volume requires an object")?;
    if object.is_empty() {
        return Ok(None);
    }
    if object.len() != 1 || !object.contains_key("percent") {
        bail!("media.volume accepts only percent");
    }
    Ok(Some(
        object["percent"]
            .as_u64()
            .filter(|v| *v <= 100)
            .context("percent must be an integer from 0 to 100")?,
    ))
}

pub(super) async fn volume(args: &Value) -> Result<String> {
    let percent = volume_percent(args)?;
    let mut arguments = vec![current_user().await?];
    let action = if let Some(percent) = percent {
        arguments.push(percent.to_string());
        "set_volume"
    } else {
        "status"
    };
    let payload = crate::platform_bridge::scheduled_audio(action, &arguments).await?;
    let value: Value = serde_json::from_str(&payload)?;
    if value["stream"] != "media" || value["operation"] != action || value["verified"] != true {
        bail!("invalid media volume acknowledgement");
    }
    Ok(payload)
}

#[cfg(test)]
mod volume_tests {
    use super::*;
    #[test]
    fn accepts_read_and_bounded_integer_percent_only() {
        assert_eq!(volume_percent(&json!({})).unwrap(), None);
        for percent in [0, 57, 100] {
            assert_eq!(
                volume_percent(&json!({"percent":percent})).unwrap(),
                Some(percent)
            );
        }
        for invalid in [
            json!(null),
            json!([]),
            json!({"percent":-1}),
            json!({"percent":101}),
            json!({"percent":0.5}),
            json!({"percent":"50"}),
            json!({"percent":50,"stream":0}),
        ] {
            assert!(volume_percent(&invalid).is_err(), "{invalid}");
        }
    }
}
