/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.sync;

/**
 * The block changes the client holds back while its own block actions wait for the Stein Sync
 * proxy's BlockAck (stein-sync's docs/protocol.md section 5.3).
 *
 * <p>Each BlockAction covers one or two positions (the dug block; the clicked block and the one on
 * the clicked face). While an action covering a position is unacknowledged, a Block Change for it
 * is not applied: the newest one is kept, and applied once no action covers the position any more
 * (its ack came, or a second passed without one). The proxy's resync already ends on the server's
 * truth, so the block goes straight from the client's prediction to the server's state.
 *
 * <p>Fixed-size tables, so nothing is allocated per action or per block change. When they are full
 * the client falls back to the vanilla behaviour: the oldest action is forgotten (its held changes
 * applied) and a change that finds no free slot is applied at once. Plain values, no game classes
 * (positions and states are opaque objects), so it is tested without the game. Used on the game
 * thread only.
 */
final class BlockHold {

    /** How long an action waits for its ack before the client forgets it (section 5.3). */
    static final long TIMEOUT_MS = 1000L;

    static final int MAX_ACTIONS = 16;
    static final int MAX_HELD = 64;

    /**
     * A position no action covers: it packs x = -2^25, y = 0, z = 0, beyond the world border, so no
     * block a player can act on has it.
     */
    static final long NONE = Long.MIN_VALUE;

    /** Where a released change goes: the world, in the game. */
    interface Apply {
        void apply(Object pos, Object state);
    }

    private final int[] seqs = new int[MAX_ACTIONS];
    private final long[] first = new long[MAX_ACTIONS];
    private final long[] second = new long[MAX_ACTIONS];
    private final long[] sentAt = new long[MAX_ACTIONS];
    private int actions;

    private final long[] heldKey = new long[MAX_HELD];
    private final Object[] heldPos = new Object[MAX_HELD];
    private final Object[] heldState = new Object[MAX_HELD];
    private int held;

    /** A block position as the protocol packs it (x 26 bits, y 12, z 26). */
    static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF) << 26) | (z & 0x3FFFFFF);
    }

    /** Actions waiting for their ack. */
    int pending() {
        return actions;
    }

    /** Block changes held back. */
    int held() {
        return held;
    }

    /**
     * A BlockAction was sent.
     *
     * @param a the first position it covers
     * @param b the second one, or {@link #NONE}
     * @param now the time in milliseconds
     */
    void begin(int seq, long a, long b, long now, Apply apply) {
        if (actions == MAX_ACTIONS) {
            removeAction(0);
            release(apply);
        }
        seqs[actions] = seq;
        first[actions] = a;
        second[actions] = b;
        sentAt[actions] = now;
        actions++;
    }

    /**
     * A Block Change from the server, before the game applies it.
     *
     * @return true when it is held (the game must not apply it now)
     */
    boolean hold(long key, Object pos, Object state) {
        if (actions == 0 || !covered(key)) {
            return false;
        }
        for (int i = 0; i < held; i++) {
            if (heldKey[i] == key) {
                heldPos[i] = pos;
                heldState[i] = state;
                return true;
            }
        }
        if (held == MAX_HELD) {
            return false;
        }
        heldKey[held] = key;
        heldPos[held] = pos;
        heldState[held] = state;
        held++;
        return true;
    }

    /**
     * The proxy acknowledged action {@code seq}: that action is done, and the changes no other
     * action covers are applied. An ack settles only its own action: the proxy acks an action it
     * sent no Barrier for at once, while an earlier one may still wait for its resync (protocol
     * 5.3), so acks can come out of order.
     */
    void ack(int seq, Apply apply) {
        for (int i = 0; i < actions; i++) {
            if (seqs[i] == seq) {
                removeAction(i);
                release(apply);
                return;
            }
        }
    }

    /** Forgets the actions older than {@link #TIMEOUT_MS} and applies what they held. */
    void expire(long now, Apply apply) {
        boolean removed = false;
        for (int i = actions - 1; i >= 0; i--) {
            if (now - sentAt[i] >= TIMEOUT_MS) {
                removeAction(i);
                removed = true;
            }
        }
        if (removed) {
            release(apply);
        }
    }

    /** A new connection or world: everything is dropped, nothing applied (the world is gone). */
    void clear() {
        actions = 0;
        for (int i = 0; i < held; i++) {
            heldPos[i] = null;
            heldState[i] = null;
        }
        held = 0;
    }

    private boolean covered(long key) {
        for (int i = 0; i < actions; i++) {
            if (first[i] == key || second[i] == key) {
                return true;
            }
        }
        return false;
    }

    private void removeAction(int i) {
        int last = actions - 1;
        System.arraycopy(seqs, i + 1, seqs, i, last - i);
        System.arraycopy(first, i + 1, first, i, last - i);
        System.arraycopy(second, i + 1, second, i, last - i);
        System.arraycopy(sentAt, i + 1, sentAt, i, last - i);
        actions = last;
    }

    /** Applies, in the order they came, the held changes no action covers any more. */
    private void release(Apply apply) {
        int kept = 0;
        for (int i = 0; i < held; i++) {
            Object pos = heldPos[i];
            Object state = heldState[i];
            if (covered(heldKey[i])) {
                heldKey[kept] = heldKey[i];
                heldPos[kept] = pos;
                heldState[kept] = state;
                kept++;
            } else {
                apply.apply(pos, state);
            }
        }
        for (int i = kept; i < held; i++) {
            heldPos[i] = null;
            heldState[i] = null;
        }
        held = kept;
    }
}
