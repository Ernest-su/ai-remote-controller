use super::state::*;
use anyhow::{bail, Context, Result};
use axum::{
    body::{Body, Bytes},
    extract::Path,
    http::{header, StatusCode},
    response::{IntoResponse, Response},
    Json,
};
use futures_util::StreamExt;
use sha2::{Digest, Sha256};
use std::{
    io::{Seek, SeekFrom},
    os::fd::AsRawFd,
    sync::atomic::Ordering,
};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
pub async fn download(Path(id): Path<String>) -> Response {
    let result = (|| -> Result<_> {
        let task = lookup(&id)?;
        if task.direction != "download"
            || !matches!(task.phase.lock().unwrap().as_str(), "ready" | "completed")
        {
            bail!("download is not ready");
        }
        cancelled(&task)?;
        let guard = claim(task.clone())?;
        let mut file = task
            .file
            .lock()
            .unwrap()
            .as_ref()
            .context("file unavailable")?
            .try_clone()?;
        file.seek(SeekFrom::Start(0))?;
        task.bytes.store(0, Ordering::Relaxed);
        task.phase(if task.size == 0 {
            "completed"
        } else {
            "downloading"
        });
        Ok((task, tokio::fs::File::from_std(file), guard))
    })();
    let (task, file, guard) = match result {
        Ok(value) => value,
        Err(error) => return (StatusCode::CONFLICT, error.to_string()).into_response(),
    };
    let length = task.size;
    let stream = futures_util::stream::try_unfold(
        (file, task, guard, Sha256::new(), 0u64),
        |(mut file, task, guard, mut digest, count)| async move {
            let result = async {
                cancelled(&task)?;
                let mut buffer = vec![0; CHUNK];
                let read = file.read(&mut buffer).await?;
                if read == 0 {
                    let actual = format!("{:x}", digest.clone().finalize());
                    if count != task.size || Some(actual) != *task.digest.lock().unwrap() {
                        bail!("file changed during streaming");
                    }
                    task.phase("completed");
                    return Ok(None);
                }
                if count + read as u64 > task.size {
                    bail!("file grew during streaming");
                }
                buffer.truncate(read);
                digest.update(&buffer);
                let count = count + read as u64;
                task.bytes.store(count, Ordering::Relaxed);
                if count == task.size {
                    if Some(format!("{:x}", digest.clone().finalize()))
                        != *task.digest.lock().unwrap()
                        || file.metadata().await?.len() != task.size
                    {
                        bail!("file changed during streaming");
                    }
                    task.phase("completed");
                }
                Ok(Some((
                    Bytes::from(buffer),
                    (file, task.clone(), guard, digest, count),
                )))
            }
            .await;
            result.map_err(|error: anyhow::Error| {
                task.failed(error.to_string());
                std::io::Error::other(error.to_string())
            })
        },
    );
    (
        [
            (header::CONTENT_TYPE, "application/octet-stream".to_owned()),
            (header::CONTENT_LENGTH, length.to_string()),
            (header::CACHE_CONTROL, "no-store".into()),
            (
                header::CONTENT_DISPOSITION,
                "attachment; filename=file.bin".into(),
            ),
            (header::X_CONTENT_TYPE_OPTIONS, "nosniff".into()),
        ],
        Body::from_stream(stream),
    )
        .into_response()
}

pub async fn upload(Path(id): Path<String>, body: Body) -> Response {
    let task = match lookup(&id) {
        Ok(task) => task,
        Err(error) => return (StatusCode::NOT_FOUND, error.to_string()).into_response(),
    };
    if task.direction != "upload" || task.phase.lock().unwrap().as_str() != "awaiting_upload" {
        return (StatusCode::CONFLICT, "upload task not awaiting data").into_response();
    }
    let _guard = match claim(task.clone()) {
        Ok(guard) => guard,
        Err(error) => return (StatusCode::CONFLICT, error.to_string()).into_response(),
    };
    task.phase("uploading");
    let result = async {
        let file = task
            .file
            .lock()
            .unwrap()
            .take()
            .context("upload file unavailable")?;
        let mut file = tokio::fs::File::from_std(file);
        let mut stream = body.into_data_stream();
        let mut digest = Sha256::new();
        let mut count = 0;
        loop {
            cancelled(&task)?;
            let next = tokio::select! { chunk=stream.next()=>chunk, _=task.wake.notified()=>{ bail!("upload cancelled"); } };
            let Some(chunk) = next else { break };
            cancelled(&task)?;
            let chunk = chunk?;
            count += chunk.len() as u64;
            if count > task.size {
                bail!("upload exceeds declared size");
            }
            for bytes in chunk.chunks(CHUNK) {
                cancelled(&task)?;
                file.write_all(bytes).await?;
                digest.update(bytes);
            }
            task.bytes.store(count, Ordering::Relaxed);
        }
        cancelled(&task)?;
        if count != task.size
            || Some(format!("{:x}", digest.finalize())) != *task.digest.lock().unwrap()
        {
            bail!("upload size or SHA-256 mismatch");
        }
        file.flush().await?;
        file.sync_all().await?;
        drop(file);
        {
            let mut phase=task.phase.lock().unwrap(); cancelled(&task)?; *phase="committing".into();
        }
        {
            let mut slot = task.destination.lock().unwrap();
            let target = slot.as_mut().context("upload cancelled")?;
            cancelled(&task)?;
            if target.overwrite {
                let current=crate::file_actions::metadata(&target.parent,&target.destination)?;
                if !crate::file_actions::same_optional(target.expected.as_ref(),current.as_ref()) { bail!("target changed after overwrite approval; request a new approval"); }
            }
            let result = unsafe {
                libc::syscall(
                    libc::SYS_renameat2,
                    target.parent.as_raw_fd(),
                    target.temporary.as_ptr(),
                    target.parent.as_raw_fd(),
                    target.destination.as_ptr(),
                    if target.overwrite {0}else{libc::RENAME_NOREPLACE},
                )
            };
            if result != 0 {
                return Err(std::io::Error::last_os_error().into());
            }
            target.committed = true;
        }
        task.phase("completed");
        Ok::<_, anyhow::Error>(())
    }
    .await;
    match result {
        Ok(()) => Json(task.progress()).into_response(),
        Err(error) => {
            task.failed(error.to_string());
            (StatusCode::BAD_REQUEST, Json(task.progress())).into_response()
        }
    }
}

pub async fn cancel(Path(id): Path<String>) -> Response {
    match super::execute("task.cancel", &serde_json::json!({"id":id})).await {
        Ok(value) => (
            StatusCode::OK,
            [(header::CONTENT_TYPE, "application/json")],
            value,
        )
            .into_response(),
        Err(error) => (StatusCode::NOT_FOUND, error.to_string()).into_response(),
    }
}
