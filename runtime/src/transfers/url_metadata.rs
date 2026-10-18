use super::*;
use reqwest::{header, Response, Url};
use serde_json::json;

fn filename(value: &str) -> Option<String> {
    let value = value.trim();
    (!value.is_empty()
        && value != "."
        && value != ".."
        && value.len() <= 255
        && !value.contains('/')
        && !value.chars().any(char::is_control))
    .then(|| value.to_owned())
}
fn decoded(value: &str) -> Option<String> {
    let mut bytes = Vec::new();
    let mut input = value.bytes();
    while let Some(byte) = input.next() {
        bytes.push(if byte == b'%' {
            let hi = (input.next()? as char).to_digit(16)?;
            let lo = (input.next()? as char).to_digit(16)?;
            (hi * 16 + lo) as u8
        } else {
            byte
        });
    }
    String::from_utf8(bytes).ok()
}
pub(super) fn inferred_name(url: &Url) -> String {
    url.path_segments()
        .and_then(|mut p| p.next_back())
        .and_then(decoded)
        .and_then(|value| filename(&value))
        .unwrap_or_else(|| "download.bin".into())
}
fn disposition_name(value: &str) -> Option<String> {
    let mut parts = Vec::new();
    let mut part = String::new();
    let (mut quoted, mut escaped) = (false, false);
    for c in value.chars() {
        if escaped {
            part.push(c);
            escaped = false;
        } else if c == '\\' && quoted {
            escaped = true;
        } else if c == '"' {
            quoted = !quoted;
        } else if c == ';' && !quoted {
            parts.push(std::mem::take(&mut part));
        } else {
            part.push(c);
        }
    }
    parts.push(part);
    let (mut plain, mut extended) = (None, None);
    for part in parts.into_iter().skip(1) {
        let Some((key, value)) = part.split_once('=') else {
            continue;
        };
        if key.trim().eq_ignore_ascii_case("filename*") {
            let mut fields = value.trim().splitn(3, '\'');
            if fields
                .next()
                .is_some_and(|v| v.eq_ignore_ascii_case("utf-8"))
            {
                fields.next();
                extended = fields.next().and_then(decoded).and_then(|v| filename(&v));
            }
        } else if key.trim().eq_ignore_ascii_case("filename") {
            plain = filename(value);
        }
    }
    extended.or(plain)
}
fn response_name(response: &Response) -> Option<String> {
    response
        .headers()
        .get(header::CONTENT_DISPOSITION)
        .and_then(|v| v.to_str().ok())
        .and_then(disposition_name)
}
fn response_size(response: &Response) -> Option<u64> {
    if response.status() == reqwest::StatusCode::PARTIAL_CONTENT {
        response
            .headers()
            .get(header::CONTENT_RANGE)?
            .to_str()
            .ok()?
            .rsplit_once('/')?
            .1
            .parse()
            .ok()
    } else {
        response
            .headers()
            .get(header::CONTENT_LENGTH)?
            .to_str()
            .ok()?
            .parse()
            .ok()
    }
}

pub(crate) async fn inspect(args: &Value) -> Result<String> {
    let fields = args.as_object().context("arguments must be object")?;
    if fields.len() != 1 {
        bail!("URL metadata accepts only url");
    }
    let value = args["url"]
        .as_str()
        .filter(|s| s.len() <= 8192)
        .context("url required")?;
    let url = Url::parse(value)?;
    super::url_download::validate_url(&url)?;
    let client = reqwest::Client::builder()
        .timeout(Duration::from_secs(10))
        .redirect(reqwest::redirect::Policy::custom(|attempt| {
            if attempt.previous().len() >= 10
                || super::url_download::validate_url(attempt.url()).is_err()
            {
                attempt.error("invalid or excessive download redirects")
            } else {
                attempt.follow()
            }
        }))
        .build()?;
    // A single deadline covers HEAD plus the optional range fallback; no body is consumed or saved.
    tokio::time::timeout(Duration::from_secs(10), async {
        let head = client
            .head(url.clone())
            .header(header::ACCEPT_ENCODING, "identity")
            .send()
            .await?;
        let fallback = matches!(head.status().as_u16(), 405 | 501);
        if !fallback && !head.status().is_success() {
            bail!("download metadata returned HTTP {}", head.status());
        }
        let mut name = response_name(&head);
        let mut size = if fallback { None } else { response_size(&head) };
        let mut final_url = head.url().clone();
        drop(head);
        if fallback || size.is_none() {
            let response = client
                .get(url)
                .header(header::RANGE, "bytes=0-0")
                .header(header::ACCEPT_ENCODING, "identity")
                .send()
                .await;
            match response {
                Ok(response) if response.status().is_success() => {
                    size = response_size(&response);
                    name = response_name(&response).or(name);
                    final_url = response.url().clone();
                }
                Ok(response) if fallback => {
                    response.error_for_status()?;
                    bail!("download metadata unavailable");
                }
                Err(error) if fallback => return Err(error.into()),
                _ => {} // A valid HEAD without a length is still useful.
            }
        }
        Ok(
            json!({"name":name.unwrap_or_else(|| inferred_name(&final_url)),"sizeBytes":size})
                .to_string(),
        )
    })
    .await
    .context("download metadata timed out")?
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    #[test]
    fn names_prefer_utf8_disposition_and_reject_paths_or_control_characters() {
        assert_eq!(
            disposition_name(
                "attachment; filename=plain.bin; filename*=UTF-8''%E7%94%B5%E8%A7%86.apk"
            ),
            Some("电视.apk".into())
        );
        assert_eq!(
            disposition_name("attachment; filename=\"a;b.bin\""),
            Some("a;b.bin".into())
        );
        assert_eq!(disposition_name("attachment; filename=\"../escape\""), None);
        assert_eq!(disposition_name("attachment; filename*=utf-8''a%0Ab"), None);
        assert_eq!(
            inferred_name(&Url::parse("https://example.test/%E7%94%B5%E8%A7%86.apk?q=x").unwrap()),
            "电视.apk"
        );
        assert_eq!(
            inferred_name(&Url::parse("https://example.test/a%2Fb").unwrap()),
            "download.bin"
        );
    }
    #[tokio::test]
    async fn unsupported_head_uses_range_total_without_fetching_the_file() {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        let server = tokio::spawn(async move {
            for (method, reply) in [("HEAD", "HTTP/1.1 405 Method Not Allowed\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"),
                ("GET", "HTTP/1.1 206 Partial Content\r\nContent-Length: 1\r\nContent-Range: bytes 0-0/123456\r\nContent-Disposition: attachment; filename=movie.mp4\r\nConnection: close\r\n\r\nx")] {
                let (mut socket, _) = listener.accept().await.unwrap();
                let mut bytes = [0; 4096];
                let count = socket.read(&mut bytes).await.unwrap();
                let request = String::from_utf8_lossy(&bytes[..count]);
                assert!(request.starts_with(method));
                if method == "GET" { assert!(request.to_lowercase().contains("range: bytes=0-0")); }
                socket.write_all(reply.as_bytes()).await.unwrap();
            }
        });
        let result: Value = serde_json::from_str(
            &inspect(&json!({"url":format!("http://{address}/opaque")}))
                .await
                .unwrap(),
        )
        .unwrap();
        assert_eq!(result["name"], "movie.mp4");
        assert_eq!(result["sizeBytes"], 123456);
        server.await.unwrap();
    }
}
