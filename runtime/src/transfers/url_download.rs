use super::commit::{copy_to_exclusive, ensure_space};
use super::*;
use serde_json::json;
use tokio::io::AsyncWriteExt;

pub(crate) fn start(args: &Value) -> Result<String> {
    let root = args
        .get("root")
        .and_then(Value::as_str)
        .unwrap_or("downloads");
    start_at(args, &crate::tools::files::root(root)?)
}

fn start_at(args: &Value, root: &std::path::Path) -> Result<String> {
    let object = args.as_object().context("arguments must be object")?;
    if object
        .keys()
        .any(|k| !["url", "root", "directory", "name", "overwriteToken"].contains(&k.as_str()))
    {
        bail!("unknown download argument");
    }
    let url = reqwest::Url::parse(args["url"].as_str().context("url required")?)?;
    validate_url(&url)?;
    let directory = args
        .get("directory")
        .map(|v| v.as_str().context("directory must be string"))
        .transpose()?
        .unwrap_or("");
    let inferred = url
        .path_segments()
        .and_then(|mut p| p.next_back())
        .filter(|p| !p.is_empty())
        .unwrap_or("download.bin");
    let name = args
        .get("name")
        .map(|v| v.as_str().context("name must be string"))
        .transpose()?
        .filter(|s| !s.is_empty())
        .unwrap_or(inferred);
    if name.contains('/')
        || name.is_empty()
        || name == "."
        || name == ".."
        || name.len() > 255
        || name.chars().any(char::is_control)
    {
        bail!("invalid download filename");
    }
    let path = if directory.is_empty() {
        name.to_owned()
    } else {
        format!("{directory}/{name}")
    };
    let root_name = args
        .get("root")
        .map(|v| v.as_str().context("root must be string"))
        .transpose()?
        .unwrap_or("downloads");
    if args.get("overwriteToken").is_none() {
        if let Some(conflict) = name_conflict(root, root_name, &path)? {
            return Ok(json!({"conflict":conflict}).to_string());
        }
    }
    let mut upload_args = json!({"root":root_name,"direction":"upload","path":path,"sizeBytes":0,"sha256":"0".repeat(64)});
    if let Some(token) = args.get("overwriteToken") {
        upload_args["overwriteToken"] = token.clone();
    }
    let mut registry = tasks().lock().unwrap();
    reserve(&mut registry)?;
    // Reuse the anchored temporary file and atomic no-replace commit used by uploads.
    let mut task = create_at_impl(&upload_args, root, true)?;
    let value = Arc::get_mut(&mut task).unwrap();
    value.direction = "url_download".into();
    value.busy.store(true, Ordering::Release);
    value.phase("connecting");
    *value.result.lock().unwrap() = Some(json!({"root":root_name,"path":path,"totalBytes":null}));
    let progress = task.progress().to_string();
    registry.insert(task.id.clone(), task.clone());
    drop(registry);
    let download_root = root.to_path_buf();
    tokio::spawn(async move {
        if let Err(error) = run(&task, url).await {
            if error
                .downcast_ref::<std::io::Error>()
                .is_some_and(|e| e.kind() == std::io::ErrorKind::AlreadyExists)
            {
                if let Ok(Some(conflict)) = name_conflict(&download_root, &task.root, &path) {
                    if let Some(result) = task.result.lock().unwrap().as_mut() {
                        result["conflict"] = conflict;
                    }
                }
            }
            task.failed(error.to_string());
        }
        task.file.lock().unwrap().take();
        task.busy.store(false, Ordering::Release);
    });
    Ok(progress)
}

fn name_conflict(root: &std::path::Path, root_name: &str, path: &str) -> Result<Option<Value>> {
    let (parent, name) = crate::tools::files::parent(root, path)?;
    let Some(existing) = crate::file_actions::metadata(&parent, &name)? else {
        return Ok(None);
    };
    let name = name.to_str()?;
    let (stem, extension) = name
        .rsplit_once('.')
        .filter(|(s, e)| !s.is_empty() && e.len() < 240)
        .map(|(s, e)| (s, format!(".{e}")))
        .unwrap_or((name, String::new()));
    for index in 1..=9999 {
        let suffix = format!(" ({index}){extension}");
        let mut prefix = stem.to_owned();
        while prefix.len() + suffix.len() > 255 {
            prefix.pop();
        }
        let suggested = format!("{prefix}{suffix}");
        let candidate = std::ffi::CString::new(suggested.as_str())?;
        if crate::file_actions::metadata(&parent, &candidate)?.is_none() {
            return Ok(Some(
                json!({"root":root_name,"path":path,"name":name,"suggestedName":suggested,
                "canOverwrite":existing.is_file() && !existing.is_symlink()}),
            ));
        }
    }
    bail!("too many files with the same name; choose another filename");
}

pub(super) fn validate_url(url: &reqwest::Url) -> Result<()> {
    if !matches!(url.scheme(), "http" | "https")
        || url.host_str().is_none()
        || !url.username().is_empty()
        || url.password().is_some()
    {
        bail!("download URL must be HTTP(S) without embedded credentials");
    }
    Ok(())
}

async fn run(task: &Arc<Transfer>, url: reqwest::Url) -> Result<()> {
    let client = reqwest::Client::builder()
        .connect_timeout(Duration::from_secs(20))
        .read_timeout(Duration::from_secs(60))
        .redirect(reqwest::redirect::Policy::custom(|attempt| {
            if attempt.previous().len() >= 10 || validate_url(attempt.url()).is_err() {
                attempt.error("invalid or excessive download redirects")
            } else {
                attempt.follow()
            }
        }))
        .build()?;
    cancelled(task)?;
    let mut response = tokio::select! {
        response=client.get(url).send()=>response?.error_for_status()?,
        _=task.wake.notified()=>bail!("download cancelled"),
    };
    if !response.status().is_success() {
        bail!("download returned HTTP {}", response.status());
    }
    let total = response.content_length();
    if total.is_some_and(|n| n > MAX_FILE) {
        bail!("download exceeds 1 TiB quota");
    }
    if let Some(result) = task.result.lock().unwrap().as_mut() {
        result["totalBytes"] = json!(total);
    }
    let file = task
        .file
        .lock()
        .unwrap()
        .take()
        .context("download file unavailable")?;
    let mut file = tokio::fs::File::from_std(file);
    ensure_space(file.as_raw_fd(), total.unwrap_or(0))?;
    let mut digest = Sha256::new();
    let mut count = 0u64;
    task.phase("downloading");
    loop {
        cancelled(task)?;
        let chunk = tokio::select! {
            chunk=response.chunk()=>chunk?,
            _=task.wake.notified()=>bail!("download cancelled"),
        };
        let Some(chunk) = chunk else { break };
        count += chunk.len() as u64;
        if count > MAX_FILE || total.is_some_and(|n| count > n) {
            bail!("download exceeds declared size or quota");
        }
        ensure_space(file.as_raw_fd(), chunk.len() as u64)?;
        for bytes in chunk.chunks(CHUNK) {
            cancelled(task)?;
            file.write_all(bytes).await?;
            digest.update(bytes);
        }
        task.bytes.store(count, Ordering::Relaxed);
    }
    if total.is_some_and(|n| count != n) {
        bail!("download length mismatch");
    }
    file.flush().await?;
    file.sync_all().await?;
    drop(file);
    {
        let mut phase = task.phase.lock().unwrap();
        cancelled(task)?;
        *phase = "committing".into();
    }
    let digest = format!("{:x}", digest.finalize());
    let copy_commit = task
        .destination
        .lock()
        .unwrap()
        .as_ref()
        .context("download target unavailable")?
        .copy_commit;
    if copy_commit {
        let target = task
            .destination
            .lock()
            .unwrap()
            .take()
            .context("download target unavailable")?;
        copy_to_exclusive(target, count, &digest).await?;
    } else {
        {
            let mut slot = task.destination.lock().unwrap();
            let target = slot.as_mut().context("download target unavailable")?;
            if target.overwrite {
                let current = crate::file_actions::metadata(&target.parent, &target.destination)?;
                if !crate::file_actions::same_optional(target.expected.as_ref(), current.as_ref()) {
                    bail!("target changed after overwrite approval; request a new approval");
                }
            }
            if unsafe {
                libc::syscall(
                    libc::SYS_renameat2,
                    target.parent.as_raw_fd(),
                    target.temporary.as_ptr(),
                    target.parent.as_raw_fd(),
                    target.destination.as_ptr(),
                    if target.overwrite {
                        0
                    } else {
                        libc::RENAME_NOREPLACE
                    },
                )
            } != 0
            {
                return Err(std::io::Error::last_os_error().into());
            }
            target.committed = true;
        }
    }
    *task.digest.lock().unwrap() = Some(digest);
    if let Some(result) = task.result.lock().unwrap().as_mut() {
        result["totalBytes"] = json!(count);
    }
    task.phase("completed");
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    async fn server(response: &'static [u8], slow: bool) -> String {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        tokio::spawn(async move {
            let (mut socket, _) = listener.accept().await.unwrap();
            let mut request = [0; 4096];
            let _ = socket.read(&mut request).await;
            let _ = socket.write_all(response).await;
            if slow {
                tokio::time::sleep(Duration::from_secs(2)).await;
            }
        });
        format!("http://{address}/file.bin")
    }

    async fn finish(id: &str) -> Arc<Transfer> {
        let task = lookup(id).unwrap();
        tokio::time::timeout(Duration::from_secs(5), async {
            while task.busy.load(Ordering::Acquire) {
                tokio::time::sleep(Duration::from_millis(10)).await;
            }
        })
        .await
        .unwrap();
        task
    }

    #[tokio::test]
    async fn exclusive_copy_saves_without_clobbering_and_cleans_corrupt_copies() {
        let root = std::env::temp_dir().join(format!("airc-url-copy-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir(&root).unwrap();
        let digest = format!("{:x}", Sha256::digest(b"data"));
        let make = || {
            let task = create_at_impl(
                &json!({"direction":"upload","path":"saved","sizeBytes":4,"sha256":digest}),
                &root,
                true,
            )
            .unwrap();
            use std::io::Write;
            task.file
                .lock()
                .unwrap()
                .as_mut()
                .unwrap()
                .write_all(b"data")
                .unwrap();
            let target = task.destination.lock().unwrap().take().unwrap();
            target
        };
        copy_to_exclusive(make(), 4, &digest).await.unwrap();
        assert_eq!(std::fs::read(root.join("saved")).unwrap(), b"data");
        assert!(copy_to_exclusive(make(), 4, &digest).await.is_err());
        assert_eq!(std::fs::read(root.join("saved")).unwrap(), b"data");
        std::fs::remove_file(root.join("saved")).unwrap();
        assert!(copy_to_exclusive(make(), 4, &"0".repeat(64)).await.is_err());
        assert_eq!(std::fs::read_dir(&root).unwrap().count(), 0);
        let target = make();
        unsafe {
            assert_eq!(
                libc::unlinkat(target.parent.as_raw_fd(), target.temporary.as_ptr(), 0),
                0
            );
            assert_eq!(
                libc::mkfifoat(target.parent.as_raw_fd(), target.temporary.as_ptr(), 0o600),
                0
            );
        }
        assert!(copy_to_exclusive(target, 4, &digest).await.is_err());
        assert_eq!(std::fs::read_dir(&root).unwrap().count(), 0);
        std::fs::remove_dir_all(root).unwrap();
    }

    #[tokio::test]
    async fn downloads_known_and_unknown_lengths_preserving_existing_files() {
        let root = std::env::temp_dir().join(format!("airc-url-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir(&root).unwrap();
        for (name, response) in [
            (
                "known",
                &b"HTTP/1.1 200 OK\r\nContent-Length: 4\r\nConnection: close\r\n\r\ndata"[..],
            ),
            (
                "unknown",
                &b"HTTP/1.1 200 OK\r\nConnection: close\r\n\r\ndata"[..],
            ),
        ] {
            let url = server(response, false).await;
            let value: Value =
                serde_json::from_str(&start_at(&json!({"url":url,"name":name}), &root).unwrap())
                    .unwrap();
            let task = finish(value["id"].as_str().unwrap()).await;
            assert_eq!(*task.phase.lock().unwrap(), "completed");
            assert_eq!(task.bytes.load(Ordering::Relaxed), 4);
            assert_eq!(
                task.result.lock().unwrap().as_ref().unwrap()["totalBytes"],
                4
            );
            assert_eq!(std::fs::read(root.join(name)).unwrap(), b"data");
            tasks().lock().unwrap().remove(&task.id);
        }
        std::fs::write(root.join("file.bin"), b"original").unwrap();
        let url = server(b"HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\ndata", false).await;
        let value: Value =
            serde_json::from_str(&start_at(&json!({"url":url}), &root).unwrap()).unwrap();
        assert_eq!(value["conflict"]["suggestedName"], "file (1).bin");
        assert_eq!(value["conflict"]["canOverwrite"], true);
        assert_eq!(std::fs::read(root.join("file.bin")).unwrap(), b"original");
        assert_eq!(std::fs::read_dir(&root).unwrap().count(), 3);
        let url = server(
            b"HTTP/1.1 200 OK\r\nContent-Length: 4\r\nConnection: close\r\n\r\ndata",
            false,
        )
        .await;
        let value: Value =
            serde_json::from_str(&start_at(&json!({"url":url,"name":"race.bin"}), &root).unwrap())
                .unwrap();
        std::fs::write(root.join("race.bin"), b"racing original").unwrap();
        let task = finish(value["id"].as_str().unwrap()).await;
        assert_eq!(*task.phase.lock().unwrap(), "failed");
        assert_eq!(
            task.result.lock().unwrap().as_ref().unwrap()["conflict"]["suggestedName"],
            "race (1).bin"
        );
        assert_eq!(
            std::fs::read(root.join("race.bin")).unwrap(),
            b"racing original"
        );
        tasks().lock().unwrap().remove(&task.id);
        std::fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn collisions_suggest_unused_names_and_preserve_links_and_directories() {
        let root = std::env::temp_dir().join(format!("airc-url-conflict-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir(&root).unwrap();
        std::fs::write(root.join("电影.mp4"), b"original").unwrap();
        std::fs::write(root.join("电影 (1).mp4"), b"copy").unwrap();
        let conflict = name_conflict(&root, "inbox", "电影.mp4").unwrap().unwrap();
        assert_eq!(conflict["suggestedName"], "电影 (2).mp4");
        assert_eq!(conflict["canOverwrite"], true);
        std::os::unix::fs::symlink(root.join("电影.mp4"), root.join("link.mp4")).unwrap();
        assert_eq!(
            name_conflict(&root, "inbox", "link.mp4").unwrap().unwrap()["canOverwrite"],
            false
        );
        std::fs::create_dir(root.join("folder")).unwrap();
        assert_eq!(
            name_conflict(&root, "inbox", "folder").unwrap().unwrap()["canOverwrite"],
            false
        );
        let long = format!("{}.mp4", "电".repeat(83));
        std::fs::write(root.join(&long), b"long").unwrap();
        let suggested = name_conflict(&root, "inbox", &long).unwrap().unwrap()["suggestedName"]
            .as_str()
            .unwrap()
            .to_owned();
        assert!(suggested.len() <= 255 && suggested.ends_with(" (1).mp4"));
        assert!(start_at(&json!({"url":"http://localhost/movie","name":"电影.mp4","overwriteToken":"unapproved"}), &root).is_err());
        std::fs::remove_dir_all(root).unwrap();
    }

    #[tokio::test]
    async fn errors_and_cancellation_remove_partial_files_and_reject_unsafe_paths() {
        let root = std::env::temp_dir().join(format!("airc-url-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir(&root).unwrap();
        for args in [
            json!({"url":"file:///etc/passwd"}),
            json!({"url":"http://u:p@localhost/file"}),
            json!({"url":"https://localhost/file","directory":"../escape"}),
            json!({"url":"https://localhost/file","name":"../escape"}),
        ] {
            assert!(start_at(&args, &root).is_err());
        }
        std::os::unix::fs::symlink(&root, root.join("link")).unwrap();
        assert!(start_at(
            &json!({"url":"https://localhost/file","directory":"link"}),
            &root
        )
        .is_err());
        std::fs::remove_file(root.join("link")).unwrap();
        for response in [
            &b"HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n"[..],
            &b"HTTP/1.1 200 OK\r\nContent-Length: 20\r\n\r\nshort"[..],
        ] {
            let url = server(response, false).await;
            let value: Value =
                serde_json::from_str(&start_at(&json!({"url":url}), &root).unwrap()).unwrap();
            let task = finish(value["id"].as_str().unwrap()).await;
            assert_eq!(*task.phase.lock().unwrap(), "failed");
            assert_eq!(std::fs::read_dir(&root).unwrap().count(), 0);
            tasks().lock().unwrap().remove(&task.id);
        }
        let url = server(
            b"HTTP/1.1 200 OK\r\nContent-Length: 200\r\n\r\npartial",
            true,
        )
        .await;
        let value: Value =
            serde_json::from_str(&start_at(&json!({"url":url}), &root).unwrap()).unwrap();
        let id = value["id"].as_str().unwrap();
        tokio::time::timeout(Duration::from_secs(2), async {
            while lookup(id).unwrap().bytes.load(Ordering::Relaxed) == 0 {
                tokio::time::sleep(Duration::from_millis(10)).await;
            }
        })
        .await
        .unwrap();
        execute("task.cancel", &json!({"id":id})).await.unwrap();
        let task = finish(id).await;
        assert_eq!(*task.phase.lock().unwrap(), "cancelled");
        assert_eq!(std::fs::read_dir(&root).unwrap().count(), 0);
        tasks().lock().unwrap().remove(id);
        std::fs::remove_dir_all(root).unwrap();
    }
}
