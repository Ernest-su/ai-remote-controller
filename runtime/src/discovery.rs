use anyhow::Result;
use mdns_sd::{IfKind, ServiceDaemon, ServiceInfo};

pub struct Advertisement {
    daemon: ServiceDaemon,
    fullname: String,
}
impl Advertisement {
    pub fn publish(id: &str, name: &str, port: u16) -> Result<Self> {
        let daemon = ServiceDaemon::new()?;
        // Advertise only addresses reachable by the IPv4 HTTP/WebSocket listener.
        daemon.disable_interface(IfKind::IPv6)?;
        let properties = [
            ("id", id),
            ("name", name),
            ("protocol", "1"),
            ("version", env!("CARGO_PKG_VERSION")),
            ("path", "/ws"),
        ];
        let service = ServiceInfo::new(
            "_airc._tcp.local.",
            &format!("AI Remote {}", &id[..8]),
            &format!("airc-{id}.local."),
            "",
            port,
            &properties[..],
        )?
        .enable_addr_auto();
        let fullname = service.get_fullname().to_owned();
        let monitor = daemon.monitor()?;
        std::thread::spawn(move || {
            while let Ok(event) = monitor.recv() {
                if let mdns_sd::DaemonEvent::Error(error) = event {
                    eprintln!("mDNS: {error}");
                }
            }
        });
        daemon.register(service)?;
        Ok(Self { daemon, fullname })
    }
}
impl Drop for Advertisement {
    fn drop(&mut self) {
        if let Ok(reply) = self.daemon.unregister(&self.fullname) {
            let _ = reply.recv_timeout(std::time::Duration::from_secs(1));
        }
        let _ = self.daemon.shutdown();
    }
}
