/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.net;

import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.INetHandler;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S01PacketJoinGame;
import net.minecraft.util.IThreadListener;

import java.util.concurrent.atomic.AtomicLong;

/**
 * How many of the server's packets the game had handled before a tick: the {@code applied} field of
 * the Stein Sync Tick (stein-sync's docs/protocol.md section 5.5), which lets the proxy's shadow
 * physics place a velocity, a teleport or an effect at the exact tick the client applied it.
 *
 * <p>The game reads each packet on the network thread, in order, and hands most of them to the game
 * thread as a task (PacketThreadUtil.checkThreadAndEnqueue), which runs them, in the same order,
 * before the ticks of the next frame; a few it handles on the network thread at once. So: each
 * packet gets its number as it arrives (counted from Join Game, which is 1); each task handed to
 * the game thread carries the number of its packet, in a ring in the order they were handed over;
 * and when the game thread has run a task, every packet up to its number has been handled. The
 * value a Tick reads at the end of its tick is that number.
 *
 * <p>Counting costs a few stores per packet and no allocation. The value is -1 until the first task
 * of a Join Game ran, and while the ring overflowed (more than {@value #RING} packets waiting for
 * the game thread): the Tick then leaves the field out.
 *
 * <p>Each ring slot also keeps the packet it numbers (cleared once its task ran), so a task the
 * game never ran cannot shift every later number by one: the next task finds its own slot again.
 * With {@code -Dsteinloader.noSteinSync} (the Loader's kill switch for all of Stein Sync) nothing
 * is counted at all.
 */
public final class SyncApplied {

    /** Tasks waiting for the game thread that the ring can number. */
    static final int RING = 1 << 16;

    private static final int MASK = RING - 1;

    /** Slots searched ahead for a task's own packet when the pairing slipped. */
    private static final int RESYNC = 256;

    /**
     * The Loader's kill switch for Stein Sync: the game then runs exactly as without this class.
     */
    private static final boolean OFF = Boolean.getBoolean("steinloader.noSteinSync");

    /** The packet of each task, by task order, until its task ran. */
    private static final Object[] PACKETS = new Object[RING];

    /**
     * The packet each network thread is handling now: per thread, so a server list ping's thread
     * cannot put its packet between the game connection's arrival and its task's slot. Holds one
     * packet per thread at most; set without allocating once a thread has its entry.
     */
    private static final ThreadLocal<Object> CURRENT_PACKET = new ThreadLocal<>();

    /** The number of each task's packet, by task order. */
    private static final long[] STAMPS = new long[RING];

    /** Packets read since Join Game (network thread only). */
    private static long arrived;

    /** The number of the packet the network thread is handling now. */
    private static long current;

    /**
     * Tasks handed to the game thread: by the game connection's network thread, and now and then by
     * another connection's (a server list ping), hence atomic.
     */
    private static final AtomicLong HANDED = new AtomicLong();

    /** Tasks the game thread ran (game thread only). */
    private static long ran;

    /** The value for the next Tick, -1 when unknown. */
    private static volatile long applied = -1;

    private SyncApplied() {}

    /**
     * A packet arrived (NetworkManager.channelRead0, network thread), before it is handled. Only
     * the game connection's count: a server list ping's packets are not the server's stream.
     */
    public static void arrived(Object packet, Object handler) {
        if (OFF) {
            return;
        }
        // Every connection's: its task finds its own slot by it.
        CURRENT_PACKET.set(packet);
        if (!(handler instanceof NetHandlerPlayClient)) {
            return;
        }
        if (packet instanceof S01PacketJoinGame) {
            arrived = 0;
        }
        current = ++arrived;
    }

    /**
     * PacketThreadUtil.checkThreadAndEnqueue hands a packet's task to the game thread: numbered,
     * then handed over as the game does.
     */
    public static Object enqueue(Object listener, Object task) {
        if (!OFF) {
            long h = HANDED.getAndIncrement();
            STAMPS[(int) (h & MASK)] = current;
            PACKETS[(int) (h & MASK)] = CURRENT_PACKET.get();
        }
        return ((IThreadListener) listener).addScheduledTask((Runnable) task);
    }

    /** A packet's task on the game thread: handled as the game does, then counted. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void run(Object packet, Object handler) {
        try {
            ((Packet) packet).processPacket((INetHandler) handler);
        } finally {
            if (!OFF) {
                counted(packet, handler instanceof NetHandlerPlayClient);
            }
        }
    }

    /**
     * Game thread: the task of {@code packet} ran. Another connection's task ({@code play} false)
     * only gives its slot back: it handled none of the server's packets.
     */
    private static void counted(Object packet, boolean play) {
        long handed = HANDED.get();
        long r = ran;
        // A task the game dropped (never ran) leaves its slot behind: look ahead for this one's.
        long limit = Math.min(handed, r + RESYNC);
        while (r < limit && PACKETS[(int) (r & MASK)] != packet) {
            r++;
        }
        if (r >= limit) {
            // Not found (the ring overflowed, or slipped further than the search): unknown, and
            // one slot taken as before, so an overflow drains as the game thread catches up. The
            // slot lets go of its packet (a chunk packet can be hundreds of KB).
            if (ran < handed) {
                PACKETS[(int) (ran & MASK)] = null;
            }
            ran = Math.min(ran + 1, handed);
            if (play) {
                applied = -1;
            }
            return;
        }
        // The slots skipped belong to tasks the game dropped: they let go of their packets.
        for (long s = ran; s < r; s++) {
            PACKETS[(int) (s & MASK)] = null;
        }
        int slot = (int) (r & MASK);
        PACKETS[slot] = null;
        ran = r + 1;
        if (!play) {
            return;
        }
        // Past a full ring the stamp was written over: unknown until the game thread catches up.
        applied = handed - r <= RING ? STAMPS[slot] : -1;
    }

    /**
     * Every packet of the server up to this number (from its Join Game, 1) was handled; -1 when not
     * known. Game thread.
     */
    public static long applied() {
        return applied;
    }
}
