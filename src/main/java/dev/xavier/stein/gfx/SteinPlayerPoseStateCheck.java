/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import static dev.xavier.stein.gfx.SteinPlayerPose.GLINT_SCALE;
import static dev.xavier.stein.gfx.SteinPlayerPoseJobs.REST;
import static dev.xavier.stein.gfx.SteinPlayerPoseJobs.ST_ARMOR;
import static dev.xavier.stein.gfx.SteinPlayerPoseJobs.ST_BODY;
import static dev.xavier.stein.gfx.SteinPlayerPoseJobs.jState;

import dev.xavier.stein.bridge.core.Log;
import dev.xavier.stein.bridge.engine.Mat4;

import java.util.Arrays;

/**
 * The state half of the pose check ({@code -Dsteinbridge.gfx.poseCheck=true}): for each part the
 * game's path drew, the texture layer, colour and damage red it took, against the state the pose
 * stored for it. The body is the control (it has gone through the pose for long); the armor checks
 * its colour, leather overlay and the 1.7 red armor on damage. It also checks the armor glint's
 * texture matrix, built from a precomputed base, against the same operations redone from the
 * identity. Counted per kind, printed and reset with the other checks every 5 s. Nothing here runs
 * without that flag.
 *
 * <p>Pipeline: Stein GFX only.
 */
final class SteinPlayerPoseStateCheck {

    private SteinPlayerPoseStateCheck() {}

    private static final String[] KINDS = {"body", "armor", "armor hurt"};

    /** Per kind: parts, layer differs, colour differs, damage red differs. */
    private static final long[] counts = new long[KINDS.length * 4];

    /** Glint texture matrices checked, and those that differ from the full computation. */
    private static long glints;

    private static long glintDiffs;

    private static final float[] GLINT = new float[16];

    /**
     * Compares the glint matrix {@code t} built from the precomputed base with the operations
     * redone from the identity, bit for bit.
     */
    static void glint(float[] t, int pass, float time) {
        float[] f = GLINT;
        Mat4.identity(f);
        Mat4.scale(f, GLINT_SCALE, GLINT_SCALE, GLINT_SCALE);
        Mat4.rotate(f, 30.0F - (float) pass * 60.0F, 0.0F, 0.0F, 1.0F);
        Mat4.translate(f, 0.0F, time * (0.001F + (float) pass * 0.003F) * 20.0F, 0.0F);
        glints++;
        for (int i = 0; i < 16; i++) {
            if (Float.floatToRawIntBits(f[i]) != Float.floatToRawIntBits(t[i])) {
                glintDiffs++;
                return;
            }
        }
    }

    /** Compares the captured part {@code c} with the pose's state {@code st}. */
    static void compare(int st, int c) {
        if (st != ST_BODY && st < ST_ARMOR) {
            return;
        }
        float[] g = SteinEntityInstancer.capturedStates();
        int go = c * SteinEntityInstancer.CAP_STATE;
        int so = st * REST;
        boolean hurt = jState[so + 19] > 0f;
        int kind = st == ST_BODY ? 0 : hurt ? 2 : 1;
        counts[kind * 4]++;
        if (jState[so + 3] != g[go]) {
            counts[kind * 4 + 1]++;
        }
        if (jState[so + 8] != g[go + 1]
                || jState[so + 9] != g[go + 2]
                || jState[so + 10] != g[go + 3]
                || jState[so + 11] != g[go + 4]) {
            counts[kind * 4 + 2]++;
        }
        // with the red off only its alpha is meaningful (the game keeps the last colour)
        boolean red = g[go + 8] > 0f;
        if (jState[so + 19] != g[go + 8]
                || red
                        && (jState[so + 16] != g[go + 5]
                                || jState[so + 17] != g[go + 6]
                                || jState[so + 18] != g[go + 7])) {
            counts[kind * 4 + 3]++;
        }
    }

    /** The report line, and resets. */
    static void print() {
        StringBuilder b = new StringBuilder("state check:");
        for (int k = 0; k < KINDS.length; k++) {
            long n = counts[k * 4];
            if (n == 0) {
                continue;
            }
            b.append(' ')
                    .append(KINDS[k])
                    .append(' ')
                    .append(n)
                    .append(" parts (layer ")
                    .append(counts[k * 4 + 1])
                    .append(", colour ")
                    .append(counts[k * 4 + 2])
                    .append(", damage red ")
                    .append(counts[k * 4 + 3])
                    .append(" differ);");
        }
        if (glints != 0) {
            b.append(" glint matrix ")
                    .append(glints)
                    .append(" (")
                    .append(glintDiffs)
                    .append(" differ);");
        }
        Log.info("gfx", b.toString());
        Arrays.fill(counts, 0);
        glints = 0;
        glintDiffs = 0;
    }
}
