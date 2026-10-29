/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import dev.xavier.stein.bridge.core.Log;
import dev.xavier.stein.gfx.vk.SteinVkRay;

import org.lwjgl.system.MemoryUtil;

/**
 * The Stein mode's light, apart from the drawing (stage 1 of docs/stein-mode-plan.md): the opaque
 * terrain layers draw into the game's target plus a G-buffer (what each pixel is: its colour and
 * material, its face, its light), and after the last of them one pass over the screen reads it with
 * the depth and traces the rays (ray_light.frag). The shaders that draw keep no ray query (on the
 * RX 7600 a discard next to one draws the discarded texels).
 *
 * <p>On when the device has ray tracing (Vulkan, {@code rt=true}) and the Lens's light is on;
 * otherwise nothing is made and the terrain draws to the game's target as before. Pipeline: Stein
 * GFX on Vulkan.
 */
public final class SteinRayLight {

    private static boolean failed;

    /** This frame's opaque terrain wrote the G-buffer: the light pass has something to read. */
    private static boolean written;

    private static SteinTexture albedo;
    private static SteinTexture normal;
    private static SteinTexture light;
    private static SteinTexture shade;
    private static SteinTexture dist;
    private static SteinRenderTarget distTarget;

    /** The game's colour and depth with the G-buffer: where the opaque layers draw. */
    private static SteinRenderTarget gbufferTarget;

    /** The game's colour alone: where the light pass adds (its depth is read). */
    private static SteinRenderTarget colorTarget;

    private static SteinTexture targetColor;
    private static SteinTexture targetDepth;

    private static SteinBuffer frameBlock;
    private static long frameAt;

    private static SteinPipeline pipeline;
    private static SteinSampler nearest;
    private static SteinSampler linear;

    /** The depth after the opaque terrain (the water's bottom) and the image before the water. */
    private static SteinTexture opaqueDepth;

    private static SteinTexture sceneCopy;
    private static SteinRenderTarget opaqueDepthTarget;
    private static SteinRenderTarget sceneCopyTarget;

    /** Until the first copies: 1x1 stand-ins (far depth, black). */
    private static SteinTexture farDepth;

    private static SteinTexture black;

    /** The bounced light, settled over frames: this frame's and last frame's, in turns. */
    private static final SteinTexture[] gi = new SteinTexture[2];

    /** The satin materials' blurred reflection, settled the same way (ray_gi.frag's second out). */
    private static final SteinTexture[] gloss = new SteinTexture[2];

    private static final SteinRenderTarget[] giTarget = new SteinRenderTarget[2];
    private static int giTurn;
    private static boolean giValid;
    private static SteinPipeline giPipeline;

    /** The History block (ray_gi.frag): last frame's projection and camera, the move, the frame. */
    private static final int HISTORY_BYTES = 160;

    private static SteinBuffer history;
    private static final float[] PREV = new float[32];
    private static final float[] NOW = new float[32];
    private static int prevCamX;
    private static int prevCamY;
    private static int prevCamZ;
    private static int frameNumber;

    private SteinRayLight() {}

    /**
     * Whether the Stein mode is on: chosen in the Lens and ray tracing on the device (decided at
     * start): the special light effects (light in the air and its dust, corner shading, light
     * through the leaves, reflections) exist only then; Ultra and below go without them.
     */
    public static boolean active() {
        return capable() && SteinBackend.rayTracingChosen();
    }

    /** Whether this session can have it: ray tracing on the device (decided at start). */
    static boolean capable() {
        return SteinVkRay.available();
    }

    /** Whether this frame's terrain goes through it. */
    static boolean on() {
        return !failed && active() && SteinLensLight.on();
    }

    /**
     * The target an opaque terrain layer draws into: the game's with the G-buffer when the mode is
     * on, else the game's.
     */
    static SteinRenderTarget target(SteinDevice dev) {
        SteinRenderTarget game = dev.gameTarget();
        if (!on()) {
            return game;
        }
        SteinTexture color = game.color(0);
        SteinTexture depth = game.depth();
        if (color == null || depth == null) {
            return game;
        }
        try {
            ensure(dev, color, depth);
        } catch (RuntimeException | LinkageError e) {
            failed = true;
            Log.warn("gfx", "Stein light turned off for this session: " + e);
            return game;
        }
        if (!written) {
            // the frame's first opaque layer: what it does not draw (the clouds, drawn before it
            // with
            // depth) must not be lit as terrain
            SteinCommands c =
                    dev.beginPass(
                            SteinRenderPass.clear("stein distance", distTarget, 0f, 0f, 0f, 0f));
            c.end();
        }
        written = true;
        return gbufferTarget;
    }

    /** The Frame block of this frame's opaque terrain (projection, camera, sun, sky). */
    static void frame(SteinBuffer block, long at) {
        frameBlock = block;
        frameAt = at;
        if (block.mapped()) {
            // the projection and the camera (the first two matrices), for next frame's reprojection
            long p = block.address(at);
            for (int i = 0; i < 32; i++) {
                NOW[i] = MemoryUtil.memGetFloat(p + 4L * i);
            }
        }
    }

    /** After the last opaque terrain layer (the game's cutout one), before entities and water. */
    public static void afterOpaque() {
        if (!written) {
            return;
        }
        written = false;
        SteinDevice dev0 = SteinDevice.get();
        if (!failed && opaqueDepthTarget != null) {
            // the water's bottom: the depth as the opaque world left it
            dev0.copyTarget(gbufferTarget, opaqueDepthTarget, true);
        }
        SteinBuffer vertices = SteinTerrain.oneBuffer();
        long top = SteinVkRay.top();
        if (failed || frameBlock == null || vertices == null || top == 0L) {
            return;
        }
        SteinDevice dev = dev0;
        try {
            if (pipeline == null) {
                create(dev);
            }
            SteinTexture giNow = bounce(dev, vertices, top);
            SteinCommands c = dev.beginPass(SteinRenderPass.keep("stein light", colorTarget));
            try {
                c.bindPipeline(pipeline);
                c.bindTexture(6, giNow, nearest);
                c.bindTexture(7, gloss[1 - giTurn], nearest);
                c.bindTexture(8, SteinMaterialMap.texture(dev), nearest);
                c.bindTexture(9, shade, nearest);
                c.bindTexture(10, dist, nearest);
                c.bindUniforms(0, frameBlock, frameAt, SteinTerrain.FRAME_BYTES);
                c.bindTexture(0, SteinTerrain.atlasTexture(), null);
                c.bindTexture(1, SteinTerrain.lightmapTexture(), null);
                c.bindTexture(2, targetDepth, nearest);
                c.bindTexture(3, albedo, nearest);
                c.bindTexture(4, normal, nearest);
                c.bindTexture(5, light, nearest);
                c.bindAccelerationStructure(0, top);
                c.bindStorage(0, vertices, 0, vertices.size());
                c.draw(3, 1, 0, 0);
            } finally {
                c.end();
            }
        } catch (RuntimeException | LinkageError e) {
            failed = true;
            Log.warn("gfx", "Stein light turned off for this session: " + e);
        }
    }

    /** The bounced light pass (ray_gi.frag) into this frame's turn; returns it. */
    private static SteinTexture bounce(SteinDevice dev, SteinBuffer vertices, long top) {
        int w = albedo.width();
        int h = albedo.height();
        if (gi[0] == null || gi[0].width() != w || gi[0].height() != h) {
            for (int i = 0; i < 2; i++) {
                if (gi[i] != null) {
                    giTarget[i].destroy();
                    gi[i].destroy();
                    gloss[i].destroy();
                }
                gi[i] =
                        dev.createTexture(
                                SteinTexture.Desc.target(
                                        "stein bounced " + i, SteinFormat.RGBA16F, w, h));
                gloss[i] =
                        dev.createTexture(
                                SteinTexture.Desc.target(
                                        "stein satin " + i, SteinFormat.RGBA16F, w, h));
                giTarget[i] = dev.createTarget(new SteinTexture[] {gi[i], gloss[i]}, null);
            }
            giValid = false;
        }
        int cx = SteinTerrain.frameCamX();
        int cy = SteinTerrain.frameCamY();
        int cz = SteinTerrain.frameCamZ();
        long at = history.reserve(HISTORY_BYTES, dev.caps().uniformAlignment);
        if (at == -2) {
            history.nextRegion();
            at = history.reserve(HISTORY_BYTES, dev.caps().uniformAlignment);
        }
        long p = history.address(at);
        for (int i = 0; i < 32; i++) {
            MemoryUtil.memPutFloat(p + 4L * i, PREV[i]);
        }
        MemoryUtil.memPutFloat(p + 128, cx - prevCamX);
        MemoryUtil.memPutFloat(p + 132, cy - prevCamY);
        MemoryUtil.memPutFloat(p + 136, cz - prevCamZ);
        MemoryUtil.memPutFloat(p + 140, frameNumber++ & 0xFFFFFF);
        MemoryUtil.memPutFloat(p + 144, giValid ? 1f : 0f);
        history.commitRing();
        int now = giTurn;
        SteinCommands c = dev.beginPass(SteinRenderPass.discard("stein bounced", giTarget[now]));
        try {
            c.bindPipeline(giPipeline);
            c.bindUniforms(0, frameBlock, frameAt, SteinTerrain.FRAME_BYTES);
            c.bindUniforms(1, history, at, HISTORY_BYTES);
            c.bindTexture(0, SteinTerrain.atlasTexture(), null);
            c.bindTexture(1, SteinTerrain.lightmapTexture(), null);
            c.bindTexture(2, targetDepth, nearest);
            c.bindTexture(3, albedo, nearest);
            c.bindTexture(4, normal, nearest);
            c.bindTexture(5, light, nearest);
            c.bindTexture(6, gi[1 - now], SteinRayLight.linear);
            c.bindTexture(7, SteinMaterialMap.texture(dev), nearest);
            c.bindTexture(8, gloss[1 - now], linear);
            c.bindTexture(9, dist, nearest);
            c.bindAccelerationStructure(0, top);
            c.bindStorage(0, vertices, 0, vertices.size());
            c.draw(3, 1, 0, 0);
        } finally {
            c.end();
        }
        System.arraycopy(NOW, 0, PREV, 0, 32);
        prevCamX = cx;
        prevCamY = cy;
        prevCamZ = cz;
        giValid = true;
        giTurn = 1 - now;
        return gi[now];
    }

    /**
     * Before the translucent layer in the Stein mode: the image so far (opaque world, entities),
     * which the water shows through itself.
     */
    static void beforeTranslucent(SteinDevice dev) {
        if (failed || sceneCopyTarget == null || colorTarget == null) {
            return;
        }
        try {
            dev.copyTarget(colorTarget, sceneCopyTarget, false);
        } catch (RuntimeException | LinkageError e) {
            failed = true;
            Log.warn("gfx", "Stein light turned off for this session: " + e);
        }
    }

    /** What the Stein water reads: the image before it, the depth before it, the rays' scene. */
    static void bindWater(SteinCommands cmd) {
        SteinDevice dev = SteinDevice.get();
        if (farDepth == null) {
            farDepth = solid(dev, "stein far depth", 0x3C00);
            black = solid(dev, "stein black", 0);
            if (nearest == null) {
                nearest = dev.createSampler(SteinSampler.Desc.nearestClamp());
            }
        }
        boolean ready = !failed && sceneCopy != null && opaqueDepth != null;
        cmd.bindTexture(8, ready ? sceneCopy : black, nearest);
        cmd.bindTexture(9, ready ? opaqueDepth : farDepth, nearest);
        long top = SteinVkRay.top();
        if (top != 0L) {
            cmd.bindAccelerationStructure(0, top);
        }
        SteinBuffer vertices = SteinTerrain.oneBuffer();
        if (vertices != null) {
            cmd.bindStorage(0, vertices, 0, vertices.size());
        }
    }

    /** A 1x1 RGBA16F texture with every channel {@code half} (a half float). */
    private static SteinTexture solid(SteinDevice dev, String name, int half) {
        SteinTexture t =
                dev.createTexture(SteinTexture.Desc.texture2D(name, SteinFormat.RGBA16F, 1, 1, 1));
        java.nio.ByteBuffer b = org.lwjgl.system.MemoryUtil.memAlloc(8);
        try {
            for (int i = 0; i < 4; i++) {
                b.putShort(i * 2, (short) half);
            }
            t.upload(0, 0, 0, 0, 1, 1, b);
        } finally {
            org.lwjgl.system.MemoryUtil.memFree(b);
        }
        return t;
    }

    /** The G-buffer at the game's size and the two targets over the game's colour and depth. */
    private static void ensure(SteinDevice dev, SteinTexture color, SteinTexture depth) {
        if (color == targetColor && depth == targetDepth && gbufferTarget != null) {
            return;
        }
        int w = color.width();
        int h = color.height();
        if (albedo == null || albedo.width() != w || albedo.height() != h) {
            if (albedo != null) {
                albedo.destroy();
                normal.destroy();
                light.destroy();
                shade.destroy();
                dist.destroy();
            }
            albedo =
                    dev.createTexture(
                            SteinTexture.Desc.target("stein albedo", SteinFormat.RGBA8, w, h));
            normal =
                    dev.createTexture(
                            SteinTexture.Desc.target("stein normal", SteinFormat.RGBA16F, w, h));
            light =
                    dev.createTexture(
                            SteinTexture.Desc.target("stein light", SteinFormat.RGBA8, w, h));
            shade =
                    dev.createTexture(
                            SteinTexture.Desc.target("stein shade", SteinFormat.R8, w, h));
            dist =
                    dev.createTexture(
                            SteinTexture.Desc.target("stein distance", SteinFormat.R32F, w, h));
        }
        if (gbufferTarget != null) {
            gbufferTarget.destroy();
            colorTarget.destroy();
        }
        // the water's copies, in the game's own formats (the copies are texture to texture)
        if (sceneCopy == null
                || sceneCopy.width() != w
                || sceneCopy.height() != h
                || sceneCopy.desc().format != color.desc().format
                || opaqueDepth.desc().format != depth.desc().format) {
            if (sceneCopy != null) {
                sceneCopyTarget.destroy();
                opaqueDepthTarget.destroy();
                sceneCopy.destroy();
                opaqueDepth.destroy();
            }
            sceneCopy =
                    dev.createTexture(
                            SteinTexture.Desc.target(
                                    "stein scene copy", color.desc().format, w, h));
            opaqueDepth =
                    dev.createTexture(
                            SteinTexture.Desc.target(
                                    "stein opaque depth", depth.desc().format, w, h));
            sceneCopyTarget = dev.createTarget(new SteinTexture[] {sceneCopy}, null);
            opaqueDepthTarget = dev.createTarget(new SteinTexture[0], opaqueDepth);
        }
        gbufferTarget =
                dev.createTarget(
                        new SteinTexture[] {color, albedo, normal, light, shade, dist}, depth);
        colorTarget = dev.createTarget(new SteinTexture[] {color}, null);
        if (distTarget == null || distTarget.color(0) != dist) {
            if (distTarget != null) {
                distTarget.destroy();
            }
            distTarget = dev.createTarget(new SteinTexture[] {dist}, null);
        }
        targetColor = color;
        targetDepth = depth;
    }

    private static void create(SteinDevice dev) {
        pipeline =
                dev.createPipeline(
                        SteinPipeline.builder("stein light")
                                .vertex(SteinShader.load(SteinShader.Stage.VERTEX, "post.vert"))
                                .fragment(
                                        SteinShader.load(
                                                SteinShader.Stage.FRAGMENT, "ray_light.frag"))
                                .depth(null, false)
                                .cull(SteinPipeline.Cull.NONE)
                                // premultiplied: src + dst * (1 - src alpha), the alpha kept
                                .blend(
                                        SteinPipeline.Factor.ONE,
                                        SteinPipeline.Factor.ONE_MINUS_SRC_ALPHA,
                                        SteinPipeline.Factor.ZERO,
                                        SteinPipeline.Factor.ONE)
                                .uniforms("Frame", 0)
                                .texture("tex", 0)
                                .texture("lightmap", 1)
                                .texture("depthMap", 2)
                                .texture("gAlbedo", 3)
                                .texture("gNormal", 4)
                                .texture("gLight", 5)
                                .texture("giNow", 6)
                                .texture("glossNow", 7)
                                .texture("materialMap", 8)
                                .texture("gShade", 9)
                                .texture("gDist", 10)
                                .acceleration("rayScene", 0)
                                .storage("TerrainVertices", 0)
                                .build());
        giPipeline =
                dev.createPipeline(
                        SteinPipeline.builder("stein bounced")
                                .vertex(SteinShader.load(SteinShader.Stage.VERTEX, "post.vert"))
                                .fragment(
                                        SteinShader.load(SteinShader.Stage.FRAGMENT, "ray_gi.frag"))
                                .depth(null, false)
                                .cull(SteinPipeline.Cull.NONE)
                                .uniforms("Frame", 0)
                                .uniforms("History", 1)
                                .texture("tex", 0)
                                .texture("lightmap", 1)
                                .texture("depthMap", 2)
                                .texture("gAlbedo", 3)
                                .texture("gNormal", 4)
                                .texture("gLight", 5)
                                .texture("giPrev", 6)
                                .texture("materialMap", 7)
                                .texture("glossPrev", 8)
                                .texture("gDist", 9)
                                .acceleration("rayScene", 0)
                                .storage("TerrainVertices", 0)
                                .build());
        if (!pipeline.valid() || !giPipeline.valid()) {
            throw new IllegalStateException("light pass pipelines did not link");
        }
        nearest = dev.createSampler(SteinSampler.Desc.nearestClamp());
        linear = dev.createSampler(SteinSampler.Desc.linearClamp());
        history =
                dev.createBuffer(
                                new SteinBuffer.Desc(
                                        "stein history",
                                        4096L * 3,
                                        SteinBuffer.Memory.UPLOAD,
                                        SteinBuffer.Usage.UNIFORM))
                        .ring(3);
    }
}
