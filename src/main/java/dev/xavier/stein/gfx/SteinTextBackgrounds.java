/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * The table of resident name backgrounds of {@link SteinTextGpu}: the 6 converted vertices (192
 * bytes) of the quad as the key, the resident run as the value. A background is uploaded on its
 * second use.
 *
 * <p>Pipeline: Stein GFX only.
 */
final class SteinTextBackgrounds {

    /** Stored name backgrounds (one per name width, in practice). */
    private static final int MAX_BACKGROUNDS = 1024;

    private static final int INTS = 6 * 8;
    private static int[][] keys = new int[MAX_BACKGROUNDS * 2][];
    private static SteinTextGpu.Alloc[] runs = new SteinTextGpu.Alloc[MAX_BACKGROUNDS * 2];
    private static byte[] uses = new byte[MAX_BACKGROUNDS * 2];
    private static int count;
    private static final int[] probe = new int[INTS];

    /**
     * color(0, 0, 0, 0.25F) in the WorldRenderer: (int) (0.25F * 255F) = 63 in alpha, bytes r, g,
     * b, a in memory.
     */
    private static final int LABEL_BACKGROUND_COLOR =
            ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN
                    ? (int) (0.25F * 255F) << 24
                    : (int) (0.25F * 255F);

    private SteinTextBackgrounds() {}

    /**
     * The background of a label assembled directly ({@link SteinNameLabels}): the same quad the
     * game's Tessellator would give (position as floats, byte color (0, 0, 0, 63) from color(0, 0,
     * 0, 0.25F), no texture), already in triangles as {@link SteinTessCapture} converts — (0, 1, 2)
     * and (2, 3, 0) —, and its resident run; null if not resident yet (the label goes through the
     * game path, which uploads it).
     */
    static SteinTextGpu.Alloc labelBackground(int j, int top, int bottom) {
        long tmp = SteinTextGpu.quadScratch();
        float x0 = (float) (-j - 1);
        float x1 = (float) (j + 1);
        float y0 = (float) top;
        float y1 = (float) bottom;
        float[] xs = {x0, x0, x1, x1, x1, x0};
        float[] ys = {y0, y1, y1, y1, y0, y0};
        for (int v = 0; v < 6; v++) {
            long d = tmp + (long) v * SteinTessCapture.VERTEX_BYTES;
            MemoryUtil.memPutFloat(d, xs[v]);
            MemoryUtil.memPutFloat(d + 4, ys[v]);
            MemoryUtil.memPutFloat(d + 8, 0f);
            MemoryUtil.memPutInt(d + 20, LABEL_BACKGROUND_COLOR);
        }
        return lookup(tmp, 6);
    }

    static SteinTextGpu.Alloc lookup(long tmp, int verts) {
        if (verts * 8 != INTS) {
            return null;
        }
        int h = 0x2F0B3A49;
        for (int i = 0; i < INTS; i++) {
            int v = MemoryUtil.memGetInt(tmp + i * 4L);
            probe[i] = v;
            h = (h ^ v) * 0x01000193;
        }
        h ^= h >>> 15;
        int mask = keys.length - 1;
        int i = h & mask;
        int[] k;
        while ((k = keys[i]) != null) {
            if (Arrays.equals(k, probe)) {
                SteinTextGpu.Alloc a = runs[i];
                if (a != null && a.valid) {
                    return a;
                }
                if (uses[i] < 1) {
                    uses[i]++;
                    return null; // second use: uploads now
                }
                a = upload(i);
                runs[i] = a;
                return a;
            }
            i = i + 1 & mask;
        }
        if (count >= MAX_BACKGROUNDS) {
            // Too many widths (names changing): starts over, the live ones come back on the next
            // use.
            clear();
            return null;
        }
        keys[i] = probe.clone();
        uses[i] = 0;
        runs[i] = null;
        count++;
        return null;
    }

    /**
     * The run of background {@code i}: position and byte color of the converted quad, with the
     * "background" marker.
     */
    private static SteinTextGpu.Alloc upload(int i) {
        int[] k = keys[i];
        ByteBuffer b = SteinTextGpu.scratch(6 * SteinTextGpu.VERTEX_BYTES);
        long d = MemoryUtil.memAddress(b);
        for (int v = 0; v < 6; v++, d += SteinTextGpu.VERTEX_BYTES) {
            int o = v * 8;
            MemoryUtil.memPutInt(d, k[o]);
            MemoryUtil.memPutInt(d + 4, k[o + 1]);
            MemoryUtil.memPutInt(d + 8, k[o + 2]);
            MemoryUtil.memPutInt(d + 36, k[o + 5]); // r, g, b, a as bytes, in memory order
            // marker: background (no texture, byte color)
            MemoryUtil.memPutByte(d + 43, (byte) 127);
        }
        return SteinTextGpu.upload(b, k);
    }

    static void clear() {
        for (int i = 0; i < keys.length; i++) {
            SteinTextGpu.release(runs[i]);
            keys[i] = null;
            runs[i] = null;
        }
        count = 0;
    }
}
