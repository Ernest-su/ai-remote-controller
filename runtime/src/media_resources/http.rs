use super::*;
use axum::{
    body::Body,
    extract::Path,
    http::{header, HeaderMap, Method, StatusCode},
    response::{IntoResponse, Response},
};
use std::{os::unix::fs::FileExt, sync::atomic::Ordering};
// Unsupported/malformed ranges are ignored; an unsatisfiable valid range gets 416.
fn range(value: Option<&str>, size: u64) -> Result<Option<(u64, u64)>> {
    let Some(value) = value.and_then(|v| v.strip_prefix("bytes=")) else {
        return Ok(None);
    };
    if value.contains(',') {
        return Ok(None);
    }
    let Some((start, end)) = value.split_once('-') else {
        return Ok(None);
    };
    if start.is_empty() {
        let Ok(suffix) = end.parse::<u64>() else {
            return Ok(None);
        };
        if suffix == 0 || size == 0 {
            bail!("unsatisfiable range");
        }
        return Ok(Some((size.saturating_sub(suffix), size - 1)));
    }
    let Ok(start) = start.parse::<u64>() else {
        return Ok(None);
    };
    let end = if end.is_empty() {
        size.saturating_sub(1)
    } else {
        match end.parse::<u64>() {
            Ok(end) if end >= start => end,
            _ => return Ok(None),
        }
    };
    if size == 0 || start >= size {
        bail!("unsatisfiable range");
    }
    Ok(Some((start, end.min(size - 1))))
}
struct Reader(Arc<Resource>);
impl Drop for Reader {
    fn drop(&mut self) {
        self.0.readers.fetch_sub(1, Ordering::Relaxed);
    }
}
pub async fn download(Path(id): Path<String>, method: Method, headers: HeaderMap) -> Response {
    let resource = match lookup(&id) {
        Ok(r) => r,
        Err(_) => return StatusCode::NOT_FOUND.into_response(),
    };
    if !crate::file_actions::same(
        &resource.metadata,
        &match resource.file.metadata() {
            Ok(m) => m,
            Err(_) => return StatusCode::CONFLICT.into_response(),
        },
    ) {
        return (StatusCode::CONFLICT, "media file changed; open it again").into_response();
    }
    let size = resource.metadata.len();
    let selection = if method == Method::HEAD || headers.contains_key(header::IF_RANGE) {
        Ok(None)
    } else {
        range(
            headers.get(header::RANGE).and_then(|v| v.to_str().ok()),
            size,
        )
    };
    let selection = match selection {
        Ok(v) => v,
        Err(_) => {
            return (
                StatusCode::RANGE_NOT_SATISFIABLE,
                [(header::CONTENT_RANGE, format!("bytes */{size}"))],
            )
                .into_response()
        }
    };
    let (start, length) = selection.map(|(a, b)| (a, b - a + 1)).unwrap_or((0, size));
    let body = if method == Method::HEAD || length == 0 {
        Body::empty()
    } else {
        if resource.readers.fetch_add(1, Ordering::Relaxed) >= 4 {
            resource.readers.fetch_sub(1, Ordering::Relaxed);
            return StatusCode::TOO_MANY_REQUESTS.into_response();
        }
        let guard = Reader(resource.clone());
        Body::from_stream(futures_util::stream::try_unfold(
            (resource.clone(), guard, start, length),
            |(resource, guard, offset, remaining)| async move {
                if remaining == 0 {
                    return Ok::<_, std::io::Error>(None);
                }
                let source = resource.clone();
                let amount = remaining.min(64 * 1024) as usize;
                let data = tokio::task::spawn_blocking(move || {
                    if source.revoked.load(Ordering::Relaxed) {
                        return Err(std::io::Error::other("media resource released"));
                    }
                    if !crate::file_actions::same(&source.metadata, &source.file.metadata()?) {
                        return Err(std::io::Error::other("media file changed"));
                    }
                    let mut data = vec![0; amount];
                    let n = source.file.read_at(&mut data, offset)?;
                    if n == 0 {
                        return Err(std::io::Error::new(
                            std::io::ErrorKind::UnexpectedEof,
                            "media truncated",
                        ));
                    }
                    data.truncate(n);
                    Ok(data)
                })
                .await
                .map_err(std::io::Error::other)??;
                *resource.touched.lock().unwrap() = Instant::now();
                let count = data.len() as u64;
                Ok(Some((
                    data,
                    (resource, guard, offset + count, remaining - count),
                )))
            },
        ))
    };
    let mut response = Response::new(body);
    *response.status_mut() = if selection.is_some() {
        StatusCode::PARTIAL_CONTENT
    } else {
        StatusCode::OK
    };
    let h = response.headers_mut();
    h.insert(header::CONTENT_TYPE, resource.mime.parse().unwrap());
    h.insert(header::CONTENT_LENGTH, length.to_string().parse().unwrap());
    h.insert(header::ACCEPT_RANGES, "bytes".parse().unwrap());
    h.insert(header::CACHE_CONTROL, "no-store".parse().unwrap());
    h.insert("X-Content-Type-Options", "nosniff".parse().unwrap());
    if let Some((a, b)) = selection {
        h.insert(
            header::CONTENT_RANGE,
            format!("bytes {a}-{b}/{size}").parse().unwrap(),
        );
    }
    response
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn ranges_cover_suffix_open_ended_and_invalid_requests() {
        assert_eq!(range(Some("bytes=2-4"), 10).unwrap(), Some((2, 4)));
        assert_eq!(range(Some("bytes=8-"), 10).unwrap(), Some((8, 9)));
        assert_eq!(range(Some("bytes=-3"), 10).unwrap(), Some((7, 9)));
        assert_eq!(range(Some("bytes=2-30"), 10).unwrap(), Some((2, 9)));
        assert!(range(Some("bytes=10-"), 10).is_err());
        assert!(range(Some("bytes=0-"), 0).is_err());
        assert_eq!(range(Some("bytes=5-2"), 10).unwrap(), None);
        assert_eq!(range(Some("bytes=0-1,5-6"), 10).unwrap(), None);
    }
}
