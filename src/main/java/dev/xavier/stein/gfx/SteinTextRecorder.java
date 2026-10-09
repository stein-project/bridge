/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import dev.xavier.stein.bridge.engine.FontBatch;

import net.minecraft.client.gui.FontRenderer;

import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Records the batches of one text assembly for {@link SteinTextCache}: while the game's
 * FontRenderer assembles a text with {@link FontBatch#textRecording} on, each {@link FontBatch}
 * batch arrives in {@link #segment} (vertices already in the {@link SteinTessCapture} format,
 * texture and color), and anything that did not go through the batch marks the recording as bad.
 *
 * <p>Pipeline: Stein GFX only.
 */
final class SteinTextRecorder {

    // Assembling a text for the cache: FontBatch.textRecording on, and the FontBatch hands over
    // each batch in segment().
    static boolean bad;
    static ByteBuffer data = ByteBuffer.allocateDirect(64 * 1024).order(ByteOrder.nativeOrder());
    static int verts;
    static int segments;
    static int[] counts = new int[16];
    static Object[] locations = new Object[16];
    static float[] colors = new float[64];

    private SteinTextRecorder() {}

    /**
     * Assembles the text through the game (renderStringAtPos) only recording, flushing the pending
     * batches before and after so that nothing from another text is in the recording. With {@code
     * dry}, the batches are not drawn (check).
     */
    static void record(FontRenderer font, String s, boolean shadow, boolean dry) {
        FontBatch.flush(); // nothing from another text in the recorded batch
        FontBatch.textRecording = true;
        if (dry) {
            FontBatch.textDry = true;
        }
        bad = false;
        verts = 0;
        segments = 0;
        try {
            font.renderStringAtPos(s, shadow);
            FontBatch.flush(); // the last batch too (the flush after renderStringAtPos
            // would be left out)
        } finally {
            if (dry) {
                FontBatch.textDry = false;
            }
            FontBatch.textRecording = false;
        }
    }

    /** The batches' textures are not kept after use (the recording keeps no reference). */
    static void releaseLocations() {
        Arrays.fill(locations, 0, segments, null);
    }

    /**
     * Something in the assembly did not go through the batch (immediate mode, Tessellator): the
     * text does not enter the cache.
     */
    static void markBad() {
        bad = true;
    }

    /**
     * A FontBatch batch during assembly: {@code count} vertices (x, y, z, u, v as floats, in
     * triangles) at {@code addr}, with the texture {@code location} (the game's ResourceLocation)
     * and the current GlStateManager color.
     */
    static void segment(long addr, int count, Object location, boolean own, int ownTexture) {
        if (bad) {
            return;
        }
        float r = SteinGameState.colorR();
        float g = SteinGameState.colorG();
        float b = SteinGameState.colorB();
        float a = SteinGameState.colorA();
        if (own) {
            // Batch of Stein's font: its atlas texture, which the repeat binds through
            // FontBatch.bindOwn. Only with its generation key (without it, it does not even
            // get here: the text does not enter the cache).
            location =
                    SteinOwnText.ENABLED && ownTexture > 0
                            ? SteinOwnText.Texture.of(ownTexture)
                            : null;
        }
        if (location == null || SteinTextCache.invalidColor(r, g, b, a)) {
            bad = true;
            return;
        }
        int need = (verts + count) * SteinTessCapture.VERTEX_BYTES;
        if (need > data.capacity()) {
            ByteBuffer bigger =
                    ByteBuffer.allocateDirect(Math.max(need, data.capacity() * 2))
                            .order(ByteOrder.nativeOrder());
            MemoryUtil.memCopy(
                    MemoryUtil.memAddress(data),
                    MemoryUtil.memAddress(bigger),
                    (long) verts * SteinTessCapture.VERTEX_BYTES);
            data = bigger;
        }
        long d = MemoryUtil.memAddress(data) + (long) verts * SteinTessCapture.VERTEX_BYTES;
        for (int i = 0; i < count; i++, d += SteinTessCapture.VERTEX_BYTES) {
            long p = addr + i * 20L;
            MemoryUtil.memPutFloat(d, MemoryUtil.memGetFloat(p));
            MemoryUtil.memPutFloat(d + 4, MemoryUtil.memGetFloat(p + 4));
            MemoryUtil.memPutFloat(d + 8, MemoryUtil.memGetFloat(p + 8));
            MemoryUtil.memPutFloat(d + 12, MemoryUtil.memGetFloat(p + 12));
            MemoryUtil.memPutFloat(d + 16, MemoryUtil.memGetFloat(p + 16));
            MemoryUtil.memPutLong(d + 20, 0L);
            MemoryUtil.memPutInt(d + 28, 0);
        }
        if (segments == counts.length) {
            counts = Arrays.copyOf(counts, segments * 2);
            locations = Arrays.copyOf(locations, segments * 2);
            colors = Arrays.copyOf(colors, segments * 8);
        }
        counts[segments] = count;
        locations[segments] = location;
        colors[segments * 4] = r;
        colors[segments * 4 + 1] = g;
        colors[segments * 4 + 2] = b;
        colors[segments * 4 + 3] = a;
        segments++;
        verts += count;
    }
}
