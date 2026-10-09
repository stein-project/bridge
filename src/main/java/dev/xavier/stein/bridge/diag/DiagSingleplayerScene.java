/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.diag;

import dev.xavier.stein.bridge.core.Log;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The {@code steinbridge.diag.singleplayer} scene: opens a world on the integrated server and
 * reports, every second, the queue delays, tick times, ping and memory.
 */
final class DiagSingleplayerScene {
    private DiagSingleplayerScene() {}

    /**
     * {@code -Dsteinbridge.diag.singleplayer=NAME} (with {@code singleplayerAtSec}, default 6):
     * opens the world NAME on the integrated server (creates it, with a fixed seed, if it does not
     * exist) and, once it is up, prints every second: the largest delay of the server queue (a task
     * posted by the client every 100 ms — the queue of the player's packets: breaking a block,
     * chat), that of the client queue (a task posted by the server — the damage and drop one),
     * ticks per second and the server's tick time, and the player's ping as measured by it.
     * Reported by players: in singleplayer, damage, drops and chat were delayed "as if on a
     * server".
     */
    private static boolean spLaunched;

    private static boolean spFailed;
    private static long spNextProbe;
    private static long spNextReport;
    private static int spLastTicks = -1;
    private static long spLastReportAt;
    private static long spLastGc;

    /**
     * {@code stallServerAtSec} / {@code stallServerMs}: stops the server thread once (a check of
     * the watchdog).
     */
    private static boolean spStalled;

    private static final AtomicLong SP_SERVER_MAX = new AtomicLong();
    private static final AtomicLong SP_CLIENT_MAX = new AtomicLong();

    private static void singleplayer(long elapsed) {
        Minecraft mc = DiagGame.mc();
        if (!spLaunched) {
            if (elapsed < DiagOptions.SP_AT * 1_000_000_000L || DiagGame.world() != null) {
                return;
            }
            spLaunched = true;
            openWorld(mc);
            return;
        }
        MinecraftServer server = mc.getIntegratedServer();
        if (server == null || DiagGame.world() == null) {
            return;
        }
        long now = System.nanoTime();
        stallServer(server, elapsed);
        probeQueues(server, mc, now);
        if (now < spNextReport) {
            return;
        }
        report(server, now);
    }

    /** Opens the world on the integrated server. */
    private static void openWorld(Minecraft mc) {
        WorldSettings settings =
                new WorldSettings(
                        20260913L,
                        WorldSettings.GameType.getByID(0),
                        true,
                        false,
                        WorldType.DEFAULT);
        // Through the client's task queue: loading draws its own progress screen
        // (Display.update inside it), and here we are already inside a Display.update.
        mc.addScheduledTask(
                (Runnable)
                        () -> {
                            try {
                                mc.launchIntegratedServer(
                                        DiagOptions.SP_WORLD, DiagOptions.SP_WORLD, settings);
                            } catch (RuntimeException e) {
                                Log.info("diag", "singleplayer: failed to open the world: " + e);
                            }
                        });
        Log.info("diag", "singleplayer: opening the world " + DiagOptions.SP_WORLD);
    }

    /** Stops the server thread once, at {@code stallServerAtSec}. */
    private static void stallServer(MinecraftServer server, long elapsed) {
        if (DiagOptions.SP_STALL_AT >= 0
                && !spStalled
                && elapsed >= DiagOptions.SP_STALL_AT * 1_000_000_000L) {
            // Singleplayer watchdog check (engine/ServerWatch): the server thread stops on purpose.
            spStalled = true;
            server.addScheduledTask(
                    (Runnable)
                            () -> {
                                try {
                                    Thread.sleep(DiagOptions.SP_STALL_MS);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                            });
            Log.info("diag", "singleplayer: server stalled for " + DiagOptions.SP_STALL_MS + " ms");
        }
    }

    /** Every 100 ms posts a task to the server queue, which posts one back to the client queue. */
    private static void probeQueues(MinecraftServer server, Minecraft mc, long now) {
        if (now >= spNextProbe) {
            spNextProbe = now + 100_000_000L;
            long posted = now;
            server.addScheduledTask(
                    (Runnable)
                            () -> {
                                long onServer = System.nanoTime();
                                SP_SERVER_MAX.accumulateAndGet(onServer - posted, Math::max);
                                mc.addScheduledTask(
                                        (Runnable)
                                                () ->
                                                        SP_CLIENT_MAX.accumulateAndGet(
                                                                System.nanoTime() - onServer,
                                                                Math::max));
                            });
        }
    }

    /** Once a second: ticks per second, tick times, queue delays, ping, GC and heap. */
    private static void report(MinecraftServer server, long now) {
        int ticks = server.getTickCounter();
        if (spLastTicks >= 0) {
            double secs = (now - spLastReportAt) / 1e9;
            long[] times = server.tickTimeArray;
            int n = Math.min(20, Math.max(0, ticks - spLastTicks));
            long sum = 0;
            long max = 0;
            for (int i = 1; i <= n; i++) {
                long t = times[Math.floorMod(ticks - i, times.length)];
                sum += t;
                max = Math.max(max, t);
            }
            List<EntityPlayerMP> players = server.getConfigurationManager().playerEntityList;
            int ping = players.isEmpty() ? -1 : players.get(0).ping;
            // Garbage collection in the second (summed time of all collectors) and memory in use:
            // in singleplayer the world is in memory twice (integrated server and client).
            long gc = 0;
            for (GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) {
                gc += Math.max(0L, b.getCollectionTime());
            }
            Runtime rt = Runtime.getRuntime();
            Log.info(
                    "diag",
                    "singleplayer: tps %.1f | mean tick %.1f ms, max %.1f ms |"
                            + " server queue up to %d ms | client queue up to %d ms | ping"
                            + " %d ms | gc %d ms | heap %d/%d MB",
                    (ticks - spLastTicks) / secs,
                    n == 0 ? 0.0 : sum / (double) n / 1e6,
                    max / 1e6,
                    SP_SERVER_MAX.getAndSet(0) / 1_000_000L,
                    SP_CLIENT_MAX.getAndSet(0) / 1_000_000L,
                    ping,
                    gc - spLastGc,
                    (rt.totalMemory() - rt.freeMemory()) >> 20,
                    rt.maxMemory() >> 20);
            spLastGc = gc;
        } else {
            for (GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) {
                spLastGc += Math.max(0L, b.getCollectionTime());
            }
        }
        spLastTicks = ticks;
        spLastReportAt = now;
        spNextReport = now + 1_000_000_000L;
    }

    static void frame() {
        if (DiagOptions.SP_WORLD != null && !spFailed) {
            try {
                singleplayer(Scenes.elapsed());
            } catch (RuntimeException e) {
                spFailed = true;
                Log.info("diag", "singleplayer: turned off by an error: " + e);
            }
        }
    }
}
