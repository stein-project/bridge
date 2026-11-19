/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

//! Client side of the Stein Sync QUIC transport (stein-sync's
//! `docs/protocol.md` section 7).
//!
//! The game connects to a loopback port ([`Link::local_addr`]) and its
//! bytes travel on one QUIC stream to the proxy; the Ticks the Bridge takes
//! off that stream go as redundant datagrams ([`Link::send_tick`]). The
//! proxy's self-signed certificate is pinned by the fingerprint its server
//! list entry announced ([`pin`]).
//!
//! The Bridge loads this through `stein-quic-ffi`; the Stein Sync tests
//! and bench use it directly, so they measure the code the game runs.

pub mod link;
pub mod pin;
pub mod wire;

pub use link::{ConnectError, Link, LinkConfig, LinkStats};
pub use pin::{fingerprint, parse_fingerprint, Fingerprint};
