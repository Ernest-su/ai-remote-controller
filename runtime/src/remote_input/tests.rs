use super::*;
use std::fs;

struct Fixture {
    directory: PathBuf,
    manager: std::sync::Arc<Manager>,
}
impl Fixture {
    fn new(fault: &str) -> Self {
        let directory = std::env::temp_dir().join(format!("airc-input-{}", uuid::Uuid::new_v4()));
        fs::create_dir(&directory).unwrap();
        let script = r#"
import json,os,pathlib,sys,time
root=pathlib.Path(sys.argv[1]);fault=sys.argv[2]
def log(action,code):
 with (root/'journal').open('a') as f: f.write(f'{action}:{code}\n')
if fault=='slow-ready': time.sleep(.15)
print(json.dumps(dict(ready=True,pid=os.getpid(),uid=os.getuid()+(1 if fault=='wrong-uid' else 0))),flush=True)
for line in sys.stdin:
 r=json.loads(line);code=r['code'];operation=r['operation']
 if operation in ('key','down'):
  log('down',code)
  if fault and not (root/'fault').exists():
   (root/'fault').touch()
   if fault=='crash': os._exit(9)
   if fault=='timeout': time.sleep(10)
   if fault=='oversize':
    print('x'*3000,flush=True)
    continue
   if fault=='bad-ack':
    print(json.dumps(dict(id=-1,ok=True,released=True)),flush=True)
    continue
  if code==19: time.sleep(0.1)
  if operation=='key': log('up',code)
 elif operation=='keep': log('keep',code)
 else: log('up',code)
 print(json.dumps(dict(id=r['id'],ok=True,released=operation not in ('down','keep'))),flush=True)
"#;
        let manager = Manager::start(Spec {
            program: "python3".into(),
            args: vec![
                "-u".into(),
                "-c".into(),
                script.into(),
                directory.as_os_str().into(),
                fault.into(),
            ],
            classpath: None,
        });
        Self {
            directory,
            manager: std::sync::Arc::new(manager),
        }
    }
    fn journal(&self) -> Vec<String> {
        fs::read_to_string(self.directory.join("journal"))
            .unwrap_or_default()
            .lines()
            .map(str::to_owned)
            .collect()
    }
    async fn down(&self) {
        tokio::time::timeout(Duration::from_secs(3), async {
            while self.journal().is_empty() {
                tokio::time::sleep(Duration::from_millis(2)).await;
            }
        })
        .await
        .unwrap();
    }
}
impl Drop for Fixture {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.directory);
    }
}

#[tokio::test]
async fn reuses_one_child_preserves_order_and_reaps_it_on_shutdown() {
    let fixture = Fixture::new("");
    fixture.manager.call(None).await.unwrap();
    let mut pid = None;
    for code in [20, 21, 22, 23, 24] {
        let reply: Value =
            serde_json::from_str(&fixture.manager.call(Some(code)).await.unwrap()).unwrap();
        assert_eq!(reply["released"], true);
        if let Some(pid) = pid {
            assert_eq!(reply["bridgePid"].as_u64(), Some(pid));
        }
        pid = reply["bridgePid"].as_u64();
    }
    assert_eq!(
        fixture.journal(),
        [
            "down:20", "up:20", "down:21", "up:21", "down:22", "up:22", "down:23", "up:23",
            "down:24", "up:24"
        ]
    );
    fixture.manager.stop().await;
    assert_eq!(unsafe { libc::kill(pid.unwrap() as i32, 0) }, -1);
    assert!(fixture.manager.call(Some(20)).await.is_err());
}

#[tokio::test]
async fn cancellation_completes_started_pair_and_discards_unstarted_keys() {
    let fixture = Fixture::new("");
    let manager = fixture.manager.clone();
    let running = tokio::spawn(async move { manager.call(Some(19)).await });
    fixture.down().await;
    let (reply, canceled) = oneshot::channel();
    fixture
        .manager
        .sender
        .try_send(Work {
            kind: WorkKind::Tap(20),
            reply,
        })
        .unwrap();
    drop(canceled);
    running.abort();
    let _ = running.await;
    fixture.manager.call(None).await.unwrap();
    assert_eq!(fixture.journal(), ["down:19", "up:19"]);
    fixture.manager.stop().await;
}

#[tokio::test]
async fn cancellation_during_startup_never_injects_a_late_down() {
    let fixture = Fixture::new("slow-ready");
    let manager = fixture.manager.clone();
    let running = tokio::spawn(async move { manager.call(Some(20)).await });
    tokio::time::sleep(Duration::from_millis(50)).await;
    running.abort();
    let _ = running.await;
    fixture.manager.call(None).await.unwrap();
    assert!(fixture.journal().is_empty());
    fixture.manager.stop().await;
}

#[tokio::test]
async fn shutdown_finishes_the_inflight_pair_before_reaping_child() {
    let fixture = Fixture::new("");
    let manager = fixture.manager.clone();
    let running = tokio::spawn(async move { manager.call(Some(19)).await });
    fixture.down().await;
    fixture.manager.stop().await;
    running.await.unwrap().unwrap();
    assert_eq!(fixture.journal(), ["down:19", "up:19"]);
}

#[tokio::test]
async fn crash_timeout_and_bad_ack_release_without_replaying_down() {
    for fault in ["crash", "timeout", "bad-ack", "oversize"] {
        let fixture = Fixture::new(fault);
        assert!(fixture.manager.call(Some(20)).await.is_err(), "{fault}");
        fixture.manager.call(Some(21)).await.unwrap();
        assert_eq!(
            fixture.journal(),
            ["down:20", "up:20", "down:21", "up:21"],
            "{fault}"
        );
        fixture.manager.stop().await;
    }
}

#[tokio::test]
async fn rejects_helper_identity_mismatch_before_any_input() {
    let fixture = Fixture::new("wrong-uid");
    assert!(fixture
        .manager
        .call(Some(20))
        .await
        .unwrap_err()
        .to_string()
        .contains("identity/readiness mismatch"));
    assert!(fixture.journal().is_empty());
    fixture.manager.stop().await;
}

#[tokio::test]
async fn queue_is_bounded_and_excess_requests_are_rejected() {
    let fixture = Fixture::new("");
    let manager = fixture.manager.clone();
    let running = tokio::spawn(async move { manager.call(Some(19)).await });
    fixture.down().await;
    let mut pending = Vec::new();
    for _ in 0..16 {
        let (reply, received) = oneshot::channel();
        fixture
            .manager
            .sender
            .try_send(Work {
                kind: WorkKind::Tap(20),
                reply,
            })
            .unwrap();
        pending.push(received);
    }
    assert_eq!(
        fixture
            .manager
            .call(Some(20))
            .await
            .unwrap_err()
            .to_string(),
        BUSY
    );
    // Cancel queued requests; none of them may inject a DOWN later.
    drop(pending);
    running.await.unwrap().unwrap();
    fixture.manager.call(None).await.unwrap();
    assert_eq!(fixture.journal(), ["down:19", "up:19"]);
    fixture.manager.stop().await;
}

fn hold(owner: &str, id: &str, code: u32, action: &str) -> WorkKind {
    WorkKind::Hold(Hold {
        owner: owner.into(),
        press_id: id.into(),
        code,
        action: action.into(),
    })
}
#[tokio::test]
async fn holds_preserve_identity_ownership_and_only_repeat_via_the_bridge() {
    let f = Fixture::new("");
    f.manager
        .submit(hold("a", "first", 82, "down"))
        .await
        .unwrap();
    f.manager
        .submit(hold("a", "first", 82, "keep"))
        .await
        .unwrap();
    assert!(f
        .manager
        .submit(hold("b", "first", 82, "cancel"))
        .await
        .is_err());
    assert!(f.manager.call(Some(82)).await.is_err());
    f.manager
        .submit(hold("a", "first", 82, "up"))
        .await
        .unwrap();
    f.manager
        .submit(hold("a", "second", 82, "down"))
        .await
        .unwrap();
    assert!(f
        .manager
        .submit(hold("a", "first", 82, "up"))
        .await
        .is_err());
    f.manager
        .submit(hold("a", "second", 82, "up"))
        .await
        .unwrap();
    assert_eq!(
        f.journal(),
        ["down:82", "keep:82", "up:82", "down:82", "up:82"]
    );
    f.manager.stop().await;
}
#[tokio::test]
async fn disconnect_releases_only_owner_and_deadman_does_not_replay_down() {
    let f = Fixture::new("");
    f.manager
        .submit(hold("a", "first", 82, "down"))
        .await
        .unwrap();
    f.manager
        .submit(hold("b", "other", 20, "down"))
        .await
        .unwrap();
    f.manager
        .submit(WorkKind::Disconnect("a".into()))
        .await
        .unwrap();
    assert_eq!(f.journal(), ["down:82", "down:20", "up:82"]);
    tokio::time::sleep(Duration::from_millis(1750)).await;
    assert_eq!(f.journal(), ["down:82", "down:20", "up:82", "up:20"]);
    assert!(f
        .manager
        .submit(hold("b", "other", 20, "keep"))
        .await
        .is_err());
    f.manager.stop().await;
}
#[tokio::test]
async fn recovery_before_holds_and_disconnect_do_not_replay_any_down() {
    let f = Fixture::new("bad-ack");
    // Consume the injected one-time failure first, then create multiple holds.
    assert!(f.manager.call(Some(21)).await.is_err());
    f.manager
        .submit(hold("a", "one", 82, "down"))
        .await
        .unwrap();
    f.manager
        .submit(hold("a", "two", 20, "down"))
        .await
        .unwrap();
    // Stopping the actor closes stdin; the actual bridge's EOF cleanup is tested on Android.
    f.manager
        .submit(WorkKind::Disconnect("a".into()))
        .await
        .unwrap();
    assert_eq!(
        f.journal(),
        ["down:21", "up:21", "down:82", "down:20", "up:20", "up:82"]
    );
    f.manager.stop().await;
}

#[tokio::test]
async fn malformed_edges_are_rejected_before_accessing_the_bridge() {
    for (args, expected) in [
        (
            json!({"key":"menu","action":"down","pressId":"bad"}),
            "UUID",
        ),
        (
            json!({"key":"menu","action":"repeat","pressId":uuid::Uuid::new_v4().to_string()}),
            "unsupported remote key action",
        ),
        (json!({"key":"menu","action":"down"}), "require only"),
        (
            json!({"key":"19;reboot","action":"down","pressId":uuid::Uuid::new_v4().to_string()}),
            "unsupported remote key",
        ),
    ] {
        assert!(edge(&args, "owner")
            .await
            .unwrap_err()
            .to_string()
            .contains(expected));
    }
}
