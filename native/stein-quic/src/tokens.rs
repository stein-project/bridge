/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

//! The proxies' address validation tokens (NEW_TOKEN, RFC 9000 section
//! 8.1.3), kept for the game's run so a rejoin skips the Retry.
//!
//! A Stein Sync proxy answers the first Initial of every unproven address
//! with a Retry, one round trip that at a high ping is most of the connect
//! time (stein-sync's `docs/protocol.md` section 7.4). After a handshake
//! the proxy sends tokens that prove this address next time; presenting
//! one skips the Retry. quinn's own store files tokens under the TLS
//! server name, which is the same (`stein-sync`) for every proxy, so a
//! token would go to the wrong server; this store files them under the
//! proxy's address instead.

use std::{
    collections::{HashMap, VecDeque},
    net::SocketAddr,
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc, Mutex, OnceLock,
    },
};

use bytes::Bytes;
use quinn::TokenStore;

/// Tokens kept per proxy: the proxy sends two per connection, and each
/// connect attempt spends one.
const PER_SERVER: usize = 2;

/// Proxies remembered; past this the whole table starts over (a player
/// never joins this many in one run, a flood of server list entries
/// cannot grow it).
const SERVERS: usize = 64;

/// The tokens of every proxy, for the process's lifetime.
fn table() -> &'static Mutex<HashMap<SocketAddr, VecDeque<Bytes>>> {
    static TABLE: OnceLock<Mutex<HashMap<SocketAddr, VecDeque<Bytes>>>> = OnceLock::new();
    TABLE.get_or_init(|| Mutex::new(HashMap::new()))
}

/// The token store of one connection: the proxy at `server`.
#[derive(Debug)]
pub(crate) struct ServerTokens {
    server: SocketAddr,
    /// Set when this connection's first Initial carried a token.
    used: Arc<AtomicBool>,
}

impl ServerTokens {
    /// The store for a connection to `server`; `used` turns true if a
    /// token is taken for it.
    pub(crate) fn new(server: SocketAddr, used: Arc<AtomicBool>) -> Self {
        Self { server, used }
    }
}

impl TokenStore for ServerTokens {
    fn insert(&self, _server_name: &str, token: Bytes) {
        let Ok(mut table) = table().lock() else {
            return;
        };
        if table.len() >= SERVERS && !table.contains_key(&self.server) {
            table.clear();
        }
        let tokens = table.entry(self.server).or_default();
        if tokens.len() >= PER_SERVER {
            tokens.pop_front();
        }
        tokens.push_back(token);
    }

    fn take(&self, _server_name: &str) -> Option<Bytes> {
        // The newest first: it has the most life left.
        let token = table().lock().ok()?.get_mut(&self.server)?.pop_back();
        if token.is_some() {
            self.used.store(true, Ordering::Relaxed);
        }
        token
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;

    #[test]
    fn tokens_are_filed_by_server_and_taken_once() {
        let a: SocketAddr = "192.0.2.1:25565".parse().unwrap();
        let b: SocketAddr = "192.0.2.2:25565".parse().unwrap();
        let used = Arc::new(AtomicBool::new(false));
        let store_a = ServerTokens::new(a, used.clone());
        let store_b = ServerTokens::new(b, Arc::new(AtomicBool::new(false)));
        store_a.insert("stein-sync", Bytes::from_static(b"1"));
        store_a.insert("stein-sync", Bytes::from_static(b"2"));
        store_a.insert("stein-sync", Bytes::from_static(b"3"));
        // Same server name, another proxy: none of a's tokens.
        assert_eq!(store_b.take("stein-sync"), None);
        assert!(!used.load(Ordering::Relaxed));
        assert_eq!(store_a.take("stein-sync"), Some(Bytes::from_static(b"3")));
        assert!(used.load(Ordering::Relaxed));
        assert_eq!(store_a.take("stein-sync"), Some(Bytes::from_static(b"2")));
        assert_eq!(store_a.take("stein-sync"), None);
    }
}
