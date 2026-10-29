/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import static dev.xavier.stein.gfx.SteinDeferredFrame.*;
import static dev.xavier.stein.gfx.SteinDeferredLights.*;
import static dev.xavier.stein.gfx.SteinDeferredModel.*;
import static dev.xavier.stein.gfx.SteinDeferredNames.*;
import static dev.xavier.stein.gfx.SteinDeferredResources.*;
import static dev.xavier.stein.gfx.SteinDeferredSessions.*;
import static dev.xavier.stein.gfx.SteinDeferredStats.*;
import static dev.xavier.stein.gfx.SteinInstanceWriter.*;
import static dev.xavier.stein.gfx.SteinPassTiming.*;

import dev.xavier.stein.bridge.engine.GLMatrix;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.entity.Entity;
import net.minecraft.tileentity.TileEntity;

import org.lwjgl.opengl.GL11;

/**
 * Deferred entities (renderer step C1): during the game's entity pass, each model part becomes data
 * — the matrix, the texture layer in the array, the colour, the lightmap, the damage red — in a
 * persistent buffer, grouped by the state in which the game would have drawn it (blend, depth,
 * face). At the end of the pass one indirect draw goes out per group: the bodies of all entities in
 * one draw, all the armor, all the glint.
 *
 * <p>The game keeps computing everything as always (pose, animation, layers, what appears): only
 * the state is read from here. Nothing runs on the tick or in the physics.
 *
 * <ul>
 *   <li>the parts' geometry is that of the {@link SteinModelInstancer} (fixed on the GPU);
 *   <li>the textures come from {@link SteinTextureArrays} (copied to the GPU, one layer per
 *       texture);
 *   <li>the shader is ours, without the OpenGL fixed-function pipeline: entity lighting, the
 *       lightmap, the alpha test, the damage red and the fog are done in it, with the state read
 *       once per frame;
 *   <li>the names above heads go out after all the bodies (otherwise the bodies drawn later would
 *       erase their background).
 * </ul>
 *
 * <p>A texture outside the array (a group with the game's texture bound directly), no texture
 * (spectator outline, the dragon's damage red) and polygon offset (crack, flashing TNT) are
 * recorded too. Only what the pipeline cannot represent is drawn immediately: partial colour mask,
 * logic op, a colour "forgotten" by the GlStateManager, an alpha test that is not GREATER, lighting
 * without COLOR_MATERIAL.
 *
 * <p>Dynamic geometry ({@link SteinTessCapture}): what the game draws through the Tessellator,
 * through the batched font and through the item lists in the pass goes into the same groups, with a
 * bit of its own in the key (another vertex format, another shader variant) and the game's texture
 * bound directly (no copy to the array). Groups without blending go out first, those with blending
 * afterwards, in the order they appeared: transparencies of different entities that cross (one
 * name's background over another's text) may come out in a different order than the game's. {@code
 * -Dsteinbridge.gfx.deferredDynamic=false}: only the model parts (as before).
 *
 * <p>Pipeline: Stein GFX only.
 */
public final class SteinEntityDeferred {

    /**
     * Deferred entities on: Stein GFX active and {@code -Dsteinbridge.gfx.deferredEntities} not
     * false.
     */
    public static final boolean REQUESTED = SteinGfxOptions.feature("deferredEntities", true);

    /**
     * Tile entities (signs, chests...) recorded too, without the pause. The sign text (lighting
     * with the glNormal3f normal, tracked by {@link SteinGlTrack}) is recorded too; only the End
     * portal (texture coordinate generation) and the cracks of a block being broken stay immediate,
     * with what was already recorded going out first.
     */
    private static final boolean TILES = SteinGfxOptions.DEFERRED_TILES;

    static boolean capturing;

    /** The stored names going out at the end of the pass (recorded too, after all the bodies). */
    static boolean draining;

    private SteinEntityDeferred() {}

    // ---- entity pass (patcher rules around renderEntities) ----

    /** Before RenderGlobal.renderEntities. */
    public static void begin() {
        if (!REQUESTED || !GLMatrix.emulating() || !ready()) {
            return;
        }
        capturing = true;
        passName = "entities";
        SteinLazyGl.activate();
        readLights(lights);
        lightsStale = false;
        SteinGlTrack.forgetNormal();
        SteinTileRetain.passBegin();
        SteinEntityShadows.entityPass = true;
    }

    /**
     * After RenderGlobal.renderEntities: the stored names (recorded too, with the dynamic geometry:
     * the background and the text go into groups with blending, which go out after all the bodies)
     * and the drawing of everything.
     */
    public static void end() {
        if (!capturing) {
            return;
        }
        boolean namesLater = !dynamicReady;
        try {
            if (!namesLater) {
                draining = true;
                drainNames();
            }
        } finally {
            draining = false;
            capturing = false;
            SteinLazyGl.deactivate();
            try {
                flush();
            } finally {
                rotate();
                mirrorN = 0;
                frames++;
            }
        }
        if (namesLater) {
            drainNames();
        }
        try {
            SteinEntityShadows.firstPersonPlayer(Minecraft.getMinecraft().timer.renderPartialTicks);
        } finally {
            SteinEntityShadows.entityPass = false;
        }
        stats();
    }

    /**
     * The first-person player, whom the entity pass leaves out, recorded and drawn into the
     * entities' shadow map only ({@link SteinEntityShadows}).
     */
    static void castPlayer(Entity player, float partial) {
        SteinEntityShadows.castOnly = true;
        capturing = true;
        SteinLazyGl.activate();
        try {
            Minecraft.getMinecraft().getRenderManager().renderEntitySimple(player, partial);
            SteinPlayerPose.drain();
        } finally {
            capturing = false;
            SteinLazyGl.deactivate();
            try {
                flush();
            } finally {
                SteinEntityShadows.castOnly = false;
            }
        }
    }

    // ---- particles (renderer step 4): the same recording around the EffectRenderer passes ----

    /**
     * Before EffectRenderer.renderLitParticles and renderParticles in renderWorldPass: what the
     * pass draws through the Tessellator (each layer's particles in a single draw, those with their
     * own draw one by one) and through models (the item being picked up) becomes data, as in the
     * entity pass.
     */
    public static void particlesBegin() {
        timeStart(0);
        // -Dsteinbridge.gfx.particles=false: the particles drawn call by call, outside a session.
        if (SteinGfxOptions.PARTICLES) {
            sessionBegin(false);
        }
    }

    /**
     * After the pass: what was recorded goes out, grouped by state, in the order it appeared. The
     * rings stay in the current region (they close once per frame, at the end of the next frame's
     * entities): closing here too would double the fences per frame and the wait for a free region.
     */
    public static void particlesEnd() {
        sessionEnd();
        timeEnd(0);
    }

    /**
     * Before RenderGlobal.renderSky: dome, sunrise and sunset, sun, moon, stars and the void
     * recorded in order.
     */
    public static void skyBegin() {
        timeStart(1);
        // the Lens's own sky first, under what the game draws of its sky (SteinSkyDome)
        SteinSkyDome.begin();
        sessionBegin(true);
    }

    public static void skyEnd() {
        sessionEnd();
        SteinSkyDome.end();
        timeEnd(1);
    }

    /**
     * Before RenderGlobal.renderClouds: the two cloud passes (depth, then colour) recorded in
     * order.
     */
    public static void cloudsBegin() {
        timeStart(2);
        sessionBegin(true);
    }

    public static void cloudsEnd() {
        sessionEnd();
        timeEnd(2);
    }

    /**
     * Before renderRainSnow, renderWorldBorder, drawSelectionBox and drawBlockDamageTexture in
     * renderWorldPass: what they draw through the Tessellator is recorded in strict order (the
     * outline lines in the line pipeline, the crack and the border with their polygon offset).
     */
    public static void worldBegin() {
        timeStart(3);
        sessionBegin(true);
    }

    public static void worldEnd() {
        sessionEnd();
        timeEnd(3);
    }

    /**
     * Before EntityRenderer.renderHand (step 5): the arm, the held item (with the glint), the map
     * and the overlays (fire, water, block from inside) recorded in strict order, with the hand's
     * projection (read at flush, still inside it) and after the depth clear that the game does
     * before.
     */
    public static void handBegin() {
        timeStart(4);
        SteinShadows.hand(true); // the hand has its own projection: no shadows on it
        sessionBegin(true);
    }

    public static void handEnd() {
        sessionEnd();
        SteinShadows.hand(false);
        timeEnd(4);
    }

    /**
     * Before GuiIngame.renderGameOverlay (step 6): the game's HUD and, until the end of the session
     * (after GameHooks.overlay, by rule order), the mods' HUD, recorded in strict order.
     */
    public static void hudBegin() {
        timeStart(5);
        sessionBegin(true);
    }

    public static void hudEnd() {
        sessionEnd();
        timeEnd(5);
    }

    /** Before currentScreen.drawScreen (inventory, chat, menus, with the mods' hooks around it). */
    public static void screenBegin() {
        timeStart(6);
        sessionBegin(true);
    }

    public static void screenEnd() {
        sessionEnd();
        timeEnd(6);
    }

    /** The game's viewport (its last glViewport; width 0 = none yet): the flush draws into it. */
    static int viewportX;

    static int viewportY;
    static int viewportW;
    static int viewportH;

    /**
     * In place of the game's glViewport (patcher rule): what was recorded so far goes out in the
     * previous viewport (the menu panorama draws at 256x256 in the middle of the screen's pass),
     * and the new one is kept for the flushes.
     */
    public static void glViewport(int x, int y, int w, int h) {
        barrier();
        viewportX = x;
        viewportY = y;
        viewportW = w;
        viewportH = h;
        if (!SteinGfxOptions.VULKAN) {
            GL11.glViewport(x, y, w, h);
        }
    }

    /** Before switching framebuffer mid-pass: what was recorded goes out on the current target. */
    public static void barrier() {
        if (capturing) {
            flushInOrder();
        }
    }

    /**
     * Draws what was recorded so far in the game's order: first, the players with their own pose
     * that have already passed through doRender and wait for the section switch (they came before
     * what comes now).
     */
    static void flushInOrder() {
        if (capturing()) {
            SteinPlayerPose.drain(); // paused (tile entity drawn immediately): no pose is waiting
        }
        flush();
    }

    /** Recording the entity pass (and not one of the extra passes). */
    static boolean inEntityPass() {
        return capturing && !session;
    }

    public static boolean capturing() {
        return capturing && !paused;
    }

    /**
     * Something in the pass is going to draw immediately (state that cannot be recorded): the
     * deferred state goes to the driver. In the core context an immediate draw does not reach the
     * screen, so what was already recorded does not need to go out first.
     */
    static void beforeImmediate() {
        SteinHeldItem.recordBad();
        if (GLMatrix.compiling()) {
            return; // inside a display list: everything would go into the list, not to the screen
        }
        // Block being recorded for retention with something immediate: it is not kept.
        SteinTileRetain.taint();
        if (session) {
            sessionImmediate++;
            kindImmediate[sessionKind]++;
        }
        SteinLazyGl.sync();
    }

    /** Recording, with dynamic geometry on (the capture's fast path asks only this). */
    static boolean capturingDynamic() {
        return capturing && !paused && dynamicReady;
    }

    static boolean paused;

    /** Before a tile entity: it draws immediately (the sign text depends on order). */
    public static void pause() {
        if (TILES && dynamicReady) {
            return; // recorded too ({@link #renderTile})
        }
        if (capturing && !paused) {
            // The recorded entities go out before the tiles, in the game's order: a tile that
            // blends without writing depth (the beacon beam) drawn before them would be erased by
            // them. Only the first tile of the frame flushes anything.
            flushInOrder();
        }
        paused = true;
        SteinLazyGl.deactivate();
    }

    public static void resume() {
        if (TILES && dynamicReady) {
            return;
        }
        paused = false;
        if (capturing) {
            SteinLazyGl.activate();
        }
    }

    /**
     * TileEntityRendererDispatcher.renderTileEntity inside renderEntities (patcher rule). With
     * {@code -Dsteinbridge.gfx.deferredTiles=true}, the tile is recorded like the entities,
     * including the crack of a block being broken (stage >= 0: polygon offset in the group key) and
     * the End portal ({@link SteinTexGen}); whatever of it still falls to immediate drawing goes
     * out after what was already recorded.
     */
    public static void renderTile(Object dispatcher, Object tile, float partial, int stage) {
        if (!TILES || !dynamicReady || !capturing) {
            ((TileEntityRendererDispatcher) dispatcher)
                    .renderTileEntity((TileEntity) tile, partial, stage);
            return;
        }
        if (SteinTileRetain.ENABLED && !session) {
            // Stationary tile of a known type: the previous frame's instances instead of the game's
            // renderer (SteinTileRetain); it calls renderTileNow when it needs to record again.
            SteinTileRetain.render(dispatcher, tile, partial, stage);
            return;
        }
        renderTileNow(dispatcher, tile, partial, stage);
    }

    /** The game's renderer for the tile, recording (the usual path for tile entities). */
    static void renderTileNow(Object dispatcher, Object tile, float partial, int stage) {
        // The crack (stage >= 0) records too: texture destroy_stage_N through the texture matrix,
        // blend DST_COLOR, SRC_COLOR and polygon offset (-3, -3), all from the state that
        // RenderGlobal.preRenderDamagedBlocks leaves.
        ((TileEntityRendererDispatcher) dispatcher)
                .renderTileEntity((TileEntity) tile, partial, stage);
    }

    /** Recording an extra pass of strict order in segments (switching groups does not flush). */
    static boolean strictSegments() {
        return capturing && session && strictOrder && !paused;
    }

    /**
     * Sky draw now ({@link SteinSky}): goes out without a texture if it has no coordinate, and
     * without light.
     */
    static boolean skyNoUv;

    /** Why the last dynamic draw was refused (renderer audit). */
    static String rejectWhy;

    /**
     * The stored names are going out now ({@link SteinTextGpu} recognises the name background by
     * this).
     */
    static boolean drainingNames() {
        return draining;
    }

    static final int RECORD_BYTES = INSTANCE_BYTES;

    /**
     * In the record: the matrix's 3 rows (0..47) and the lightmap (96..103) change per name; the
     * rest is the template.
     */
    static final int RECORD_LIGHT = 96;

    static final int POSE_INSTANCE_BYTES = INSTANCE_BYTES;

    // ---- entry points kept here, implemented by the classes of the package ----

    /** A part with the matrix {@code m[o..o+15]}, in the state read by {@link #beginModel()}. */
    public static void add(int list, float[] m, int o) {
        SteinDeferredModel.add(list, m, o);
    }

    /**
     * Reads the game's state for the model about to be drawn; false = cannot be recorded (draws
     * immediately).
     */
    public static boolean beginModel() {
        return SteinDeferredModel.beginModel();
    }

    static boolean beginDynamic(int bits, boolean ownBlend) {
        return SteinDeferredDynamic.beginDynamic(bits, ownBlend, false);
    }

    /** {@code lines}: the draw is lines (as a segment list), in a line pipeline. */
    static boolean beginDynamic(int bits, boolean ownBlend, boolean lines) {
        return SteinDeferredDynamic.beginDynamic(bits, ownBlend, lines);
    }

    static long reserveDynamic(int verts) {
        return SteinDeferredDynamic.reserveDynamic(verts);
    }

    static void commitDynamic(int verts) {
        SteinDeferredDynamic.commitDynamic(verts, false);
    }

    static void commitDynamic(int verts, boolean texBaked) {
        SteinDeferredDynamic.commitDynamic(verts, texBaked);
    }

    static boolean commitStatic(int first, int verts) {
        return SteinDeferredDynamic.commitStatic(first, verts);
    }

    static void dynRest(float[] out, int at) {
        SteinDeferredDynamic.dynRest(out, at);
    }

    static boolean commitText(int first, int verts, boolean background) {
        return SteinDeferredText.commitText(first, verts, background);
    }

    static boolean textPipelineValid() {
        return SteinDeferredText.textPipelineValid();
    }

    static long flushSerial() {
        return SteinDeferredText.flushSerial();
    }

    static void probeBegin() {
        SteinDeferredText.probeBegin();
    }

    static int probeEnd() {
        return SteinDeferredText.probeEnd();
    }

    static long probeKey(int i) {
        return SteinDeferredText.probeKey(i);
    }

    static int probeFirst(int i) {
        return SteinDeferredText.probeFirst(i);
    }

    static int probeVerts(int i) {
        return SteinDeferredText.probeVerts(i);
    }

    static boolean probeBackground(int i) {
        return SteinDeferredText.probeBackground(i);
    }

    static long probeRecord(int i) {
        return SteinDeferredText.probeRecord(i);
    }

    static long textKeyBase(long key) {
        return SteinDeferredText.textKeyBase(key);
    }

    static long textKeyWith(long base, int tex) {
        return SteinDeferredText.textKeyWith(base, tex);
    }

    static void labelFill(long template, long out) {
        SteinDeferredText.labelFill(template, out);
    }

    static int labelRecord(long template) {
        return SteinDeferredText.labelRecord(template);
    }

    static void labelCommand(long key, int first, int verts, int inst) {
        SteinDeferredText.labelCommand(key, first, verts, inst);
    }

    static String takeTextRecords() {
        return SteinDeferredText.takeTextRecords();
    }

    static boolean emitRetainedBatch(
            Object[] gs,
            int n,
            byte[] dyn,
            int[] verts,
            int[] ref,
            long iblob,
            float[] data,
            double[] fix,
            long vblob,
            int dynCount) {
        return SteinDeferredRetained.emitRetainedBatch(
                gs, n, dyn, verts, ref, iblob, data, fix, vblob, dynCount);
    }

    static void retainedLightmap() {
        SteinDeferredRetained.retainedLightmap();
    }

    static void startRecording() {
        SteinDeferredModel.startRecording();
    }

    /** {start, end} of the recorded parts, or null. */
    static int[] stopRecording() {
        return SteinDeferredModel.stopRecording();
    }

    static void replay(int[][] recs, int n, float[] tex) {
        SteinDeferredModel.replay(recs, n, tex);
    }

    static int groupEpoch() {
        return SteinDeferredGroups.groupEpoch();
    }

    static Object groupFor(long key) {
        return SteinDeferredGroups.groupFor(key);
    }

    static boolean oneShotBegin() {
        return SteinDeferredSessions.oneShotBegin();
    }

    static void oneShotEnd() {
        SteinDeferredSessions.oneShotEnd();
    }

    /** After each glLightModel and glPopAttrib of the game (patcher rules). */
    public static void lightsChanged() {
        SteinDeferredLights.lightsChanged();
    }

    /** Render.renderName inside doRender (patcher rule): during the pass, stores it for the end. */
    public static void renderName(Object render, Object entity, double x, double y, double z) {
        SteinDeferredNames.renderName(render, entity, x, y, z);
    }

    // ---- API for the pose ----

    static long poseReserve(int n) {
        return SteinDeferredPose.poseReserve(n);
    }

    static long poseAddress(long at) {
        return SteinDeferredPose.poseAddress(at);
    }

    static void poseNextRegion() {
        SteinDeferredPose.poseNextRegion();
    }

    static boolean poseGpuAllowed() {
        return SteinDeferredPose.poseGpuAllowed();
    }

    static long poseSlots(long key, int count) {
        return SteinDeferredPose.poseSlots(key, count);
    }

    static void poseShadow(long slot, int q, int verts, int first, int instance) {
        SteinDeferredPose.poseShadow(slot, q, verts, first, instance);
    }

    static int poseBlockCount() {
        return SteinDeferredPose.poseBlockCount();
    }

    static int poseBlockAt(int block) {
        return SteinDeferredPose.poseBlockAt(block);
    }

    static void posePart(long key, int list, int instance) {
        SteinDeferredPose.posePart(key, list, instance);
    }

    static void poseStatic(long key, int first, int verts, int instance) {
        SteinDeferredPose.poseStatic(key, first, verts, instance);
    }

    static int poseKey(
            int klass,
            boolean blend,
            int src,
            int dst,
            int srcA,
            int dstA,
            boolean depthTest,
            int depthFunc,
            boolean depthMask) {
        return SteinDeferredKeys.poseKey(
                klass, blend, src, dst, srcA, dstA, depthTest, depthFunc, depthMask);
    }

    static int poseFlags(boolean lighting, boolean lightmap, boolean fog) {
        return SteinDeferredKeys.poseFlags(lighting, lightmap, fog);
    }

    static void poseLightmap(int id) {
        SteinDeferredPose.poseLightmap(id);
    }
}
