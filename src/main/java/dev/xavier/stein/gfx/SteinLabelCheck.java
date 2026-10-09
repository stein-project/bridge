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
 * {@code -Dsteinbridge.gfx.gpuLabelCheck=true}: the direct label generated without recording (if
 * possible) and the recorded game path, compared command by command (key, first vertex, vertices)
 * and each one's candidate record, byte by byte. Draws through the game.
 *
 * <p>Pipeline: Stein GFX only.
 */
final class SteinLabelCheck {

    static final long[] dry = new long[4 * 64];
    static int dryN;
    static int dryLabels;
    static final long dryRecord = MemoryUtil.nmemAlloc(4L * SteinEntityDeferred.RECORD_BYTES);

    private SteinLabelCheck() {}

    /**
     * Check: the direct label generated without recording (if possible) and the recorded game path;
     * compares command by command (key, first vertex, vertices) and each one's candidate record,
     * byte by byte. Draws through the game.
     */
    static void check(int n, SteinLabelTemplates.Template t) throws Throwable {
        boolean sneak = SteinLabelContext.sneak;
        String name = SteinLabelContext.name;
        boolean compare = n > 0 && t.valid;
        dryN = 0;
        dryLabels = 0;
        if (compare) {
            for (int i = 0; i < n; i++) {
                SteinLabelEmit.emit(SteinNameLabels.labels[i], t, dry);
            }
        }
        SteinEntityDeferred.probeBegin();
        int c;
        try {
            SteinLabelTail.tail();
        } finally {
            c = SteinEntityDeferred.probeEnd();
        }
        if (compare) {
            String why = compareCommands(c);
            if (why == null) {
                SteinLabelStats.checkedOk++;
            } else {
                SteinLabelStats.checkedBad++;
                if (SteinLabelStats.reported++ < 20) {
                    Log.info(
                            "gfx",
                            "direct label differs from the game ("
                                    + (sneak ? "sneaking" : "standing")
                                    + ", '"
                                    + name
                                    + "'): "
                                    + why);
                }
            }
        } else {
            SteinLabelStats.checkedSkip++;
        }
        if (c > 0) {
            SteinLabelTemplates.learn(sneak ? SteinNameLabels.SNEAK : SteinNameLabels.LABEL, c);
        }
    }

    /** What differs between the {@code c} game commands and the direct ones; null = equal. */
    private static String compareCommands(int c) {
        if (c != dryN) {
            return "commands " + dryN + " direct x " + c + " game";
        }
        for (int i = 0; i < c; i++) {
            long rec = dryRecord + dry[i * 4 + 3] * SteinEntityDeferred.RECORD_BYTES;
            if (dry[i * 4] != SteinEntityDeferred.probeKey(i)) {
                return "command key "
                        + i
                        + ": "
                        + Long.toHexString(dry[i * 4])
                        + " x "
                        + Long.toHexString(SteinEntityDeferred.probeKey(i));
            }
            if (dry[i * 4 + 1] != SteinEntityDeferred.probeFirst(i)
                    || dry[i * 4 + 2] != SteinEntityDeferred.probeVerts(i)) {
                return "command run "
                        + i
                        + ": "
                        + dry[i * 4 + 1]
                        + "+"
                        + dry[i * 4 + 2]
                        + " x "
                        + SteinEntityDeferred.probeFirst(i)
                        + "+"
                        + SteinEntityDeferred.probeVerts(i);
            }
            long g = SteinEntityDeferred.probeRecord(i);
            for (int b = 0; b < SteinEntityDeferred.RECORD_BYTES; b += 4) {
                if (MemoryUtil.memGetInt(rec + b) != MemoryUtil.memGetInt(g + b)) {
                    return "command record "
                            + i
                            + ", byte "
                            + b
                            + ": "
                            + Float.intBitsToFloat(MemoryUtil.memGetInt(rec + b))
                            + " x "
                            + Float.intBitsToFloat(MemoryUtil.memGetInt(g + b));
                }
            }
        }
        return null;
    }
}
