/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import dev.xavier.stein.bridge.core.SteinLog;
import dev.xavier.stein.bridge.core.SteinLog.Topic;

import net.minecraft.client.Minecraft;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;

/**
 * Multiplayer watchdog: when the game keeps running but stops talking to the server, it writes a
 * snapshot of all threads to the log (the game's latest.log). {@link ServerWatch} handles
 * singleplayer and measures lag; here the subject is silence, on a real server.
 *
 * <p>Player report (19 to 23/09/2026): every now and then no chat message arrives and items cannot
 * be used, but the game stays smooth, "as if the internet had dropped without dropping". In the 15
 * logs they sent there is no trace of it — no error, no disconnection, and the session of the 19th
 * simply stops in the middle of a chat line. No log records a stalled thread: a snapshot of the
 * threads at the moment of the freeze is needed.
 *
 * <p>Three counters, none of them expensive: packets <b>arriving</b> and <b>leaving</b> (the {@link
 * PacketLog}, which every packet passes through) and the game's <b>ticks</b>. With the player in a
 * world:
 *
 * <ul>
 *   <li>nothing arrives and nothing leaves = the connection went quiet on both sides (the reported
 *       case);
 *   <li>it arrives and does not leave, or the reverse = only one direction stalled, and the
 *       snapshot says on which thread;
 *   <li>ticks stopped = the game thread froze (then the game also stops drawing).
 * </ul>
 *
 * <p>The snapshot goes to the game log and, with the Stein log on, to it as well; and it is this
 * same thread, which already wakes up every second, that writes the per-second beat (frames, ticks,
 * memory) and the network summary to the Stein log — with the log off, it does none of that.
 *
 * <p>{@code -Dsteinbridge.opt.noWatchdog=true} turns it off; {@code
 * -Dsteinbridge.watchdog.silenceSec} (default 20) and {@code -Dsteinbridge.watchdog.tickSec}
 * (default 10) change the patience. At most three snapshots per freeze, spaced out, and one line
 * when it returns to normal.
 */
public final class Watchdog {

    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final long BEAT_MS = 1000L;

    private static final boolean OFF = EngineOptions.NO_WATCHDOG;
    private static final long SILENCE_NS = EngineOptions.WATCHDOG_SILENCE_SEC * NANOS_PER_SECOND;
    private static final long TICK_NS = EngineOptions.WATCHDOG_TICK_SEC * NANOS_PER_SECOND;

    /** Snapshots per freeze: one as soon as it is noticed and two more while it lasts. */
    private static final int MAX_DUMPS = 3;

    private static final long BETWEEN_DUMPS_NS = 30_000_000_000L;

    /** Enough to see where the thread is; the whole stack would only fill the log. */
    private static final int STACK_LINES = 25;

    private static volatile long in;
    private static volatile long out;
    private static volatile long ticks;
    private static volatile boolean started;
    private static volatile boolean broken;
    private static long lastGc;

    private Watchdog() {}

    /**
     * A packet from the server arrived (network thread). It only adds: nothing may delay the
     * packet's path.
     */
    public static void packetIn() {
        in++;
    }

    /** A packet of ours went into the channel. */
    public static void packetOut() {
        out++;
    }

    /** End of a game tick (game thread); also starts the watchdog the first time. */
    public static void tick() {
        ticks++;
        if (!started) {
            start();
        }
    }

    private static synchronized void start() {
        if (OFF || started) {
            return;
        }
        started = true;
        Thread t = new Thread(Watchdog::run, "stein-watchdog");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private static void run() {
        long lastIn = -1;
        long lastOut = -1;
        long lastTicks = -1;
        long now = System.nanoTime();
        long inAt = now;
        long outAt = now;
        long ticksAt = now;
        long stallStart = 0;
        long lastDump = 0;
        int dumps = 0;
        while (true) {
            try {
                Thread.sleep(BEAT_MS);
            } catch (InterruptedException e) {
                return;
            }
            now = System.nanoTime();
            long i = in;
            long o = out;
            long k = ticks;
            try {
                // Garbage collection is always read, on or off: so the first line after turning
                // the log on already shows that second's figures, and not everything accumulated
                // since the game started.
                long gc = gcMillis();
                if (SteinLog.on(Topic.PERF)) {
                    pulse(lastTicks < 0 ? 0 : k - lastTicks, gc - lastGc);
                }
                lastGc = gc;
                if (SteinLog.on(Topic.NET)) {
                    PacketLog.report();
                }
            } catch (Throwable t) {
                // the watchdog exists for the freeze: writing to the log must not bring it down
                SteinLog.note("watchdog", "the per-second beat failed: " + t);
            }
            if (i != lastIn) {
                lastIn = i;
                inAt = now;
            }
            if (o != lastOut) {
                lastOut = o;
                outAt = now;
            }
            if (k != lastTicks) {
                lastTicks = k;
                ticksAt = now;
            }
            if (!inWorld()) {
                inAt = now; // in the menu there is no server: silence is expected
                outAt = now;
                ticksAt = now;
                continue;
            }
            long silenceIn = now - inAt;
            long silenceOut = now - outAt;
            long stopped = now - ticksAt;
            if (silenceIn < SILENCE_NS && silenceOut < SILENCE_NS && stopped < TICK_NS) {
                if (stallStart != 0) {
                    SteinLog.note(
                            "watchdog",
                            "back to normal after " + (now - stallStart) / NANOS_PER_SECOND + " s");
                    stallStart = 0;
                    dumps = 0;
                }
                continue;
            }
            if (stallStart == 0) {
                stallStart = now;
                lastDump = 0;
            }
            if (dumps >= MAX_DUMPS || (lastDump != 0 && now - lastDump < BETWEEN_DUMPS_NS)) {
                continue;
            }
            lastDump = now;
            dumps++;
            SteinLog.note(
                    "watchdog",
                    String.format(
                            "stalled: nothing received for %d s, nothing sent for %d s, no tick for"
                                    + " %d s | %d packets received and %d sent in the session"
                                    + " | dump %d of %d:",
                            silenceIn / NANOS_PER_SECOND,
                            silenceOut / NANOS_PER_SECOND,
                            stopped / NANOS_PER_SECOND,
                            i,
                            o,
                            dumps,
                            MAX_DUMPS));
            try {
                dump();
            } catch (Throwable t) {
                SteinLog.note("watchdog", "the thread dump failed: " + t);
            }
        }
    }

    /**
     * The per-second beat in the Stein log: frames, ticks, memory and garbage collection. With the
     * log on, it is the backdrop for the network lines — it shows whether the freeze came together
     * with a memory pause.
     */
    private static void pulse(long ticksNow, long sinceGc) {
        Runtime rt = Runtime.getRuntime();
        SteinLog.debug(
                Topic.PERF,
                "game",
                fps()
                        + " frames, "
                        + ticksNow
                        + " ticks, memory "
                        + ((rt.totalMemory() - rt.freeMemory()) >> 20)
                        + " of "
                        + (rt.maxMemory() >> 20)
                        + " MB, garbage collection "
                        + sinceGc
                        + " ms");
    }

    private static long gcMillis() {
        long total = 0;
        for (GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) {
            total += Math.max(0L, b.getCollectionTime());
        }
        return total;
    }

    /** Minecraft.debugFPS, the same number as F3; -1 if the game has not counted any yet. */
    private static int fps() {
        try {
            return Minecraft.debugFPS;
        } catch (Throwable e) {
            return -1;
        }
    }

    /** The player is in a world: outside one there is nobody to talk to. */
    private static boolean inWorld() {
        if (broken) {
            return false;
        }
        try {
            Minecraft mc = Minecraft.getMinecraft();
            return mc.theWorld != null && mc.thePlayer != null;
        } catch (Throwable t) {
            broken = true;
            SteinLog.note("watchdog", "no access to the game, watchdog off: " + t);
            return false;
        }
    }

    /**
     * All the threads that matter, with the stack, what each one waits on and who holds the lock.
     * It is built by Java itself (ThreadMXBean), which can also point out a deadlock.
     */
    private static void dump() {
        ThreadMXBean mx = ManagementFactory.getThreadMXBean();
        ThreadInfo[] all;
        try {
            long[] deadlocked = mx.findDeadlockedThreads();
            if (deadlocked != null) {
                SteinLog.note("watchdog", "DEADLOCK between " + deadlocked.length + " thread(s)");
            }
            all = mx.dumpAllThreads(true, true);
        } catch (UnsupportedOperationException e) { // JVM without locks in the snapshot
            all = mx.getThreadInfo(mx.getAllThreadIds(), STACK_LINES);
        }
        for (ThreadInfo t : all) {
            if (t == null || !interesting(t.getThreadName())) {
                continue;
            }
            StringBuilder b =
                    new StringBuilder("\"")
                            .append(t.getThreadName())
                            .append("\" ")
                            .append(t.getThreadState());
            LockInfo lock = t.getLockInfo();
            if (lock != null) {
                b.append(" waiting on ").append(lock);
                if (t.getLockOwnerName() != null) {
                    b.append(" (held by \"").append(t.getLockOwnerName()).append("\")");
                }
            }
            SteinLog.note("watchdog", b.toString());
            StackTraceElement[] stack = t.getStackTrace();
            for (int i = 0; i < stack.length && i < STACK_LINES; i++) {
                SteinLog.note("watchdog", "    " + stack[i]);
                for (MonitorInfo m : t.getLockedMonitors()) {
                    if (m.getLockedStackDepth() == i) {
                        SteinLog.note("watchdog", "    - holding " + m);
                    }
                }
            }
        }
    }

    /**
     * The game, network and mod threads; those of sound, logging and the JVM itself would only add
     * volume.
     */
    private static boolean interesting(String name) {
        if (name == null) {
            return false;
        }
        return name.startsWith("Client thread")
                || name.startsWith("Server thread")
                || name.startsWith("Netty")
                || name.startsWith("main")
                || name.startsWith("stein")
                || name.startsWith("Chunk")
                || name.startsWith("Thread-")
                || name.contains("nioEventLoop")
                || name.startsWith("Timer")
                || name.startsWith("Snooper");
    }
}
