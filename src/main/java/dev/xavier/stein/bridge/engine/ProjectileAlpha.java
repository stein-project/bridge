/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import dev.xavier.stein.gfx.SteinGameState;

import net.minecraft.client.renderer.GlStateManager;

/**
 * Fix for arrows, the fishing bobber and the fire on burning entities depending on the alpha test
 * the previous draw left: each one is drawn with the alpha test it needs, and the state is put back
 * after it.
 *
 * <p>RenderArrow.doRender, RenderFish.doRender and Render.renderEntityOnFire draw textures with
 * transparent areas through the Tessellator but never set the alpha test; when the entity drawn
 * before them left it off (or with another function), those areas came out as black or opaque boxes
 * around the arrow, the bobber or the flames. Each of them now runs with the game's default test
 * (GREATER 0.1, the cutout later versions draw these with); the test's previous on/off state and
 * function are restored where each one ends (before Render.doRender for the arrow and the bobber,
 * after the fire's popMatrix), so what follows sees exactly what it saw before. Blending is not
 * touched: the textures are cutouts. The fishing line (untextured, opaque colour) passes the test
 * unchanged.
 *
 * <p>The fire runs inside an entity's render, after the arrow's or bobber's own section ended, so
 * each section keeps its own saved state. Both pipelines: Stein GFX records these draws with the
 * GlStateManager state, which this sets through its own methods. {@code
 * -Dsteinbridge.opt.vanillaProjectileAlpha=true} brings back the leaked state.
 */
public final class ProjectileAlpha {
    private ProjectileAlpha() {}

    private static final boolean VANILLA = RenderFixOptions.VANILLA_PROJECTILE_ALPHA;

    private static final int GREATER = 516;
    private static final float REF = 0.1F;

    // Saved state of the arrow or bobber section (they never nest).
    private static boolean shotSet;
    private static boolean shotOn;
    private static int shotFunc;
    private static float shotRef;

    // Saved state of the fire section.
    private static boolean fireSet;
    private static boolean fireOn;
    private static int fireFunc;
    private static float fireRef;

    /** Before the arrow's or the bobber's pushMatrix (the start of its drawing). */
    public static void shotBegin() {
        if (VANILLA) {
            return;
        }
        shotSet = true;
        shotOn = SteinGameState.alphaTest();
        shotFunc = SteinGameState.alphaFunc();
        shotRef = SteinGameState.alphaRef();
        GlStateManager.enableAlpha();
        GlStateManager.alphaFunc(GREATER, REF);
    }

    /** Before the arrow's or the bobber's Render.doRender (its name tag, if any). */
    public static void shotEnd() {
        if (!shotSet) {
            return;
        }
        shotSet = false;
        restore(shotOn, shotFunc, shotRef);
    }

    /** Before the fire's pushMatrix in Render.renderEntityOnFire. */
    public static void fireBegin() {
        if (VANILLA) {
            return;
        }
        fireSet = true;
        fireOn = SteinGameState.alphaTest();
        fireFunc = SteinGameState.alphaFunc();
        fireRef = SteinGameState.alphaRef();
        GlStateManager.enableAlpha();
        GlStateManager.alphaFunc(GREATER, REF);
    }

    /** After the fire's popMatrix. */
    public static void fireEnd() {
        if (!fireSet) {
            return;
        }
        fireSet = false;
        restore(fireOn, fireFunc, fireRef);
    }

    private static void restore(boolean on, int func, float ref) {
        GlStateManager.alphaFunc(func, ref);
        if (!on) {
            GlStateManager.disableAlpha();
        }
    }
}
