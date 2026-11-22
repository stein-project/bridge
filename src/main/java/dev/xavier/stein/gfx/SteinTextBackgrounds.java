/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import dev.xavier.stein.bridge.engine.NameStyle;

import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * The table of resident name backgrounds of {@link SteinTextGpu}: the converted vertices of the
 * background as the key (the game's quad: 6 vertices, 192 bytes; a styled one, {@link
 * SteinLabelShape}, up to {@value #MAX_VERTS}), the resident run as the value. A background is
 * uploaded on its second use.
 *
 * <p>Pipeline: Stein GFX only.
 */
final class SteinTextBackgrounds {

    /** Stored name backgrounds (one per name width, in practice). */
    private static final int MAX_BACKGROUNDS = 1024;

    /** The largest background: a styled label's shape. */
    static final int MAX_VERTS = SteinLabelShape.MAX_QUADS * 6;

    private static final int MAX_INTS = MAX_VERTS * 8;
    private static int[][] keys = new int[MAX_BACKGROUNDS * 2][];
    private static SteinTextGpu.Alloc[] runs = new SteinTextGpu.Alloc[MAX_BACKGROUNDS * 2];
    private static byte[] uses = new byte[MAX_BACKGROUNDS * 2];
    private static int count;
    private static final int[] probe = new int[MAX_INTS];

    /**
     * color(0, 0, 0, 0.25F) in the WorldRenderer: (int) (0.25F * 255F) = 63 in alpha, bytes r, g,
     * b, a in memory.
     */
    private static final int LABEL_BACKGROUND_COLOR =
            ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN
                    ? (int) (0.25F * 255F) << 24
                    : (int) (0.25F * 255F);

    /**
     * Goes up on every {@link #clear}: the table forgets its keys there without invalidating the
     * runs, so a run remembered by size is only trusted within the same epoch.
     */
    private static int epoch;

    // The resident background by label size: (j, top, bottom) fully determines the quad's 48 key
    // ints (the color is a constant), so within one epoch a run that is still valid is exactly what
    // the quad lookup would return — its slot is only rewritten when the run there is invalid, and
    // keys only leave the table in clear(). Saves writing, re-reading and hashing 192 bytes and
    // the key comparison for every name in every frame.
    private static final boolean BY_SIZE = SteinGfxOptions.LABEL_BACKGROUND_CACHE;
    private static final boolean CHECK = BY_SIZE && SteinGfxOptions.LABEL_BACKGROUND_CHECK;
    private static final int SIZE_SLOTS = 512;
    private static final int[] sizeJ = new int[SIZE_SLOTS];
    private static final int[] sizeTop = new int[SIZE_SLOTS];
    private static final int[] sizeBottom = new int[SIZE_SLOTS];
    private static final int[] sizeEpoch = new int[SIZE_SLOTS];
    private static final SteinTextGpu.Alloc[] sizeRun = new SteinTextGpu.Alloc[SIZE_SLOTS];

    /**
     * The {@link NameStyle} version the sizes were remembered for: a new style changes the shape
     * and color behind the same size, so it starts a new epoch.
     */
    private static int styleVersion = NameStyle.version();

    private SteinTextBackgrounds() {}

    /** Whether {@code -Dsteinbridge.gfx.labelBackgroundCheck=true} is on (for the report). */
    static boolean checking() {
        return CHECK;
    }

    /**
     * The background of a label assembled directly ({@link SteinNameLabels}): the same quad the
     * game's Tessellator would give (position as floats, byte color (0, 0, 0, 63) from color(0, 0,
     * 0, 0.25F), no texture), already in triangles as {@link SteinTessCapture} converts — (0, 1, 2)
     * and (2, 3, 0) —, and its resident run; null if not resident yet (the label goes through the
     * game path, which uploads it).
     */
    static SteinTextGpu.Alloc labelBackground(int j, int top, int bottom) {
        if (styleVersion != NameStyle.version()) {
            styleVersion = NameStyle.version();
            epoch++;
        }
        if (!BY_SIZE) {
            return quadBackground(j, top, bottom);
        }
        int s = (j * 0x9E3779B9 + top * 31 + bottom) >>> 23 & SIZE_SLOTS - 1;
        SteinTextGpu.Alloc a = sizeRun[s];
        if (a != null
                && a.valid
                && sizeEpoch[s] == epoch
                && sizeJ[s] == j
                && sizeTop[s] == top
                && sizeBottom[s] == bottom) {
            if (CHECK) {
                if (quadBackground(j, top, bottom) == a) {
                    SteinLabelStats.backgroundSame++;
                } else {
                    SteinLabelStats.backgroundDifferent++;
                }
            }
            return a;
        }
        a = quadBackground(j, top, bottom);
        if (a != null) {
            sizeRun[s] = a;
            sizeJ[s] = j;
            sizeTop[s] = top;
            sizeBottom[s] = bottom;
            sizeEpoch[s] = epoch;
        }
        return a;
    }

    /** The background through the quad itself: the key built and looked up in the table. */
    private static SteinTextGpu.Alloc quadBackground(int j, int top, int bottom) {
        if (NameStyle.custom()) {
            // the styled shape (padding, corners, color): what the game path draws, see
            // SteinLabelShape
            int quads = SteinLabelShape.build(j, top, bottom);
            long tmp = SteinTextGpu.quadScratch(quads * 6);
            return lookup(tmp, SteinLabelShape.writeTriangles(tmp, quads));
        }
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
        if (verts < 3 || verts > MAX_VERTS) {
            return null;
        }
        int len = verts * 8;
        int h = 0x2F0B3A49;
        for (int i = 0; i < len; i++) {
            int v = MemoryUtil.memGetInt(tmp + i * 4L);
            probe[i] = v;
            h = (h ^ v) * 0x01000193;
        }
        h ^= h >>> 15;
        int mask = keys.length - 1;
        int i = h & mask;
        int[] k;
        while ((k = keys[i]) != null) {
            if (k.length == len && Arrays.equals(k, 0, len, probe, 0, len)) {
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
        keys[i] = Arrays.copyOf(probe, len);
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
        int verts = k.length / 8;
        ByteBuffer b = SteinTextGpu.scratch(verts * SteinTextGpu.VERTEX_BYTES);
        long d = MemoryUtil.memAddress(b);
        for (int v = 0; v < verts; v++, d += SteinTextGpu.VERTEX_BYTES) {
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
        epoch++;
    }
}
