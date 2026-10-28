/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import static dev.xavier.stein.gfx.SteinTerrain.VERTEX;

import org.lwjgl.system.MemoryUtil;

/**
 * The conversion of the game's chunk vertices into the terrain's 16-byte vertex ({@link
 * SteinTerrain}), once per upload, optionally with the faces without the alpha test first ({@link
 * SteinOpaqueCutout}).
 *
 * <p>Pipeline: Stein GFX only.
 */
final class SteinTerrainVertices {
    private SteinTerrainVertices() {}

    /**
     * The game's vertex (BLOCK format): position 3 float, color 4 byte, texture 2 float, light 2
     * short.
     */
    private static final int GAME_VERTEX = 28;

    private static final float POS_SCALE = 1024f;
    private static final float POS_BIAS = 16f;

    /** Vertices whose position fell outside the 16-bit range (clamped). */
    static long clamped;

    static void convert(long src, long dst, int count) {
        for (int i = 0; i < count; i++) {
            convertVertex(src + (long) i * GAME_VERTEX, dst + (long) i * VERTEX);
        }
    }

    /**
     * Like {@link #convert}, with the {@code opaque} vertices of the quads {@link
     * SteinOpaqueCutout#classify} marked first and the others after, each group in the game's
     * order.
     */
    static void convertSplit(long src, long dst, int count, int opaque) {
        int front = 0;
        int back = opaque;
        for (int q = 0, quads = count / 4; q < quads; q++) {
            int to;
            if (SteinOpaqueCutout.opaqueQuad(q)) {
                to = front;
                front += 4;
            } else {
                to = back;
                back += 4;
            }
            for (int k = 0; k < 4; k++) {
                convertVertex(
                        src + (long) (q * 4 + k) * GAME_VERTEX, dst + (long) (to + k) * VERTEX);
            }
        }
    }

    private static void convertVertex(long s, long d) {
        MemoryUtil.memPutShort(d, position(MemoryUtil.memGetFloat(s)));
        MemoryUtil.memPutShort(d + 2, position(MemoryUtil.memGetFloat(s + 4)));
        MemoryUtil.memPutShort(d + 4, position(MemoryUtil.memGetFloat(s + 8)));
        int block = Math.min(MemoryUtil.memGetShort(s + 24) & 0xFFFF, 255);
        int sky = Math.min(MemoryUtil.memGetShort(s + 26) & 0xFFFF, 255);
        MemoryUtil.memPutShort(d + 6, (short) (block | sky << 8));
        MemoryUtil.memPutInt(d + 8, MemoryUtil.memGetInt(s + 12));
        MemoryUtil.memPutShort(d + 12, unit(MemoryUtil.memGetFloat(s + 16)));
        MemoryUtil.memPutShort(d + 14, unit(MemoryUtil.memGetFloat(s + 20)));
    }

    private static short position(float v) {
        int q = Math.round((v + POS_BIAS) * POS_SCALE);
        if (q < 0 || q > 0xFFFF) {
            clamped++;
            q = Math.max(0, Math.min(0xFFFF, q));
        }
        return (short) q;
    }

    private static short unit(float v) {
        return (short) Math.round(Math.max(0f, Math.min(1f, v)) * 32768f);
    }
}
