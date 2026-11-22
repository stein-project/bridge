/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

//! A second copy of each Tick datagram, a moment later, while the path
//! loses packets.
//!
//! Each datagram already carries the two Ticks before it, so a lost one is
//! covered by the next. But the next one leaves a tick (50 ms) later: the
//! proxy then sees two Ticks arrive together after a 100 ms hole, which is
//! the stutter that remains on a lossy link (one hole per lost datagram,
//! about 1 in 33 at 3 % loss). Sending the same datagram again
//! [`REPEAT_DELAY`] later, in its own packet, fills that hole unless both
//! copies are lost; the proxy takes each Tick once and counts the rest
//! (`tick_repeats`, section 7.3). It costs one packet per tick, so it runs
//! only while the connection has lost a packet in the last
//! [`LOSSY_WINDOW`]: a clean path sends exactly what it did before.

use std::{
    sync::{
        atomic::{AtomicU64, Ordering},
        Arc, Mutex,
    },
    time::{Duration, Instant},
};

use bytes::Bytes;
use quinn::Connection;
use tokio::sync::Notify;

/// How long after a datagram its copy leaves: long enough that a short
/// burst of loss (Wi-Fi, a busy router queue) that took the first does
/// not take the second, short enough that the Tick still arrives well
/// before the next one (50 ms).
pub(crate) const REPEAT_DELAY: Duration = Duration::from_millis(20);

/// How long after the last lost packet copies keep being sent.
pub(crate) const LOSSY_WINDOW: Duration = Duration::from_secs(30);

/// The repeat state of one link.
#[derive(Debug)]
pub(crate) struct Repeater {
    /// The datagram to send again, if one is due.
    slot: Mutex<Option<Bytes>>,
    wake: Notify,
    started: Instant,
    /// `lost_packets` when last looked at.
    lost_seen: AtomicU64,
    /// Milliseconds after `started` of the last time the loss count
    /// grew; `u64::MAX` before any loss.
    lossy_since_ms: AtomicU64,
    /// Copies sent.
    pub(crate) repeats: AtomicU64,
}

impl Repeater {
    pub(crate) fn new() -> Self {
        Self {
            slot: Mutex::new(None),
            wake: Notify::new(),
            started: Instant::now(),
            lost_seen: AtomicU64::new(0),
            lossy_since_ms: AtomicU64::new(u64::MAX),
            repeats: AtomicU64::new(0),
        }
    }

    /// Whether the path lost a packet within [`LOSSY_WINDOW`], given the
    /// connection's loss count now.
    fn lossy(&self, lost_packets: u64) -> bool {
        let now_ms = u64::try_from(self.started.elapsed().as_millis()).unwrap_or(u64::MAX - 1);
        if lost_packets > self.lost_seen.swap(lost_packets, Ordering::Relaxed) {
            self.lossy_since_ms.store(now_ms, Ordering::Relaxed);
        }
        let since = self.lossy_since_ms.load(Ordering::Relaxed);
        since != u64::MAX && now_ms.saturating_sub(since) < LOSSY_WINDOW.as_millis() as u64
    }

    /// After `datagram` was sent on `conn`: schedules its copy if the path
    /// is lossy. A newer datagram replaces a copy still waiting (it
    /// carries the same Ticks and newer ones).
    pub(crate) fn sent(&self, conn: &Connection, datagram: &Bytes) {
        if !self.lossy(conn.stats().path.lost_packets) {
            return;
        }
        if let Ok(mut slot) = self.slot.lock() {
            // A clone of `Bytes` shares the buffer: no copy, no allocation.
            *slot = Some(datagram.clone());
        }
        self.wake.notify_one();
    }

    /// Sends the copies until the connection ends.
    pub(crate) async fn run(self: Arc<Self>, conn: Connection) {
        loop {
            tokio::select! {
                _ = conn.closed() => return,
                () = self.wake.notified() => {}
            }
            tokio::time::sleep(REPEAT_DELAY).await;
            let due = self.slot.lock().ok().and_then(|mut slot| slot.take());
            if let Some(datagram) = due {
                if conn.send_datagram(datagram).is_ok() {
                    self.repeats.fetch_add(1, Ordering::Relaxed);
                }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn copies_run_only_after_a_loss_and_stop_after_the_window() {
        let r = Repeater::new();
        assert!(!r.lossy(0));
        assert!(!r.lossy(0));
        assert!(r.lossy(1));
        assert!(r.lossy(1));
        // The window is counted from the last loss.
        r.lossy_since_ms.store(0, Ordering::Relaxed);
        let late = Repeater {
            started: Instant::now() - LOSSY_WINDOW - Duration::from_millis(1),
            ..Repeater::new()
        };
        late.lost_seen.store(1, Ordering::Relaxed);
        late.lossy_since_ms.store(0, Ordering::Relaxed);
        assert!(!late.lossy(1));
        assert!(late.lossy(2));
    }
}
