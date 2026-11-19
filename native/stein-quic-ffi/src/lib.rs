/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

//! The C ABI of the Stein Sync QUIC transport, for the Bridge's
//! `dev.xavier.stein.bridge.net.SteinQuic` (Java's foreign function API, so
//! no JNI glue on either side).
//!
//! Links live in a table and Java holds their number. Every function
//! catches panics (a panic must never unwind into the JVM) and answers with
//! a plain value; the transport runs on one runtime thread of its own, so
//! the game's threads only hand bytes over and never wait on the network,
//! except [`stein_quic_connect`], which runs on the game's connection
//! thread like the TCP connect it replaces.

use std::{
    collections::HashMap,
    net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr},
    panic::{catch_unwind, AssertUnwindSafe},
    sync::{
        atomic::{AtomicI64, Ordering},
        Mutex, OnceLock,
    },
    time::Duration,
};

use stein_quic::{ConnectError, Link, LinkConfig};
use tokio::runtime::Runtime;

/// The version of this ABI; the Bridge refuses a library with another.
pub const ABI_VERSION: i32 = 2;

/// The most ports a connect call tries.
const MAX_PORTS: usize = 4;

/// Error results of [`stein_quic_connect`] (negative, so a handle is
/// always positive).
const ERR_TIMEOUT: i64 = -1;
const ERR_HANDSHAKE: i64 = -2;
const ERR_IO: i64 = -3;
const ERR_ARGUMENT: i64 = -4;
const ERR_PANIC: i64 = -5;

fn runtime() -> Option<&'static Runtime> {
    static RUNTIME: OnceLock<Option<Runtime>> = OnceLock::new();
    RUNTIME
        .get_or_init(|| {
            tokio::runtime::Builder::new_multi_thread()
                // One thread carries every link: a player has one at a time,
                // and the work per tick is a datagram and a few copies.
                .worker_threads(1)
                .thread_name("stein-quic")
                .enable_all()
                .build()
                .ok()
        })
        .as_ref()
}

fn links() -> &'static Mutex<HashMap<i64, Link>> {
    static LINKS: OnceLock<Mutex<HashMap<i64, Link>>> = OnceLock::new();
    LINKS.get_or_init(|| Mutex::new(HashMap::new()))
}

fn link(handle: i64) -> Option<Link> {
    links().lock().ok()?.get(&handle).cloned()
}

static NEXT_HANDLE: AtomicI64 = AtomicI64::new(1);

/// The ABI version ([`ABI_VERSION`]).
#[no_mangle]
pub extern "C" fn stein_quic_abi_version() -> i32 {
    ABI_VERSION
}

/// Connects to the proxy at `ip` (4 or 16 bytes) on the first of `ports`
/// that completes the handshake with the certificate whose SHA-256 is the
/// 32 bytes at `pin`. On success writes the loopback port the game must
/// connect to into `out_local_port` and returns the link's handle (> 0);
/// otherwise a negative error: -1 nothing answered in time, -2 the
/// handshake failed, -3 a local socket failed, -4 bad arguments, -5 an
/// internal error. Blocks for at most `ports` x `attempt_ms`.
///
/// # Safety
///
/// `ip` points to `ip_len` readable bytes, `ports` to `port_count` u16,
/// `pin` to 32 bytes and `out_local_port` to one writable i32, all valid
/// for the duration of the call.
#[no_mangle]
pub unsafe extern "C" fn stein_quic_connect(
    ip: *const u8,
    ip_len: i32,
    ports: *const u16,
    port_count: i32,
    pin: *const u8,
    attempt_ms: i32,
    out_local_port: *mut i32,
) -> i64 {
    if ip.is_null() || ports.is_null() || pin.is_null() || out_local_port.is_null() {
        return ERR_ARGUMENT;
    }
    let Ok(port_count) = usize::try_from(port_count) else {
        return ERR_ARGUMENT;
    };
    if port_count == 0 || port_count > MAX_PORTS || !(100..=10_000).contains(&attempt_ms) {
        return ERR_ARGUMENT;
    }
    let ip_len = match ip_len {
        4 => 4,
        16 => 16,
        _ => return ERR_ARGUMENT,
    };
    // SAFETY: the caller guarantees these lengths (checked to be small and
    // fixed above) and that the memory stays valid during the call.
    let (ip, ports, pin) = unsafe {
        (
            std::slice::from_raw_parts(ip, ip_len),
            std::slice::from_raw_parts(ports, port_count),
            std::slice::from_raw_parts(pin, 32),
        )
    };
    let address = if let Ok(v4) = <[u8; 4]>::try_from(ip) {
        IpAddr::V4(Ipv4Addr::from(v4))
    } else if let Ok(v6) = <[u8; 16]>::try_from(ip) {
        IpAddr::V6(Ipv6Addr::from(v6))
    } else {
        return ERR_ARGUMENT;
    };
    let mut fingerprint = [0u8; 32];
    fingerprint.copy_from_slice(pin);
    let config = LinkConfig {
        servers: ports
            .iter()
            .map(|&port| SocketAddr::new(address, port))
            .collect(),
        pin: fingerprint,
        attempt_timeout: Duration::from_millis(attempt_ms.unsigned_abs().into()),
        bind: None,
    };
    let result = catch_unwind(AssertUnwindSafe(|| {
        let runtime = runtime()?;
        Some(runtime.block_on(Link::connect(&config)))
    }));
    let link = match result {
        Ok(Some(Ok(link))) => link,
        Ok(Some(Err(ConnectError::Timeout))) => return ERR_TIMEOUT,
        Ok(Some(Err(ConnectError::Handshake(_)))) => return ERR_HANDSHAKE,
        Ok(Some(Err(ConnectError::Io(_)))) => return ERR_IO,
        Ok(Some(Err(ConnectError::NoServers))) => return ERR_ARGUMENT,
        Ok(None) | Err(_) => return ERR_PANIC,
    };
    let handle = NEXT_HANDLE.fetch_add(1, Ordering::Relaxed);
    let local_port = i32::from(link.local_addr().port());
    match links().lock() {
        Ok(mut table) => {
            table.insert(handle, link);
        }
        Err(_) => {
            link.close();
            return ERR_PANIC;
        }
    }
    // SAFETY: the caller guarantees `out_local_port` is writable.
    unsafe { out_local_port.write(local_port) };
    handle
}

/// Sends a Tick (the `stein:sync` message, `len` bytes at `tick`) as a
/// datagram with the Ticks before it; `after` is the number of packets the
/// game wrote on the stream after its Accept. Returns 1 when sent, 0 when
/// not (the caller then sends the Tick on the stream). Never blocks.
///
/// # Safety
///
/// `tick` points to `len` readable bytes valid during the call.
#[no_mangle]
pub unsafe extern "C" fn stein_quic_send_tick(
    handle: i64,
    after: i64,
    tick: *const u8,
    len: i32,
) -> i32 {
    let (Ok(after), Ok(len)) = (u64::try_from(after), usize::try_from(len)) else {
        return 0;
    };
    if tick.is_null() || !(1..=4096).contains(&len) {
        return 0;
    }
    // SAFETY: the caller guarantees `len` readable bytes at `tick`.
    let tick = unsafe { std::slice::from_raw_parts(tick, len) };
    catch_unwind(AssertUnwindSafe(|| {
        link(handle).is_some_and(|link| link.send_tick(after, tick))
    }))
    .map_or(0, i32::from)
}

/// Sends `len` bytes at `datagram` as one datagram: a Tick datagram the
/// Bridge built itself (stein-sync's `docs/protocol.md` sections 7.3 and
/// 7.8). Returns 1 when sent, 0 when not (the caller then sends the Tick
/// on the stream). Never blocks.
///
/// # Safety
///
/// `datagram` points to `len` readable bytes valid during the call.
#[no_mangle]
pub unsafe extern "C" fn stein_quic_send_datagram(
    handle: i64,
    datagram: *const u8,
    len: i32,
) -> i32 {
    let Ok(len) = usize::try_from(len) else {
        return 0;
    };
    if datagram.is_null() || !(1..=1400).contains(&len) {
        return 0;
    }
    // SAFETY: the caller guarantees `len` readable bytes at `datagram`.
    let datagram = unsafe { std::slice::from_raw_parts(datagram, len) };
    catch_unwind(AssertUnwindSafe(|| {
        link(handle).is_some_and(|link| link.send_datagram(datagram))
    }))
    .map_or(0, i32::from)
}

/// Writes the link's counters into the 6 values at `out`: round-trip time
/// (microseconds), QUIC packets sent, QUIC packets lost, Tick datagrams
/// sent, Tick datagrams refused, and 1 if the connection is closed.
/// Returns 1, or 0 for an unknown handle.
///
/// # Safety
///
/// `out` points to 6 writable i64 valid during the call.
#[no_mangle]
pub unsafe extern "C" fn stein_quic_stats(handle: i64, out: *mut i64) -> i32 {
    if out.is_null() {
        return 0;
    }
    let Ok(Some((stats, closed))) = catch_unwind(AssertUnwindSafe(|| {
        link(handle).map(|link| (link.stats(), link.is_closed()))
    })) else {
        return 0;
    };
    let values = [
        stats.rtt_us,
        stats.sent_packets,
        stats.lost_packets,
        stats.datagrams,
        stats.datagrams_refused,
        u64::from(closed),
    ];
    for (i, value) in values.into_iter().enumerate() {
        // SAFETY: the caller guarantees 6 writable values at `out`.
        unsafe { out.add(i).write(i64::try_from(value).unwrap_or(i64::MAX)) };
    }
    1
}

/// Moves the link to a new local UDP port on the same address family, as
/// a player's address change would (a diagnostic for the migration test).
/// Returns 1 when moved, 0 otherwise.
#[no_mangle]
pub extern "C" fn stein_quic_rebind(handle: i64) -> i32 {
    catch_unwind(AssertUnwindSafe(|| {
        let Some(link) = link(handle) else {
            return false;
        };
        let any = match link.remote_addr() {
            SocketAddr::V4(_) => SocketAddr::from((Ipv4Addr::UNSPECIFIED, 0)),
            SocketAddr::V6(_) => SocketAddr::from((Ipv6Addr::UNSPECIFIED, 0)),
        };
        // Entered for the new socket's registration with the runtime.
        let Some(runtime) = runtime() else {
            return false;
        };
        let _guard = runtime.enter();
        link.rebind(any).is_ok()
    }))
    .unwrap_or(false)
    .into()
}

/// Closes the link and forgets the handle. Safe to call twice.
#[no_mangle]
pub extern "C" fn stein_quic_close(handle: i64) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        let link = links().lock().ok().and_then(|mut t| t.remove(&handle));
        if let Some(link) = link {
            link.close();
        }
    }));
}
