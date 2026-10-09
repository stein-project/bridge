/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

package dev.xavier.stein.gfx;

import org.lwjgl.system.MemoryUtil;

/**
 * The instance record of the deferred entities, written in one place: the three matrix rows, the
 * texture tail and the material tail, always in the same order and with the same casts, to memory
 * (the mapped ring) or to an array (retention, glint mirror), plus the ring sizes the records live
 * in.
 *
 * <p>Pipeline: Stein GFX only.
 */
final class SteinInstanceWriter {

    /** Bytes of one instance record of the deferred entities. */
    static final int INSTANCE_BYTES = 128;

    static final int COMMAND_BYTES = 16;

    /** Parts (instances or commands) per ring region. */
    static final int RING_PARTS = 64 * 1024;

    static final int REGIONS = 3;
    static final int FRAME_BYTES = 192;

    /** Dynamic vertices per ring region. */
    static final int RING_VERTS = 256 * 1024;

    private SteinInstanceWriter() {}

    /**
     * The 3 rows of the matrix {@code m[o..o+15]} (column-major) as row-major floats, at bytes
     * 0..47 of the record.
     */
    static void putMatrixRows(long p, float[] m, int o) {
        for (int r = 0; r < 3; r++) {
            MemoryUtil.memPutFloat(p, m[o + r]);
            MemoryUtil.memPutFloat(p + 4, m[o + 4 + r]);
            MemoryUtil.memPutFloat(p + 8, m[o + 8 + r]);
            MemoryUtil.memPutFloat(p + 12, m[o + 12 + r]);
            p += 16;
        }
    }

    /** The same as {@link #putMatrixRows(long, float[], int)}, into an array (from {@code at}). */
    static void putMatrixRows(float[] d, int at, float[] m, int o) {
        for (int r = 0; r < 3; r++) {
            d[at + r * 4] = m[o + r];
            d[at + r * 4 + 1] = m[o + 4 + r];
            d[at + r * 4 + 2] = m[o + 8 + r];
            d[at + r * 4 + 3] = m[o + 12 + r];
        }
    }

    /**
     * The affine texture matrix (s' = t0 . (s, t, 1), t' = t1 . (s, t, 1); identity if {@code t} is
     * null), the texture layer and the alpha reference: 32 bytes, at bytes 48..79 of the record.
     */
    static void putTexture(long p, float[] t, float layer, float alphaRef) {
        MemoryUtil.memPutFloat(p, t == null ? 1f : t[0]);
        MemoryUtil.memPutFloat(p + 4, t == null ? 0f : t[4]);
        MemoryUtil.memPutFloat(p + 8, t == null ? 0f : t[12]);
        MemoryUtil.memPutFloat(p + 12, layer);
        MemoryUtil.memPutFloat(p + 16, t == null ? 0f : t[1]);
        MemoryUtil.memPutFloat(p + 20, t == null ? 1f : t[5]);
        MemoryUtil.memPutFloat(p + 24, t == null ? 0f : t[13]);
        MemoryUtil.memPutFloat(p + 28, alphaRef);
    }

    /** The same as {@link #putTexture(long, float[], float, float)}, into an array. */
    static void putTexture(float[] d, int at, float[] t, float layer, float alphaRef) {
        d[at] = t == null ? 1f : t[0];
        d[at + 1] = t == null ? 0f : t[4];
        d[at + 2] = t == null ? 0f : t[12];
        d[at + 3] = layer;
        d[at + 4] = t == null ? 0f : t[1];
        d[at + 5] = t == null ? 1f : t[5];
        d[at + 6] = t == null ? 0f : t[13];
        d[at + 7] = alphaRef;
    }

    /**
     * Colour, lightmap, option flags and the 4 floats of the overlay's place (damage colour and
     * weight, or the current normal): 48 bytes, at bytes 80..127 of the record.
     */
    static void putMaterial(
            long p,
            float r,
            float g,
            float b,
            float a,
            float lightU,
            float lightV,
            int flags,
            float x,
            float y,
            float z,
            float w) {
        MemoryUtil.memPutFloat(p, r);
        MemoryUtil.memPutFloat(p + 4, g);
        MemoryUtil.memPutFloat(p + 8, b);
        MemoryUtil.memPutFloat(p + 12, a);
        MemoryUtil.memPutFloat(p + 16, lightU);
        MemoryUtil.memPutFloat(p + 20, lightV);
        MemoryUtil.memPutFloat(p + 24, flags);
        MemoryUtil.memPutFloat(p + 28, 0f);
        MemoryUtil.memPutFloat(p + 32, x);
        MemoryUtil.memPutFloat(p + 36, y);
        MemoryUtil.memPutFloat(p + 40, z);
        MemoryUtil.memPutFloat(p + 44, w);
    }

    /** The same as the long-address {@code putMaterial}, into an array. */
    static void putMaterial(
            float[] d,
            int at,
            float r,
            float g,
            float b,
            float a,
            float lightU,
            float lightV,
            int flags,
            float x,
            float y,
            float z,
            float w) {
        d[at] = r;
        d[at + 1] = g;
        d[at + 2] = b;
        d[at + 3] = a;
        d[at + 4] = lightU;
        d[at + 5] = lightV;
        d[at + 6] = flags;
        d[at + 7] = 0f;
        d[at + 8] = x;
        d[at + 9] = y;
        d[at + 10] = z;
        d[at + 11] = w;
    }
}
