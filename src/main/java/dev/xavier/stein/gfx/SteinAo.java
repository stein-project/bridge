/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import dev.xavier.stein.bridge.core.Log;

import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;

/**
 * The Lens's corner shading (screen-space ambient occlusion): where the world folds in on itself —
 * the foot of a wall, a corner, under a step, around a mob standing on the ground — the light of
 * the sky and of the bounce gets in less, so it darkens a little. Two passes over the world's
 * depth, inside the post-processing, before the bloom and the composite: at half the size, the
 * point and its face from the depth and a few taps in a half-ball turned per pixel (post_ao.frag);
 * then at full size, spread back with the depth (the shading never leaks across an edge),
 * multiplied into the copy of the scene (post_ao_apply.frag).
 *
 * <p>Why on the screen: the game's smooth lighting already darkens the corners of blocks, but not
 * where an entity, a slab or a far wall meets the ground; the screen sees all of them alike. It
 * fades out with the distance (the haze takes over) and leaves the hand and the sky alone.
 *
 * <p>Off (level 0) nothing is made nor drawn. Pipeline: Stein GFX only.
 */
public final class SteinAo {

    /** How dark a fully closed corner gets, per level (0 off, 1 soft, 2 strong). */
    private static final float[] STRENGTH = {0f, 0.6f, 1.0f};

    /** How far the half-ball reaches, in blocks. */
    private static final float RADIUS = 0.8f;

    /** The shading fades out between these distances (blocks). */
    private static final float FADE_FROM = 24f;

    private static final float FADE_TO = 48f;

    /** Bytes of a block (post.glsl's Post: 19 vec4). */
    private static final int BLOCK_BYTES = 304;

    private static final int RING_REGIONS = 3;

    private static volatile int level;
    private static boolean failed;

    private static SteinPipeline ao;
    private static SteinPipeline apply;
    private static SteinSampler nearest;
    private static SteinSampler linear;
    private static SteinBuffer blocks;
    private static SteinRenderTarget half;

    /** The scene's colour alone (its own target has the hand's depth, which the pass reads). */
    private static SteinRenderTarget sceneColorTarget;

    private static SteinTexture sceneColorOf;

    /** The projection's x, y and depth terms, from the first terrain layer of the frame. */
    private static final float[] PROJ = new float[4];

    private static boolean captured;

    private static final float[] B = new float[BLOCK_BYTES / 4];

    private SteinAo() {}

    /** The Lens option (any thread): 0 off, 1 soft, 2 strong. */
    public static void configure(int aoLevel) {
        level = Math.max(0, Math.min(2, aoLevel));
    }

    /** Whether it draws: the option on, the Stein mode (ray tracing) and nothing failed. */
    static boolean on() {
        return level > 0 && !failed && SteinGfxOptions.ENABLED && SteinRayLight.active();
    }

    /** At each terrain layer's Frame block: the first time in a frame, keeps the projection. */
    static void capture(FloatBuffer proj) {
        if (captured || level == 0) {
            return;
        }
        captured = true;
        PROJ[0] = proj.get(0);
        PROJ[1] = proj.get(5);
        PROJ[2] = proj.get(10);
        PROJ[3] = proj.get(14);
    }

    /**
     * Darkens the corners of {@code sceneColor} (the post-processing's copy of the game's image),
     * from the world's depth; {@code handDepth} is the hand's alone when {@code handApart}, so the
     * hand is left as drawn. Render thread, inside the post-processing.
     */
    static void apply(
            SteinDevice dev,
            SteinTexture sceneColor,
            SteinTexture worldDepth,
            SteinTexture handDepth,
            boolean handApart,
            int w,
            int h) {
        if (!captured) {
            return; // no world drawn this frame (a menu over nothing)
        }
        captured = false;
        try {
            if (ao == null) {
                create(dev);
            }
            int hw = Math.max(1, w / 2);
            int hh = Math.max(1, h / 2);
            if (half == null || half.color(0).width() != hw || half.color(0).height() != hh) {
                if (half != null) {
                    half.color(0).destroy();
                    half.destroy();
                }
                SteinTexture t =
                        dev.createTexture(
                                SteinTexture.Desc.target("post ao", SteinFormat.R8, hw, hh));
                half = dev.createTarget(new SteinTexture[] {t}, null);
            }
            int align = dev.caps().uniformAlignment;
            long first = block(align, w, h, hw, hh, handApart);
            long second = block(align, hw, hh, w, h, handApart);
            blocks.commitRing();
            SteinCommands c = dev.beginPass(SteinRenderPass.discard("post ao", half));
            try {
                c.bindPipeline(ao);
                c.bindUniforms(0, blocks, first, BLOCK_BYTES);
                c.bindTexture(0, worldDepth, nearest);
                c.draw(3, 1, 0, 0);
            } finally {
                c.end();
            }
            if (sceneColorTarget == null || sceneColorOf != sceneColor) {
                if (sceneColorTarget != null) {
                    sceneColorTarget.destroy();
                }
                sceneColorTarget = dev.createTarget(new SteinTexture[] {sceneColor}, null);
                sceneColorOf = sceneColor;
            }
            c = dev.beginPass(SteinRenderPass.keep("post ao apply", sceneColorTarget));
            try {
                c.bindPipeline(apply);
                c.bindUniforms(0, blocks, second, BLOCK_BYTES);
                c.bindTexture(0, half.color(0), linear);
                c.bindTexture(1, worldDepth, nearest);
                c.bindTexture(2, handDepth, nearest);
                c.draw(3, 1, 0, 0);
            } finally {
                c.end();
            }
        } catch (RuntimeException | LinkageError e) {
            failed = true;
            Log.warn("gfx", "corner shading turned off for this session: " + e);
        }
    }

    /** After the world's drawing, also when the post-processing did not run. */
    static void worldEnd() {
        captured = false;
    }

    /**
     * One pass's block: texel (1 / size read, 1 / size drawn), then params: strength, radius, fade
     * from, fade to; params2: the projection's terms; params3.x 1 when the hand is apart.
     */
    private static long block(
            int align, int readW, int readH, int drawW, int drawH, boolean handApart) {
        B[0] = 1f / readW;
        B[1] = 1f / readH;
        B[2] = 1f / drawW;
        B[3] = 1f / drawH;
        B[4] = STRENGTH[level];
        B[5] = RADIUS;
        B[6] = FADE_FROM;
        B[7] = FADE_TO;
        System.arraycopy(PROJ, 0, B, 8, 4);
        B[12] = handApart ? 1f : 0f;
        long at = blocks.reserve(BLOCK_BYTES, align);
        if (at == -2) {
            blocks.nextRegion();
            at = blocks.reserve(BLOCK_BYTES, align);
        }
        long p = blocks.address(at);
        for (int i = 0; i < B.length; i++) {
            MemoryUtil.memPutFloat(p + 4L * i, B[i]);
            B[i] = 0f;
        }
        return at;
    }

    private static void create(SteinDevice dev) {
        SteinShader vertex = SteinShader.load(SteinShader.Stage.VERTEX, "post.vert");
        ao =
                dev.createPipeline(
                        SteinPipeline.builder("post ao")
                                .vertex(vertex)
                                .fragment(
                                        SteinShader.load(
                                                SteinShader.Stage.FRAGMENT, "post_ao.frag"))
                                .depth(null, false)
                                .cull(SteinPipeline.Cull.NONE)
                                .uniforms("Post", 0)
                                .texture("depth", 0)
                                .build());
        // multiplies the scene by the shading: src * dst + dst * 0
        apply =
                dev.createPipeline(
                        SteinPipeline.builder("post ao apply")
                                .vertex(vertex)
                                .fragment(
                                        SteinShader.load(
                                                SteinShader.Stage.FRAGMENT, "post_ao_apply.frag"))
                                .depth(null, false)
                                .cull(SteinPipeline.Cull.NONE)
                                .blend(
                                        SteinPipeline.Factor.DST_COLOR,
                                        SteinPipeline.Factor.ZERO,
                                        SteinPipeline.Factor.ZERO,
                                        SteinPipeline.Factor.ONE)
                                .uniforms("Post", 0)
                                .texture("ao", 0)
                                .texture("depth", 1)
                                .texture("handDepth", 2)
                                .build());
        if (!ao.valid() || !apply.valid()) {
            throw new IllegalStateException("corner shading pipelines did not link");
        }
        nearest = dev.createSampler(SteinSampler.Desc.nearestClamp());
        linear = dev.createSampler(SteinSampler.Desc.linearClamp());
        blocks =
                dev.createBuffer(
                                new SteinBuffer.Desc(
                                        "post ao blocks",
                                        4096L * RING_REGIONS,
                                        SteinBuffer.Memory.UPLOAD,
                                        SteinBuffer.Usage.UNIFORM))
                        .ring(RING_REGIONS);
    }
}
