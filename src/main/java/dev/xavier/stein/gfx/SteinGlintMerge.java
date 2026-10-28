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
 * The players' armor glint in one pass instead of two ({@code -Dsteinbridge.gfx.glintMerge}): the
 * state of pass 0 carries the texture matrix of pass 1 in its colour slot and draws with the {@code
 * GLINT2} variant of the entity shaders, which sample the glint with both matrices.
 *
 * <p>Why the image stays the same: the glint blends SRC_COLOR, ONE, so each pass adds {@code c * c}
 * to the destination where its depth is EQUAL to the piece's; one pass that outputs {@code sqrt(c0
 * * c0 + c1 * c1)} adds the same sum. The difference is the rounding: once to the 8-bit target
 * instead of twice, at most one colour level. Each sample keeps its own alpha test.
 *
 * <p>The colour slot is free because the armor glint's colour is a constant ({@code GLINT_COLOR} in
 * entity.vert, the same floats as SteinPlayerPoseCollect.addGlint); the 6 numbers of the second
 * matrix fit in 4 because it is a scaled rotation plus a translation ({@code t1 = -t4}, {@code t5 =
 * t0}), which {@link #pack} checks bit for bit before merging (otherwise both passes stay).
 *
 * <p>Pipeline: Stein GFX only.
 */
final class SteinGlintMerge {
    private SteinGlintMerge() {}

    /** The key bit of the merged glint group: its own shaders ({@link #vertex}, {@link #frag}). */
    static final long KEY_GLINT2 = 1L << 62;

    static final boolean ON = SteinGfxOptions.GLINT_MERGE;

    private static boolean loaded;
    private static SteinShader vertex;
    private static SteinShader fragment;

    /** The merged glint's vertex shader (entity.vert with GLINT2). */
    static SteinShader vertex() {
        load();
        return vertex;
    }

    /** The merged glint's fragment shader (entity.frag with GLINT2). */
    static SteinShader frag() {
        load();
        return fragment;
    }

    private static void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        vertex = SteinShader.load(SteinShader.Stage.VERTEX, "entity_glint.vert");
        fragment = SteinShader.load(SteinShader.Stage.FRAGMENT, "entity_glint.frag");
    }

    private static int usable = -1;

    /**
     * The merged pipeline of {@code key} (pass 0's) compiles: checked once, the first time; false
     * keeps the two passes for the whole run.
     */
    static boolean usable(long key) {
        if (usable < 0) {
            boolean ok;
            try {
                ok =
                        SteinDeferredResources.ready()
                                && SteinDeferredResources.pipeline(key | KEY_GLINT2).valid();
            } catch (RuntimeException e) {
                ok = false;
            }
            usable = ok ? 1 : 0;
            Log.info(
                    "gfx",
                    ok
                            ? "armor glint: both passes in one draw"
                            : "armor glint: the merged shader did not compile (two passes)");
        }
        return usable == 1;
    }

    /**
     * Puts the second pass's texture matrix {@code t} (column-major, as glintMatrix makes it) in
     * the colour slot of the state at {@code rest[o..]}: (t0, t4, t12, t13). False (nothing
     * written) if {@code t} is not a scaled rotation whose t row the shader can rebuild exactly.
     */
    static boolean pack(float[] t, float[] rest, int o) {
        if (Float.floatToRawIntBits(t[1]) != Float.floatToRawIntBits(-t[4])
                || Float.floatToRawIntBits(t[5]) != Float.floatToRawIntBits(t[0])) {
            return false;
        }
        rest[o + 8] = t[0];
        rest[o + 9] = t[4];
        rest[o + 10] = t[12];
        rest[o + 11] = t[13];
        return true;
    }
}
