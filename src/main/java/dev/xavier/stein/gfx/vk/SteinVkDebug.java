/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx.vk;

import dev.xavier.stein.bridge.core.Log;
import dev.xavier.stein.gfx.SteinBuffer;
import dev.xavier.stein.gfx.SteinPipeline;
import dev.xavier.stein.gfx.SteinTexture;

import org.lwjgl.system.MemoryUtil;

import java.util.Arrays;

/**
 * The Vulkan backend's draw log ({@code -Dsteinbridge.gfx.vkDebugDrawsAtSec}): the first frame
 * presented after that many seconds, once, one line per draw.
 *
 * <p>Pipeline: Stein GFX only (the Vulkan backend, diagnostics).
 */
final class SteinVkDebug {

    private static final long STARTED = System.nanoTime();
    private static final long AT_NANOS = SteinVkOptions.DEBUG_DRAWS_AT_SEC * 1_000_000_000L;

    private static boolean logging;
    private static boolean done;

    private SteinVkDebug() {}

    /** Draws of the frame being recorded are logged. */
    static boolean logging() {
        return logging;
    }

    /** At each presentation: the logged frame ends, or the chosen one starts. */
    static void frame() {
        if (logging) {
            logging = false;
            done = true;
        } else if (!done && AT_NANOS >= 0 && System.nanoTime() - STARTED > AT_NANOS) {
            logging = true;
        }
    }

    /** One line describing a draw of the logged frame. */
    static void draw(
            String pass,
            SteinPipeline.Desc d,
            int[] viewport,
            SteinTexture[] textures,
            SteinBuffer[] uniforms,
            long[] uniformOffset) {
        StringBuilder b = new StringBuilder("vulkan draw: pass ").append(pass);
        b.append(", pipeline ").append(d.name).append(", blend ").append(d.blend);
        if (d.factors != null) {
            b.append(Arrays.toString(d.factors));
        }
        b.append(", depth ").append(d.depthTest).append(d.depthWrite ? " write" : "");
        b.append(", cull ").append(d.cull).append(", viewport ").append(Arrays.toString(viewport));
        for (int i = 0; i < textures.length; i++) {
            if (textures[i] != null) {
                b.append(", texture ").append(i).append(' ').append(textures[i].desc().name);
            }
        }
        for (int i = 0; i < uniforms.length; i++) {
            if (uniforms[i] != null && uniforms[i].mapped()) {
                // the first 16 floats (the frame's projection, in the renderer's blocks)
                long at = uniforms[i].address(uniformOffset[i]);
                b.append(", uniforms ").append(i).append(" [");
                for (int k = 0; k < 16; k++) {
                    b.append(k == 0 ? "" : " ").append(MemoryUtil.memGetFloat(at + k * 4L));
                }
                b.append(']');
            }
        }
        Log.info("gfx", b.toString());
    }
}
