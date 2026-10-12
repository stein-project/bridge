/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import dev.xavier.stein.bridge.core.Log;

/**
 * The counters of {@link SteinTextCache} and their 5-second report ({@code
 * -Dsteinbridge.gfx.deferredStats=true}, or the text cache check).
 *
 * <p>Pipeline: Stein GFX only.
 */
final class SteinTextCacheStats {

    private static final boolean STATS = SteinGfxOptions.DEFERRED_STATS;
    private static final long STATS_INTERVAL_NANOS = 5_000_000_000L;

    static long hits;
    static long misses;
    static long bypass;
    static long evicted;
    static long firstSight;
    private static long statsAt = System.nanoTime();

    private SteinTextCacheStats() {}

    /** Once per frame (end of the deferred pass). */
    static void report() {
        if (!STATS && !SteinTextCache.CHECK) {
            return;
        }
        long now = System.nanoTime();
        if (now - statsAt < STATS_INTERVAL_NANOS) {
            return;
        }
        statsAt = now;
        if (SteinTextCache.CHECK) {
            SteinTextCacheCheck.report();
        }
        Log.info(
                "gfx",
                "cached text (5 s): "
                        + hits
                        + " repeated, "
                        + misses
                        + " assembled, "
                        + firstSight
                        + " first sightings, "
                        + bypass
                        + " bypassed, "
                        + evicted
                        + " evicted; "
                        + SteinTextCache.entries
                        + " assemblies in "
                        + SteinTextCache.nodes
                        + " texts, "
                        + (SteinTextCache.bytes >> 10)
                        + " KB, probe "
                        + SteinTextCache.longestProbe());
        String gpu = SteinTextGpu.REQUESTED ? SteinTextGpu.stats() : null;
        if (gpu != null) {
            Log.info("gfx", gpu);
        }
        hits = 0;
        misses = 0;
        bypass = 0;
        evicted = 0;
        firstSight = 0;
    }
}
