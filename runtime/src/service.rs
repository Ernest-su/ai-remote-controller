use crate::config::{prepare_directory, private_write};
use anyhow::{bail, Context, Result};
use serde::{Deserialize, Serialize};
use serde_json::json;
use std::{
    fs,
    io::{Read, Write},
    net::{SocketAddr, TcpStream},
    os::unix::{fs::OpenOptionsExt, io::AsRawFd, process::CommandExt},
    path::{Path, PathBuf},
    process::{Command, Stdio},
    time::{Duration, Instant},
};

pub struct Lock(fs::File);
impl Lock {
    pub fn acquire(path: &Path) -> Result<Self> {
        let file = fs::OpenOptions::new()
            .read(true)
            .write(true)
            .create(true)
            .truncate(false)
            .mode(0o600)
            .custom_flags(libc::O_NOFOLLOW)
            .open(path)?;
        if unsafe { libc::flock(file.as_raw_fd(), libc::LOCK_EX | libc::LOCK_NB) } != 0 {
            bail!("service lifecycle is busy or the service is already running");
        }
        Ok(Self(file))
    }
}
impl Drop for Lock {
    fn drop(&mut self) {
        unsafe {
            libc::flock(self.0.as_raw_fd(), libc::LOCK_UN);
        }
    }
}

#[derive(Deserialize, Serialize)]
pub struct Record {
    pub pid: u32,
    pub start_ticks: u64,
    pub executable: PathBuf,
    pub port: u16,
    pub device_id: String,
}

fn start_ticks(pid: u32) -> Option<u64> {
    let stat = fs::read_to_string(format!("/proc/{pid}/stat")).ok()?;
    let fields: Vec<&str> = stat.rsplit_once(") ")?.1.split_whitespace().collect();
    if fields.first() == Some(&"Z") {
        return None;
    }
    fields.get(19)?.parse().ok()
}
impl Record {
    pub fn owned(&self) -> bool {
        start_ticks(self.pid) == Some(self.start_ticks)
            && fs::read_link(format!("/proc/{}/exe", self.pid))
                .ok()
                .as_ref()
                == Some(&self.executable)
    }
    pub fn read(dir: &Path) -> Result<Option<Self>> {
        let path = dir.join("service.json");
        match fs::read(path) {
            Ok(bytes) => Ok(Some(
                serde_json::from_slice(&bytes).context("invalid service state")?,
            )),
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(None),
            Err(error) => Err(error.into()),
        }
    }
    pub fn save(dir: &Path, port: u16, device_id: String) -> Result<Self> {
        let pid = std::process::id();
        let record = Self {
            pid,
            start_ticks: start_ticks(pid).context("cannot inspect own process")?,
            executable: std::env::current_exe()?,
            port,
            device_id,
        };
        private_write(&dir.join("service.json"), &serde_json::to_vec(&record)?)?;
        Ok(record)
    }
}

fn ready(record: &Record) -> bool {
    let address = SocketAddr::from(([127, 0, 0, 1], record.port));
    let result = (|| -> Result<bool> {
        let mut stream = TcpStream::connect_timeout(&address, Duration::from_millis(300))?;
        stream.set_read_timeout(Some(Duration::from_millis(300)))?;
        stream.set_write_timeout(Some(Duration::from_millis(300)))?;
        stream
            .write_all(b"GET /status HTTP/1.0\r\nHost: localhost\r\nConnection: close\r\n\r\n")?;
        let mut body = String::new();
        stream.take(64 * 1024).read_to_string(&mut body)?;
        let payload = body
            .split_once("\r\n\r\n")
            .context("invalid status response")?
            .1;
        let status: serde_json::Value = serde_json::from_str(payload)?;
        Ok(status["deviceId"].as_str() == Some(&record.device_id) && status["protocol"] == 1)
    })();
    result.unwrap_or(false)
}

pub fn control(action: &str, dir: &Path, port: u16) -> Result<()> {
    prepare_directory(dir)?;
    let _control = Lock::acquire(&dir.join("control.lock"))?;
    let existing = Record::read(dir)?.filter(Record::owned);
    match action {
        "status" => {
            println!(
                "{}",
                match existing {
                    Some(record) =>
                        json!({"running":true,"ready":ready(&record),"pid":record.pid,"port":record.port,"deviceId":record.device_id}),
                    None => json!({"running":false}),
                }
            );
        }
        "stop" => {
            if let Some(record) = existing {
                if unsafe { libc::kill(record.pid as i32, libc::SIGTERM) } != 0 {
                    bail!(
                        "cannot stop owned service: {}",
                        std::io::Error::last_os_error()
                    );
                }
                let deadline = Instant::now() + Duration::from_secs(5);
                while record.owned() && Instant::now() < deadline {
                    std::thread::sleep(Duration::from_millis(50));
                }
                if record.owned() {
                    bail!("service has not stopped; retry status before updating");
                }
            }
            let _ = fs::remove_file(dir.join("service.json"));
            println!("{}", json!({"running":false}));
        }
        "start" => {
            if let Some(record) = existing {
                if !ready(&record) {
                    bail!("existing service is not healthy; stop it before restarting");
                }
                println!(
                    "{}",
                    json!({"running":true,"ready":true,"pid":record.pid,"port":record.port})
                );
                return Ok(());
            }
            let log = fs::OpenOptions::new()
                .append(true)
                .create(true)
                .mode(0o600)
                .custom_flags(libc::O_NOFOLLOW)
                .open(dir.join("runtime.log"))?;
            let mut command = Command::new(std::env::current_exe()?);
            command
                .arg("serve")
                .arg("--state-dir")
                .arg(dir)
                .arg("--port")
                .arg(port.to_string())
                .stdin(Stdio::null())
                .stdout(log.try_clone()?)
                .stderr(log);
            unsafe {
                command.pre_exec(|| {
                    if libc::setsid() < 0 {
                        return Err(std::io::Error::last_os_error());
                    }
                    Ok(())
                });
            }
            let mut child = command.spawn()?;
            let deadline = Instant::now() + Duration::from_secs(12);
            while Instant::now() < deadline {
                if let Some(status) = child.try_wait()? {
                    bail!("service failed to start ({status}); inspect runtime.log");
                }
                if let Some(record) = Record::read(dir)?.filter(Record::owned) {
                    if record.pid == child.id() && ready(&record) {
                        println!(
                            "{}",
                            json!({"running":true,"ready":true,"pid":record.pid,"port":record.port})
                        );
                        return Ok(());
                    }
                }
                std::thread::sleep(Duration::from_millis(100));
            }
            let _ = child.kill();
            let _ = child.wait();
            bail!("service readiness timed out; inspect runtime.log");
        }
        _ => bail!("unknown service operation: {action}"),
    }
    Ok(())
}

pub struct StateGuard {
    dir: PathBuf,
    pid: u32,
}
impl StateGuard {
    pub fn new(dir: PathBuf) -> Self {
        Self {
            dir,
            pid: std::process::id(),
        }
    }
}
impl Drop for StateGuard {
    fn drop(&mut self) {
        if Record::read(&self.dir)
            .ok()
            .flatten()
            .is_some_and(|record| record.pid == self.pid)
        {
            let _ = fs::remove_file(self.dir.join("service.json"));
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn never_owns_reused_pid() {
        let record = Record {
            pid: std::process::id(),
            start_ticks: 0,
            executable: std::env::current_exe().unwrap(),
            port: 1234,
            device_id: "test".into(),
        };
        assert!(!record.owned());
        assert!(start_ticks(std::process::id()).is_some());
    }
}
