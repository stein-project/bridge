/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx.vk;

import static org.lwjgl.vulkan.VK10.VK_FORMAT_UNDEFINED;

import dev.xavier.stein.gfx.SteinRenderTarget;
import dev.xavier.stein.gfx.SteinTexture;

import java.util.Arrays;

/**
 * Vulkan render target: just the list of textures (with dynamic rendering there is no framebuffer).
 * Their formats are the key to the variant of the pipeline that draws here ({@link Formats}).
 *
 * <p>Pipeline: Stein GFX only (experimental Vulkan backend, not used by the game).
 */
final class SteinVkRenderTarget extends SteinRenderTarget {

    /** Formats of the colors and of the depth: a Vulkan pipeline is created for one set of them. */
    record Formats(int[] colors, int depth) {
        @Override
        public boolean equals(Object o) {
            return o instanceof Formats f && f.depth == depth && Arrays.equals(f.colors, colors);
        }

        @Override
        public int hashCode() {
            return 31 * Arrays.hashCode(colors) + depth;
        }
    }

    private final SteinVkTexture[] colors;
    private final SteinVkTexture depth;
    private final int width;
    private final int height;
    private final Formats formats;

    SteinVkRenderTarget(SteinTexture[] colors, SteinTexture depth) {
        SteinTexture[] c = colors == null ? new SteinTexture[0] : colors;
        // the colors go in sequence (no gaps: Vulkan accepts them, but the renderer does not use
        // them)
        int n = 0;
        for (SteinTexture t : c) {
            if (t != null) {
                n++;
            }
        }
        this.colors = new SteinVkTexture[n];
        int[] fmt = new int[n];
        int i = 0;
        for (SteinTexture t : c) {
            if (t != null) {
                if (!t.desc().renderTarget) {
                    throw new IllegalArgumentException(
                            "texture '" + t.desc().name + "' was not created as a render target");
                }
                this.colors[i] = (SteinVkTexture) t;
                fmt[i] = this.colors[i].format();
                i++;
            }
        }
        this.depth = (SteinVkTexture) depth;
        if (depth != null && !depth.desc().renderTarget) {
            throw new IllegalArgumentException(
                    "texture '" + depth.desc().name + "' was not created as a render target");
        }
        SteinTexture first = n > 0 ? this.colors[0] : depth;
        this.width = first == null ? 0 : first.width();
        this.height = first == null ? 0 : first.height();
        this.formats = new Formats(fmt, depth == null ? VK_FORMAT_UNDEFINED : this.depth.format());
    }

    SteinVkTexture[] colors() {
        return colors;
    }

    SteinVkTexture depthTexture() {
        return depth;
    }

    Formats formats() {
        return formats;
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }

    @Override
    public SteinTexture color(int i) {
        return i < colors.length ? colors[i] : null;
    }

    @Override
    public SteinTexture depth() {
        return depth;
    }

    @Override
    public long handle() {
        return System.identityHashCode(this);
    }

    @Override
    public void destroy() {
        // the textures belong to whoever created the target; there is no Vulkan object here
    }
}
