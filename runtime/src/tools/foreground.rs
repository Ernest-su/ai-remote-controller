use anyhow::Result;
use serde_json::json;

fn package(output: &str) -> Option<&str> {
    output
        .lines()
        .filter(|line| {
            line.contains("mResumedActivity:")
                || line.contains("topResumedActivity=")
                || line.contains("mResumedActivity=")
        })
        .find_map(|line| {
            line.split_whitespace().find_map(|part| {
                let (package, _) = part.split_once('/')?;
                (package.contains('.')
                    && package
                        .bytes()
                        .all(|b| b.is_ascii_alphanumeric() || b == b'.' || b == b'_'))
                .then_some(package)
            })
        })
}
pub(super) async fn execute() -> Result<String> {
    let output = super::run_command("dumpsys", &["activity".into(), "activities".into()]).await?;
    Ok(
        json!({"packageName":package(&output),"source":"activity_resumed","readOnly":true})
            .to_string(),
    )
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn ignores_background_activities() {
        assert_eq!(package("mLastPausedActivity: ActivityRecord{aa u0 other.app/.Main}\n mResumedActivity: ActivityRecord{bb u0 test.app/.Main t12}"), Some("test.app"));
        assert_eq!(package("permission denied"), None);
    }
}
