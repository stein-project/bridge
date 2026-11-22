/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

//! The client's congestion control: CUBIC with a floor under its window.
//!
//! What a player sends is the game's input: a Tick datagram per tick,
//! clicks and keep alive answers, a few kB/s set by the game's clock, not
//! by the network. After a loss CUBIC shrinks its window, down to two
//! packets on a lossy path. At a high ping that is less than one round
//! trip of the game's own Ticks, so the Ticks queue behind the window and
//! reach the server late and in bursts: exactly the stutter the datagrams
//! are there to remove (measured: 80 to 800 ms of ping with 1 % loss, the
//! movement holes above 250 ms came from this). The floor,
//! [`WINDOW_FLOOR`] per round trip, is well above anything the game sends
//! at any ping a player can play on, so the window never holds the game
//! back; CUBIC still grows above it for whatever needs more, and a client
//! that floods is bounded by the floor and by the proxy's own rate limit.

use std::{any::Any, sync::Arc, time::Instant};

use quinn::congestion::{Controller, ControllerFactory, ControllerMetrics, CubicConfig};
use quinn_proto::RttEstimator;

/// The smallest congestion window, in bytes: about two seconds of the
/// game's uplink with the datagram copies, so even a 1.5 s round trip
/// never waits on the window.
pub(crate) const WINDOW_FLOOR: u64 = 32 * 1024;

/// Builds [`Floored`] CUBIC controllers.
#[derive(Debug, Default)]
pub(crate) struct FlooredCubic;

impl ControllerFactory for FlooredCubic {
    fn build(self: Arc<Self>, now: Instant, current_mtu: u16) -> Box<dyn Controller> {
        Box::new(Floored {
            inner: Arc::new(CubicConfig::default()).build(now, current_mtu),
        })
    }
}

/// A CUBIC controller whose window never goes below [`WINDOW_FLOOR`].
struct Floored {
    inner: Box<dyn Controller>,
}

impl Controller for Floored {
    fn on_sent(&mut self, now: Instant, bytes: u64, last_packet_number: u64) {
        self.inner.on_sent(now, bytes, last_packet_number);
    }

    fn on_ack(
        &mut self,
        now: Instant,
        sent: Instant,
        bytes: u64,
        app_limited: bool,
        rtt: &RttEstimator,
    ) {
        self.inner.on_ack(now, sent, bytes, app_limited, rtt);
    }

    fn on_end_acks(
        &mut self,
        now: Instant,
        in_flight: u64,
        app_limited: bool,
        largest_packet_num_acked: Option<u64>,
    ) {
        self.inner
            .on_end_acks(now, in_flight, app_limited, largest_packet_num_acked);
    }

    fn on_congestion_event(
        &mut self,
        now: Instant,
        sent: Instant,
        is_persistent_congestion: bool,
        lost_bytes: u64,
    ) {
        self.inner
            .on_congestion_event(now, sent, is_persistent_congestion, lost_bytes);
    }

    fn on_mtu_update(&mut self, new_mtu: u16) {
        self.inner.on_mtu_update(new_mtu);
    }

    fn window(&self) -> u64 {
        self.inner.window().max(WINDOW_FLOOR)
    }

    fn metrics(&self) -> ControllerMetrics {
        let mut metrics = self.inner.metrics();
        metrics.congestion_window = self.window();
        metrics
    }

    fn clone_box(&self) -> Box<dyn Controller> {
        Box::new(Floored {
            inner: self.inner.clone_box(),
        })
    }

    fn initial_window(&self) -> u64 {
        self.inner.initial_window().max(WINDOW_FLOOR)
    }

    fn into_any(self: Box<Self>) -> Box<dyn Any> {
        self
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn losses_never_take_the_window_below_the_floor() {
        let now = Instant::now();
        let mut cc = Arc::new(FlooredCubic).build(now, 1200);
        assert!(cc.window() >= WINDOW_FLOOR);
        for i in 0..20 {
            cc.on_congestion_event(now, now, i % 5 == 4, 1200);
        }
        assert_eq!(cc.window(), WINDOW_FLOOR);
        assert_eq!(cc.metrics().congestion_window, WINDOW_FLOOR);
        assert_eq!(cc.clone_box().window(), WINDOW_FLOOR);
    }
}
