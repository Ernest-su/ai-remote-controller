use super::*;
use std::collections::BTreeSet;

type InstallArguments = (String, Vec<(String, String)>, bool);
pub(super) fn arguments(args: &Value) -> Result<InstallArguments> {
    let object = args
        .as_object()
        .context("installation arguments must be object")?;
    if object
        .keys()
        .any(|k| !["root", "path", "sha256", "apks", "update"].contains(&k.as_str()))
    {
        bail!("unknown installation argument");
    }
    let root = args
        .get("root")
        .map(|v| v.as_str().context("root must be string"))
        .transpose()?
        .unwrap_or("downloads")
        .to_owned();
    let update = args
        .get("update")
        .map(|v| v.as_bool().context("update must be boolean"))
        .transpose()?
        .unwrap_or(false);
    let entries = if let Some(apks) = args.get("apks") {
        if args.get("path").is_some() || args.get("sha256").is_some() {
            bail!("use apks or path/sha256, not both");
        }
        apks.as_array().context("apks must be array")?.clone()
    } else {
        vec![json!({"path":args["path"],"sha256":args["sha256"]})]
    };
    if entries.is_empty() || entries.len() > 32 {
        bail!("select between 1 and 32 APKs");
    }
    let mut paths = BTreeSet::new();
    let mut inputs = Vec::new();
    for entry in entries {
        let object = entry.as_object().context("APK entry must be object")?;
        if object.len() != 2 {
            bail!("APK entry accepts only path and sha256");
        }
        let path = entry["path"].as_str().context("APK path required")?;
        let digest = entry["sha256"]
            .as_str()
            .context("inspected APK SHA-256 required")?;
        if path.is_empty() || !paths.insert(path.to_owned()) {
            bail!("empty or duplicate APK path");
        }
        if digest.len() != 64
            || !digest
                .bytes()
                .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
        {
            bail!("invalid APK SHA-256");
        }
        inputs.push((path.to_owned(), digest.to_owned()));
    }
    Ok((root, inputs, update))
}

pub(super) fn validate(apks: &[&super::super::apk::ApkInfo]) -> Result<()> {
    let first = apks.first().context("empty APK set")?;
    let mut splits = BTreeSet::new();
    let mut bases = 0;
    for apk in apks {
        if apk.package != first.package || apk.version_code != first.version_code {
            bail!("APK package/version mismatch");
        }
        let split = apk.split.as_deref().unwrap_or("");
        if split.is_empty() {
            bases += 1;
        }
        if !splits.insert(split) {
            bail!("duplicate base or split name");
        }
        // Metadata is not cryptographic verification: PackageManager verifies the session's signatures.
        let certificates = |apk: &super::super::apk::ApkInfo| {
            apk.signatures
                .iter()
                .flat_map(|s| s.certificate_sha256.iter().cloned())
                .collect::<BTreeSet<_>>()
        };
        let expected = certificates(first);
        let actual = certificates(apk);
        if !expected.is_empty() && !actual.is_empty() && expected != actual {
            bail!("APK declared signing certificate mismatch");
        }
    }
    if bases != 1 {
        bail!("APK set must contain exactly one base APK");
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn validates_bounded_explicit_inputs() {
        let hash = "a".repeat(64);
        assert!(arguments(&json!({"path":"base.apk","sha256":hash})).is_ok());
        assert!(arguments(
            &json!({"apks":[{"path":"base.apk","sha256":hash},{"path":"split.apk","sha256":hash}]})
        )
        .is_ok());
        for args in [
            json!({"apks":[]}),
            json!({"apks":[{"path":"base.apk","sha256":hash}],"path":"other.apk"}),
            json!({"apks":[{"path":"base.apk","sha256":hash},{"path":"base.apk","sha256":hash}]}),
            json!({"path":"base.apk","sha256":"bad"}),
            json!({"path":"base.apk","sha256":hash,"update":"true"}),
        ] {
            assert!(arguments(&args).is_err());
        }
    }
    fn apk(path: &str, split: Option<&str>) -> super::super::super::apk::ApkInfo {
        super::super::super::apk::ApkInfo {
            root: "inbox".into(),
            path: path.into(),
            package: "test.app".into(),
            version_code: 1,
            version_name: None,
            split: split.map(String::from),
            size_bytes: 10,
            sha256: "a".repeat(64),
            signatures: vec![],
            signature_verified: false,
        }
    }
    #[test]
    fn rejects_missing_base_duplicates_and_package_version_mismatch() {
        let base = apk("base.apk", None);
        let split = apk("split.apk", Some("config.en"));
        assert!(validate(&[&split, &base]).is_ok());
        assert!(validate(&[&split]).is_err());
        assert!(validate(&[&base, &base]).is_err());
        assert!(validate(&[&base, &split, &split]).is_err());
        let mut wrong = apk("bad.apk", Some("config.fr"));
        wrong.package = "other.app".into();
        assert!(validate(&[&base, &wrong]).is_err());
        wrong.package = base.package.clone();
        wrong.version_code = 2;
        assert!(validate(&[&base, &wrong]).is_err());
    }
}
