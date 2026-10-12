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

import java.util.Arrays;

/**
 * The retainTilesCheck mode: compares what is retained with what the game's renderer generates now
 * and counts the divergences.
 *
 * <p>Pipeline: Stein GFX only.
 */
final class SteinTileCheck {
    private SteinTileCheck() {}

    static long checked;
    static long divergent;
    static int printed;

    /**
     * The retained one ({@code old}, taken to the current camera) against what the game's renderer
     * generates now ({@code now}).
     */
    static void compare(int kind, SteinTileEntry old, SteinTileEntry now, long pass) {
        checked++;
        String why = null;
        double[] fix = SteinCameraCorrection.correction(old, pass);
        if (fix == SteinCameraCorrection.NO_INVERSE) {
            why = "matrix without an inverse";
        } else if (old.n != now.n) {
            why = "instances " + old.n + " x " + now.n;
        } else if (!Arrays.equals(old.exit, now.exit) || !Arrays.equals(old.diff, now.diff)) {
            why = "exit state";
        } else {
            for (int i = 0; i < old.n && why == null; i++) {
                if (old.type[i] != now.type[i]
                        || old.gkey[i] != now.gkey[i]
                        || old.verts[i] != now.verts[i]
                        || old.tex[i] != now.tex[i]
                        || old.chk[i] != now.chk[i]) {
                    why = "instance " + i + ": group/geometry/texture";
                } else if (old.type[i] == SteinTileEntry.PART && old.ref[i] != now.ref[i]) {
                    why = "instance " + i + ": display list";
                } else if (old.type[i] == SteinTileEntry.DYN
                        && !sameBytes(
                                old.vblob + old.ref[i],
                                now.vblob + now.ref[i],
                                (long) old.verts[i] * SteinTessCapture.VERTEX_BYTES)) {
                    why = "instance " + i + ": vertices";
                } else {
                    int at = i * SteinTileEntry.INST;
                    for (int k = 12; k < SteinTileEntry.INST && why == null; k++) {
                        if (Float.floatToRawIntBits(old.data[at + k])
                                != Float.floatToRawIntBits(now.data[at + k])) {
                            why =
                                    "instance "
                                            + i
                                            + ": field "
                                            + k
                                            + " "
                                            + old.data[at + k]
                                            + " x "
                                            + now.data[at + k];
                        }
                    }
                    for (int r = 0; r < 3 && why == null; r++) {
                        for (int c = 0; c < 4 && why == null; c++) {
                            double want = now.data[at + r * 4 + c];
                            double got = old.data[at + r * 4 + c];
                            if (fix != null) {
                                got =
                                        fix[r * 4] * old.data[at + c]
                                                + fix[r * 4 + 1] * old.data[at + 4 + c]
                                                + fix[r * 4 + 2] * old.data[at + 8 + c]
                                                + (c == 3 ? fix[r * 4 + 3] : 0.0);
                                // The recorded matrix went through float: round the same way.
                                got = (float) got;
                            }
                            if (Math.abs(got - want)
                                    > 1e-5 * (1.0 + Math.max(Math.abs(got), Math.abs(want)))) {
                                why =
                                        "instance "
                                                + i
                                                + ": matrix ["
                                                + r
                                                + "]["
                                                + c
                                                + "] "
                                                + got
                                                + " x "
                                                + want;
                            }
                        }
                    }
                }
            }
        }
        if (why != null) {
            divergent++;
            if (printed < 20) {
                printed++;
                Log.info(
                        "gfx",
                        "retention: DIVERGENCE in "
                                + SteinTileKey.KIND_NAMES[kind]
                                + " ("
                                + why
                                + ")");
            }
        }
    }

    static boolean sameBytes(long a, long b, long n) {
        long i = 0;
        for (; i + 8 <= n; i += 8) {
            if (MemoryUtil.memGetLong(a + i) != MemoryUtil.memGetLong(b + i)) {
                return false;
            }
        }
        for (; i < n; i++) {
            if (MemoryUtil.memGetByte(a + i) != MemoryUtil.memGetByte(b + i)) {
                return false;
            }
        }
        return true;
    }

    static String firstDiff(int[] a, int[] b) {
        for (int i = 0; i < SteinTileState.STATE_FIELDS; i++) {
            if (a[i] != b[i]) {
                return "field " + i + ": " + a[i] + " x " + b[i];
            }
        }
        return "-";
    }
}
