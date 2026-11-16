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
 * Fix for heads drawn without blending: the model of TileEntitySkullRenderer.renderSkull goes out
 * with blending on when the caller left it off.
 *
 * <p>renderSkull (a head on a block, worn by a mob or player, or held) never sets blending, so the
 * semi-transparent pixels of a skin's hat layer came out opaque (or blended, depending on what was
 * drawn before). Later versions draw heads translucent; here, when blending is off, it is turned on
 * with the usual function (SRC_ALPHA, ONE_MINUS_SRC_ALPHA; alpha ONE, ZERO, so the framebuffer's
 * alpha is written as without blending) around the model's render, and the previous function and
 * the off state are put back after it. When blending is already on, nothing changes: that is the
 * crack of a head being broken (DST_COLOR, SRC_COLOR from RenderGlobal) or a caller that chose its
 * own function. Fully transparent pixels are still discarded by the alpha test, so only the partial
 * ones look different.
 *
 * <p>Both pipelines: Stein GFX records the head with the state it is drawn in, so it goes into a
 * blended group (drawn after the opaque ones, in order, depth test and writes as before); a
 * retained head ({@code SteinTileRetain}) sees the same entry and exit state as the game's. {@code
 * -Dsteinbridge.opt.vanillaSkullBlend=true} brings back 1.8.9's opaque heads.
 */
public final class SkullBlend {
    private SkullBlend() {}

    private static final boolean VANILLA = RenderFixOptions.VANILLA_SKULL_BLEND;

    private static final int SRC_ALPHA = 770;
    private static final int ONE_MINUS_SRC_ALPHA = 771;
    private static final int ONE = 1;
    private static final int ZERO = 0;

    /** Blending was turned on here for the model being drawn. */
    private static boolean set;

    private static int src;
    private static int dst;
    private static int srcAlpha;
    private static int dstAlpha;

    /** Before ModelBase.render in renderSkull. */
    public static void before() {
        if (VANILLA || SteinGameState.blend()) {
            return;
        }
        set = true;
        src = SteinGameState.blendSrc();
        dst = SteinGameState.blendDst();
        srcAlpha = SteinGameState.blendSrcAlpha();
        dstAlpha = SteinGameState.blendDstAlpha();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(SRC_ALPHA, ONE_MINUS_SRC_ALPHA, ONE, ZERO);
    }

    /** After ModelBase.render in renderSkull. */
    public static void after() {
        if (!set) {
            return;
        }
        set = false;
        GlStateManager.tryBlendFuncSeparate(src, dst, srcAlpha, dstAlpha);
        GlStateManager.disableBlend();
    }
}
