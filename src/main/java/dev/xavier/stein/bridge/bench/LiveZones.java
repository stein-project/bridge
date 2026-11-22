/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.bench;

/**
 * The benchmark's CPU zones ({@link BenchZones}) while the game is played, without the benchmark:
 * for a profiler (the Loader's, which a mod shows) that wants the game's and the Bridge's share of
 * the frame next to the mods'. Off by default; on, each zone costs two {@code System.nanoTime} and
 * every frame adds its zones to a window that the reader takes and clears.
 *
 * <p>On the render thread (the game thread in 1.8.9), like the zones themselves.
 */
public final class LiveZones {

    private static final long[] WINDOW = new long[BenchZones.COUNT];
    private static int frames;

    private LiveZones() {}

    /** Turns the zones on or off; either way the window starts over. */
    public static void set(boolean on) {
        if (BenchZones.live == on) {
            return;
        }
        BenchZones.forgetOpen();
        if (!BenchOptions.ON) {
            BenchZones.clear(); // the benchmark clears them itself when it runs
        }
        for (int i = 0; i < WINDOW.length; i++) {
            WINDOW[i] = 0L;
        }
        frames = 0;
        BenchZones.live = on;
    }

    /** Whether the zones are being timed for a profiler. */
    public static boolean on() {
        return BenchZones.live;
    }

    /** In Display.update, before the benchmark's row: the frame's zones go into the window. */
    public static void frameEnd() {
        if (!BenchZones.live) {
            return;
        }
        BenchZones.tickRest();
        long[] spent = BenchZones.SPENT;
        for (int i = 0; i < WINDOW.length; i++) {
            WINDOW[i] += spent[i];
        }
        frames++;
        if (!BenchOptions.ON) {
            BenchZones.clear();
        }
    }

    /**
     * Copies the window's nanoseconds per zone into {@code out} (at least {@link #count} long),
     * starts a new window and answers how many frames it had.
     */
    public static int take(long[] out) {
        int n = Math.min(out.length, WINDOW.length);
        for (int i = 0; i < n; i++) {
            out[i] = WINDOW[i];
        }
        for (int i = 0; i < WINDOW.length; i++) {
            WINDOW[i] = 0L;
        }
        int f = frames;
        frames = 0;
        return f;
    }

    /** How many zones there are. */
    public static int count() {
        return BenchZones.COUNT;
    }

    /** The zone's name in the benchmark's report ("world", "terrain", "swap"...). */
    public static String name(int zone) {
        return BenchZones.NAMES[zone];
    }

    /** The zone this one is inside of, or -1 when it is directly in the frame. */
    public static int parent(int zone) {
        return BenchZones.PARENT[zone];
    }
}
