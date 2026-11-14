/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

/**
 * A render pass: the target, what to do with what was already in it at the start ({@link Load}) and
 * the area. Every draw in the API happens inside one (the {@link SteinCommands} that {@link
 * SteinDevice#beginPass} returns).
 *
 * <p>Pipeline: Stein GFX only.
 */
public final class SteinRenderPass {

    public enum Load {
        /** Keeps what was there (draws on top of the game). */
        KEEP,
        /** Clears with the given value. */
        CLEAR,
        /** Does not matter (it will be fully covered). */
        DONT_CARE
    }

    public final String name;
    public final SteinRenderTarget target;
    public final Load colorLoad;
    public final Load depthLoad;
    public final float[] clearColor;
    public final float clearDepth;

    /** Area (x, y, width, height); null = the whole target. */
    public final int[] viewport;

    private SteinRenderPass(
            String name,
            SteinRenderTarget target,
            Load colorLoad,
            Load depthLoad,
            float[] clearColor,
            float clearDepth,
            int[] viewport) {
        this.name = name;
        this.target = target;
        this.colorLoad = colorLoad;
        this.depthLoad = depthLoad;
        this.clearColor = clearColor;
        this.clearDepth = clearDepth;
        this.viewport = viewport;
    }

    /** Draws on top of what is already in the target (color and depth kept). */
    public static SteinRenderPass keep(String name, SteinRenderTarget target) {
        return new SteinRenderPass(name, target, Load.KEEP, Load.KEEP, null, 1f, null);
    }

    /** What was in the target does not matter: the pass covers all of it. */
    public static SteinRenderPass discard(String name, SteinRenderTarget target) {
        return new SteinRenderPass(name, target, Load.DONT_CARE, Load.DONT_CARE, null, 1f, null);
    }

    /** Clears color and depth first. */
    public static SteinRenderPass clear(
            String name, SteinRenderTarget target, float r, float g, float b, float a) {
        return new SteinRenderPass(
                name, target, Load.CLEAR, Load.CLEAR, new float[] {r, g, b, a}, 1f, null);
    }

    public SteinRenderPass viewport(int x, int y, int w, int h) {
        return new SteinRenderPass(
                name, target, colorLoad, depthLoad, clearColor, clearDepth, new int[] {x, y, w, h});
    }
}
