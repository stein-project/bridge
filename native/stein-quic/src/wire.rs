/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

//! The bytes of the QUIC transport that are not the game's own stream
//! (stein-sync's `docs/protocol.md` section 7): the application protocol
//! name and the uplink Tick datagram.
//!
//! A Tick datagram carries the newest Tick and the ones before it
//! ([`REDUNDANCY`] in all, oldest first), so a lost datagram costs nothing
//! as long as one of the next ones arrives:
//!
//! ```text
//! datagram := kind u8 (= KIND_TICKS) | count u8 (1..=MAX_ENTRIES) | entry * count
//! entry    := after varuint | len varuint (1..=MAX_TICK_LEN) | tick bytes
//! ```
//!
//! `after` is how many packets the client had written on the stream after
//! its Accept when it made the Tick; the proxy translates the Tick only
//! once it has read that many, so a Tick never overtakes a packet the game
//! sent before it (a click, a placement). `tick bytes` is the Tick message
//! exactly as on the `stein:sync` channel (section 5.5), type byte
//! included. `varuint` is unsigned LEB128, at most 10 bytes.

/// The ALPN name of the transport. A proxy and a client that do not
/// share it never complete the handshake, so a later incompatible
/// transport takes a new name instead of breaking old clients mid-game.
pub const ALPN: &[u8] = b"stein-sync/1";

/// The TLS server name the client asks for. The certificate is pinned by
/// its fingerprint (the name is never checked), so one constant keeps the
/// handshake the same for every address a player types.
pub const SERVER_NAME: &str = "stein-sync";

/// First byte of a Tick datagram.
pub const KIND_TICKS: u8 = 1;

/// First byte of a Tick datagram whose entries carry their window of
/// copied stream packets (stein-sync's `docs/protocol.md` section 7.8):
///
/// ```text
/// entry  := after varuint | len varuint | tick bytes | window
/// window := from varuint | copies u8 (bit 7: incomplete; bits 0-6: n)
///           | (index varuint | len varuint | packet) * n
/// ```
///
/// The window holds the stream packets the game wrote between the Tick
/// before and this one, numbered `from + 1 ..= after`: the proxy forwards
/// them from here when the stream has not delivered them yet, so the
/// Tick does not wait for a retransmission.
pub const KIND_TICKS_COPIES: u8 = 2;

/// The most copies one window carries.
pub const MAX_COPIES: usize = 16;

/// The longest packet a copy may hold.
pub const MAX_COPY_LEN: usize = 256;

/// The most bytes of copies one entry keeps; a window past it goes as
/// incomplete (the proxy then waits for the stream, as without copies).
pub const MAX_WINDOW_LEN: usize = 320;

/// The longest datagram written with windows: below the smallest
/// datagram a QUIC path must carry (1200 bytes) with room for QUIC's own
/// headers. Windows of the older entries are left out (sent as
/// incomplete) when the newest needs the room.
pub const MAX_WINDOW_DATAGRAM_LEN: usize = 1100;

/// Ticks per datagram: the newest and the two before it. Three copies of
/// every Tick survive two datagrams lost in a row (a burst of 100 ms at
/// 20 ticks per second) for about 3 x 45 bytes per tick.
pub const REDUNDANCY: usize = 3;

/// The most entries a reader accepts in one datagram.
pub const MAX_ENTRIES: usize = 8;

/// The longest Tick message an entry may carry (a Tick with four attacks
/// is about 180 bytes).
pub const MAX_TICK_LEN: usize = 256;

/// The longest datagram a writer produces: header and [`REDUNDANCY`]
/// entries of the longest Tick, below the 1200-byte QUIC minimum.
pub const MAX_DATAGRAM_LEN: usize = 2 + REDUNDANCY * (10 + 2 + MAX_TICK_LEN);

/// Appends `value` as unsigned LEB128.
pub fn put_varuint(out: &mut Vec<u8>, mut value: u64) {
    loop {
        let byte = (value & 0x7F) as u8;
        value >>= 7;
        if value == 0 {
            out.push(byte);
            return;
        }
        out.push(byte | 0x80);
    }
}

/// Reads an unsigned LEB128 value at `*pos`, advancing it; `None` when it
/// is cut short or longer than 10 bytes.
pub fn get_varuint(bytes: &[u8], pos: &mut usize) -> Option<u64> {
    let mut value = 0u64;
    for shift in (0..70).step_by(7) {
        let byte = *bytes.get(*pos)?;
        *pos += 1;
        value |= u64::from(byte & 0x7F).checked_shl(shift)?;
        if byte & 0x80 == 0 {
            return Some(value);
        }
    }
    None
}

/// One Tick waiting to be sent again, in a fixed slot (no allocation per
/// tick).
#[derive(Clone, Copy)]
struct Slot {
    after: u64,
    len: usize,
    bytes: [u8; MAX_TICK_LEN],
    /// The window: `from`, complete, copy count and the encoded copies.
    from: u64,
    complete: bool,
    copies: u8,
    window_len: usize,
    window: [u8; MAX_WINDOW_LEN],
}

/// Bytes of `value` as unsigned LEB128.
fn varuint_len(mut value: u64) -> usize {
    let mut n = 1;
    while value >= 0x80 {
        value >>= 7;
        n += 1;
    }
    n
}

/// The copies of one window, encoded as they go on the wire, built by
/// the caller between two Ticks.
pub struct WindowBuilder {
    from: u64,
    complete: bool,
    count: u8,
    bytes: Vec<u8>,
}

impl Default for WindowBuilder {
    fn default() -> Self {
        Self {
            from: 0,
            complete: true,
            count: 0,
            bytes: Vec::with_capacity(MAX_WINDOW_LEN),
        }
    }
}

impl WindowBuilder {
    /// Starts the window after stream packet `from` (the `after` of the
    /// Tick just sent).
    pub fn reset(&mut self, from: u64) {
        self.from = from;
        self.complete = true;
        self.count = 0;
        self.bytes.clear();
    }

    /// A stream packet the order depends on, numbered `index`: copied, or
    /// the window marked incomplete when it does not fit.
    pub fn copy(&mut self, index: u64, packet: &[u8]) {
        let head = varuint_len(index) + varuint_len(packet.len() as u64);
        let fits = self.complete
            && usize::from(self.count) < MAX_COPIES
            && !packet.is_empty()
            && packet.len() <= MAX_COPY_LEN
            && self.bytes.len() + head + packet.len() <= MAX_WINDOW_LEN;
        if !fits {
            self.complete = false;
            self.count = 0;
            self.bytes.clear();
            return;
        }
        put_varuint(&mut self.bytes, index);
        put_varuint(&mut self.bytes, packet.len() as u64);
        self.bytes.extend_from_slice(packet);
        self.count += 1;
    }
}

/// The last [`REDUNDANCY`] Ticks and the datagram built from them.
pub struct TickRing {
    slots: [Slot; REDUNDANCY],
    /// Ticks pushed so far (the newest is at `(pushed - 1) % REDUNDANCY`).
    pushed: usize,
    out: Vec<u8>,
}

impl Default for TickRing {
    fn default() -> Self {
        Self {
            slots: [Slot {
                after: 0,
                len: 0,
                bytes: [0; MAX_TICK_LEN],
                from: 0,
                complete: false,
                copies: 0,
                window_len: 0,
                window: [0; MAX_WINDOW_LEN],
            }; REDUNDANCY],
            pushed: 0,
            out: Vec::with_capacity(MAX_DATAGRAM_LEN.max(MAX_WINDOW_DATAGRAM_LEN)),
        }
    }
}

impl TickRing {
    /// Adds the newest Tick and returns the datagram that carries it and
    /// the ones before it; `None` (nothing added) for an empty Tick or one
    /// longer than [`MAX_TICK_LEN`].
    pub fn push(&mut self, after: u64, tick: &[u8]) -> Option<&[u8]> {
        if tick.is_empty() || tick.len() > MAX_TICK_LEN {
            return None;
        }
        let slot = &mut self.slots[self.pushed % REDUNDANCY];
        slot.after = after;
        slot.len = tick.len();
        slot.bytes[..tick.len()].copy_from_slice(tick);
        self.pushed += 1;
        let count = self.pushed.min(REDUNDANCY);
        self.out.clear();
        self.out.push(KIND_TICKS);
        self.out.push(count as u8);
        for back in (0..count).rev() {
            let slot = &self.slots[(self.pushed - 1 - back) % REDUNDANCY];
            put_varuint(&mut self.out, slot.after);
            put_varuint(&mut self.out, slot.len as u64);
            self.out.extend_from_slice(&slot.bytes[..slot.len]);
        }
        Some(&self.out)
    }

    /// Adds the newest Tick with its window of copies and returns the
    /// datagram ([`KIND_TICKS_COPIES`]) that carries it and the ones
    /// before it, with their windows; `None` (nothing added) for an empty
    /// or too long Tick.
    pub fn push_window(
        &mut self,
        after: u64,
        tick: &[u8],
        window: &WindowBuilder,
    ) -> Option<&[u8]> {
        if tick.is_empty() || tick.len() > MAX_TICK_LEN {
            return None;
        }
        let slot = &mut self.slots[self.pushed % REDUNDANCY];
        slot.after = after;
        slot.len = tick.len();
        slot.bytes[..tick.len()].copy_from_slice(tick);
        slot.from = window.from.min(after);
        slot.complete = window.complete && window.bytes.len() <= MAX_WINDOW_LEN;
        slot.copies = if slot.complete { window.count } else { 0 };
        slot.window_len = if slot.complete { window.bytes.len() } else { 0 };
        slot.window[..slot.window_len].copy_from_slice(&window.bytes[..slot.window_len]);
        self.pushed += 1;
        let count = self.pushed.min(REDUNDANCY);
        // The windows that fit, newest first: an older entry's copies are
        // left out (sent as incomplete) before the newest's.
        let mut room = MAX_WINDOW_DATAGRAM_LEN.saturating_sub(2);
        let mut keep = [false; REDUNDANCY];
        for (back, kept) in keep.iter_mut().enumerate().take(count) {
            let s = &self.slots[(self.pushed - 1 - back) % REDUNDANCY];
            let base = 10 + 2 + s.len + 10 + 1;
            room = room.saturating_sub(base);
            if s.window_len <= room {
                room -= s.window_len;
                *kept = true;
            }
        }
        self.out.clear();
        self.out.push(KIND_TICKS_COPIES);
        self.out.push(count as u8);
        for back in (0..count).rev() {
            let slot = &self.slots[(self.pushed - 1 - back) % REDUNDANCY];
            put_varuint(&mut self.out, slot.after);
            put_varuint(&mut self.out, slot.len as u64);
            self.out.extend_from_slice(&slot.bytes[..slot.len]);
            put_varuint(&mut self.out, slot.from);
            if keep[back] && slot.complete {
                self.out.push(slot.copies);
                self.out.extend_from_slice(&slot.window[..slot.window_len]);
            } else {
                self.out.push(0x80);
            }
        }
        Some(&self.out)
    }

    /// Forgets every Tick (a new connection).
    pub fn clear(&mut self) {
        self.pushed = 0;
    }
}

/// One entry of a Tick datagram, borrowed from it.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Entry<'a> {
    /// Stream packets written after the Accept before this Tick.
    pub after: u64,
    /// The Tick message (type byte included).
    pub tick: &'a [u8],
}

/// Reads the entries of a Tick datagram into `out`, oldest first, and
/// returns how many there are; `None` for anything malformed (another
/// kind, a bad count, a cut or oversized entry, trailing bytes).
pub fn read_ticks<'a>(datagram: &'a [u8], out: &mut [Entry<'a>; MAX_ENTRIES]) -> Option<usize> {
    let (&kind, rest) = datagram.split_first()?;
    if kind != KIND_TICKS {
        return None;
    }
    let (&count, _) = rest.split_first()?;
    let count = usize::from(count);
    if count == 0 || count > MAX_ENTRIES {
        return None;
    }
    let mut pos = 2;
    for slot in out.iter_mut().take(count) {
        let after = get_varuint(datagram, &mut pos)?;
        let len = usize::try_from(get_varuint(datagram, &mut pos)?).ok()?;
        if len == 0 || len > MAX_TICK_LEN {
            return None;
        }
        let tick = datagram.get(pos..pos + len)?;
        pos += len;
        *slot = Entry { after, tick };
    }
    (pos == datagram.len()).then_some(count)
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;

    #[test]
    fn varuint_round_trips() {
        for v in [0u64, 1, 127, 128, 300, u32::MAX as u64, u64::MAX] {
            let mut out = Vec::new();
            put_varuint(&mut out, v);
            let mut pos = 0;
            assert_eq!(get_varuint(&out, &mut pos), Some(v));
            assert_eq!(pos, out.len());
        }
        let mut pos = 0;
        assert_eq!(get_varuint(&[0x80], &mut pos), None);
        let mut pos = 0;
        assert_eq!(get_varuint(&[0xFF; 11], &mut pos), None);
    }

    #[test]
    fn ring_carries_the_last_three_oldest_first() {
        let mut ring = TickRing::default();
        let mut entries = [Entry {
            after: 0,
            tick: &[],
        }; MAX_ENTRIES];
        let first = ring.push(0, &[7, 1]).unwrap().to_vec();
        assert_eq!(read_ticks(&first, &mut entries), Some(1));
        assert_eq!(entries[0].tick, &[7, 1]);
        ring.push(2, &[7, 2]).unwrap();
        ring.push(2, &[7, 3]).unwrap();
        let fourth = ring.push(5, &[7, 4, 9]).unwrap().to_vec();
        assert_eq!(read_ticks(&fourth, &mut entries), Some(3));
        assert_eq!(
            entries[0],
            Entry {
                after: 2,
                tick: &[7, 2]
            }
        );
        assert_eq!(
            entries[1],
            Entry {
                after: 2,
                tick: &[7, 3]
            }
        );
        assert_eq!(
            entries[2],
            Entry {
                after: 5,
                tick: &[7, 4, 9]
            }
        );
    }

    #[test]
    fn windows_ride_with_their_ticks_and_old_ones_give_way_to_new() {
        let mut ring = TickRing::default();
        let mut window = WindowBuilder::default();
        window.reset(0);
        window.copy(1, &[0x0A]);
        let first = ring.push_window(2, &[7, 1], &window).unwrap().to_vec();
        // kind, count, after 2, len 2, tick, from 0, 1 copy: index 1,
        // len 1, the swing.
        assert_eq!(first, [KIND_TICKS_COPIES, 1, 2, 2, 7, 1, 0, 1, 1, 1, 0x0A]);
        // A window too large to keep goes as incomplete.
        window.reset(2);
        for i in 3..30 {
            window.copy(i, &[0x02; 40]);
        }
        let second = ring.push_window(29, &[7, 2], &window).unwrap().to_vec();
        assert_eq!(&second[second.len() - 2..], [2, 0x80]);
        // Big windows: the oldest are left out first.
        let mut big = WindowBuilder::default();
        for n in 0..3u64 {
            big.reset(100 + n * 10);
            for i in 1..=4 {
                big.copy(100 + n * 10 + i, &[0x08; 70]);
            }
            ring.push_window(110 + n * 10, &[7, 3 + n as u8], &big)
                .unwrap();
        }
        let last = ring.push_window(140, &[7, 9], &big).unwrap().to_vec();
        assert!(last.len() <= MAX_WINDOW_DATAGRAM_LEN, "{}", last.len());
        assert_eq!(last[0], KIND_TICKS_COPIES);
    }

    #[test]
    fn malformed_datagrams_are_refused() {
        let mut entries = [Entry {
            after: 0,
            tick: &[],
        }; MAX_ENTRIES];
        assert_eq!(read_ticks(&[], &mut entries), None);
        assert_eq!(read_ticks(&[2, 1, 0, 1, 7], &mut entries), None);
        assert_eq!(read_ticks(&[KIND_TICKS, 0], &mut entries), None);
        assert_eq!(read_ticks(&[KIND_TICKS, 9], &mut entries), None);
        assert_eq!(read_ticks(&[KIND_TICKS, 1, 0, 2, 7], &mut entries), None);
        assert_eq!(read_ticks(&[KIND_TICKS, 1, 0, 1, 7, 0], &mut entries), None);
        assert_eq!(read_ticks(&[KIND_TICKS, 1, 0, 0], &mut entries), None);
        assert_eq!(read_ticks(&[KIND_TICKS, 1, 0, 1, 7], &mut entries), Some(1));
        let mut ring = TickRing::default();
        assert!(ring.push(0, &[]).is_none());
        assert!(ring.push(0, &[0; MAX_TICK_LEN + 1]).is_none());
    }
}
