/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

package dev.xavier.stein.gfx;

import static dev.xavier.stein.gfx.SteinDeferredGroups.*;

import dev.xavier.stein.bridge.core.Log;

import java.util.IdentityHashMap;

/**
 * Verification mode of the strict-order segments ({@code -Dsteinbridge.gfx.segmentCheck=true}):
 * checks at each flush that the segments cover every command of every group in order.
 *
 * <p>Pipeline: Stein GFX only.
 */
final class SteinSegmentCheck {

    /**
     * {@code -Dsteinbridge.gfx.segmentCheck=true}: at flush, checks that each group's segments
     * cover all its commands, in order and without gaps (what the per-returning-group flush would
     * draw, in the same sequence), and counts the segments and flushes of the extra passes.
     */
    static final boolean SEGMENT_CHECK = SteinGfxOptions.SEGMENT_CHECK;

    /**
     * The flush's segments, checked (with {@link #SEGMENT_CHECK}): each group covered from 0 to n,
     * in order.
     */
    static void checkSegments() {
        IdentityHashMap<Group, Integer> next = new IdentityHashMap<>();
        int bad = 0;
        for (int i = 0; i < segs; i++) {
            Group g = segGroup[i];
            int at = next.getOrDefault(g, 0);
            if (segFrom[i] != at || segTo[i] < segFrom[i]) {
                bad++;
            }
            next.put(g, segTo[i]);
        }
        for (Group g : order) {
            if (g.n > 0 && next.getOrDefault(g, -1) != g.n) {
                bad++;
            }
        }
        segChecked++;
        segTotal += segs;
        if (bad > 0) {
            segBad++;
            if (segBad <= 10) {
                Log.info(
                        "gfx",
                        "strict-order segments do not cover the commands ("
                                + bad
                                + " failures in "
                                + segs
                                + " segments)");
            }
        }
    }

    static long segChecked;
    static long segTotal;
    static long segBad;

    /** Prints the last interval's counts (strict order in segments) and starts the next. */
    static void report() {
        Log.info(
                "gfx",
                "strict order in segments (5 s): "
                        + segChecked
                        + " flushes checked, "
                        + (segChecked > 0 ? segTotal / segChecked : 0)
                        + " segments per flush, "
                        + segBad
                        + " failed");
        segChecked = 0;
        segTotal = 0;
        segBad = 0;
    }
}
