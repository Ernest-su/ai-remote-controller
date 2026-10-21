//! Bounded chunks allow explicit resumption while the target service keeps its transfer task.
use super::state::*;
use anyhow::{bail, Context, Result};
use axum::{
    body::Body,
    extract::Path,
    http::{header, HeaderMap, StatusCode},
    response::{IntoResponse, Response},
    Json,
};
use futures_util::StreamExt;
use sha2::{Digest, Sha256};
use std::{
    io::{Read, Seek, SeekFrom},
    os::fd::AsRawFd,
    sync::atomic::Ordering,
};
use tokio::io::{AsyncReadExt, AsyncSeekExt, AsyncWriteExt};
const LIMIT: u64 = 4 * 1024 * 1024;
fn range(headers: &HeaderMap, size: u64) -> Result<(u64, u64)> {
    let value = headers
        .get(header::RANGE)
        .context("Range required")?
        .to_str()?
        .strip_prefix("bytes=")
        .context("invalid Range")?;
    let (start, end) = value.split_once('-').context("explicit range required")?;
    let start: u64 = start.parse()?;
    let end: u64 = end.parse()?;
    if start > end || end >= size || end - start >= LIMIT {
        bail!("range outside file or exceeds 4 MiB");
    }
    Ok((start, end))
}
pub async fn download(Path(id): Path<String>, headers: HeaderMap) -> Response {
    match lookup(&id) {
        Ok(task) => download_for(task, headers).await,
        Err(error) => (StatusCode::NOT_FOUND, error.to_string()).into_response(),
    }
}
async fn download_for(task: std::sync::Arc<Transfer>, headers: HeaderMap) -> Response {
    let result = (|| -> Result<_> {
        cancelled(&task)?;
        if task.direction != "download"
            || !matches!(task.phase.lock().unwrap().as_str(), "ready" | "completed")
        {
            bail!("download not ready");
        }
        let (start, end) = range(&headers, task.size)?;
        let guard = claim(task.clone())?;
        let mut file = task
            .file
            .lock()
            .unwrap()
            .as_ref()
            .context("file missing")?
            .try_clone()?;
        file.seek(SeekFrom::Start(start))?;
        if file.metadata()?.len() != task.size {
            bail!("source size changed");
        }
        Ok((task, file, guard, start, end))
    })();
    let (task, file, guard, start, end) = match result {
        Ok(v) => v,
        Err(e) => return (StatusCode::CONFLICT, e.to_string()).into_response(),
    };
    let size = task.size;
    let length = end - start + 1;
    let stream = futures_util::stream::try_unfold(
        (tokio::fs::File::from_std(file), task, guard, length),
        |(mut file, task, guard, left)| async move {
            if left == 0 {
                return Ok::<_, std::io::Error>(None);
            }
            if let Err(error) = cancelled(&task) {
                return Err(std::io::Error::other(error.to_string()));
            }
            let mut bytes = vec![0; left.min(CHUNK as u64) as usize];
            file.read_exact(&mut bytes).await?;
            Ok(Some((
                bytes,
                (file, task, guard, left - (left.min(CHUNK as u64))),
            )))
        },
    );
    (
        StatusCode::PARTIAL_CONTENT,
        [
            (header::CONTENT_TYPE, "application/octet-stream".to_string()),
            (header::CONTENT_LENGTH, length.to_string()),
            (header::CONTENT_RANGE, format!("bytes {start}-{end}/{size}")),
            (header::CACHE_CONTROL, "no-store".into()),
        ],
        Body::from_stream(stream),
    )
        .into_response()
}
pub async fn upload(Path(id): Path<String>, headers: HeaderMap, body: Body) -> Response {
    match lookup(&id) {
        Ok(task) => upload_for(task, headers, body).await,
        Err(error) => (StatusCode::NOT_FOUND, error.to_string()).into_response(),
    }
}
async fn upload_for(task: std::sync::Arc<Transfer>, headers: HeaderMap, body: Body) -> Response {
    let setup = (|| -> Result<_> {
        cancelled(&task)?;
        if task.direction != "upload" || task.phase.lock().unwrap().as_str() != "awaiting_upload" {
            bail!("upload not ready");
        }
        let (start, end) = if task.size == 0 {
            (0, 0)
        } else {
            range(&headers, task.size)?
        };
        let guard = claim(task.clone())?;
        if task.bytes.load(Ordering::Relaxed) != start {
            bail!("upload offset changed; query task.progress");
        }
        let file = task
            .file
            .lock()
            .unwrap()
            .as_ref()
            .context("upload file unavailable")?
            .try_clone()?;
        Ok((task, file, guard, start, end))
    })();
    let (task, file, _guard, start, end) = match setup {
        Ok(v) => v,
        Err(e) => return (StatusCode::CONFLICT, e.to_string()).into_response(),
    };
    let mut file = tokio::fs::File::from_std(file);
    let result = async {
        file.seek(SeekFrom::Start(start)).await?;
        let expected = if task.size == 0 { 0 } else { end - start + 1 };
        let mut received = 0u64;
        let mut stream = body.into_data_stream();
        while let Some(chunk) =
            tokio::select! {v=stream.next()=>v,_=task.wake.notified()=>{bail!("upload cancelled");}}
        {
            cancelled(&task)?;
            let chunk = chunk?;
            received += chunk.len() as u64;
            if received > expected {
                bail!("chunk exceeds declared range");
            }
            for bytes in chunk.chunks(CHUNK) {
                file.write_all(bytes).await?;
            }
        }
        if received != expected {
            bail!("chunk incomplete; resume from last acknowledged offset");
        }
        file.flush().await?;
        file.sync_all().await?;
        cancelled(&task)?;
        Ok::<_, anyhow::Error>(start + received)
    }
    .await;
    let count = match result {
        Ok(n) => n,
        Err(e) => {
            let _ = file.set_len(start).await;
            let _ = file.sync_all().await;
            if task.cancelled.load(Ordering::Relaxed) {
                task.failed(e.to_string());
            }
            return (StatusCode::BAD_REQUEST, e.to_string()).into_response();
        }
    };
    task.bytes.store(count, Ordering::Relaxed);
    task.phase("awaiting_upload");
    if count < task.size {
        return Json(task.progress()).into_response();
    }
    task.phase("committing");
    let file = file.into_std().await;
    let task_copy = task.clone();
    let commit = tokio::task::spawn_blocking(move || -> Result<Option<UploadTarget>> {
        cancelled(&task_copy)?;
        let mut file = file;
        file.seek(SeekFrom::Start(0))?;
        let mut hash = Sha256::new();
        let mut buffer = vec![0; CHUNK];
        loop {
            cancelled(&task_copy)?;
            let n = file.read(&mut buffer)?;
            if n == 0 {
                break;
            }
            hash.update(&buffer[..n]);
        }
        if Some(format!("{:x}", hash.finalize())) != *task_copy.digest.lock().unwrap() {
            bail!("source SHA-256 changed; restart transfer");
        }
        let mut slot = task_copy.destination.lock().unwrap();
        let dest = slot.as_mut().context("destination missing")?;
        if dest.overwrite {
            bail!("resumable overwrite is not supported; use the confirmed single-file upload");
        }
        cancelled(&task_copy)?;
        if dest.copy_commit {
            return Ok(slot.take());
        }
        if unsafe {
            libc::syscall(
                libc::SYS_renameat2,
                dest.parent.as_raw_fd(),
                dest.temporary.as_ptr(),
                dest.parent.as_raw_fd(),
                dest.destination.as_ptr(),
                libc::RENAME_NOREPLACE,
            )
        } != 0
        {
            return Err(std::io::Error::last_os_error().into());
        }
        dest.committed = true;
        task_copy.phase("completed");
        Ok(None)
    });
    let result = match commit.await {
        Ok(Ok(Some(target))) => {
            let digest = task
                .digest
                .lock()
                .unwrap()
                .clone()
                .context("upload digest missing");
            match digest {
                Ok(digest) => super::commit::copy_to_exclusive(target, task.size, &digest).await,
                Err(error) => Err(error),
            }
        }
        Ok(Ok(None)) => Ok(()),
        Ok(Err(error)) => Err(error),
        Err(error) => Err(error.into()),
    };
    match result {
        Ok(()) => {
            task.file.lock().unwrap().take();
            task.phase("completed");
            Json(task.progress()).into_response()
        }
        Err(error) => {
            task.file.lock().unwrap().take();
            let error = error.to_string();
            task.failed(error);
            (StatusCode::BAD_REQUEST, Json(task.progress())).into_response()
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    fn headers(start: u64, end: u64) -> HeaderMap {
        let mut headers = HeaderMap::new();
        headers.insert(
            header::RANGE,
            format!("bytes={start}-{end}").parse().unwrap(),
        );
        headers
    }
    #[tokio::test]
    async fn resumes_only_acknowledged_chunks_and_checks_digest_before_commit() {
        let dir = std::env::temp_dir().join(format!("airc-chunks-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir(&dir).unwrap();
        let hash = format!("{:x}", Sha256::digest(b"abcdefgh"));
        let task = super::super::create_at(
            &json!({"direction":"upload","path":"file.bin","sizeBytes":8,"sha256":hash}),
            &dir,
        )
        .unwrap();
        assert_eq!(
            upload_for(task.clone(), headers(0, 3), Body::from("abcd"))
                .await
                .status(),
            StatusCode::OK
        );
        assert_eq!(task.bytes.load(Ordering::Relaxed), 4);
        assert!(!dir.join("file.bin").exists());
        assert_eq!(
            upload_for(task.clone(), headers(0, 3), Body::from("abcd"))
                .await
                .status(),
            StatusCode::CONFLICT
        );
        assert_eq!(
            upload_for(task.clone(), headers(4, 7), Body::from("ef"))
                .await
                .status(),
            StatusCode::BAD_REQUEST
        );
        assert_eq!(task.bytes.load(Ordering::Relaxed), 4);
        assert_eq!(
            upload_for(task.clone(), headers(4, 7), Body::from("efgh"))
                .await
                .status(),
            StatusCode::OK
        );
        assert_eq!(std::fs::read(dir.join("file.bin")).unwrap(), b"abcdefgh");
        let download_task =
            super::super::create_at(&json!({"direction":"download","path":"file.bin"}), &dir)
                .unwrap();
        download_task.phase("ready");
        let response = download_for(download_task.clone(), headers(4, 7)).await;
        assert_eq!(response.status(), StatusCode::PARTIAL_CONTENT);
        assert_eq!(response.headers()[header::CONTENT_RANGE], "bytes 4-7/8");
        assert_eq!(
            axum::body::to_bytes(response.into_body(), 16)
                .await
                .unwrap()
                .as_ref(),
            b"efgh"
        );
        assert_eq!(
            download_for(download_task.clone(), headers(4, 8))
                .await
                .status(),
            StatusCode::CONFLICT
        );
        let bad = super::super::create_at(
            &json!({"direction":"upload","path":"bad.bin","sizeBytes":1,"sha256":"0".repeat(64)}),
            &dir,
        )
        .unwrap();
        assert_eq!(
            upload_for(bad.clone(), headers(0, 0), Body::from("x"))
                .await
                .status(),
            StatusCode::BAD_REQUEST
        );
        assert!(!dir.join("bad.bin").exists());
        std::fs::remove_dir_all(dir).unwrap();
    }
    #[tokio::test]
    async fn copy_fallback_resumes_then_commits_and_preserves_racing_files() {
        let dir = std::env::temp_dir().join(format!("airc-chunks-copy-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir(&dir).unwrap();
        let task = super::super::tests::copy_upload(&dir, "saved", b"abcdefgh");
        assert_eq!(
            upload_for(task.clone(), headers(0, 3), Body::from("abcd"))
                .await
                .status(),
            StatusCode::OK
        );
        assert!(!dir.join("saved").exists());
        assert_eq!(
            upload_for(task.clone(), headers(4, 7), Body::from("efgh"))
                .await
                .status(),
            StatusCode::OK
        );
        assert_eq!(std::fs::read(dir.join("saved")).unwrap(), b"abcdefgh");
        assert!(task.file.lock().unwrap().is_none());
        let race = super::super::tests::copy_upload(&dir, "race", b"new");
        std::fs::write(dir.join("race"), b"original").unwrap();
        assert_eq!(
            upload_for(race.clone(), headers(0, 2), Body::from("new"))
                .await
                .status(),
            StatusCode::BAD_REQUEST
        );
        assert_eq!(std::fs::read(dir.join("race")).unwrap(), b"original");
        let bad = super::super::tests::copy_upload(&dir, "bad", b"good");
        assert_eq!(
            upload_for(bad.clone(), headers(0, 3), Body::from("bad!"))
                .await
                .status(),
            StatusCode::BAD_REQUEST
        );
        assert!(!dir.join("bad").exists());
        assert_eq!(std::fs::read_dir(&dir).unwrap().count(), 2);
        for task in [task, race, bad] {
            tasks().lock().unwrap().remove(&task.id);
        }
        std::fs::remove_dir_all(dir).unwrap();
    }
}
