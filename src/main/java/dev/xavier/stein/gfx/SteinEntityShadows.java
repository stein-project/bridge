/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import static dev.xavier.stein.gfx.SteinDeferredKeys.*;
import static dev.xavier.stein.gfx.SteinDeferredResources.*;

import dev.xavier.stein.bridge.core.Log;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;

import org.lwjgl.system.MemoryUtil;

import java.util.List;

/**
 * The shadows of entities and of the player (with {@link SteinShadows}): a map of their own, {@link
 * #SIZE} pixels over {@link #RADIUS} blocks around the camera, seen from the same light.
 *
 * <p>The casters are the model parts of the entity pass, as recorded ({@link SteinEntityDeferred}):
 * when a flush of the pass draws them on the screen, the groups without blending draw again here
 * (only the depth, the skin's clear pixels left out). In first person the player is not in the
 * pass: the game's renderer draws them once more, recorded for this map only. Held items, names and
 * everything drawn through the Tessellator do not cast.
 *
 * <p>The terrain is drawn before the entities, so the receivers read the map of the frame before
 * (with the anchor it was drawn around): the shadow follows one frame behind, which does not show.
 * The terrain is not in this map; the receivers take the darker of the two.
 *
 * <p>Pipeline: Stein GFX with indirect draw (OpenGL 4.4+, Vulkan).
 */
final class SteinEntityShadows {

    /** Blocks covered around the camera, each way. */
    static final float RADIUS = 32f;

    /** The map's size in pixels. */
    static final int SIZE = 2048;

    /** How far the map reaches towards and away from the light, in blocks. */
    private static final float DEPTH = 96f;

    /** Bytes of the receivers' block: the matrix and the settings. */
    static final int BYTES = 64 + 16;

    private static SteinTexture map;
    private static SteinRenderTarget target;
    private static SteinPipeline arrayPipeline;
    private static SteinPipeline gameTexPipeline;
    private static SteinBuffer frameRing;
    private static boolean failed;

    /** This frame's casters go into the map (the light is on). */
    private static boolean drawing;

    /** The map was cleared and drawn into this frame. */
    private static boolean drawnNow;

    /** The frames (each terrain drawing), and the one the map was last drawn in. */
    private static long frames;

    private static long drawnFrame = -2;

    /** The receivers read the map this frame (it holds the last frame's casters). */
    private static boolean readable;

    /** The anchor this frame draws around, and the one the map was drawn around. */
    private static double anchorX;

    private static double anchorY;
    private static double anchorZ;
    private static double drawnX;
    private static double drawnY;
    private static double drawnZ;

    /** Eye -> the map, for this frame's casters and for the receivers (the map drawn last). */
    private static final float[] DRAW = new float[16];

    private static final float[] RECEIVE = new float[16];

    /** The first-person player being drawn for this map only (not on the screen). */
    static boolean castOnly;

    /** The entity pass is open (its flushes draw the casters). */
    static boolean entityPass;

    private SteinEntityShadows() {}

    /** A frame without the sun's shadows. */
    static void off() {
        frames++;
        drawing = false;
        readable = false;
        drawnNow = false;
    }

    /**
     * At the terrain's first layer, with the light's view decided: the receivers' matrix for the
     * map drawn last, and the anchor and matrix for this frame's casters.
     */
    static void frame(float[] m, double cx, double cy, double cz) {
        readable = drawnFrame == frames - 1; // (off() already counted this frame)
        drawing = !failed && SteinDeferredResources.indirect;
        if (readable) {
            SteinShadows.lightMatrix(
                    RECEIVE, m, cx - drawnX, cy - drawnY, cz - drawnZ, RADIUS, DEPTH);
        }
        anchorX = Math.floor(cx);
        anchorY = Math.floor(cy);
        anchorZ = Math.floor(cz);
        SteinShadows.lightMatrix(DRAW, m, cx - anchorX, cy - anchorY, cz - anchorZ, RADIUS, DEPTH);
    }

    /**
     * Writes the receivers' block at {@code p}: the matrix, then on, texel (uv), texel (blocks).
     */
    static void write(long p, boolean lit) {
        for (int i = 0; i < 16; i++) {
            MemoryUtil.memPutFloat(p + i * 4L, RECEIVE[i]);
        }
        MemoryUtil.memPutFloat(p + 64, lit && readable ? 1f : 0f);
        MemoryUtil.memPutFloat(p + 68, 1f / SIZE);
        MemoryUtil.memPutFloat(p + 72, 2f * RADIUS / SIZE);
        MemoryUtil.memPutFloat(p + 76, 0f);
    }

    /** The map to sample now, or the stand-in. */
    static SteinTexture texture() {
        return readable && map != null && SteinShadows.strength() > 0f ? map : SteinShadows.dummy();
    }

    // ---- the casters ----

    /** Whether a flush of the entity pass should draw its groups here too. */
    static boolean casting() {
        return drawing && entityPass && !SteinDeferredSessions.session;
    }

    /**
     * After a flush drew the groups on the screen (their commands are written): the groups without
     * blending, model parts only, draw their depth here.
     */
    static void cast(List<SteinDeferredGroups.Group> groups) {
        if (!casting()) {
            return;
        }
        SteinDevice dev = SteinDevice.get();
        try {
            if (map == null) {
                create(dev);
            }
            long frameAt = frameRing.reserve(SteinInstanceWriter.FRAME_BYTES, alignment(dev));
            if (frameAt == -2) {
                frameRing.nextRegion();
                frameAt = frameRing.reserve(SteinInstanceWriter.FRAME_BYTES, alignment(dev));
            }
            if (frameAt < 0) {
                return;
            }
            long p = frameRing.address(frameAt);
            MemoryUtil.memSet(p, 0, SteinInstanceWriter.FRAME_BYTES);
            for (int i = 0; i < 16; i++) {
                MemoryUtil.memPutFloat(p + i * 4L, DRAW[i]);
            }
            frameRing.commitRing();
            SteinRenderPass rp =
                    drawnNow
                            ? SteinRenderPass.keep("entity shadows", target)
                            : SteinRenderPass.clear("entity shadows", target, 0f, 0f, 0f, 0f);
            drawnNow = true;
            drawnFrame = frames;
            drawnX = anchorX;
            drawnY = anchorY;
            drawnZ = anchorZ;
            SteinCommands c = dev.beginPass(rp);
            try {
                c.bindVertexBuffer(0, SteinModelInstancer.geometry(), 0);
                c.bindVertexBuffer(1, instances, 0);
                c.bindUniforms(0, frameRing, frameAt, SteinInstanceWriter.FRAME_BYTES);
                c.setDepthBias(true, 1.5f, 2f);
                for (int i = 0, n = groups.size(); i < n; i++) {
                    SteinDeferredGroups.Group g = groups.get(i);
                    // (the first-person player is recorded with the blending the pass left on: the
                    // skin's alpha test decides there too)
                    if (g.n == 0
                            || g.cmdAt < 0
                            || (g.blend && !castOnly)
                            || g.dynamic
                            || g.offset != 0) {
                        continue;
                    }
                    if (g.gameTex) {
                        c.bindPipeline(gameTexPipeline);
                        c.bindTexture(0, g.gameTexture, null);
                    } else {
                        c.bindPipeline(arrayPipeline);
                        c.bindTexture(
                                0,
                                SteinTextureArrays.array(keyClass(g.key)),
                                SteinTextureArrays.sampler(keyClass(g.key)));
                    }
                    c.drawIndirect(commands, g.cmdAt, g.n);
                }
                // off again: the bias is state that outlives the pass on some paths (the HUD's
                // and the inventory's items came out behind their slots)
                c.setDepthBias(false, 0f, 0f);
            } finally {
                c.end();
            }
        } catch (RuntimeException | LinkageError e) {
            failed = true;
            drawing = false;
            readable = false;
            Log.warn("gfx", "entity shadows turned off for this session: " + e);
        }
    }

    /**
     * At the end of the entity pass, in first person: the player, whom the pass leaves out, drawn
     * by the game's renderer for this map only.
     */
    static void firstPersonPlayer(float partial) {
        if (!drawing || failed) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        if (player == null
                || mc.gameSettings.thirdPersonView != 0
                || mc.getRenderViewEntity() != player
                || player.isInvisible()
                || player.isSpectator()
                || player.isPlayerSleeping()) {
            return;
        }
        SteinEntityDeferred.castPlayer(player, partial);
    }

    private static int alignment(SteinDevice dev) {
        return dev.caps().uniformAlignment;
    }

    private static void create(SteinDevice dev) {
        map =
                dev.createTexture(
                        SteinTexture.Desc.target(
                                "entity shadow map", SteinFormat.DEPTH32F, SIZE, SIZE));
        target = dev.createTarget(new SteinTexture[0], map);
        SteinShader vertex = SteinShader.load(SteinShader.Stage.VERTEX, "entity.vert");
        arrayPipeline = pipeline(dev, vertex, "entity_cast.frag", "entity shadows");
        gameTexPipeline =
                pipeline(dev, vertex, "entity_cast_gametex.frag", "entity shadows (game texture)");
        frameRing =
                dev.createBuffer(
                                new SteinBuffer.Desc(
                                        "entity shadows: frame",
                                        8192L * 3,
                                        SteinBuffer.Memory.UPLOAD,
                                        SteinBuffer.Usage.UNIFORM))
                        .ring(3);
        Log.info(
                "gfx",
                "entity shadows: "
                        + SIZE
                        + "x"
                        + SIZE
                        + " map over "
                        + (int) (2 * RADIUS)
                        + " blocks");
    }

    private static SteinPipeline pipeline(
            SteinDevice dev, SteinShader vertex, String fragment, String name) {
        SteinPipeline p =
                dev.createPipeline(
                        SteinPipeline.builder(name)
                                .vertex(vertex)
                                .fragment(SteinShader.load(SteinShader.Stage.FRAGMENT, fragment))
                                .layout(layout)
                                .texture("tex", 0)
                                .uniforms("Frame", 0)
                                .depth(SteinPipeline.Depth.LEQUAL, true)
                                .cull(SteinPipeline.Cull.NONE)
                                .build());
        if (!p.valid()) {
            throw new IllegalStateException("the entity shadow pipeline did not link: " + name);
        }
        return p;
    }
}
