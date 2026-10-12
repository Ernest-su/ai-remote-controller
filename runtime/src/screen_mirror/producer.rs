use super::Lease;
use axum::body::Bytes;
use futures_util::{stream, StreamExt};
use std::{
    sync::{atomic::Ordering, Arc},
    time::Duration,
};

pub(super) fn buffered_stream<S>(
    chunks: S,
    producer_lease: Arc<Lease>,
) -> impl futures_util::Stream<Item = Result<Bytes, std::io::Error>>
where
    S: futures_util::Stream<Item = Result<Bytes, std::io::Error>> + Send + 'static,
{
    // Encoding and cancellation must keep running even when the HTTP body is
    // backpressured. Only two fixed-size chunks may queue ahead of the reader.
    let (sender, receiver) = tokio::sync::mpsc::channel(2);
    let worker = tokio::spawn(async move {
        let deadline = tokio::time::Instant::now()
            + Duration::from_secs(producer_lease.settings.duration as u64 + 5);
        futures_util::pin_mut!(chunks);
        while let Some(chunk) = chunks.next().await {
            if producer_lease.canceled.load(Ordering::SeqCst) {
                break;
            }
            tokio::select! {
                _ = producer_lease.notify.notified() => break,
                _ = tokio::time::sleep_until(deadline) => break,
                result = sender.send(chunk) => if result.is_err() { break; },
            }
        }
    });
    stream::unfold(
        (receiver, Producer(worker)),
        |(mut receiver, guard)| async move {
            receiver
                .recv()
                .await
                .map(|chunk| (chunk, (receiver, guard)))
        },
    )
}
struct Producer(tokio::task::JoinHandle<()>);
impl Drop for Producer {
    fn drop(&mut self) {
        self.0.abort();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::screen_mirror::{cancel, settings};
    use serde_json::json;
    use std::{sync::atomic::AtomicBool, time::Instant};
    use tokio::sync::{Notify, Semaphore};
    #[tokio::test]
    async fn stalled_body_expires_without_stop_or_disconnect() {
        struct Released(Arc<AtomicBool>);
        impl Drop for Released {
            fn drop(&mut self) {
                self.0.store(true, Ordering::SeqCst);
            }
        }
        let released = Arc::new(AtomicBool::new(false));
        let semaphore = Arc::new(Semaphore::new(1));
        // Retain the real minimum ten-second recording duration and five-second
        // cleanup allowance: prove the actual timer, not a test-only shortcut.
        let lease = Arc::new(Lease {
            id: uuid::Uuid::new_v4().to_string(),
            owner: "expiry-test".into(),
            created: Instant::now(),
            settings: settings(&json!({"durationSeconds":10})).unwrap(),
            claimed: AtomicBool::new(true),
            canceled: AtomicBool::new(false),
            notify: Notify::new(),
            _slot: semaphore.clone().try_acquire_owned().unwrap(),
        });
        let source = stream::unfold(Released(released.clone()), |guard| async move {
            Some((Ok(Bytes::from(vec![0; 65536])), guard))
        });
        let started = Instant::now();
        let body = buffered_stream(source, lease);
        tokio::time::sleep(Duration::from_secs(1)).await;
        assert!(!released.load(Ordering::SeqCst));
        assert_eq!(semaphore.available_permits(), 0);
        tokio::time::timeout(Duration::from_secs(17), async {
            while !released.load(Ordering::SeqCst) || semaphore.available_permits() != 1 {
                tokio::time::sleep(Duration::from_millis(20)).await;
            }
        })
        .await
        .unwrap();
        assert!(started.elapsed() >= Duration::from_secs(15));
        // Keep body alive throughout expiry, without cancel() or dropping its guard.
        drop(body);
    }
    #[tokio::test]
    async fn backpressured_body_does_not_prevent_producer_resource_release() {
        struct Released(Arc<AtomicBool>);
        impl Drop for Released {
            fn drop(&mut self) {
                self.0.store(true, Ordering::SeqCst);
            }
        }
        let released = Arc::new(AtomicBool::new(false));
        let semaphore = Arc::new(Semaphore::new(1));
        let lease = Arc::new(Lease {
            id: uuid::Uuid::new_v4().to_string(),
            owner: "test".into(),
            created: Instant::now(),
            settings: settings(&json!({})).unwrap(),
            claimed: AtomicBool::new(true),
            canceled: AtomicBool::new(false),
            notify: Notify::new(),
            _slot: semaphore.clone().try_acquire_owned().unwrap(),
        });
        let generated = Arc::new(std::sync::atomic::AtomicUsize::new(0));
        let counter = generated.clone();
        let source = stream::unfold(Released(released.clone()), move |guard| {
            let counter = counter.clone();
            async move {
                counter.fetch_add(1, Ordering::SeqCst);
                Some((Ok(Bytes::from(vec![0; 65536])), guard))
            }
        });
        let body = buffered_stream(source, lease.clone());
        // Deliberately never poll the body: two queued chunks and one working chunk.
        tokio::time::timeout(Duration::from_secs(1), async {
            while generated.load(Ordering::SeqCst) < 3 {
                tokio::task::yield_now().await;
            }
        })
        .await
        .unwrap();
        assert_eq!(generated.load(Ordering::SeqCst), 3);
        cancel(&lease);
        drop(lease);
        tokio::time::timeout(Duration::from_secs(1), async {
            while !released.load(Ordering::SeqCst) || semaphore.available_permits() != 1 {
                tokio::task::yield_now().await;
            }
        })
        .await
        .unwrap();
        drop(body);
    }
}
