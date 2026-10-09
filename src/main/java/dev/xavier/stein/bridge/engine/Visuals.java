/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import net.minecraft.client.model.ModelBase;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.client.renderer.entity.RendererLivingEntity;
import net.minecraft.client.renderer.entity.layers.LayerArmorBase;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.resources.model.IBakedModel;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.world.WorldProvider;

import org.lwjgl.opengl.GL11;

/**
 * Visual options that a mod turns on through the Loader API (the bridge's visual options, in the
 * video settings). All of them change the image, and all of them start at the game's behavior:
 *
 * <ul>
 *   <li>enchantment glint: on, near only or off. "Near only" applies to whatever is drawn as an
 *       entity — armor, an item in another player's hand, a dropped item or one in an item frame —
 *       farther than {@code glintNear} blocks from the camera; the GUI, the first-person hand and
 *       the player on the inventory screen always keep the glint;
 *   <li>maximum nametag distance (0: the game's, 64 blocks, 32 when sneaking);
 *   <li>worn armor: show or hide.
 * </ul>
 *
 * <p>A skipped glint applies the GL state its drawing would have ended with: depth back to LEQUAL
 * and writing, blend and light as the game leaves them, atlas texture. Skipping only the drawing
 * would leave what comes next (other pieces, the world border) with the wrong state.
 *
 * <p>The distance comes from RenderManager.doRenderEntity, which passes the entity's position
 * relative to the camera to the Render: one field written per entity drawn.
 *
 * <p>Initial values for measurement, without a mod (the mod overrides them on start): {@code
 * -Dsteinbridge.visuals.glint=0|1|2} (on, near only, off), {@code
 * -Dsteinbridge.visuals.glintNear=16}, {@code -Dsteinbridge.visuals.nametags=N} (blocks; 0 = the
 * game's) and {@code -Dsteinbridge.visuals.armor=false}.
 */
public final class Visuals {

    public static final int GLINT_ON = 0;
    public static final int GLINT_NEAR = 1;
    public static final int GLINT_OFF = 2;

    private static int glint = GLINT_ON;
    private static double glintNearSquared = 16.0 * 16.0;

    /** Maximum nametag distance, squared; 0 = the game's. */
    private static double nametagSquared;

    private static boolean armor = EngineOptions.ARMOR;

    static {
        setGlint(EngineOptions.GLINT, Float.parseFloat(EngineOptions.GLINT_NEAR));
        setNametagDistance(EngineOptions.NAMETAGS);
    }

    /** Squared distance from the entity being drawn to the camera; -1 outside the RenderManager. */
    private static double entityDistSquared = -1.0;

    private Visuals() {}

    // ---- options (Loader API) ----

    /** Enchantment glint: GLINT_ON, GLINT_NEAR (up to nearBlocks from the camera) or GLINT_OFF. */
    public static void setGlint(int mode, float nearBlocks) {
        glint = mode == GLINT_NEAR || mode == GLINT_OFF ? mode : GLINT_ON;
        double n = Math.max(1.0, nearBlocks);
        glintNearSquared = n * n;
    }

    /** Maximum nametag distance in blocks; 0 goes back to the game's. */
    public static void setNametagDistance(int blocks) {
        nametagSquared = blocks > 0 ? (double) blocks * blocks : 0.0;
    }

    public static void setArmorVisible(boolean visible) {
        armor = visible;
    }

    /**
     * Whether the glint is skipped for the entity being drawn (the GUI's and the hand's, outside
     * the RenderManager, are not affected by the distance).
     */
    public static boolean glintSkipped() {
        return glint == GLINT_OFF || glint == GLINT_NEAR && entityDistSquared > glintNearSquared;
    }

    /** Is the armor visible (player option)? */
    public static boolean armorVisible() {
        return armor;
    }

    // ---- fog ----

    /** The game's fog. */
    public static final int FOG_NORMAL = 0;

    /** Fog only in the final stretch of the render distance. */
    public static final int FOG_LIGHT = 1;

    /** No distance fog (water, lava, clouds, blindness and the Nether keep the game's). */
    public static final int FOG_OFF = 2;

    private static int fog = FOG_NORMAL;

    /** Start and end of the fog recorded by the setupFog in progress; -1 = not recorded. */
    private static float fogStartSeen = -1.0F;

    private static float fogEndSeen = -1.0F;

    static {
        setFog(EngineOptions.FOG);
    }

    public static void setFog(int mode) {
        fog = mode == FOG_LIGHT || mode == FOG_OFF ? mode : FOG_NORMAL;
    }

    /** Start of setupFog (before getRenderViewEntity, its first call). */
    public static void fogBegin() {
        fogStartSeen = -1.0F;
        fogEndSeen = -1.0F;
    }

    /** setFogStart inside setupFog: records and passes along. */
    public static void fogStart(float start) {
        fogStartSeen = start;
        GlStateManager.setFogStart(start);
    }

    /** setFogEnd inside setupFog: records and passes along. */
    public static void fogEnd(float end) {
        fogEndSeen = end;
        GlStateManager.setFogEnd(end);
    }

    private static final float GAME_FOG_START_RATIO = 0.75F;
    private static final float LIGHT_FOG_START_RATIO = 0.92F;
    private static final float NO_FOG_START_RATIO = 8.0F;
    private static final float NO_FOG_END_RATIO = 16.0F;

    /**
     * End of setupFog (after enableFog). Only the terrain fog changes: it is the only one where the
     * game puts the start at 3/4 of the end (the sky's starts at 0, blindness at 1/4, the Nether's
     * at 1/10; water, lava and clouds use exponential fog, with no start and end).
     */
    public static void fogDone() {
        if (fog == FOG_NORMAL
                || fogEndSeen <= 0.0F
                || fogStartSeen != fogEndSeen * GAME_FOG_START_RATIO) {
            return;
        }
        if (fog == FOG_LIGHT) {
            GlStateManager.setFogStart(fogEndSeen * LIGHT_FOG_START_RATIO);
        } else {
            // Start and end beyond everything that is drawn: fog factor 1 across the whole frame.
            GlStateManager.setFogStart(fogEndSeen * NO_FOG_START_RATIO);
            GlStateManager.setFogEnd(fogEndSeen * NO_FOG_END_RATIO);
        }
    }

    // ---- clouds ----

    /** Blocks above the game's cloud height. */
    private static float cloudOffset;

    static {
        setCloudHeight(EngineOptions.CLOUD_HEIGHT);
    }

    public static void setCloudHeight(int offsetBlocks) {
        cloudOffset = Math.max(0, Math.min(256, offsetBlocks));
    }

    /**
     * Cloud height for whoever draws them — RenderGlobal.renderClouds and renderCloudsFancy
     * (detour) and the CloudCache: the game's plus the chosen one.
     */
    public static float cloudHeight(Object provider) {
        return ((WorldProvider) provider).getCloudHeight() + cloudOffset;
    }

    // ---- hooks (patcher/redirects-mods.tsv) ----

    /**
     * RenderManager.doRenderEntity -> Render.doRender: stores the entity's distance to the camera.
     */
    @SuppressWarnings("unchecked")
    public static void doRender(
            Object render,
            Object entity,
            double x,
            double y,
            double z,
            float yaw,
            float partialTicks) {
        double outer = entityDistSquared;
        entityDistSquared = x * x + y * y + z * z;
        try {
            ((Render<Entity>) render).doRender((Entity) entity, x, y, z, yaw, partialTicks);
        } finally {
            entityDistSquared = outer;
        }
    }

    /** RenderItem.renderEffect, the item glint: skipped, the state it ends with is kept. */
    public static void renderEffect(Object renderItem, Object model) {
        if (!glintSkipped()) {
            ((RenderItem) renderItem).renderEffect((IBakedModel) model);
            return;
        }
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GlStateManager.enableLighting();
        GlStateManager.depthFunc(GL11.GL_LEQUAL);
        GlStateManager.depthMask(true);
        ((RenderItem) renderItem).textureManager.bindTexture(TextureMap.locationBlocksTexture);
    }

    /**
     * RendererLivingEntity.canRenderName, in renderName: no name beyond the chosen distance.
     *
     * <p>If a mod ever needs to touch the name, its question goes <b>in here</b>, and not in a rule
     * of its own: this call site's detour already belongs to this class, and two rules at the same
     * point with the same scope do not coexist — the last one read replaces the previous one,
     * without warning.
     */
    @SuppressWarnings("unchecked")
    public static boolean canRenderName(Object renderer, Object entity) {
        if (nametagSquared > 0.0 && entityDistSquared > nametagSquared) {
            return false;
        }
        return ((RendererLivingEntity<EntityLivingBase>) renderer)
                .canRenderName((EntityLivingBase) entity);
    }

    /** LayerArmorBase.renderLayer, one worn piece: hidden, no piece at all (nor its glint). */
    @SuppressWarnings("unchecked")
    public static void renderArmorPiece(
            Object layer,
            Object entity,
            float limbSwing,
            float limbAmount,
            float partialTicks,
            float ageInTicks,
            float headYaw,
            float headPitch,
            float scale,
            int slot) {
        if (armor) {
            ((LayerArmorBase<ModelBase>) layer)
                    .renderLayer(
                            (EntityLivingBase) entity,
                            limbSwing,
                            limbAmount,
                            partialTicks,
                            ageInTicks,
                            headYaw,
                            headPitch,
                            scale,
                            slot);
        }
    }
}
