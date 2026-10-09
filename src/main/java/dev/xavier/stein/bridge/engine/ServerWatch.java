/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import dev.xavier.stein.bridge.core.Log;

import net.minecraft.client.Minecraft;
import net.minecraft.server.integrated.IntegratedServer;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Singleplayer watchdog: when the integrated server lags, a line in the log (the game's latest.log)
 * says how much, on which side and with how much memory. Reported by players (13/09/2026): in
 * singleplayer, damage, drops and chat were delayed "as if on a server", and then caught up. In the
 * benchmark (new world, walking 40 s through the terrain the server generates, Ultra, 2 GB and 1
 * GB) it did not reproduce: 20 ticks per second, ticks of 2 to 7 ms, no waiting on memory. This
 * records what happens on the machine of whoever is playing.
 *
 * <p>Every frame (Display.update), with an integrated server: a task on the server queue every 250
 * ms — the queue of the player's packets (breaking a block, chat, a hit) — and, from it, one on the
 * client queue (the damage and drop one); every second, the server's tick times. It logs a tick
 * above 250 ms or a wait above 500 ms in either queue, with the second's garbage collection and the
 * memory in use; at most one line per second. The first 15 s of the world are left out (joining
 * always has one long tick, as in the game). Without an integrated server (multiplayer), one field
 * read per frame. {@code -Dsteinbridge.opt.noServerWatch=true} turns it off.
 */
public final class ServerWatch {

    private static final boolean OFF = EngineOptions.NO_SERVER_WATCH;
    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final long NANOS_PER_MILLI = 1_000_000L;
    private static final long PROBE_NS = 250_000_000L;
    private static final long TICK_LIMIT_NS = 250_000_000L;
    private static final long QUEUE_LIMIT_NS = 500_000_000L;
    private static final long WARMUP_NS = 15_000_000_000L;

    private static boolean broken;
    private static Object lastServer;
    private static long watchFrom;
    private static long nextProbe;
    private static long nextReport;
    private static int lastTicks = -1;
    private static long lastGc;
    private static final AtomicLong SERVER_MAX = new AtomicLong();
    private static final AtomicLong CLIENT_MAX = new AtomicLong();

    private ServerWatch() {}

    /** Display.update, every frame. */
    public static void frame() {
        if (OFF || broken) {
            return;
        }
        try {
            watch();
        } catch (Throwable t) {
            broken = true;
            Log.info("server", "singleplayer watchdog off: " + t);
        }
    }

    private static void watch() {
        Minecraft mc = Minecraft.getMinecraft();
        IntegratedServer server = mc.getIntegratedServer();
        long now = System.nanoTime();
        if (server != lastServer) { // world opened, closed or switched
            lastServer = server;
            watchFrom = now + WARMUP_NS;
            lastTicks = -1;
            SERVER_MAX.set(0);
            CLIENT_MAX.set(0);
        }
        if (server == null) {
            return;
        }
        if (now >= nextProbe) {
            nextProbe = now + PROBE_NS;
            postProbe(server, mc, now);
        }
        if (now < nextReport) {
            return;
        }
        nextReport = now + NANOS_PER_SECOND;
        int ticks = server.getTickCounter();
        long gc = gcMillis();
        long serverWait = SERVER_MAX.getAndSet(0);
        long clientWait = CLIENT_MAX.getAndSet(0);
        if (lastTicks >= 0 && now >= watchFrom) {
            long[] times = server.tickTimeArray;
            int n = Math.min(times.length, Math.max(0, ticks - lastTicks));
            long longest = 0;
            for (int i = 1; i <= n; i++) {
                longest = Math.max(longest, times[Math.floorMod(ticks - i, times.length)]);
            }
            if (longest > TICK_LIMIT_NS
                    || serverWait > QUEUE_LIMIT_NS
                    || clientWait > QUEUE_LIMIT_NS) {
                Runtime rt = Runtime.getRuntime();
                Log.info(
                        "server",
                        "singleplayer fell behind: longest tick %d ms, %d ticks in the"
                                + " second | player action waited up to %d ms on the server, the"
                                + " reply up to %d ms on the client | garbage collection %d ms in"
                                + " the second | memory %d of %d MB",
                        longest / NANOS_PER_MILLI,
                        ticks - lastTicks,
                        serverWait / NANOS_PER_MILLI,
                        clientWait / NANOS_PER_MILLI,
                        gc - lastGc,
                        (rt.totalMemory() - rt.freeMemory()) >> 20,
                        rt.maxMemory() >> 20);
            }
        }
        lastTicks = ticks;
        lastGc = gc;
    }

    /**
     * Posts a task to the server queue, which in turn posts one to the client queue: records how
     * long each one waited.
     */
    private static void postProbe(IntegratedServer server, Minecraft mc, long posted) {
        Runnable onServerQueue = () -> serverProbe(mc, posted);
        server.addScheduledTask(onServerQueue);
    }

    /** Runs on the server queue: records the wait, then posts the reply task to the client. */
    private static void serverProbe(Minecraft mc, long posted) {
        long onServer = System.nanoTime();
        recordMax(SERVER_MAX, onServer - posted);
        try {
            Runnable onClientQueue = () -> recordMax(CLIENT_MAX, System.nanoTime() - onServer);
            mc.addScheduledTask(onClientQueue);
        } catch (Throwable t) {
            // without the return, the line carries on with
            // the server queue
        }
    }

    private static void recordMax(AtomicLong max, long value) {
        max.accumulateAndGet(value, Math::max);
    }

    private static long gcMillis() {
        long total = 0;
        for (GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) {
            total += Math.max(0L, b.getCollectionTime());
        }
        return total;
    }
}
