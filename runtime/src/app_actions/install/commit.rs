use super::*;
use std::{
    io::{Seek, SeekFrom},
    sync::atomic::{AtomicU64, Ordering},
};

fn success(output: &str) -> Result<()> {
    if !output.lines().any(|line| line.trim() == "Success") {
        bail!(
            "package manager failed: {}",
            output.chars().take(512).collect::<String>()
        );
    }
    Ok(())
}
fn session_id(output: &str) -> Result<u32> {
    let value = output
        .trim()
        .strip_prefix("Success: created install session [")
        .and_then(|v| v.strip_suffix(']'))
        .context("invalid installation session response")?;
    Ok(value.parse()?)
}
pub(super) async fn execute(mut p: Prepared, count: &AtomicU64) -> Result<Value> {
    let total: u64 = p.files.iter().map(|f| f.apk.size_bytes).sum();
    let args = vec![
        "install-create".into(),
        if p.update { "-r".into() } else { "-R".into() },
        "--user".into(),
        p.user_id.to_string(),
        "--pkg".into(),
        p.apk.package.clone(),
        "-S".into(),
        total.to_string(),
    ];
    let id = session_id(&crate::tools::run_command("pm", &args).await?)?;
    let outcome=async {
        let mut bytes=0;
        for (index, staged) in p.files.iter_mut().enumerate() {
            staged.file.seek(SeekFrom::Start(0))?;
            let args=vec!["install-write".into(),"-S".into(),staged.apk.size_bytes.to_string(),id.to_string(),format!("part{index}.apk"),"-".into()];
            let output=crate::tools::run_command_with_input("pm",&args,staged.file.try_clone()?).await?;
            // Android install-write reports "Success: streamed ... bytes".
            if !output.trim().starts_with("Success") { bail!("APK session write failed"); }
            bytes+=staged.apk.size_bytes;count.store(bytes,Ordering::Relaxed);
        }
        success(&crate::tools::run_command("pm",&["install-commit".into(),id.to_string()]).await?)?;
        let installed=super::super::info::read(&p.apk.package).await?;
        if installed.user_id != p.user_id || installed.version_code != p.apk.version_code { bail!("installation identity differs; query application information"); }
        Ok(json!({"package":installed.package,"userId":installed.user_id,"versionCode":installed.version_code,"installed":true,"verifiedBy":"android_package_manager","sha256":p.apk.sha256,"apks":p.files.iter().map(|f| &f.apk).collect::<Vec<_>>()}))
    }.await;
    if outcome.is_err() {
        let _ = crate::tools::run_command("pm", &["install-abandon".into(), id.to_string()]).await;
    }
    outcome.map_err(|error: anyhow::Error| {
        error.context("query apps.info to confirm final installation state")
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn accepts_only_exact_session_response() {
        assert_eq!(
            session_id("Success: created install session [42]\n").unwrap(),
            42
        );
        for input in [
            "42",
            "Success [42]",
            "Success: created install session [1;reboot]",
            "Success: created install session [-1]",
        ] {
            assert!(session_id(input).is_err());
        }
    }
}
