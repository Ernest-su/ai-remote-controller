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
        .any(|k| !["url", "root", "directory", "name"].contains(&k.as_str()))
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
    let mut registry = tasks().lock().unwrap();
    reserve(&mut registry)?;
    // Reuse the anchored temporary file and atomic no-replace commit used by uploads.
    let mut task = create_at_impl(
        &json!({"root":root_name,"direction":"upload","path":path,"sizeBytes":0,"sha256":"0".repeat(64)}),
        root,
        true,
    )?;
    let value = Arc::get_mut(&mut task).unwrap();
    value.direction = "url_download".into();
    value.busy.store(true, Ordering::Release);
    value.phase("connecting");
    *value.result.lock().unwrap() = Some(json!({"root":root_name,"path":path,"totalBytes":null}));
    let progress = task.progress().to_string();
    registry.insert(task.id.clone(), task.clone());
    drop(registry);
    tokio::spawn(async move {
        if let Err(error) = run(&task, url).await {
            task.failed(error.to_string());
        }
        task.file.lock().unwrap().take();
        task.busy.store(false, Ordering::Release);
    });
    Ok(progress)
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
            if unsafe {
                libc::syscall(
                    libc::SYS_renameat2,
                    target.parent.as_raw_fd(),
                    target.temporary.as_ptr(),
                    target.parent.as_raw_fd(),
                    target.destination.as_ptr(),
                    libc::RENAME_NOREPLACE,
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

// Android shared-storage FUSE can reject renameat2(RENAME_NOREPLACE). Finish fetching
// into the private temporary file, then copy to an exclusively created destination.
// The destination descriptor never overwrites an existing entry, even under races.
async fn copy_to_exclusive(target: UploadTarget, size: u64, expected_digest: &str) -> Result<()> {
    use std::os::unix::fs::MetadataExt;
    use tokio::io::AsyncReadExt;
    let input_fd = unsafe {
        libc::openat(
            target.parent.as_raw_fd(),
            target.temporary.as_ptr(),
            libc::O_RDONLY
                | libc::O_LARGEFILE
                | libc::O_NOFOLLOW
                | libc::O_NONBLOCK
                | libc::O_CLOEXEC,
        )
    };
    if input_fd < 0 {
        return Err(std::io::Error::last_os_error().into());
    }
    let input = unsafe { File::from_raw_fd(input_fd) };
    if !input.metadata()?.is_file() {
        bail!("download temporary file is no longer a regular file");
    }
    ensure_space(input.as_raw_fd(), size)?;
    let parent = target.parent.try_clone()?;
    let fd = unsafe {
        libc::openat(
            target.parent.as_raw_fd(),
            target.destination.as_ptr(),
            libc::O_WRONLY
                | libc::O_LARGEFILE
                | libc::O_CREAT
                | libc::O_EXCL
                | libc::O_NOFOLLOW
                | libc::O_CLOEXEC,
            0o600,
        )
    };
    if fd < 0 {
        return Err(std::io::Error::last_os_error().into());
    }
    let output = unsafe { File::from_raw_fd(fd) };
    let metadata = output.metadata()?;
    let mut guard = ExclusiveCopy {
        parent,
        name: target.destination.clone(),
        device: metadata.dev(),
        inode: metadata.ino(),
        keep: false,
    };
    let mut input = tokio::fs::File::from_std(input);
    let mut output = tokio::fs::File::from_std(output);
    let mut buffer = vec![0; CHUNK];
    let mut count = 0;
    let mut digest = Sha256::new();
    loop {
        let read = input.read(&mut buffer).await?;
        if read == 0 {
            break;
        }
        count += read as u64;
        if count > size {
            bail!("download temporary file changed before saving");
        }
        output.write_all(&buffer[..read]).await?;
        digest.update(&buffer[..read]);
    }
    if count != size || format!("{:x}", digest.finalize()) != expected_digest {
        bail!("download temporary file changed before saving");
    }
    output.flush().await?;
    output.sync_all().await?;
    if !guard.owned() {
        bail!("download destination changed while saving");
    }
    guard.keep = true;
    // UploadTarget drops here and removes only the temporary file.
    Ok(())
}

struct ExclusiveCopy {
    parent: File,
    name: std::ffi::CString,
    device: u64,
    inode: u64,
    keep: bool,
}
impl ExclusiveCopy {
    fn owned(&self) -> bool {
        use std::os::unix::fs::MetadataExt;
        crate::file_actions::metadata(&self.parent, &self.name)
            .ok()
            .flatten()
            .is_some_and(|m| m.dev() == self.device && m.ino() == self.inode && m.is_file())
    }
}
impl Drop for ExclusiveCopy {
    fn drop(&mut self) {
        if !self.keep && self.owned() {
            unsafe {
                libc::unlinkat(self.parent.as_raw_fd(), self.name.as_ptr(), 0);
            }
        }
    }
}

fn ensure_space(fd: std::os::fd::RawFd, required: u64) -> Result<()> {
    let mut stat = std::mem::MaybeUninit::<libc::statvfs>::uninit();
    if unsafe { libc::fstatvfs(fd, stat.as_mut_ptr()) } != 0 {
        return Err(std::io::Error::last_os_error().into());
    }
    let stat = unsafe { stat.assume_init() };
    // statvfs fields differ between 32-bit Android and the host.
    #[allow(clippy::unnecessary_cast)]
    let available = (stat.f_bavail as u64).saturating_mul(stat.f_frsize as u64);
    if available < required.saturating_add(16 * 1024 * 1024) {
        bail!("insufficient target storage space; 16 MiB reserve required");
    }
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
        let task = finish(value["id"].as_str().unwrap()).await;
        assert_eq!(*task.phase.lock().unwrap(), "failed");
        assert_eq!(std::fs::read(root.join("file.bin")).unwrap(), b"original");
        assert_eq!(std::fs::read_dir(&root).unwrap().count(), 3);
        tasks().lock().unwrap().remove(&task.id);
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
