use anyhow::{bail, Context, Result};
use serde::{Deserialize, Serialize};
use std::{
    fs,
    os::unix::fs::{OpenOptionsExt, PermissionsExt},
    path::Path,
};

#[derive(Clone, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ModelConfig {
    pub base_url: String,
    pub api_key: String,
    pub model: String,
}

impl ModelConfig {
    pub fn validate(&self) -> Result<()> {
        let url = reqwest::Url::parse(&self.base_url).context("invalid model API address")?;
        if !matches!(url.scheme(), "http" | "https")
            || url.host_str().is_none()
            || url.query().is_some()
            || url.fragment().is_some()
            || !url.username().is_empty()
            || url.password().is_some()
        {
            bail!("model API address must be an HTTP(S) base URL without credentials or query");
        }
        if self.api_key.trim().is_empty() || self.model.trim().is_empty() {
            bail!("configure API key and model first");
        }
        Ok(())
    }
    pub fn load(dir: &Path) -> Result<Self> {
        let file = dir.join("model.json");
        if !file.exists() {
            return Ok(Self::default());
        }
        Ok(serde_json::from_slice(&fs::read(file)?)?)
    }
    pub fn save(&self, dir: &Path) -> Result<()> {
        self.validate()?;
        private_write(&dir.join("model.json"), &serde_json::to_vec(self)?)
    }
}

pub fn prepare_directory(dir: &Path) -> Result<()> {
    fs::create_dir_all(dir)?;
    let meta = fs::symlink_metadata(dir)?;
    if !meta.is_dir() || meta.file_type().is_symlink() {
        bail!("state directory must be a real directory");
    }
    fs::set_permissions(dir, fs::Permissions::from_mode(0o700))?;
    Ok(())
}

pub fn private_write(path: &Path, content: &[u8]) -> Result<()> {
    use std::io::Write;
    let temp = path.with_extension(format!("{}.tmp", uuid::Uuid::new_v4()));
    let mut file = fs::OpenOptions::new()
        .write(true)
        .create_new(true)
        .mode(0o600)
        .open(&temp)?;
    file.write_all(content)?;
    file.sync_all()?;
    fs::rename(temp, path)?;
    Ok(())
}

pub fn device_id(dir: &Path) -> Result<String> {
    let file = dir.join("device-id");
    if file.exists() {
        let id = fs::read_to_string(file)?;
        uuid::Uuid::parse_str(id.trim()).context("invalid saved device ID")?;
        return Ok(id.trim().into());
    }
    let id = uuid::Uuid::new_v4().to_string();
    private_write(&file, id.as_bytes())?;
    Ok(id)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn legacy_transcription_setting_is_ignored_and_not_saved() {
        let config: ModelConfig = serde_json::from_str(r#"{"baseUrl":"https://api.test/v1","apiKey":"fixture-key","model":"fixture-model","transcriptionModel":"legacy-asr"}"#).unwrap();
        config.validate().unwrap();
        assert!(serde_json::to_value(config)
            .unwrap()
            .get("transcriptionModel")
            .is_none());
    }
    #[test]
    fn rejects_ambiguous_base_urls() {
        for url in [
            "file:///tmp/key",
            "https://secret@api.test/v1",
            "https://api.test/v1?key=x",
        ] {
            assert!(ModelConfig {
                base_url: url.into(),
                api_key: "x".into(),
                model: "m".into(),
            }
            .validate()
            .is_err());
        }
    }
}
