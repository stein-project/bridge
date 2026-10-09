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
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.texture.TextureManager;

import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/**
 * Repeats a text kept by {@link SteinTextCache}: each batch with its texture and color (through the
 * runs resident on the GPU, or copying the vertices), and the font left as the assembly would leave
 * it.
 *
 * <p>Pipeline: Stein GFX only.
 */
final class SteinTextReplay {

    private SteinTextReplay() {}

    /**
     * The assembly's runs for the GPU ({@link SteinTextGpu}): consecutive batches with the same
     * texture together (each batch's color goes in the vertex). A font page switch (unicode)
     * separates them.
     */
    static void buildRuns(SteinTextCache.Entry e) {
        int n = e.segments;
        e.runFirst = new int[n];
        e.runCount = new int[n];
        e.runSegment = new int[n];
        e.runLocation = new Object[n];
        int runs = 0;
        int vert = 0;
        for (int i = 0; i < n; i++) {
            if (runs > 0 && e.runLocation[runs - 1] == e.locations[i]) {
                e.runCount[runs - 1] += e.counts[i];
            } else {
                e.runFirst[runs] = vert;
                e.runCount[runs] = e.counts[i];
                e.runSegment[runs] = i;
                e.runLocation[runs] = e.locations[i];
                runs++;
            }
            vert += e.counts[i];
        }
        e.runs = runs;
    }

    /**
     * The assembly's vertices in the GPU format (48 bytes, each batch's color as floats), to upload
     * once.
     */
    private static ByteBuffer residentData(SteinTextCache.Entry e) {
        int verts = (int) (e.data.capacity() / SteinTessCapture.VERTEX_BYTES);
        int total = 0;
        for (int i = 0; i < e.segments; i++) {
            total += e.counts[i];
        }
        if (total == 0 || total > verts) {
            return null;
        }
        ByteBuffer out = SteinTextGpu.scratch(total * SteinTextGpu.VERTEX_BYTES);
        long src = MemoryUtil.memAddress(e.data);
        long dst = MemoryUtil.memAddress(out);
        float[] c = e.colors;
        for (int s = 0; s < e.segments; s++) {
            for (int v = 0;
                    v < e.counts[s];
                    v++, src += SteinTessCapture.VERTEX_BYTES, dst += SteinTextGpu.VERTEX_BYTES) {
                SteinTextGpu.putGlyph(src, dst, c[s * 4], c[s * 4 + 1], c[s * 4 + 2], c[s * 4 + 3]);
            }
        }
        return out;
    }

    /**
     * The text through the runs resident on the GPU: only each run's texture, the record and the
     * command (no vertex copied). False = could not (not uploaded yet, no room in the ring, state
     * refused on the first run): the caller repeats through the copy path. A refusal after the
     * first run continues through the copy path from that batch on, in the same order.
     */
    private static boolean replayResident(TextureManager tm, SteinTextCache.Entry e) {
        if (e.runs == 0) {
            return false;
        }
        SteinTextGpu.Alloc a = e.resident;
        if (a == null || !a.valid) {
            if (++e.uses < 2) {
                return false;
            }
            ByteBuffer data = residentData(e);
            a = data == null ? null : SteinTextGpu.upload(data, e);
            if (a == null) {
                SteinTextGpuStats.fallbacks++;
                return false;
            }
            e.resident = a;
        }
        FontBatch.flush();
        int base = a.firstVertex();
        for (int r = 0; r < e.runs; r++) {
            bind(tm, e.runLocation[r]);
            if (!SteinTessCapture.fontResident(
                    base + e.runFirst[r], e.runCount[r], SteinOwnText.isOwn(e.runLocation[r]))) {
                if (r == 0) {
                    SteinTextGpuStats.fallbacks++;
                    return false;
                }
                replayCopy(tm, e, e.runSegment[r]);
                SteinTextGpu.drawn(a);
                return true;
            }
            SteinTextGpuStats.runsDrawn++;
        }
        SteinTextGpu.drawn(a);
        // The color the copy path would leave: that of the last batch (each batch's went in the
        // vertex, without passing through the state), when the assembly does not leave one of its
        // own (that comes right after, at the end of the replay).
        int last = e.segments - 1;
        if (last >= 0 && !e.hasEndColor()) {
            float[] c = e.colors;
            GlStateManager.color(c[last * 4], c[last * 4 + 1], c[last * 4 + 2], c[last * 4 + 3]);
        }
        return true;
    }

    /**
     * The batches from {@code from} onward through the copy path: texture, color and the vertices
     * of each.
     */
    private static void replayCopy(TextureManager tm, SteinTextCache.Entry e, int from) {
        long at = MemoryUtil.memAddress(e.data);
        for (int i = 0; i < from; i++) {
            at += (long) e.counts[i] * SteinTessCapture.VERTEX_BYTES;
        }
        float[] c = e.colors;
        for (int i = from; i < e.segments; i++) {
            bind(tm, e.locations[i]);
            GlStateManager.color(c[i * 4], c[i * 4 + 1], c[i * 4 + 2], c[i * 4 + 3]);
            FontBatch.replay(at, e.counts[i], SteinOwnText.isOwn(e.locations[i]));
            at += (long) e.counts[i] * SteinTessCapture.VERTEX_BYTES;
        }
    }

    /**
     * The text again: each batch with its texture and color, and the font left as the assembly
     * would leave it.
     */
    static void replay(FontRenderer font, SteinTextCache.Entry e) {
        TextureManager tm = font.renderEngine;
        if (!SteinTextGpu.REQUESTED || !SteinTextGpu.active() || !replayResident(tm, e)) {
            replayCopy(tm, e, 0);
        }
        // The color the assembly left (a color code at the end of the text changes the color with
        // no batch after it).
        if (e.hasEndColor()) {
            GlStateManager.color(e.endR, e.endG, e.endB, e.endA);
        }
        restoreEndState(font, e);
    }

    /** The font's position, text color and styles as the assembly left them. */
    static void restoreEndState(FontRenderer font, SteinTextCache.Entry e) {
        font.posX = e.endX;
        font.posY = e.endY;
        font.textColor = e.endTextColor;
        // Bit i: random, bold, italic, underline, strikethrough
        font.randomStyle = (e.endStyles & 1) != 0;
        font.boldStyle = (e.endStyles & 1 << 1) != 0;
        font.italicStyle = (e.endStyles & 1 << 2) != 0;
        font.underlineStyle = (e.endStyles & 1 << 3) != 0;
        font.strikethroughStyle = (e.endStyles & 1 << 4) != 0;
    }

    /** Binds the batch's texture as the FontRenderer (or the TextHook) would. */
    private static void bind(TextureManager tm, Object location) {
        if (location instanceof SteinOwnText.Texture o) {
            FontBatch.bindOwn(o.id);
        } else {
            FontBatch.bindTexture(tm, location);
        }
    }
}
