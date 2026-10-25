/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx.vk;

import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * The dynamic state the command buffer holds now (viewport, scissor, depth bias, line width), so a
 * draw re-emits only what changed. It lives as long as one command buffer: a new one starts with
 * nothing known.
 *
 * <p>Pipeline: Stein GFX only (experimental Vulkan backend, not used by the game).
 */
final class SteinVkDynamic {

    private VkCommandBuffer cb = null;
    private final int[] viewport = new int[4];
    private final int[] scissor = new int[4];
    private float biasUnits;
    private float biasFactor;
    private float lineWidth;

    /**
     * Nothing is known: a command buffer begins (even one that is the same handle as before, reset
     * and recycled, holds no dynamic state).
     */
    void forget() {
        cb = null;
    }

    /** The state for {@code c}: nothing known when it is another command buffer. */
    SteinVkDynamic of(VkCommandBuffer c) {
        if (cb == null || c.address() != cb.address()) {
            cb = c;
            viewport[0] = viewport[1] = viewport[2] = viewport[3] = Integer.MIN_VALUE;
            scissor[0] = scissor[1] = scissor[2] = scissor[3] = Integer.MIN_VALUE;
            biasUnits = Float.NaN;
            biasFactor = Float.NaN;
            lineWidth = Float.NaN;
        }
        return this;
    }

    /**
     * Records a viewport; true if it differs from the one in the command buffer (then it must be
     * set).
     */
    boolean viewport(int x, int y, int w, int h) {
        return changed(viewport, x, y, w, h);
    }

    /** Records a scissor; true if it differs. */
    boolean scissor(int x, int y, int w, int h) {
        return changed(scissor, x, y, w, h);
    }

    /** Records a depth bias; true if it differs. */
    boolean bias(float units, float factor) {
        if (biasUnits == units && biasFactor == factor) {
            return false;
        }
        biasUnits = units;
        biasFactor = factor;
        return true;
    }

    /** Records a line width; true if it differs. */
    boolean lineWidth(float w) {
        if (lineWidth == w) {
            return false;
        }
        lineWidth = w;
        return true;
    }

    private static boolean changed(int[] s, int x, int y, int w, int h) {
        if (s[0] == x && s[1] == y && s[2] == w && s[3] == h) {
            return false;
        }
        s[0] = x;
        s[1] = y;
        s[2] = w;
        s[3] = h;
        return true;
    }
}
