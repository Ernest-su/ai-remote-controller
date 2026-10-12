use super::run_command;
use anyhow::{bail, Context, Result};
use serde_json::{json, Value};

fn coordinate(args: &Value, name: &str, extent: u32) -> Result<String> {
    let value = args
        .get(name)
        .and_then(Value::as_f64)
        .with_context(|| format!("missing numeric coordinate: {name}"))?;
    if !value.is_finite() || !(0.0..=1.0).contains(&value) {
        bail!("{name} must be a finite number between 0 and 1");
    }
    Ok((value * f64::from(extent - 1)).round().to_string())
}

fn duration(args: &Value, default: u64) -> Result<String> {
    let value = match args.get("durationMs") {
        None => default,
        Some(value) => value.as_u64().context("durationMs must be an integer")?,
    };
    if !(100..=5000).contains(&value) {
        bail!("durationMs must be between 100 and 5000");
    }
    Ok(value.to_string())
}

fn validate(tool: &str, args: &Value) -> Result<()> {
    let keys: &[&str] = match tool {
        "remote.tap" => &["x", "y", "count"],
        "remote.long_press" => &["x", "y", "durationMs"],
        "remote.swipe" => &["startX", "startY", "endX", "endY", "durationMs"],
        _ => bail!("unknown gesture"),
    };
    let object = args
        .as_object()
        .context("gesture arguments must be an object")?;
    if object.keys().any(|key| !keys.contains(&key.as_str())) {
        bail!("unknown gesture argument");
    }
    if tool == "remote.tap" {
        tap_count(args)?;
    }
    // Validate before invoking any platform command.
    command(tool, args, 1, 1)?;
    Ok(())
}

fn tap_count(args: &Value) -> Result<u64> {
    let count = match args.get("count") {
        None => 1,
        Some(value) => value.as_u64().context("count must be an integer")?,
    };
    if !(1..=2).contains(&count) {
        bail!("count must be 1 or 2");
    }
    Ok(count)
}

fn command(tool: &str, args: &Value, width: u32, height: u32) -> Result<Vec<String>> {
    if width == 0 || height == 0 || width > i32::MAX as u32 || height > i32::MAX as u32 {
        bail!("unsupported screen dimensions");
    }
    let point = |x, y| -> Result<Vec<String>> {
        Ok(vec![
            coordinate(args, x, width)?,
            coordinate(args, y, height)?,
        ])
    };
    match tool {
        "remote.tap" => {
            let mut argv = vec!["tap".into()];
            argv.extend(point("x", "y")?);
            Ok(argv)
        }
        "remote.long_press" => {
            let point = point("x", "y")?;
            let mut argv = vec!["swipe".into()];
            argv.extend(point.clone());
            argv.extend(point);
            let duration = duration(args, 800)?;
            if duration.parse::<u64>()? < 500 {
                bail!("long press durationMs must be at least 500");
            }
            argv.push(duration);
            Ok(argv)
        }
        "remote.swipe" => {
            let mut argv = vec!["swipe".into()];
            argv.extend(point("startX", "startY")?);
            argv.extend(point("endX", "endY")?);
            argv.push(duration(args, 300)?);
            Ok(argv)
        }
        _ => bail!("unknown gesture"),
    }
}

// Input's logical frame follows rotation and resolution overrides, unlike wm's natural size.
pub(super) fn display_dimensions(output: &str) -> Option<(u32, u32)> {
    output.lines().find_map(|line| {
        if !line.trim_start().starts_with("Viewport ")
            || !line
                .split_once(':')?
                .1
                .split(',')
                .any(|field| field.trim() == "displayId=0")
            || !(line.contains("isActive=[1]") || line.contains("isActive=true"))
        {
            return None;
        }
        let frame = line.split_once("logicalFrame=[")?.1.split_once(']')?.0;
        let values: Vec<i64> = frame
            .split(',')
            .map(|value| value.trim().parse())
            .collect::<std::result::Result<_, _>>()
            .ok()?;
        if values.len() != 4 || values[0] != 0 || values[1] != 0 {
            return None;
        }
        let width = u32::try_from(values[2]).ok()?;
        let height = u32::try_from(values[3]).ok()?;
        (width > 0 && height > 0 && width <= i32::MAX as u32 && height <= i32::MAX as u32)
            .then_some((width, height))
    })
}

pub(super) async fn execute(tool: &str, args: &Value) -> Result<String> {
    validate(tool, args)?;
    let display = run_command("dumpsys", &["input".into()]).await?;
    let (width, height) = display_dimensions(&display)
        .context("target did not report an active default display viewport")?;
    let argv = command(tool, args, width, height)?;
    let count = if tool == "remote.tap" {
        tap_count(args)?
    } else {
        1
    };
    let mut output = run_command("input", &argv).await?;
    if count == 2 {
        tokio::time::sleep(std::time::Duration::from_millis(80)).await;
        output.push_str(&run_command("input", &argv).await?);
    }
    Ok(json!({"width":width,"height":height,"output":output}).to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn double_tap_count_is_bounded() {
        assert_eq!(tap_count(&json!({})).unwrap(), 1);
        assert_eq!(tap_count(&json!({"count":2})).unwrap(), 2);
        for count in [json!(0), json!(3), json!(1.5), json!("2"), Value::Null] {
            assert!(validate("remote.tap", &json!({"x":0.5,"y":0.5,"count":count})).is_err());
        }
    }

    #[test]
    fn viewport_uses_active_default_logical_frame() {
        let output = "Viewport EXTERNAL: displayId=1, logicalFrame=[0, 0, 300, 200], isActive=[1]\nViewport INTERNAL: displayId=0, orientation=1, logicalFrame=[0, 0, 1080, 1920], isActive=[1]";
        assert_eq!(display_dimensions(output), Some((1080, 1920)));
        assert_eq!(
            display_dimensions(
                "Viewport INTERNAL: displayId=0, logicalFrame=[0, 0, 10, 20], isActive=[0]"
            ),
            None
        );
        assert_eq!(display_dimensions("permission denied"), None);
    }

    #[test]
    fn coordinates_use_screen_bounds_and_long_press_keeps_position() {
        assert_eq!(
            command("remote.tap", &json!({"x":1,"y":0}), 1920, 1080).unwrap(),
            ["tap", "1919", "0"]
        );
        assert_eq!(
            command("remote.long_press", &json!({"x":0.5,"y":0.5}), 1280, 720).unwrap(),
            ["swipe", "640", "360", "640", "360", "800"]
        );
        assert_eq!(
            command(
                "remote.swipe",
                &json!({"startX":0,"startY":1,"endX":1,"endY":0,"durationMs":5000}),
                100,
                50
            )
            .unwrap(),
            ["swipe", "0", "49", "99", "0", "5000"]
        );
    }

    #[test]
    fn invalid_coordinates_durations_and_extra_arguments_are_rejected() {
        for args in [
            json!({"x":-0.1,"y":0}),
            json!({"x":1.1,"y":0}),
            json!({"x":"0;reboot","y":0}),
            json!({"x":0}),
            json!({"x":0,"y":0,"display":1}),
        ] {
            assert!(validate("remote.tap", &args).is_err());
        }
        for value in [json!(0), json!(5001), json!(0.5), json!("800"), Value::Null] {
            assert!(validate(
                "remote.long_press",
                &json!({"x":0,"y":0,"durationMs":value})
            )
            .is_err());
        }
        assert!(command("remote.tap", &json!({"x":0,"y":0}), 0, 100).is_err());
    }
}
