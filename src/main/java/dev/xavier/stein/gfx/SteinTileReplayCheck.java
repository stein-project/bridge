/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import dev.xavier.stein.bridge.core.Log;

import org.lwjgl.system.MemoryUtil;

/**
 * The retainTilesReplayCheck mode: every re-emitted retained tile is also computed the plain way
 * (corrected matrix rows read back from the native instance copy, groups straight from the map) and
 * the mismatches with the fast path are counted and printed every 5 s.
 *
 * <p>Pipeline: Stein GFX only.
 */
final class SteinTileReplayCheck {
    private SteinTileReplayCheck() {}

    static final boolean ENABLED =
            SteinTileRetain.ENABLED && SteinGfxOptions.RETAIN_TILES_REPLAY_CHECK;

    private static long instances;
    private static long instanceMismatches;
    private static long groups;
    private static long groupMismatches;
    private static long reportAt = System.nanoTime();

    /**
     * The 3 corrected matrix rows {@code rows} (as written to the ring) against the plain
     * computation from the native instance at {@code src}.
     */
    static void instance(long src, double[] fix, float[] rows) {
        instances++;
        for (int r = 0; r < 3; r++) {
            double c0 = fix[r * 4];
            double c1 = fix[r * 4 + 1];
            double c2 = fix[r * 4 + 2];
            for (int c = 0; c < 4; c++) {
                double v =
                        c0 * MemoryUtil.memGetFloat(src + c * 4L)
                                + c1 * MemoryUtil.memGetFloat(src + 16 + c * 4L)
                                + c2 * MemoryUtil.memGetFloat(src + 32 + c * 4L);
                if (c == 3) {
                    v += fix[r * 4 + 3];
                }
                if (Float.floatToRawIntBits((float) v)
                        != Float.floatToRawIntBits(rows[r * 4 + c])) {
                    instanceMismatches++;
                    return;
                }
            }
        }
    }

    /** A group served from the direct cache: {@code same} = the map has the same one. */
    static void group(boolean same) {
        groups++;
        if (!same) {
            groupMismatches++;
        }
    }

    /** Once per entity pass: the numbers every 5 s. */
    static void report() {
        long now = System.nanoTime();
        if (now - reportAt < 5_000_000_000L) {
            return;
        }
        reportAt = now;
        Log.info(
                "gfx",
                "retained replay check: "
                        + instances
                        + " corrected instances, "
                        + instanceMismatches
                        + " mismatches; "
                        + groups
                        + " cached groups, "
                        + groupMismatches
                        + " mismatches");
    }
}
