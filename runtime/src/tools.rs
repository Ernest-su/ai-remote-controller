use anyhow::{bail, Context, Result};
use serde_json::Value;
use std::{process::Stdio, time::Duration};
use tokio::io::{AsyncRead, AsyncReadExt};
use tokio::process::Command;

mod app_icon;
mod apps;
mod device_info;
mod file_directories;
pub(crate) mod files;
pub(crate) mod gamepad;
mod gestures;
mod media;
pub(crate) mod media_open;
mod remote_keys;
mod remote_text;
mod screenshot;
mod ui_hierarchy;

pub const TOOLS: &[&str] = &[
    "remote.key",
    "remote.text",
    "gamepad.pulse",
    "gamepad.reset",
    "gamepad.virtual_capabilities",
    "gamepad.virtual_open",
    "gamepad.virtual_update",
    "gamepad.virtual_close",
    "remote.tap",
    "remote.swipe",
    "remote.long_press",
    "task.start",
    "task.progress",
    "task.cancel",
    "files.mkdir",
    "files.pending",
    "files.delete",
    "files.prepare_overwrite",
    "storage.analyze",
    "files.list",
    "files.stat",
    "files.download",
    "apps.list",
    "apps.icon",
    "apps.info",
    "apps.cache_capabilities",
    "apps.cache_pending",
    "apps.clear_cache",
    "apps.apk_info",
    "apps.install",
    "apps.install_pending",
    "apps.uninstall",
    "apps.pending",
    "apps.open",
    "device.info",
    "device.ui_hierarchy",
    "device.system_capabilities",
    "device.system_state",
    "device.system_action",
    "device.system_pending",
    "media.status",
    "media.seek",
    "media.custom_action",
    "media.players",
    "media.publish",
    "media.publications",
    "media.open",
    "media.release",
    "device.screenshot",
    "screen.preview_start",
    "screen.mirror_start",
    "screen.mirror_stop",
    "screen.preview_stop",
    "device.shell",
];

pub fn command_for(tool: &str, args: &Value) -> Result<(String, Vec<String>)> {
    let string = |key: &str| {
        args.get(key)
            .and_then(Value::as_str)
            .with_context(|| format!("missing string argument: {key}"))
    };
    let (program, argv): (&str, Vec<String>) = match tool {
        "remote.key" => {
            let code = remote_keys::code(string("key")?)?;
            ("input", vec!["keyevent".into(), code.to_string()])
        }
        "apps.open" => {
            let package = string("package")?;
            if package.is_empty()
                || !package.contains('.')
                || !package
                    .bytes()
                    .all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'.')
            {
                bail!("invalid Android package name");
            }
            (
                "monkey",
                vec![
                    "-p".into(),
                    package.into(),
                    "-c".into(),
                    "android.intent.category.LAUNCHER".into(),
                    "1".into(),
                ],
            )
        }
        "device.shell" => ("sh", vec!["-c".into(), string("command")?.into()]),
        _ => bail!("unknown tool: {tool}"),
    };
    Ok((program.into(), argv))
}

pub async fn execute_owned(tool: &str, args: &Value, owner: &str) -> Result<String> {
    if matches!(tool, "device.system_action" | "device.system_pending") {
        return crate::system_actions::execute(tool, args, owner).await;
    }
    if matches!(tool, "screen.mirror_start" | "screen.mirror_stop") {
        return crate::screen_mirror::execute(tool, args, owner);
    }
    if matches!(
        tool,
        "gamepad.virtual_capabilities"
            | "gamepad.virtual_open"
            | "gamepad.virtual_update"
            | "gamepad.virtual_close"
    ) {
        return crate::virtual_gamepad::execute(tool, args, owner);
    }
    execute(tool, args).await
}

pub async fn execute(tool: &str, args: &Value) -> Result<String> {
    if tool == "apps.icon" {
        return app_icon::execute(args).await;
    }
    if tool == "device.ui_hierarchy" {
        return ui_hierarchy::execute(args).await;
    }
    if tool == "remote.text" {
        return remote_text::execute(args).await;
    }
    if tool == "device.system_state" {
        return crate::system_state::execute(args).await;
    }
    if tool == "device.system_capabilities" {
        return crate::system_capabilities::execute(args).await;
    }
    if matches!(tool, "gamepad.pulse" | "gamepad.reset") {
        return gamepad::execute(tool, args).await;
    }
    if tool == "storage.analyze" {
        return crate::storage_analysis::start(args);
    }
    if matches!(tool, "remote.tap" | "remote.swipe" | "remote.long_press") {
        return tokio::time::timeout(Duration::from_secs(30), gestures::execute(tool, args))
            .await
            .context("gesture timed out")?;
    }
    if tool == "media.publications" {
        return crate::media_publications::list(args);
    }
    if matches!(
        tool,
        "apps.info"
            | "apps.cache_capabilities"
            | "apps.cache_pending"
            | "apps.clear_cache"
            | "apps.apk_info"
            | "apps.install"
            | "apps.install_pending"
            | "apps.uninstall"
            | "apps.pending"
    ) {
        return crate::app_actions::execute(tool, args).await;
    }
    if tool == "media.publish" {
        return crate::media_publications::start(args);
    }
    if tool == "files.mkdir" {
        return file_directories::execute(args);
    }
    if tool == "files.pending" {
        return crate::file_actions::pending(args);
    }
    if matches!(tool, "files.delete" | "files.prepare_overwrite") {
        return crate::file_actions::prepare(tool, args);
    }
    if matches!(tool, "task.start" | "task.progress" | "task.cancel") {
        return crate::transfers::execute(tool, args).await;
    }
    if matches!(tool, "files.list" | "files.stat" | "files.download") {
        return files::execute(tool, args).await;
    }
    if matches!(tool, "screen.mirror_start" | "screen.mirror_stop") {
        bail!("mirror tools require a connection owner");
    }
    if matches!(tool, "screen.preview_start" | "screen.preview_stop") {
        return crate::screen_preview::execute(tool, args);
    }
    if tool == "device.screenshot" {
        return screenshot::execute(args).await;
    }
    if matches!(tool, "media.players" | "media.open" | "media.release") {
        return media_open::execute(tool, args).await;
    }
    if tool == "media.custom_action" {
        return media::custom_action(args).await;
    }
    if tool == "media.seek" {
        return media::seek(args).await;
    }
    if tool == "media.status" {
        return media::execute(args).await;
    }
    if tool == "apps.list" {
        return tokio::time::timeout(Duration::from_secs(30), apps::list(args))
            .await
            .context("apps.list timed out")?;
    }
    if tool == "device.info" {
        if !args.is_object() || args.as_object().is_some_and(|object| !object.is_empty()) {
            bail!("device.info accepts an empty argument object");
        }
        return tokio::time::timeout(Duration::from_secs(30), device_info::execute())
            .await
            .context("device.info timed out")?;
    }
    let (program, argv) = command_for(tool, args)?;
    run_command(&program, &argv).await
}

pub(crate) async fn run_command(program: &str, argv: &[String]) -> Result<String> {
    Ok(String::from_utf8_lossy(&run_binary_command(program, argv, 256 * 1024).await?).into_owned())
}

pub(crate) async fn run_binary_command(
    program: &str,
    argv: &[String],
    limit: usize,
) -> Result<Vec<u8>> {
    run_binary_with_input(
        program,
        argv,
        limit,
        Stdio::null(),
        Duration::from_secs(30),
        None,
    )
    .await
}
pub(crate) async fn run_command_with_input(
    program: &str,
    argv: &[String],
    file: std::fs::File,
) -> Result<String> {
    Ok(String::from_utf8_lossy(
        &run_binary_with_input(
            program,
            argv,
            256 * 1024,
            file.into(),
            Duration::from_secs(300),
            None,
        )
        .await?,
    )
    .into_owned())
}
pub(crate) async fn run_platform_bridge(argv: &[String], path: &std::path::Path) -> Result<String> {
    Ok(String::from_utf8_lossy(
        &run_binary_with_input(
            "app_process",
            argv,
            256 * 1024,
            Stdio::null(),
            Duration::from_secs(10),
            Some(path),
        )
        .await?,
    )
    .into_owned())
}

async fn run_binary_with_input(
    program: &str,
    argv: &[String],
    limit: usize,
    input: Stdio,
    timeout: Duration,
    classpath: Option<&std::path::Path>,
) -> Result<Vec<u8>> {
    let mut command = Command::new(program);
    if let Some(path) = classpath {
        command.env("CLASSPATH", path);
    }
    let mut child = command
        .args(argv)
        .stdin(input)
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .process_group(0)
        .kill_on_drop(true)
        .spawn()?;
    let _group = ProcessGroup(child.id().context("missing child PID")? as i32);
    let stdout = child.stdout.take().context("missing stdout")?;
    let stderr = child.stderr.take().context("missing stderr")?;
    let (status, stdout, stderr) = tokio::time::timeout(timeout, async {
        tokio::try_join!(
            async { Ok::<_, anyhow::Error>(child.wait().await?) },
            read_output(stdout, limit),
            read_output(stderr, 256 * 1024)
        )
    })
    .await
    .context("tool execution timed out")??;
    if !status.success() {
        bail!(
            "tool exited with {}: {}",
            status,
            String::from_utf8_lossy(if stderr.is_empty() { &stdout } else { &stderr })
        );
    }
    Ok(stdout)
}

async fn read_output(mut reader: impl AsyncRead + Unpin, limit: usize) -> Result<Vec<u8>> {
    let mut output = Vec::new();
    // Keep command pipe buffers off nested async state-machine stacks.
    let mut buffer = vec![0u8; 8192];
    loop {
        let count = reader.read(&mut buffer).await?;
        if count == 0 {
            return Ok(output);
        }
        if output.len() + count > limit {
            bail!(
                "tool output exceeds {} KiB; narrow the command output",
                limit / 1024
            );
        }
        output.extend_from_slice(&buffer[..count]);
    }
}

pub(crate) struct ProcessGroup(pub(crate) i32);
impl Drop for ProcessGroup {
    fn drop(&mut self) {
        unsafe {
            libc::kill(-self.0, libc::SIGKILL);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    #[tokio::test]
    async fn device_info_rejects_parameters() {
        assert!(execute("device.info", &json!({"command":"reboot"}))
            .await
            .is_err());
        assert!(execute("device.info", &Value::Null).await.is_err());
    }

    #[test]
    fn remote_keys_are_explicit() {
        assert_eq!(
            command_for("remote.key", &json!({"key":"up"})).unwrap(),
            ("input".into(), vec!["keyevent".into(), "19".into()])
        );
        assert!(command_for("remote.key", &json!({"key":"19; reboot"})).is_err());
    }
    #[test]
    fn package_cannot_inject_arguments() {
        assert!(command_for("apps.open", &json!({"package":"com.example;reboot"})).is_err());
        assert!(command_for("apps.open", &json!({"package":"com.example.app"})).is_ok());
    }
    #[tokio::test]
    async fn real_shell_results_and_bounded_output() {
        assert_eq!(
            execute("device.shell", &json!({"command":"printf actual-ok"}))
                .await
                .unwrap(),
            "actual-ok"
        );
        assert!(execute("device.shell", &json!({"command":"exit 7"}))
            .await
            .unwrap_err()
            .to_string()
            .contains("exit status: 7"));
        assert!(execute("device.shell", &json!({"command":"yes bounded"}))
            .await
            .unwrap_err()
            .to_string()
            .contains("256 KiB"));
    }
}
