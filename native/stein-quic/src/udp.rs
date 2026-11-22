/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

//! The client's UDP socket, which notes whether anything came back.
//!
//! A slow handshake and no UDP at all look the same from quinn's side
//! until the attempt times out: both are a `Connecting` that has not
//! finished. They must not be treated the same. A network that drops UDP
//! should fall back to TCP at once; a proxy behind a long or lossy path
//! that answers, only slowly, should be given the time (stein-sync's
//! `docs/protocol.md` section 7.6). So the socket under the endpoint says
//! whether any datagram arrived, which is the proof that UDP gets through.

use std::{
    fmt,
    io::{self, IoSliceMut},
    net::SocketAddr,
    pin::Pin,
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc,
    },
    task::{Context, Poll},
};

use quinn::{
    udp::{RecvMeta, Transmit},
    AsyncUdpSocket, Runtime, TokioRuntime, UdpPoller,
};

/// A UDP socket that sets `answered` on the first datagram it receives.
pub(crate) struct AnsweringSocket {
    inner: Arc<dyn AsyncUdpSocket>,
    answered: Arc<AtomicBool>,
}

impl fmt::Debug for AnsweringSocket {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("AnsweringSocket")
            .field("answered", &self.answered.load(Ordering::Relaxed))
            .finish_non_exhaustive()
    }
}

impl AnsweringSocket {
    /// Wraps `socket` for the Tokio runtime.
    pub(crate) fn wrap(
        socket: std::net::UdpSocket,
        answered: Arc<AtomicBool>,
    ) -> io::Result<Arc<dyn AsyncUdpSocket>> {
        let inner = TokioRuntime.wrap_udp_socket(socket)?;
        Ok(Arc::new(Self { inner, answered }))
    }
}

impl AsyncUdpSocket for AnsweringSocket {
    fn create_io_poller(self: Arc<Self>) -> Pin<Box<dyn UdpPoller>> {
        self.inner.clone().create_io_poller()
    }

    fn try_send(&self, transmit: &Transmit) -> io::Result<()> {
        self.inner.try_send(transmit)
    }

    fn poll_recv(
        &self,
        cx: &mut Context,
        bufs: &mut [IoSliceMut<'_>],
        meta: &mut [RecvMeta],
    ) -> Poll<io::Result<usize>> {
        let polled = self.inner.poll_recv(cx, bufs, meta);
        if matches!(polled, Poll::Ready(Ok(n)) if n > 0) {
            self.answered.store(true, Ordering::Relaxed);
        }
        polled
    }

    fn local_addr(&self) -> io::Result<SocketAddr> {
        self.inner.local_addr()
    }

    fn max_transmit_segments(&self) -> usize {
        self.inner.max_transmit_segments()
    }

    fn max_receive_segments(&self) -> usize {
        self.inner.max_receive_segments()
    }

    fn may_fragment(&self) -> bool {
        self.inner.may_fragment()
    }
}
