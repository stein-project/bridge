/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import net.minecraft.client.model.ModelBiped;
import net.minecraft.client.model.ModelPlayer;
import net.minecraft.client.renderer.GlStateManager;

/**
 * Fixes to how 1.8.9 poses the player model, in the game's code and in Stein's own pose (which
 * reads {@link #SLIM_SHIFT} and {@link #VANILLA_SLIM}):
 *
 * <ul>
 *   <li><b>Held item on the thin (Alex) arm</b> (MC-72397): ModelPlayer.postRenderArm moved the arm
 *       one pixel out before placing the item, as if it were 4 pixels wide; the item floated beside
 *       the hand. The thin arm is 3 pixels wide: half a pixel, as from 1.9.
 *   <li><b>First-person arm while riding</b>: RenderPlayer.renderRightArm reset the shared model's
 *       sneak but not its riding flag, so the arm in first person took the riding pose of whatever
 *       player was drawn last with the same model.
 *   <li><b>Layers that leave the depth mask off</b>: the charged creeper's aura, the spider's eyes
 *       and the wither's aura on an invisible mob turn depth writes off and never back on;
 *       everything drawn after them, in the same frame, wrote no depth (entities seen through each
 *       other).
 * </ul>
 *
 * {@code -Dsteinbridge.opt.vanillaSlimArm=true} brings back 1.8.9's item position.
 */
public final class ModelFixes {
    private ModelFixes() {}

    /** The 1.8.9 thin-arm shift, + 1, kept for comparison. */
    public static final boolean VANILLA_SLIM = EngineOptions.VANILLA_SLIM_ARM;

    /** How far the thin arm moves out while the held item is placed: half a pixel. */
    public static final float SLIM_SHIFT = 0.5F;

    /** The body of ModelPlayer.postRenderArm(scale). */
    public static void postRenderArm(Object self, float scale) {
        ModelPlayer m = (ModelPlayer) self;
        if (m.smallArms) {
            float shift = VANILLA_SLIM ? 1.0F : SLIM_SHIFT;
            m.bipedRightArm.rotationPointX += shift;
            m.bipedRightArm.postRender(scale);
            m.bipedRightArm.rotationPointX -= shift;
        } else {
            m.bipedRightArm.postRender(scale);
        }
    }

    /** In place of setRotationAngles inside RenderPlayer.renderRightArm: not riding first. */
    public static void firstPersonArm(
            Object model,
            float limbSwing,
            float limbSwingAmount,
            float ageInTicks,
            float netHeadYaw,
            float headPitch,
            float scale,
            Object entity) {
        ModelBiped m = (ModelBiped) model;
        m.isRiding = false;
        m.setRotationAngles(
                limbSwing,
                limbSwingAmount,
                ageInTicks,
                netHeadYaw,
                headPitch,
                scale,
                (net.minecraft.entity.Entity) entity);
    }

    /** After each layer in RendererLivingEntity.renderLayers: depth writes back on. */
    public static void afterLayer() {
        GlStateManager.depthMask(true);
    }
}
