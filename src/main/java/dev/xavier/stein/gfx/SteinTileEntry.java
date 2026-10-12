/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import org.lwjgl.system.MemoryUtil;

import java.util.Arrays;

/**
 * What is retained of one block: its key, the state it was recorded in, the recorded instances and
 * the exit state.
 *
 * <p>Pipeline: Stein GFX only.
 */
final class SteinTileEntry {
    static final byte PART = 0;
    static final byte DYN = 1;

    /** Floats per instance (128 bytes). */
    static final int INST = 32;

    long[] key;
    Object[] refs;
    int[] sig;

    /**
     * Recorded with something drawn immediately (not yet stored) or with an exit that is not
     * reapplied (left to the game).
     */
    boolean stub;

    boolean noRetain;
    int taints;
    int n;
    byte[] type;
    long[] gkey;
    int[] verts;

    /** Part: the display list; dynamic: the vertex offset in {@link #vblob}. */
    int[] ref;

    /** Part: the texture (-1 = none); dynamic: the texture (0 = none). */
    int[] tex;

    /**
     * Part: the layer in the array (class << 16 | layer, -1 outside the array); dynamic: the
     * filter.
     */
    int[] chk;

    /**
     * The instances (check) and the same thing in our own memory, contiguous, for a single copy.
     */
    float[] data;

    long iblob;
    long vblob;
    long vbytes;

    /** Dynamic vertices in total (contiguous in {@link #vblob}). */
    int dynVerts;

    /** Each instance's groups, kept while none leaves the map ({@link #groupsEpoch}). */
    Object[] groups;

    int groupsEpoch = -1;
    boolean lightmap;

    /** The state at the renderer's exit and the fields that changed (to reapply). */
    int[] exit;

    int[] diff;
    final float[] view = new float[16];
    double sx;
    double sy;
    double sz;
    long lastPass;

    /**
     * Are the key built now ({@link SteinTileKey#keyBuf}, {@link SteinTileKey#refBuf}) and the
     * entry state {@code s} the ones recorded?
     */
    boolean matches(int[] s) {
        if (key.length != SteinTileKey.keyN || refs.length != SteinTileKey.refN) {
            return false;
        }
        for (int i = 0; i < SteinTileKey.keyN; i++) {
            if (key[i] != SteinTileKey.keyBuf[i]) {
                return false;
            }
        }
        for (int i = 0; i < SteinTileKey.refN; i++) {
            if (refs[i] != SteinTileKey.refBuf[i]) {
                return false;
            }
        }
        return Arrays.equals(sig, s);
    }

    void free() {
        if (iblob != 0L) {
            MemoryUtil.nmemFree(iblob);
            iblob = 0L;
        }
        if (vblob != 0L) {
            MemoryUtil.nmemFree(vblob);
            vblob = 0L;
        }
    }
}
