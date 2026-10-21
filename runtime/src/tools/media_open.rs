use super::run_command;
use anyhow::{bail, Context, Result};
use serde_json::{json, Value};
pub(crate) fn mime(path: &str, explicit: Option<&Value>) -> Result<&'static str> {
    let kind = if let Some(value) = explicit {
        value.as_str().context("mimeType must be string")?
    } else {
        match path
            .rsplit('.')
            .next()
            .unwrap_or("")
            .to_ascii_lowercase()
            .as_str()
        {
            "png" => "image/png",
            "jpg" | "jpeg" => "image/jpeg",
            "webp" => "image/webp",
            "gif" => "image/gif",
            "mp4" => "video/mp4",
            "mkv" => "video/x-matroska",
            "webm" => "video/webm",
            "mp3" => "audio/mpeg",
            "m4a" => "audio/mp4",
            "aac" => "audio/aac",
            "wav" => "audio/wav",
            "ogg" => "audio/ogg",
            "flac" => "audio/flac",
            _ => bail!("choose a supported image/audio/video MIME type"),
        }
    };
    match kind {
        "image/png" => Ok("image/png"),
        "image/jpeg" => Ok("image/jpeg"),
        "image/webp" => Ok("image/webp"),
        "image/gif" => Ok("image/gif"),
        "video/mp4" => Ok("video/mp4"),
        "video/webm" => Ok("video/webm"),
        "video/x-matroska" => Ok("video/x-matroska"),
        "audio/mpeg" => Ok("audio/mpeg"),
        "audio/mp4" => Ok("audio/mp4"),
        "audio/aac" => Ok("audio/aac"),
        "audio/wav" => Ok("audio/wav"),
        "audio/ogg" => Ok("audio/ogg"),
        "audio/flac" => Ok("audio/flac"),
        _ => bail!("unsupported media MIME type"),
    }
}
fn package(value: &str) -> bool {
    !value.is_empty()
        && value.contains('.')
        && value
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'.')
}
fn parse_players(output: &str) -> Result<Vec<String>> {
    if output.to_ascii_lowercase().contains("error")
        || output.to_ascii_lowercase().contains("permission denial")
    {
        bail!("cannot query target media handlers");
    }
    let mut players = std::collections::BTreeSet::new();
    for line in output.lines().map(str::trim) {
        if let Some((name, component)) = line.split_once('/') {
            if package(name) && !component.is_empty() && !name.ends_with(".frameworkpackagestubs") {
                players.insert(name.to_string());
            }
        }
    }
    Ok(players.into_iter().collect())
}
pub(super) async fn execute(tool: &str, args: &Value) -> Result<String> {
    let object = args.as_object().context("arguments must be object")?;
    if tool == "media.release" {
        if object.len() != 1 {
            bail!("only resource id accepted");
        }
        let id = args["id"].as_str().context("id required")?;
        if crate::media_publications::release(id).is_err() {
            crate::media_resources::revoke(id)?;
        }
        return Ok(json!({"released":true}).to_string());
    }
    if object
        .keys()
        .any(|key| !["root", "path", "mimeType", "package", "transport"].contains(&key.as_str()))
        || (tool == "media.players" && object.contains_key("package"))
    {
        bail!("unknown media parameter");
    }
    let root = args
        .get("root")
        .map(|v| v.as_str().context("root must be string"))
        .transpose()?
        .unwrap_or("downloads");
    let path = args["path"].as_str().context("path required")?;
    let file = crate::tools::files::open(&crate::tools::files::root(root)?, path)?;
    if !file.metadata()?.is_file() {
        bail!("media requires a regular file");
    }
    let mime = mime(path, args.get("mimeType"))?;
    let transport = args
        .get("transport")
        .map(|v| v.as_str().context("transport must be string"))
        .transpose()?
        .unwrap_or(if root == "downloads" { "file" } else { "http" });
    if !["file", "http", "local-copy"].contains(&transport)
        || (tool == "media.open" && transport == "local-copy")
    {
        bail!("publish the playback copy before opening it");
    }
    if transport == "file" && root != "downloads" {
        bail!("direct file playback requires Downloads");
    }
    let probe = if transport == "local-copy" {
        reqwest::Url::from_file_path(
            crate::tools::files::root("downloads")?
                .join(".airc-media-probe")
                .join(path.rsplit('/').next().unwrap()),
        )
        .map_err(|_| anyhow::anyhow!("invalid probe URI"))?
        .to_string()
    } else if transport == "file" {
        reqwest::Url::from_file_path(crate::tools::files::root(root)?.join(path))
            .map_err(|_| anyhow::anyhow!("invalid file URI"))?
            .to_string()
    } else {
        format!("{}/media/probe", crate::media_resources::base_url()?)
    };
    let players = parse_players(
        &run_command(
            "cmd",
            &[
                "package".into(),
                "query-activities".into(),
                "--brief".into(),
                "-a".into(),
                "android.intent.action.VIEW".into(),
                "-d".into(),
                probe.clone(),
                "-t".into(),
                mime.into(),
            ],
        )
        .await?,
    )?;
    if tool == "media.players" {
        return Ok(json!({"root":root,"path":path,"mimeType":mime,"transport":transport,"packages":players,"sizeBytes":file.metadata()?.len()}).to_string());
    }
    let selected = args
        .get("package")
        .map(|v| v.as_str().context("package must be string"))
        .transpose()?;
    if let Some(selected) = selected {
        if !package(selected) || !players.iter().any(|p| p == selected) {
            bail!("selected player cannot handle this media transport");
        }
    }
    if players.is_empty() {
        bail!("no installed player supports this media transport and MIME type");
    }
    let (id, url) = if transport == "file" {
        (None, probe)
    } else {
        let (id, url) = crate::media_resources::register(file, mime)?;
        (Some(id), url)
    };
    let mut argv = vec![
        "start".into(),
        "-W".into(),
        "-a".into(),
        "android.intent.action.VIEW".into(),
        "-d".into(),
        url,
        "-t".into(),
        mime.into(),
    ];
    if let Some(package) = selected {
        argv.extend(["-p".into(), package.into()]);
    }
    let result = run_command("am", &argv).await;
    let output = match result {
        Ok(output) if output.lines().any(|line| line.trim() == "Status: ok") => output,
        other => {
            if let Some(id) = &id {
                let _ = crate::media_resources::revoke(id);
            }
            bail!(
                "player launch failed: {}",
                other
                    .map(|v| v.chars().take(256).collect::<String>())
                    .unwrap_or_else(|e| e.to_string())
            );
        }
    };
    Ok(json!({"root":root,"path":path,"mimeType":mime,"transport":transport,"package":selected,"launched":true,"playbackVerified":false,"resourceId":id,"output":output}).to_string())
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn media_types_and_players_are_explicit() {
        assert_eq!(mime("clip.MP4", None).unwrap(), "video/mp4");
        assert_eq!(mime("recording.aac", None).unwrap(), "audio/aac");
        assert_eq!(mime("recording.AAC", None).unwrap(), "audio/aac");
        assert_eq!(mime("recording", Some(&json!("audio/aac"))).unwrap(), "audio/aac");
        assert!(mime("file.apk", None).is_err());
        assert!(mime("file", Some(&json!("text/html"))).is_err());
        assert_eq!(parse_players("1 activities found:\ncom.example.player/.Main\ncom.android.tv.frameworkpackagestubs/.Stub").unwrap(),vec!["com.example.player"]);
        assert!(parse_players("Error: permission denied").is_err());
        assert!(!package("com.player;reboot"));
    }
}
