/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.sync;

/**
 * The uplink Tick datagram of a QUIC connection (stein-sync's docs/protocol.md sections 7.3 and
 * 7.8), built here for every transport: the newest Tick and the two before it, each with the number
 * of stream packets written after the Accept before it ({@code after}) and its window, the copies
 * of the stream packets the game wrote since the Tick before.
 *
 * <pre>
 * datagram := kind u8 (= 2) | count u8 | entry * count                    (oldest first)
 * entry    := after varuint | len varuint | Tick | from varuint
 *             | copies u8 (bit 7: incomplete; bits 0-6: n) | (index varuint | len varuint | packet) * n
 * </pre>
 *
 * Over QUIC a Tick waits at the proxy for every stream packet written before it, so one lost packet
 * (an attack, a click) held the movement back until QUIC retransmitted it. With the copies riding
 * in the Tick's datagrams, as redundant as the Ticks, the proxy forwards the copy in the packet's
 * place and the Tick goes on; the stream's own copy is dropped when it comes. A packet the order
 * does not depend on (a keep alive answer, a Fence) is not copied; one too large for the window
 * makes it incomplete, and that Tick waits for the stream as before.
 *
 * <p>Plain values and fixed arrays: no game classes (tested without the game) and no allocation per
 * Tick or per packet. One instance per connection, used from its network thread.
 */
final class SteinSyncDatagram {

    static final int KIND_TICKS_COPIES = 2;

    /** Ticks per datagram, as the redundancy of the native transport. */
    static final int REDUNDANCY = 3;

    static final int MAX_TICK_LEN = 256;
    static final int MAX_COPIES = 16;
    static final int MAX_COPY_LEN = 256;

    /** The most bytes of copies one entry keeps. */
    static final int MAX_WINDOW_LEN = 320;

    /**
     * The longest datagram: below the 1200 bytes every QUIC path carries, with room for QUIC's own
     * headers. Older entries' windows are left out (as incomplete) first.
     */
    static final int MAX_DATAGRAM_LEN = 1100;

    private final long[] after = new long[REDUNDANCY];
    private final int[] tickLen = new int[REDUNDANCY];
    private final byte[][] ticks = new byte[REDUNDANCY][MAX_TICK_LEN];
    private final long[] from = new long[REDUNDANCY];
    private final boolean[] complete = new boolean[REDUNDANCY];
    private final int[] copies = new int[REDUNDANCY];
    private final int[] windowLen = new int[REDUNDANCY];
    private final byte[][] windows = new byte[REDUNDANCY][MAX_WINDOW_LEN];

    /** Ticks pushed so far. */
    private long pushed;

    // The window being filled, until the next Tick.
    private long openFrom;
    private boolean openComplete = true;
    private int openCopies;
    private int openLen;
    private final byte[] open = new byte[MAX_WINDOW_LEN];

    private final boolean[] keep = new boolean[REDUNDANCY];

    /** The longest datagram {@link #push} writes. */
    static final int BUFFER = MAX_DATAGRAM_LEN;

    /** Starts the window after stream packet {@code from} (the Accept: 0). */
    void openWindow(long from) {
        openFrom = from;
        openComplete = true;
        openCopies = 0;
        openLen = 0;
    }

    /**
     * Stream packet number {@code index}, one the order depends on: copied into the window, or the
     * window marked incomplete when it does not fit.
     */
    void copy(long index, byte[] packet, int len) {
        int head = varLen(index) + varLen(len);
        boolean fits =
                openComplete
                        && openCopies < MAX_COPIES
                        && len > 0
                        && len <= MAX_COPY_LEN
                        && openLen + head + len <= MAX_WINDOW_LEN;
        if (!fits) {
            incomplete();
            return;
        }
        openLen = putVar(open, openLen, index);
        openLen = putVar(open, openLen, len);
        System.arraycopy(packet, 0, open, openLen, len);
        openLen += len;
        openCopies++;
    }

    /** A stream packet the order depends on could not be copied: the window is incomplete. */
    void incomplete() {
        openComplete = false;
        openCopies = 0;
        openLen = 0;
    }

    /**
     * Adds the newest Tick ({@code len} bytes of the {@code stein:sync} message) with the open
     * window, writes the datagram into {@code out} (at least {@link #BUFFER} long) and opens the
     * next window after {@code after}.
     *
     * @return the datagram's length, or 0 for a Tick that cannot be sent this way (then nothing is
     *     added)
     */
    int push(long after, byte[] tick, int len, byte[] out) {
        if (len <= 0 || len > MAX_TICK_LEN) {
            return 0;
        }
        int slot = (int) (pushed % REDUNDANCY);
        this.after[slot] = after;
        tickLen[slot] = len;
        System.arraycopy(tick, 0, ticks[slot], 0, len);
        from[slot] = Math.min(openFrom, after);
        complete[slot] = openComplete;
        copies[slot] = openComplete ? openCopies : 0;
        windowLen[slot] = openComplete ? openLen : 0;
        System.arraycopy(open, 0, windows[slot], 0, windowLen[slot]);
        pushed++;
        openWindow(after);
        int count = (int) Math.min(pushed, REDUNDANCY);
        // The windows that fit, newest first.
        int room = MAX_DATAGRAM_LEN - 2;
        for (int back = 0; back < count; back++) {
            int s = (int) ((pushed - 1 - back) % REDUNDANCY);
            room -= 10 + 2 + tickLen[s] + 10 + 1;
            keep[back] = windowLen[s] <= room;
            if (keep[back]) {
                room -= windowLen[s];
            }
        }
        int p = 0;
        out[p++] = (byte) KIND_TICKS_COPIES;
        out[p++] = (byte) count;
        for (int back = count - 1; back >= 0; back--) {
            int s = (int) ((pushed - 1 - back) % REDUNDANCY);
            p = putVar(out, p, this.after[s]);
            p = putVar(out, p, tickLen[s]);
            System.arraycopy(ticks[s], 0, out, p, tickLen[s]);
            p += tickLen[s];
            p = putVar(out, p, from[s]);
            if (keep[back] && complete[s]) {
                out[p++] = (byte) copies[s];
                System.arraycopy(windows[s], 0, out, p, windowLen[s]);
                p += windowLen[s];
            } else {
                out[p++] = (byte) 0x80;
            }
        }
        return p;
    }

    /** Forgets everything (a new connection). */
    void clear() {
        pushed = 0;
        openWindow(0);
    }

    private static int varLen(long v) {
        int n = 1;
        while ((v & ~0x7FL) != 0) {
            v >>>= 7;
            n++;
        }
        return n;
    }

    private static int putVar(byte[] out, int p, long v) {
        while ((v & ~0x7FL) != 0) {
            out[p++] = (byte) ((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out[p++] = (byte) v;
        return p;
    }
}
