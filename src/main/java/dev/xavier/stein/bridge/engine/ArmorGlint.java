/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import dev.xavier.stein.bridge.core.Log;
import dev.xavier.stein.gfx.SteinModelInstancer;

import net.minecraft.client.model.ModelBase;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.entity.layers.LayerArmorBase;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;

import org.lwjgl.opengl.GL11;

/**
 * Armor glint grouped per pass, instead of piece by piece.
 *
 * <p>LayerArmorBase draws, for each piece: base, glint, glint — helmet, chestplate, leggings,
 * boots. Each glint pass changes state (blend, depth, color, texture matrix), and each change
 * closes a batch: ~12 armor batches per player. Here each piece's glint is recorded, and at the end
 * of the layer the two passes go out once each, with all the enchanted pieces: ~5 batches.
 *
 * <p>The image is the same: the glint only paints where the depth is EQUAL to the piece's
 * (depthFunc EQUAL, without writing depth) and adds color (additive blend). With all the bases
 * drawn first, where one piece covers another the glint of the one behind fails the depth test — as
 * in the original, where the base of the front piece, drawn later, covered the glint. Where the
 * piece is the closest, the glint adds on top of it in both cases. The state the glint would leave
 * for the next piece (lighting on, normal depth, no blend) is applied right away, through the
 * GlStateManager, which emits nothing if the state is already that. Constants and the order of the
 * float arithmetic are those of the original renderGlint.
 *
 * <p>{@code -Dsteinbridge.opt.vanillaArmorGlint=true}: piece-by-piece glint, as in 1.8.9.
 */
public final class ArmorGlint {

    private static final boolean VANILLA = EngineOptions.VANILLA_ARMOR_GLINT;
    private static final int MAX_PIECES = 8;
    private static final long DEBUG_INTERVAL_NS = 5_000_000_000L;

    private static final Object[] LAYERS = new Object[MAX_PIECES];
    private static final Object[] ENTITIES = new Object[MAX_PIECES];
    private static final Object[] MODELS = new Object[MAX_PIECES];
    private static final int[] SLOTS = new int[MAX_PIECES];
    private static final float[][] ARGS = new float[MAX_PIECES][7];

    /**
     * New renderer (SteinModelInstancer): each piece's base commands, so the glint only re-emits
     * them.
     */
    private static final int[][] RECS = new int[MAX_PIECES][];

    private static final boolean DEBUG_REPLAY = EngineOptions.DEBUG_GLINT;
    private static long replays;
    private static long fallbacks;
    private static long debugAt;
    private static int count;

    /**
     * Piece of the last visibility applied by renderLayer (renderGlint does not receive the piece).
     */
    private static int lastSlot;

    static {
        if (VANILLA) {
            Log.info("armorglint", "glint piece by piece (vanilla)");
        }
    }

    private ArmorGlint() {}

    @SuppressWarnings("unchecked")
    private static LayerArmorBase<ModelBase> layer(Object layer) {
        return (LayerArmorBase<ModelBase>) layer;
    }

    /** renderLayer applying the piece's visibility on the model: stores the piece and moves on. */
    public static void setVisible(Object layer, Object model, int slot) {
        lastSlot = slot;
        layer(layer).setModelPartVisible((ModelBase) model, slot);
        base = null;
        baseTaken = false;
        SteinModelInstancer.startRecording(); // the piece's base, which comes now
    }

    /** The commands of the piece's first draw (the base), separated at {@link #baseDrawn()}. */
    private static int[] base;

    private static boolean baseTaken;

    /**
     * After each ModelBase.render inside renderLayer (patcher rule): the first is the piece's base;
     * the second, only on leather, is the overlay (the undyed part), with the same geometry. The
     * game's renderGlint draws the model once per pass, so the glint re-emits only the base —
     * recording both, enchanted leather came out with a doubled glint.
     */
    public static void baseDrawn() {
        if (!baseTaken) {
            baseTaken = true;
            base = SteinModelInstancer.stopRecording();
        }
    }

    /**
     * renderGlint of a piece (inside renderLayer): records; the draw goes out at the end of the
     * layer.
     */
    public static void deferGlint(
            Object layer,
            Object entity,
            Object model,
            float limbSwing,
            float limbAmount,
            float partialTicks,
            float ageInTicks,
            float headYaw,
            float headPitch,
            float scale) {
        int[] recorded = baseTaken ? base : SteinModelInstancer.stopRecording();
        base = null;
        baseTaken = false;
        // Player option (Visuals): only the state the glint would leave.
        if (Visuals.glintSkipped()) {
            leaveState();
            return;
        }
        if (VANILLA) {
            layer(layer)
                    .renderGlint(
                            (EntityLivingBase) entity,
                            (ModelBase) model,
                            limbSwing,
                            limbAmount,
                            partialTicks,
                            ageInTicks,
                            headYaw,
                            headPitch,
                            scale);
            return;
        }
        if (count == MAX_PIECES || count > 0 && (ENTITIES[0] != entity || LAYERS[0] != layer)) {
            flushDeferred(); // does not happen in 1.8.9: one layer at a time, 4 pieces
        }
        LAYERS[count] = layer;
        ENTITIES[count] = entity;
        MODELS[count] = model;
        SLOTS[count] = lastSlot;
        RECS[count] = recorded;
        float[] args = ARGS[count];
        args[0] = limbSwing;
        args[1] = limbAmount;
        args[2] = partialTicks;
        args[3] = ageInTicks;
        args[4] = headYaw;
        args[5] = headPitch;
        args[6] = scale;
        count++;
        leaveState();
    }

    /** The state renderGlint leaves for the next piece. */
    private static void leaveState() {
        GLMatrix.matrixMode(GL11.GL_TEXTURE);
        GLMatrix.loadIdentity();
        GLMatrix.matrixMode(GL11.GL_MODELVIEW);
        GlStateManager.enableLighting();
        GlStateManager.depthMask(true);
        GlStateManager.depthFunc(GL11.GL_LEQUAL);
        GlStateManager.disableBlend();
    }

    /** After each LayerRenderer.doRenderLayer: the recorded glint passes, grouped. */
    public static void flushDeferred() {
        if (count == 0) {
            return;
        }
        int n = count;
        count = 0;
        boolean replay = SteinModelInstancer.active();
        for (int p = 0; p < n && replay; p++) {
            replay = RECS[p] != null;
        }
        if (DEBUG_REPLAY) {
            countReplay(replay);
        }
        Object layer = LAYERS[0];
        Object entity = ENTITIES[0];
        // Like renderGlint: (float) ticksExisted + partialTicks.
        float time = (float) ((Entity) entity).ticksExisted + ARGS[0][2];
        layer(layer).renderer.bindTexture(LayerArmorBase.ENCHANTED_ITEM_GLINT_RES);
        GlStateManager.enableBlend();
        GlStateManager.depthFunc(GL11.GL_EQUAL);
        GlStateManager.depthMask(false);
        float half = 0.5F;
        GlStateManager.color(half, half, half, 1.0F);
        for (int i = 0; i < 2; ++i) {
            GlStateManager.disableLighting();
            GlStateManager.blendFunc(GL11.GL_SRC_COLOR, GL11.GL_ONE);
            float k = 0.76F;
            GlStateManager.color(0.5F * k, 0.25F * k, 0.8F * k, 1.0F);
            GLMatrix.matrixMode(GL11.GL_TEXTURE);
            GLMatrix.loadIdentity();
            float third = 0.33333334F;
            GLMatrix.scale(third, third, third);
            GLMatrix.rotate(30.0F - (float) i * 60.0F, 0.0F, 0.0F, 1.0F);
            GLMatrix.translate(0.0F, time * (0.001F + (float) i * 0.003F) * 20.0F, 0.0F);
            GLMatrix.matrixMode(GL11.GL_MODELVIEW);
            if (replay) {
                // New renderer: the same base commands, with the matrices already on the card
                // (the same depth, which depthFunc EQUAL requires) and the glint's texture
                // matrix.
                SteinModelInstancer.replay(RECS, n, GLMatrix.textureOrNull());
                continue;
            }
            renderPieces(n);
        }
        leaveState();
        for (int p = 0; p < n; p++) {
            LAYERS[p] = null;
            ENTITIES[p] = null;
            MODELS[p] = null;
            RECS[p] = null;
        }
    }

    /**
     * Debug: counts the groups re-emitted and the ones through the old path; reports every few s.
     */
    private static void countReplay(boolean replay) {
        if (replay) {
            replays++;
        } else {
            fallbacks++;
        }
        long now = System.nanoTime();
        if (now - debugAt > DEBUG_INTERVAL_NS) {
            debugAt = now;
            Log.info(
                    "glint-debug",
                    "re-emitted "
                            + replays
                            + ", through the old path "
                            + fallbacks
                            + " (active "
                            + SteinModelInstancer.active()
                            + ")");
            replays = 0;
            fallbacks = 0;
        }
    }

    /** One glint pass through the old path: the game's own model render of each piece. */
    private static void renderPieces(int n) {
        for (int p = 0; p < n; p++) {
            layer(LAYERS[p]).setModelPartVisible((ModelBase) MODELS[p], SLOTS[p]);
            float[] args = ARGS[p];
            ((ModelBase) MODELS[p])
                    .render(
                            (Entity) ENTITIES[p],
                            args[0],
                            args[1],
                            args[3],
                            args[4],
                            args[5],
                            args[6]);
        }
    }
}
