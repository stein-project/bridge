/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import static dev.xavier.stein.gfx.SteinDeferredStats.*;

import dev.xavier.stein.bridge.core.Log;

/**
 * CPU time and draw counts of the extra passes (particles, sky, clouds, hand, HUD...) of {@link
 * SteinEntityDeferred}, reported every few seconds with {@code -Dsteinbridge.gfx.passTiming=true}.
 *
 * <p>Pipeline: Stein GFX only.
 */
final class SteinPassTiming {

    // ---- CPU time of the extra passes (from the start hook to the end, with the flush) ----

    /**
     * {@code -Dsteinbridge.gfx.passTiming=true}: every 5 s, ms per frame of particles, sky, clouds
     * and extras.
     */
    static final boolean TIMING = SteinGfxOptions.PASS_TIMING;

    static final String[] TIMED = {
        "particles", "sky", "clouds", "rain/border/outline/crack", "hand", "hud", "screen"
    };
    static final long[] timeAt = new long[7];
    static final long[] timeSum = new long[7];

    /**
     * The current extra pass (index in TIMED) and, per pass, draws recorded and immediate
     * (statistics).
     */
    static int sessionKind;

    static final long[] kindCaptured = new long[7];
    static final long[] kindImmediate = new long[7];
    static long flushPrep;
    static long flushOpen;
    static long flushRest;
    static long flushCount;
    static long timeFrames;
    static long timeReport = System.nanoTime();

    /**
     * The name of the current pass in the flushes ("entities", the extra pass or "loose"): the
     * label of the per-pass GPU time ({@code gpuPassTiming}). It stays in force until the next one
     * starts, because the flush at the end of each session runs after the session closes.
     */
    static String passName = "entities";

    static void timeStart(int k) {
        sessionKind = k;
        passName = TIMED[k];
        if (TIMING) {
            timeAt[k] = System.nanoTime();
        }
    }

    static void timeEnd(int k) {
        if (!TIMING) {
            return;
        }
        long now = System.nanoTime();
        timeSum[k] += now - timeAt[k];
        if (k == 1) {
            timeFrames++; // the sky runs once per frame (particles are two passes)
        }
        if (now - timeReport > STATS_INTERVAL_NANOS && timeFrames > 0) {
            StringBuilder b = new StringBuilder("extra passes, CPU per frame:");
            for (int i = 0; i < TIMED.length; i++) {
                b.append(' ')
                        .append(TIMED[i])
                        .append(String.format(" %.3f ms", timeSum[i] / 1e6 / timeFrames))
                        .append(" (")
                        .append(kindCaptured[i])
                        .append(" recorded, ")
                        .append(kindImmediate[i])
                        .append(" immediate)");
                kindCaptured[i] = 0;
                kindImmediate[i] = 0;
                timeSum[i] = 0;
            }
            if (flushCount > 0) {
                b.append(
                        String.format(
                                " | flushes %.1f per frame, each: prepare %.4f, open %.4f,"
                                        + " draw and close %.4f ms",
                                (double) flushCount / timeFrames,
                                flushPrep / 1e6 / flushCount,
                                flushOpen / 1e6 / flushCount,
                                flushRest / 1e6 / flushCount));
            }
            flushPrep = 0;
            flushOpen = 0;
            flushRest = 0;
            flushCount = 0;
            Log.info("gfx", b.toString());
            timeFrames = 0;
            timeReport = now;
        }
    }
}
