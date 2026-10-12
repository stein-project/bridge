/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import dev.xavier.stein.bridge.core.Log;

import net.minecraft.client.gui.FontRenderer;

import org.lwjgl.system.MemoryUtil;

/**
 * {@code -Dsteinbridge.gfx.textCacheCheck=true}: on every repeated cached text, the assembly is
 * redone only recording (without drawing) and compared with the kept one — batches, textures,
 * colors and vertices, and the font's final state. Catches a key that does not cover everything
 * (the generation of Stein's font, a reloaded page). The drawing is the cache's.
 *
 * <p>Pipeline: Stein GFX only.
 */
final class SteinTextCacheCheck {

    private static long checkedOk;
    private static long checkedBad;

    private SteinTextCacheCheck() {}

    static void check(FontRenderer font, String s, boolean shadow, SteinTextCache.Entry e) {
        SteinTextRecorder.record(font, s, shadow, true);
        String why = compare(font, e);
        SteinTextRecorder.releaseLocations();
        if (why == null) {
            checkedOk++;
        } else if (checkedBad++ < 20) {
            Log.info(
                    "gfx",
                    "cached text differs from the assembly ('"
                            + s
                            + "'"
                            + (SteinOwnText.active() ? ", Stein font" : "")
                            + "): "
                            + why);
        }
    }

    /** What differs between the recording just made and the kept assembly; null = equal. */
    private static String compare(FontRenderer font, SteinTextCache.Entry e) {
        if (SteinTextRecorder.bad) {
            return "assembly outside a batch";
        }
        int segments = SteinTextRecorder.segments;
        if (segments != e.segments) {
            return "batches " + segments + " x " + e.segments;
        }
        int total = 0;
        for (int i = 0; i < segments; i++) {
            if (SteinTextRecorder.counts[i] != e.counts[i]
                    || SteinTextRecorder.locations[i] != e.locations[i]) {
                return "batch " + i + " (vertices or texture)";
            }
            for (int k = 0; k < 4; k++) {
                if (Float.floatToRawIntBits(SteinTextRecorder.colors[i * 4 + k])
                        != Float.floatToRawIntBits(e.colors[i * 4 + k])) {
                    return "batch color " + i;
                }
            }
            total += SteinTextRecorder.counts[i];
        }
        long a = MemoryUtil.memAddress(SteinTextRecorder.data);
        long b = MemoryUtil.memAddress(e.data);
        for (int v = 0; v < total; v++) {
            for (int o = 0; o < 20; o += 4) {
                if (MemoryUtil.memGetInt(a + (long) v * SteinTessCapture.VERTEX_BYTES + o)
                        != MemoryUtil.memGetInt(b + (long) v * SteinTessCapture.VERTEX_BYTES + o)) {
                    return "vertex " + v;
                }
            }
        }
        if (font.posX != e.endX || font.posY != e.endY) {
            return "final position";
        }
        return null;
    }

    /** The 5-second line of the check numbers; the counters start over. */
    static void report() {
        Log.info(
                "gfx",
                "cached text check (5 s): " + checkedOk + " equal, " + checkedBad + " different");
        checkedOk = 0;
        checkedBad = 0;
    }
}
