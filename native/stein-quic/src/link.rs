/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

//! One QUIC connection to a Stein Sync proxy, seen by the game as a plain
//! TCP connection to a loopback port.
//!
//! The game keeps its own network code: it connects to [`Link::local_addr`]
//! and every byte it writes or reads there (handshake, login, encryption,
//! compression, play) travels on one bidirectional QUIC stream, so the
//! proxy reads exactly what it would read over TCP. Only the Ticks leave
//! the stream: the Bridge hands each one to [`Link::send_tick`], which
//! sends it as a datagram together with the Ticks before it
//! ([`crate::wire`]). A lost datagram is covered by the next one instead of
//! holding back everything behind it, which is what TCP does to every
//! packet after a lost segment.

use std::{
    io,
    net::{Ipv4Addr, Ipv6Addr, SocketAddr},
    sync::{
        atomic::{AtomicBool, AtomicU64, Ordering},
        Arc, Mutex,
    },
    time::Duration,
};

use bytes::Bytes;
use quinn::{
    crypto::rustls::QuicClientConfig, ClientConfig, Connection, Endpoint, EndpointConfig,
    IdleTimeout, TokioRuntime, TransportConfig, VarInt,
};
use tokio::{
    io::AsyncWriteExt,
    net::{TcpListener, TcpStream},
};
use tracing::debug;

use crate::{
    congestion::FlooredCubic,
    pin::{Fingerprint, PinnedVerifier},
    repeat::Repeater,
    tokens::ServerTokens,
    udp::AnsweringSocket,
    wire::{TickRing, WindowBuilder, ALPN, SERVER_NAME},
};

/// How long the game has to connect to the loopback port after the QUIC
/// connection is up (it connects right away; the margin is for a stalled
/// game thread).
const LOCAL_ACCEPT_TIMEOUT: Duration = Duration::from_secs(15);

/// No packet in either direction for this long ends the connection. The
/// game's own read timeout is 30 seconds, and the proxy's keep alives
/// arrive every few seconds while a player is connected.
const IDLE_TIMEOUT: Duration = Duration::from_secs(30);

/// A QUIC ping at least this often when nothing else is sent, so a NAT
/// between the player and the server keeps the UDP mapping (many forget
/// one after 30 seconds of silence).
const KEEP_ALIVE: Duration = Duration::from_secs(5);

/// Datagrams waiting to be sent, in bytes. Ticks are only worth sending
/// while they are fresh: when the link stalls, quinn drops the oldest
/// queued datagram for the new one, and the redundancy carries the
/// Ticks a dropped datagram had.
const DATAGRAM_SEND_BUFFER: usize = 16 * 1024;

/// How long the end of a session waits for the proxy to acknowledge the
/// last bytes before the connection is closed (closing drops anything
/// still unsent).
const FINISH_WAIT: Duration = Duration::from_secs(2);

/// Where and how to connect.
#[derive(Debug, Clone)]
pub struct LinkConfig {
    /// The proxy's QUIC addresses, tried in order (the ports the status
    /// announced, on the address the player typed).
    pub servers: Vec<SocketAddr>,
    /// The certificate fingerprint the status announced.
    pub pin: Fingerprint,
    /// How long each address may take to answer at all (with
    /// [`Link::connect`], to complete the handshake).
    pub attempt_timeout: Duration,
    /// The local UDP address; `None` for any address of the server's
    /// family.
    pub bind: Option<SocketAddr>,
}

/// How patient a connect is, and what the link does once up
/// ([`Link::connect_with`]).
#[derive(Debug, Clone, Default)]
pub struct ConnectOptions {
    /// Once an address answered (UDP gets through), how long its
    /// handshake may take in all; `None` (or anything shorter than
    /// `attempt_timeout`) keeps `attempt_timeout` as the whole limit.
    pub handshake_limit: Option<Duration>,
    /// The round trip the server list ping measured, as quinn's first
    /// estimate (its default, 333 ms, makes it resend late on a short
    /// path and early on a long one).
    pub rtt_hint: Option<Duration>,
    /// Send each Tick datagram a second time while the path loses
    /// packets ([`crate::repeat`]).
    pub repeat_datagrams: bool,
}

/// The first round-trip estimates accepted from a hint.
const RTT_HINT_RANGE: (Duration, Duration) = (Duration::from_millis(5), Duration::from_secs(3));

/// Why no QUIC connection came up.
#[derive(Debug)]
pub enum ConnectError {
    /// No address answered in time: UDP blocked on the way, or no QUIC
    /// listener there.
    Timeout,
    /// An address answered, so UDP gets through, but its handshake did
    /// not finish within the limit (a very long or very lossy path).
    Slow,
    /// An address answered but the handshake failed (another
    /// certificate, another protocol version).
    Handshake(String),
    /// A local socket could not be set up.
    Io(io::Error),
    /// No address to try.
    NoServers,
}

impl std::fmt::Display for ConnectError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            ConnectError::Timeout => f.write_str("no answer over UDP"),
            ConnectError::Slow => f.write_str("UDP answers but the handshake is too slow"),
            ConnectError::Handshake(why) => write!(f, "handshake failed: {why}"),
            ConnectError::Io(err) => write!(f, "local socket: {err}"),
            ConnectError::NoServers => f.write_str("no address to try"),
        }
    }
}

impl std::error::Error for ConnectError {}

impl ConnectError {
    /// How much the error says about the server, for keeping the most
    /// telling of several addresses: a slow path (UDP gets through, so the
    /// server is tried again sooner) over a failed handshake, over
    /// silence, over a local socket error. Silence wins over a local
    /// socket error: the Bridge tries its Java QUIC after a local error
    /// (a socket that implementation may open), and when the other ports
    /// went unanswered (UDP blocked) that would only make the player wait
    /// a second time before TCP.
    fn weight(&self) -> u8 {
        match self {
            ConnectError::Slow => 4,
            ConnectError::Handshake(_) => 3,
            ConnectError::Timeout => 2,
            ConnectError::Io(_) => 1,
            ConnectError::NoServers => 0,
        }
    }

    /// Keeps the more telling of `self` and `other`.
    fn keep(self, other: ConnectError) -> ConnectError {
        if other.weight() > self.weight() {
            other
        } else {
            self
        }
    }
}

/// Counters of one link, for logs and the transport report.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct LinkStats {
    /// Smoothed round-trip time, in microseconds.
    pub rtt_us: u64,
    /// QUIC packets sent and declared lost.
    pub sent_packets: u64,
    /// QUIC packets declared lost.
    pub lost_packets: u64,
    /// Tick datagrams handed to the connection.
    pub datagrams: u64,
    /// Tick datagrams the connection refused (closed, or too large).
    pub datagrams_refused: u64,
    /// Second copies of datagrams sent on a lossy path.
    pub repeats: u64,
    /// Whether the connection skipped the Retry with a token from an
    /// earlier one.
    pub used_token: bool,
}

struct Shared {
    endpoint: Endpoint,
    conn: Connection,
    ticks: Mutex<TickRing>,
    local: SocketAddr,
    datagrams: AtomicU64,
    datagrams_refused: AtomicU64,
    repeater: Option<Arc<Repeater>>,
    used_token: bool,
}

/// A connected QUIC link; dropping every clone leaves the connection to
/// end with the game's socket.
#[derive(Clone)]
pub struct Link {
    shared: Arc<Shared>,
}

impl std::fmt::Debug for Link {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Link")
            .field("local", &self.shared.local)
            .field("remote", &self.shared.conn.remote_address())
            .finish_non_exhaustive()
    }
}

/// The client's transport settings (`docs/protocol.md` section 7.4).
fn transport(rtt_hint: Option<Duration>) -> TransportConfig {
    let mut transport = TransportConfig::default();
    if let Some(rtt) = rtt_hint {
        transport.initial_rtt(rtt.clamp(RTT_HINT_RANGE.0, RTT_HINT_RANGE.1));
    }
    // In range by construction (30 s is far below the QUIC maximum).
    if let Ok(idle) = IdleTimeout::try_from(IDLE_TIMEOUT) {
        transport.max_idle_timeout(Some(idle));
    }
    transport
        .keep_alive_interval(Some(KEEP_ALIVE))
        // The game's uplink is never throttled by its own window
        // ([`crate::congestion`]).
        .congestion_controller_factory(Arc::new(FlooredCubic))
        // The proxy never opens a stream toward the client.
        .max_concurrent_bidi_streams(VarInt::from_u32(0))
        .max_concurrent_uni_streams(VarInt::from_u32(0))
        .datagram_send_buffer_size(DATAGRAM_SEND_BUFFER)
        // The proxy sends no datagrams yet; a small buffer bounds what a
        // misbehaving one could make the client hold.
        .datagram_receive_buffer_size(Some(16 * 1024));
    transport
}

fn client_config(
    pin: Fingerprint,
    rtt_hint: Option<Duration>,
) -> Result<ClientConfig, ConnectError> {
    let provider = Arc::new(rustls::crypto::ring::default_provider());
    let mut tls = rustls::ClientConfig::builder_with_provider(provider.clone())
        .with_protocol_versions(&[&rustls::version::TLS13])
        .map_err(|err| ConnectError::Handshake(err.to_string()))?
        .dangerous()
        .with_custom_certificate_verifier(Arc::new(PinnedVerifier::new(pin, provider)))
        .with_no_client_auth();
    tls.alpn_protocols = vec![ALPN.to_vec()];
    let crypto =
        QuicClientConfig::try_from(tls).map_err(|err| ConnectError::Handshake(err.to_string()))?;
    let mut config = ClientConfig::new(Arc::new(crypto));
    config.transport_config(Arc::new(transport(rtt_hint)));
    Ok(config)
}

/// A client endpoint on `bind` whose socket sets `answered` when anything
/// arrives.
fn endpoint(bind: SocketAddr, answered: Arc<AtomicBool>) -> io::Result<Endpoint> {
    let socket = AnsweringSocket::wrap(std::net::UdpSocket::bind(bind)?, answered)?;
    Endpoint::new_with_abstract_socket(
        EndpointConfig::default(),
        None,
        socket,
        Arc::new(TokioRuntime),
    )
}

fn any_address_for(server: SocketAddr) -> SocketAddr {
    match server {
        SocketAddr::V4(_) => SocketAddr::from((Ipv4Addr::UNSPECIFIED, 0)),
        SocketAddr::V6(_) => SocketAddr::from((Ipv6Addr::UNSPECIFIED, 0)),
    }
}

impl Link {
    /// Connects to the first address of `config` that completes the
    /// handshake within `attempt_timeout` and opens the loopback port the
    /// game connects to. Must run inside a Tokio runtime, which then
    /// carries the link.
    pub async fn connect(config: &LinkConfig) -> Result<Link, ConnectError> {
        Self::connect_with(config, &ConnectOptions::default()).await
    }

    /// [`Link::connect`] with `options`: an address that answers within
    /// `attempt_timeout` gets up to `handshake_limit` to finish, so a slow
    /// path is not mistaken for one without UDP. A token from an earlier
    /// connection to the same proxy skips its Retry ([`crate::tokens`]).
    pub async fn connect_with(
        config: &LinkConfig,
        options: &ConnectOptions,
    ) -> Result<Link, ConnectError> {
        let client = client_config(config.pin, options.rtt_hint)?;
        let mut limit = options
            .handshake_limit
            .unwrap_or(config.attempt_timeout)
            .max(config.attempt_timeout);
        let mut last = ConnectError::NoServers;
        for &server in &config.servers {
            let bind = config.bind.unwrap_or_else(|| any_address_for(server));
            let answered = Arc::new(AtomicBool::new(false));
            // A socket of one family may fail (no IPv6 on the host) while
            // the next address is of the other: tried on.
            let endpoint = match endpoint(bind, answered.clone()) {
                Ok(endpoint) => endpoint,
                Err(err) => {
                    last = last.keep(ConnectError::Io(err));
                    continue;
                }
            };
            let used_token = Arc::new(AtomicBool::new(false));
            let mut for_server = client.clone();
            for_server.token_store(Arc::new(ServerTokens::new(server, used_token.clone())));
            let connecting = match endpoint.connect_with(for_server, server, SERVER_NAME) {
                Ok(connecting) => connecting,
                Err(err) => {
                    last = last.keep(ConnectError::Handshake(err.to_string()));
                    continue;
                }
            };
            match handshake(connecting, config.attempt_timeout, limit, &answered).await {
                Ok(conn) => {
                    let used = used_token.load(Ordering::Relaxed);
                    return Self::start(endpoint, conn, options, used).await;
                }
                Err(err) => {
                    debug!(%server, %err, "QUIC connect failed");
                    if matches!(err, ConnectError::Slow) {
                        // One slow path had the long wait: the other
                        // addresses get the short one, so the join waits
                        // about one limit, not one per address.
                        limit = config.attempt_timeout;
                    }
                    last = last.keep(err);
                }
            }
            endpoint.close(VarInt::from_u32(0), b"");
        }
        Err(last)
    }

    async fn start(
        endpoint: Endpoint,
        conn: Connection,
        options: &ConnectOptions,
        used_token: bool,
    ) -> Result<Link, ConnectError> {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0))
            .await
            .map_err(ConnectError::Io)?;
        let local = listener.local_addr().map_err(ConnectError::Io)?;
        let repeater = options.repeat_datagrams.then(|| Arc::new(Repeater::new()));
        let shared = Arc::new(Shared {
            endpoint,
            conn,
            ticks: Mutex::new(TickRing::default()),
            local,
            datagrams: AtomicU64::new(0),
            datagrams_refused: AtomicU64::new(0),
            repeater: repeater.clone(),
            used_token,
        });
        if let Some(repeater) = repeater {
            tokio::spawn(repeater.run(shared.conn.clone()));
        }
        tokio::spawn(bridge(listener, shared.conn.clone()));
        Ok(Link { shared })
    }

    /// The loopback address the game connects to.
    pub fn local_addr(&self) -> SocketAddr {
        self.shared.local
    }

    /// The proxy's address the connection uses now.
    pub fn remote_addr(&self) -> SocketAddr {
        self.shared.conn.remote_address()
    }

    /// Sends the newest Tick (the message as on `stein:sync`, type byte
    /// included) with the ones before it. `after` is how many packets the
    /// game wrote on the stream after its Accept before this Tick. Never
    /// blocks; false when the Tick could not be sent (too long, or the
    /// connection is gone), and the caller then sends it on the stream.
    pub fn send_tick(&self, after: u64, tick: &[u8]) -> bool {
        let datagram = {
            let Ok(mut ring) = self.shared.ticks.lock() else {
                return false;
            };
            match ring.push(after, tick) {
                // quinn takes ownership of the bytes it sends: one copy
                // per tick is the price of its API.
                Some(datagram) => Bytes::copy_from_slice(datagram),
                None => return false,
            }
        };
        self.send(datagram)
    }

    /// [`Link::send_tick`] with the Tick's window of copied stream packets
    /// (stein-sync's `docs/protocol.md` section 7.8): the proxy forwards
    /// them in their place when the stream has not delivered them yet.
    pub fn send_tick_window(&self, after: u64, tick: &[u8], window: &WindowBuilder) -> bool {
        let datagram = {
            let Ok(mut ring) = self.shared.ticks.lock() else {
                return false;
            };
            match ring.push_window(after, tick, window) {
                Some(datagram) => Bytes::copy_from_slice(datagram),
                None => return false,
            }
        };
        self.send(datagram)
    }

    /// Sends a datagram the caller built (the Bridge builds its Tick
    /// datagrams in Java, the same bytes for every transport). Never
    /// blocks; false when it could not be sent.
    pub fn send_datagram(&self, datagram: &[u8]) -> bool {
        self.send(Bytes::copy_from_slice(datagram))
    }

    fn send(&self, datagram: Bytes) -> bool {
        // Kept for the repeat: a clone shares the bytes.
        let copy = self.shared.repeater.as_ref().map(|_| datagram.clone());
        match self.shared.conn.send_datagram(datagram) {
            Ok(()) => {
                self.shared.datagrams.fetch_add(1, Ordering::Relaxed);
                if let (Some(repeater), Some(copy)) = (&self.shared.repeater, copy) {
                    repeater.sent(&self.shared.conn, &copy);
                }
                true
            }
            Err(err) => {
                self.shared
                    .datagrams_refused
                    .fetch_add(1, Ordering::Relaxed);
                debug!(%err, "Tick datagram refused");
                false
            }
        }
    }

    /// Moves the connection to a new local UDP socket on `bind`, as when
    /// the player's address changes (another network, a NAT that remapped
    /// the port). The proxy follows the connection to the new address.
    pub fn rebind(&self, bind: SocketAddr) -> io::Result<()> {
        let socket = std::net::UdpSocket::bind(bind)?;
        self.shared.endpoint.rebind(socket)
    }

    /// The link's counters now.
    pub fn stats(&self) -> LinkStats {
        let stats = self.shared.conn.stats();
        LinkStats {
            rtt_us: u64::try_from(stats.path.rtt.as_micros()).unwrap_or(u64::MAX),
            sent_packets: stats.path.sent_packets,
            lost_packets: stats.path.lost_packets,
            datagrams: self.shared.datagrams.load(Ordering::Relaxed),
            datagrams_refused: self.shared.datagrams_refused.load(Ordering::Relaxed),
            repeats: self
                .shared
                .repeater
                .as_ref()
                .map_or(0, |r| r.repeats.load(Ordering::Relaxed)),
            used_token: self.shared.used_token,
        }
    }

    /// True once the connection ended (either side, or a timeout).
    pub fn is_closed(&self) -> bool {
        self.shared.conn.close_reason().is_some()
    }

    /// Closes the connection now (the game is gone).
    pub fn close(&self) {
        self.shared.conn.close(VarInt::from_u32(0), b"");
    }
}

/// Waits for `connecting`: `answer` for the address to answer at all, and
/// once it has, until `limit` in all for the handshake to finish.
async fn handshake(
    connecting: quinn::Connecting,
    answer: Duration,
    limit: Duration,
    answered: &AtomicBool,
) -> Result<Connection, ConnectError> {
    let started = tokio::time::Instant::now();
    tokio::pin!(connecting);
    let outcome = match tokio::time::timeout(answer, &mut connecting).await {
        Ok(outcome) => outcome,
        // Silence: nothing came back, UDP does not get through.
        Err(_) if !answered.load(Ordering::Relaxed) => return Err(ConnectError::Timeout),
        // It answers, only slowly: give it the rest of the limit.
        Err(_) => match tokio::time::timeout_at(started + limit, &mut connecting).await {
            Ok(outcome) => outcome,
            Err(_) => return Err(ConnectError::Slow),
        },
    };
    outcome.map_err(|err| match err {
        quinn::ConnectionError::TimedOut if answered.load(Ordering::Relaxed) => ConnectError::Slow,
        quinn::ConnectionError::TimedOut => ConnectError::Timeout,
        other => ConnectError::Handshake(other.to_string()),
    })
}

/// Carries the game's socket over the QUIC stream until either side ends,
/// then closes the connection.
async fn bridge(listener: TcpListener, conn: Connection) {
    let accepted = tokio::time::timeout(LOCAL_ACCEPT_TIMEOUT, listener.accept()).await;
    drop(listener);
    let tcp = match accepted {
        Ok(Ok((tcp, _))) => tcp,
        _ => {
            debug!("the game never connected to the QUIC link");
            conn.close(VarInt::from_u32(0), b"");
            return;
        }
    };
    if let Err(err) = carry(tcp, &conn).await {
        debug!(%err, "QUIC link ended");
    }
    conn.close(VarInt::from_u32(0), b"");
}

async fn carry(tcp: TcpStream, conn: &Connection) -> io::Result<()> {
    // The game's packets are small and latency-bound, as on its own TCP
    // socket.
    tcp.set_nodelay(true)?;
    let (mut send, mut recv) = conn.open_bi().await.map_err(io::Error::other)?;
    let (mut game_read, mut game_write) = tcp.into_split();
    let up = async {
        let copied = tokio::io::copy(&mut game_read, &mut send).await;
        // The game closed its socket (or it failed): the proxy reads the
        // end of the stream, as it would read the TCP close.
        let _ = send.finish();
        let _ = tokio::time::timeout(FINISH_WAIT, send.stopped()).await;
        copied
    };
    let down = async {
        let copied = tokio::io::copy(&mut recv, &mut game_write).await;
        let _ = game_write.shutdown().await;
        copied
    };
    // Either direction ending ends the session (the game closes its
    // socket right after the proxy's Disconnect, and a proxy that ended
    // the stream has nothing more to say).
    tokio::select! {
        result = up => result.map(drop),
        result = down => result.map(drop),
    }
}

#[cfg(test)]
mod tests {
    use super::ConnectError;

    #[test]
    fn the_most_telling_error_of_several_addresses_is_kept() {
        // Port 1 answered but was slow, port 2 was silent: still slow, so
        // the Bridge tries the server again in 2 minutes, not 10.
        let kept = ConnectError::NoServers
            .keep(ConnectError::Slow)
            .keep(ConnectError::Timeout);
        assert!(matches!(kept, ConnectError::Slow));
        // One port could not open a local socket, the others were silent:
        // UDP is blocked, no second wait for the Java QUIC.
        let kept = ConnectError::Timeout.keep(ConnectError::Io(std::io::Error::other("no v6")));
        assert!(matches!(kept, ConnectError::Timeout));
        let kept = ConnectError::Io(std::io::Error::other("no v6")).keep(ConnectError::Timeout);
        assert!(matches!(kept, ConnectError::Timeout));
        let kept = ConnectError::NoServers.keep(ConnectError::Io(std::io::Error::other("down")));
        assert!(matches!(kept, ConnectError::Io(_)));
        let kept = ConnectError::Handshake("pin".into()).keep(ConnectError::Timeout);
        assert!(matches!(kept, ConnectError::Handshake(_)));
    }
}
