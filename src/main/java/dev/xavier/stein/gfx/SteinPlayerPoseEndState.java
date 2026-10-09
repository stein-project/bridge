/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import static dev.xavier.stein.gfx.SteinPlayerPoseGame.*;

import dev.xavier.stein.bridge.engine.ArmorGlint;

import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.entity.Render;

import org.lwjgl.opengl.GL11;

/**
 * The draw state the game's doRender would leave behind for what comes after the player: what the
 * collect step noted (the last colour, the blend, the alpha function) and the calls that re-create
 * it, so the GlStateManager cache and the GL state agree with a game-drawn player.
 *
 * <p>Pipeline: Stein GFX only.
 */
final class SteinPlayerPoseEndState {
    private SteinPlayerPoseEndState() {}

    /**
     * The color that the armor (or glint) left behind: the last one, if no later layer touches it.
     */
    static boolean endColor;

    static float endR;
    static float endG;
    static float endB;
    static float endA;

    /**
     * The blend factors that remain (blend off): the glint's or the translucent body's; 0 = none.
     */
    static int endBlendSrc;

    static int endBlendDst;

    /** The translucent body leaves alphaFunc(GREATER, 0.1). */
    static boolean endAlpha01;

    /** The state that the body and armor leave behind (color only with {@code color}). */
    static void bodyState(boolean color) {
        if (color && endColor) {
            GlStateManager.color(endR, endG, endB, endA);
        }
        if (endBlendSrc != 0) {
            GlStateManager.blendFunc(endBlendSrc, endBlendDst);
        }
        if (endAlpha01) {
            GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1F);
        }
    }

    /**
     * Something in the helmet slot: LayerCustomHead sets the color to white at the end, even
     * without drawing.
     */
    static boolean endWhite;

    /**
     * The state the game's doRender would leave for what comes after (the GlStateManager does not
     * emit what is already set). {@code color}: false when the game's layers already ran (the color
     * is the one they left).
     */
    static void leaveState(boolean color, boolean held, Object render) {
        GlStateManager.enableAlpha();
        if (color) {
            if (held) {
                // only the held item among the layers, and it went through the pose: the state its
                // path would leave
                bodyState(true);
                heldState(render);
            } else {
                bodyState(!endWhite);
                if (endWhite) {
                    GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
                }
            }
        }
        GlStateManager.depthMask(true);
        GlStateManager.setActiveTexture(LIGHTMAP_UNIT);
        GlStateManager.enableTexture2D();
        GlStateManager.setActiveTexture(DEFAULT_UNIT);
        GlStateManager.enableCull();
        GlStateManager.disableRescaleNormal();
    }

    /**
     * The state (GlStateManager cache and GL color) that the game's held-item layer leaves behind,
     * when the item went through the pose: renderItemModelTransform (atlas bound, preTransform's
     * white color, rescale and blend turned on and off, alphaFunc 0.1, separate blend 770/771/1/0,
     * back face), renderEffect or its skip in Visuals (770/771, light, LEQUAL depth writing,
     * atlas), the ItemModelCache list (color 1 in GL and the cache's color forgotten), the normal
     * forgotten by the capture and the glint flush after the layer. Final values only: the order
     * among them does not change the cache.
     */
    static void heldState(Object render) {
        ((Render) render).bindTexture(BLOCKS_ATLAS); // the same TextureManager as RenderItem
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        SteinLazyGl.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.resetColor();
        GlStateManager.disableRescaleNormal();
        GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1F);
        GlStateManager.disableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0);
        GlStateManager.cullFace(GL11.GL_BACK);
        GlStateManager.depthFunc(GL11.GL_LEQUAL);
        GlStateManager.depthMask(true);
        GlStateManager.enableLighting();
        SteinGlTrack.forgetNormal();
        ArmorGlint.flushDeferred();
    }
}
