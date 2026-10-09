/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import static dev.xavier.stein.gfx.SteinDeferredGroups.*;
import static dev.xavier.stein.gfx.SteinDeferredResources.*;
import static dev.xavier.stein.gfx.SteinDeferredSessions.*;
import static dev.xavier.stein.gfx.SteinSegmentCheck.*;

import dev.xavier.stein.bridge.core.Log;

/**
 * Counters and the periodic report of {@link SteinEntityDeferred} ({@code
 * -Dsteinbridge.gfx.deferredStats=true}).
 *
 * <p>Pipeline: Stein GFX only.
 */
final class SteinDeferredStats {

    static final boolean STATS = SteinGfxOptions.DEFERRED_STATS;

    /** Interval of the periodic reports (here and in {@link SteinPassTiming}). */
    static final long STATS_INTERVAL_NANOS = 5_000_000_000L;

    // ---- numbers ----

    static long parts;
    static long draws;
    static long flushes;
    static long rejected;
    static long lostGlint;
    static long dynDraws;
    static long dynVerts;
    static long dynRejected;
    static long frames;
    static long statsAt = System.nanoTime();

    static void stats() {
        if (!STATS && !SEGMENT_CHECK) {
            return;
        }
        long now = System.nanoTime();
        if (now - statsAt < STATS_INTERVAL_NANOS || frames == 0) {
            return;
        }
        statsAt = now;
        if (SEGMENT_CHECK) {
            SteinSegmentCheck.report();
            if (!STATS) {
                return;
            }
        }
        Log.info(
                "gfx",
                "deferred per frame: "
                        + parts / frames
                        + " parts in "
                        + draws / frames
                        + " draws ("
                        + flushes / frames
                        + " flushes), "
                        + rejected / frames
                        + " models immediate, "
                        + groups.size()
                        + " groups, texture copies "
                        + SteinTextureArrays.takeCopies()
                        + (lostGlint > 0 ? ", glint lost " + lostGlint : ""));
        if (dynamicReady) {
            Log.info(
                    "gfx",
                    "dynamic per frame: "
                            + dynDraws / frames
                            + " draws recorded ("
                            + dynVerts / frames
                            + " vertices in the ring), "
                            + dynRejected / frames
                            + " immediate; extra passes: "
                            + sessionImmediate
                            + " immediate draws in 5 s");
            sessionImmediate = 0;
        }
        dynDraws = 0;
        dynVerts = 0;
        dynRejected = 0;
        parts = 0;
        draws = 0;
        flushes = 0;
        rejected = 0;
        lostGlint = 0;
        frames = 0;
    }
}
