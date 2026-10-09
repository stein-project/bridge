/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx.gl;

import dev.xavier.stein.bridge.core.Log;
import dev.xavier.stein.gfx.SteinGfxOptions;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Coverage audit of the renderer ({@code -Dsteinbridge.gfx.audit=true}, together with {@code
 * -Dsteinbridge.gfx=true}): every draw that reaches the driver other than through the Stein backend
 * (glDrawArrays from the game and from the engine, glCallList, glBegin) is counted by its origin
 * (the first frames of the caller's stack) and by the current station — the label that the check
 * scene sets ({@link #station}). The goal is zero at every station.
 *
 * <p>The Stein backend calls OpenGL directly and does not go through here; the game goes through
 * the patcher's rules ({@link SteinCoreGl}), and the engine calls {@link #draw} before each draw of
 * its own. A draw into a display list being compiled does not count (it does not go to the screen).
 *
 * <p>Pipeline: Stein GFX only.
 */
public final class SteinGlAudit {

    public static final boolean ON = SteinGlOptions.AUDIT && SteinGfxOptions.ENABLED;

    private static final HashMap<String, long[]> SITES = new HashMap<>();

    /** Total draws per station, in the order the stations came. */
    private static final LinkedHashMap<String, long[]> TOTALS = new LinkedHashMap<>();

    private static String station = "start";
    private static long dumpAt = System.nanoTime();

    /**
     * A display list being compiled (glNewList to glEndList): what is drawn goes into it, not to
     * the screen.
     */
    static boolean compiling;

    /**
     * The reason for the next outside draw (whoever decided to draw immediately sets it here; it
     * goes into the key and is then cleared).
     */
    public static String why;

    static {
        if (ON) {
            TOTALS.put(station, new long[1]);
            Runtime.getRuntime()
                    .addShutdownHook(new Thread(SteinGlAudit::summary, "stein-gfx-audit"));
        }
    }

    private SteinGlAudit() {}

    /** A draw outside the Stein backend, right now. */
    public static void draw(String what) {
        if (!ON || compiling) {
            return;
        }
        StackTraceElement[] st = new Throwable().getStackTrace();
        StringBuilder key = new StringBuilder(what);
        if (why != null) {
            key.append(" [").append(why).append(']');
            why = null;
        }
        int n = 0;
        for (StackTraceElement e : st) {
            String c = e.getClassName();
            if (c.startsWith("dev.xavier.stein.gfx.gl.SteinGlAudit")
                    || c.startsWith("dev.xavier.stein.gfx.gl.SteinCoreGl")
                    || c.startsWith("java.lang.invoke.")) {
                continue;
            }
            key.append(" <- ").append(c).append('.').append(e.getMethodName());
            if (++n == 6) {
                break;
            }
        }
        synchronized (SITES) {
            SITES.computeIfAbsent(key.toString(), k -> new long[1])[0]++;
            TOTALS.computeIfAbsent(station, k -> new long[1])[0]++;
        }
        long now = System.nanoTime();
        if (now - dumpAt > 5_000_000_000L) {
            dump();
        }
    }

    /**
     * The check scene moved to the station {@code name}: what came before is logged under the
     * previous label.
     */
    public static void station(String name) {
        if (!ON) {
            return;
        }
        dump();
        synchronized (SITES) {
            station = name;
            TOTALS.putIfAbsent(name, new long[1]);
        }
        Log.info("audit", "station " + name);
    }

    private static void dump() {
        synchronized (SITES) {
            dumpAt = System.nanoTime();
            if (SITES.isEmpty()) {
                return;
            }
            Log.info("audit", "outside Stein GFX at " + station + ":");
            SITES.entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
                    .limit(15)
                    .forEach(e -> Log.info("audit", e.getValue()[0] + "  " + e.getKey()));
            SITES.clear();
        }
    }

    private static void summary() {
        dump();
        StringBuilder b = new StringBuilder("total by station:");
        synchronized (SITES) {
            for (Map.Entry<String, long[]> e : TOTALS.entrySet()) {
                b.append(' ').append(e.getKey()).append('=').append(e.getValue()[0]);
            }
        }
        Log.info("audit", b.toString());
    }
}
