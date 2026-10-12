use anyhow::{bail, Context, Result};
use serde_json::{json, Value};

fn parts(args: &Value) -> Result<Vec<String>> {
    let object = args.as_object().context("text arguments must be object")?;
    if object.len() != 1 || !object.contains_key("text") {
        bail!("only text accepted");
    }
    let text = args["text"].as_str().context("text must be string")?;
    if text.is_empty() || text.len() > 2048 || !text.bytes().all(|c| (32..=126).contains(&c)) {
        bail!("Android key text supports 1..2048 printable ASCII bytes; Unicode input is not supported");
    }
    // Android input converts %s to a space even without shell interpretation.
    let mut result = Vec::new();
    let mut start = 0;
    for (index, _) in text.match_indices("%s") {
        result.push(text[start..index + 1].to_string());
        start = index + 1;
    }
    result.push(text[start..].to_string());
    if result.len() > 9 {
        bail!("too many literal percent-s sequences; split the input request");
    }
    Ok(result)
}

pub(super) async fn execute(args: &Value) -> Result<String> {
    let values = parts(args)?;
    let length: usize = values.iter().map(String::len).sum();
    tokio::time::timeout(std::time::Duration::from_secs(30), async {
        for value in values {
            super::run_command("input", &["text".into(), value]).await?;
        }
        Ok::<_, anyhow::Error>(())
    })
    .await
    .context("text injection timed out; focused field may contain partial input")??;
    Ok(json!({"submitted":true,"characters":length,"contentVerified":false}).to_string())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn literal_percent_sequences_are_preserved_without_interpreter() {
        let text = "hello %s; $(reboot) %s";
        let result = parts(&json!({"text":text})).unwrap();
        assert_eq!(result.concat(), text);
        assert!(result.iter().all(|value| !value.contains("%s")));
    }
    #[test]
    fn unknown_fields_controls_unicode_and_limits_are_rejected() {
        for value in [
            json!({"text":""}),
            json!({"text":"中文"}),
            json!({"text":"a\nb"}),
            json!({"text":"a","extra":true}),
            json!({"text":"x".repeat(2049)}),
            json!({"text":"%s".repeat(9)}),
        ] {
            assert!(parts(&value).is_err());
        }
    }
}
