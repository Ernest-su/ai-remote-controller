use anyhow::{bail, Context, Result};
use serde_json::{json, Value};
use std::{
    future::Future,
    sync::{Arc, OnceLock},
};
use tokio::sync::Semaphore;

const BUTTONS: &[(&str, u32)] = &[
    ("a", 96),
    ("b", 97),
    ("x", 99),
    ("y", 100),
    ("l1", 102),
    ("r1", 103),
    ("l2", 104),
    ("r2", 105),
    ("start", 108),
    ("select", 109),
    ("thumb_l", 106),
    ("thumb_r", 107),
    ("up", 19),
    ("down", 20),
    ("left", 21),
    ("right", 22),
];
static INPUT: OnceLock<Arc<Semaphore>> = OnceLock::new();
fn semaphore() -> Arc<Semaphore> {
    INPUT.get_or_init(|| Arc::new(Semaphore::new(1))).clone()
}
fn arguments(args: &Value) -> Result<Vec<String>> {
    let object = args
        .as_object()
        .context("gamepad arguments must be object")?;
    if object
        .keys()
        .any(|key| !["buttons", "x", "y", "rightX", "rightY", "holdMs"].contains(&key.as_str()))
    {
        bail!("only buttons, x, y, rightX, rightY and holdMs accepted");
    }
    let empty = vec![];
    let buttons = args
        .get("buttons")
        .map(|value| value.as_array().context("buttons must be array"))
        .transpose()?
        .unwrap_or(&empty);
    if buttons.len() > 6 {
        bail!("at most six buttons accepted");
    }
    let mut names = std::collections::BTreeSet::new();
    let mut codes = vec![];
    for button in buttons {
        let name = button.as_str().context("button must be string")?;
        let code = BUTTONS
            .iter()
            .find(|(key, _)| *key == name)
            .context("unsupported gamepad button")?
            .1;
        if !names.insert(name) {
            bail!("duplicate button");
        }
        codes.push(code.to_string());
    }
    if (names.contains("up") && names.contains("down"))
        || (names.contains("left") && names.contains("right"))
    {
        bail!("opposite directions cannot be combined");
    }
    let axis = |key| {
        args.get(key)
            .map(|value| {
                value
                    .as_f64()
                    .filter(|v| v.is_finite() && (-1.0..=1.0).contains(v))
                    .context("axes must be finite numbers in -1..1")
            })
            .transpose()
            .map(|value| value.unwrap_or(0.0))
    };
    let x = axis("x")?;
    let y = axis("y")?;
    let right_x = axis("rightX")?;
    let right_y = axis("rightY")?;
    let hold = args
        .get("holdMs")
        .map(|value| value.as_u64().context("holdMs must be integer"))
        .transpose()?
        .unwrap_or(100);
    if !(10..=2000).contains(&hold) {
        bail!("holdMs must be 10..2000");
    }
    if buttons.is_empty() && x == 0.0 && y == 0.0 && right_x == 0.0 && right_y == 0.0 {
        bail!("pulse must include buttons or nonzero axes; use gamepad.reset");
    }
    Ok(vec![
        codes.join(","),
        x.to_string(),
        y.to_string(),
        hold.to_string(),
        right_x.to_string(),
        right_y.to_string(),
    ])
}
async fn owned<F>(work: F) -> Result<String>
where
    F: Future<Output = Result<String>> + Send + 'static,
{
    // Dropping the requesting connection must not abort key release or stick neutralization.
    tokio::spawn(work)
        .await
        .context("gamepad transaction worker failed")?
}
pub(super) async fn execute(tool: &str, args: &Value) -> Result<String> {
    let reset = tool == "gamepad.reset";
    let parameters = if reset {
        if !args.is_object() || args.as_object().is_some_and(|object| !object.is_empty()) {
            bail!("gamepad.reset accepts an empty object");
        }
        vec![
            BUTTONS
                .iter()
                .map(|(_, code)| code.to_string())
                .collect::<Vec<_>>()
                .join(","),
            "0".into(),
            "0".into(),
            "100".into(),
            "0".into(),
            "0".into(),
        ]
    } else {
        arguments(args)?
    };
    let permit = if reset {
        tokio::time::timeout(
            std::time::Duration::from_secs(20),
            semaphore().acquire_owned(),
        )
        .await
        .context("ongoing gamepad transaction did not finish; retry reset")??
    } else {
        semaphore()
            .try_acquire_owned()
            .context("gamepad transaction already active")?
    };
    owned(async move {
        let _permit = permit;
        let result =
            crate::platform_bridge::gamepad(if reset { "release" } else { "pulse" }, &parameters)
                .await;
        match result {
            Ok(value) => {
                let mut value: Value = serde_json::from_str(&value)?;
                value["operation"] = json!(if reset { "reset" } else { "pulse" });
                Ok(value.to_string())
            }
            Err(error) => {
                let cleanup = crate::platform_bridge::gamepad("release", &parameters).await;
                match cleanup {
                    Ok(_) => Err(error.context("gamepad input failed; release retried")),
                    Err(cleanup) => {
                        Err(error.context(format!("gamepad release also failed: {cleanup}")))
                    }
                }
            }
        }
    })
    .await
}
pub(crate) async fn drain() {
    let _ = tokio::time::timeout(
        std::time::Duration::from_secs(25),
        semaphore().acquire_owned(),
    )
    .await;
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn controlled_buttons_axes_and_timings_reject_arbitrary_injection() {
        assert_eq!(
            arguments(&json!({"buttons":["a","r1"],"x":0.5,"y":-1,"holdMs":300})).unwrap(),
            vec!["96,103", "0.5", "-1", "300", "0", "0"]
        );
        for args in [
            json!({}),
            json!({"buttons":["power"]}),
            json!({"buttons":["a;reboot"]}),
            json!({"buttons":["a","a"]}),
            json!({"buttons":["up","down"]}),
            json!({"x":1.1}),
            json!({"x":1,"holdMs":2001}),
            json!({"x":1,"shell":true}),
        ] {
            assert!(arguments(&args).is_err());
        }
    }
    #[test]
    fn dual_sticks_and_six_buttons_are_validated_and_forwarded() {
        assert_eq!(
            arguments(
                &json!({"buttons":["a","b","x","y","select","start"],"rightX":-0.5,"rightY":1})
            )
            .unwrap(),
            vec!["96,97,99,100,109,108", "0", "0", "100", "-0.5", "1"]
        );
        assert!(arguments(&json!({"rightX":1.1})).is_err());
        assert!(arguments(&json!({"rightY":"1"})).is_err());
        assert!(arguments(&json!({"buttons":["a","b","x","y","select","start","l1"]})).is_err());
    }
    #[tokio::test]
    async fn dropped_request_does_not_abort_owned_completion() {
        let (started, entered) = tokio::sync::oneshot::channel();
        let (release, finish) = tokio::sync::oneshot::channel();
        let (done, completed) = tokio::sync::oneshot::channel();
        let request = tokio::spawn(owned(async move {
            started.send(()).unwrap();
            finish.await.unwrap();
            done.send(()).unwrap();
            Ok("released".into())
        }));
        entered.await.unwrap();
        request.abort();
        assert!(request.await.unwrap_err().is_cancelled());
        release.send(()).unwrap();
        tokio::time::timeout(std::time::Duration::from_secs(1), completed)
            .await
            .unwrap()
            .unwrap();
    }
}
