/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import dev.xavier.stein.bridge.core.Log;

import net.minecraft.client.renderer.GlStateManager;

import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;

/**
 * Stein's post-processing of the world's image (the Lens "Image" options): bloom and edge
 * smoothing. It runs right after EntityRenderer.renderWorld in updateCameraAndRender — the world
 * and the hand are done, the HUD and the screens come after, untouched — where the game's own
 * shader group (spectator, F4) also runs.
 *
 * <p>Each frame with something on: the game's target (colour and, for the edges, depth) is copied
 * into ours ({@link SteinDevice#copyTarget}), the bloom levels are built from the copy ({@link
 * SteinBloom}), and one pass draws the result over the game's colour (its alpha is kept). With
 * everything off nothing runs and nothing is allocated; the composite with neutral settings gives
 * back the game's pixels unchanged.
 *
 * <p>With HDR output ({@link SteinHdr}) the composite always runs: the light sources (what passes
 * the bloom's threshold) rise above the paper white, and the bloom adds instead of blending.
 *
 * <p>Any failure turns it off for the session with one line in the log, instead of taking the game
 * down.
 *
 * <p>Pipeline: Stein GFX only (OpenGL and Vulkan).
 */
public final class SteinPostFx {

    public static final int BLOOM_OFF = 0;
    public static final int BLOOM_LOW = 1;
    public static final int BLOOM_HIGH = 2;

    /** Strength of the bloom, spread over its levels, added by "screen". */
    private static final float[] BLOOM_STRENGTH = {0f, 1.2f, 2.4f};

    /**
     * The luma from which the scene glows: the scene's mean times {@link #BLOOM_MEAN_FACTOR}, kept
     * between these two. At night (mean ~0.15) the lights pass; by day (~0.4) only near white.
     */
    private static final float BLOOM_MIN_THRESHOLD = 0.5f;

    private static final float BLOOM_MAX_THRESHOLD = 0.9f;
    private static final float BLOOM_MEAN_FACTOR = 2f;
    private static final float BLOOM_KNEE = 0.15f;

    /** The small image whose mipmaps give the scene's mean luma. */
    private static final int LUMA_SIZE = 64;

    private static final int LUMA_MIPS = 7;

    /**
     * How much bloom luma around a pixel makes it a full light with HDR output: a lone bright texel
     * spreads to almost nothing, a glowing block to well past this.
     */
    private static final float HDR_GLOW_GAIN = 4f;

    /** The edge smoothing fades out between half of this and this, in blocks. */
    private static final float EDGE_DISTANCE = 32f;

    /** Bytes of one pass's Post block (five vec4). */
    private static final int BLOCK_BYTES = 80;

    /** One block being written (reused: no allocation per frame). */
    private static final float[] B = new float[BLOCK_BYTES / 4];

    /** Passes per frame at most: the mean luma, the bloom's way down and up, and the composite. */
    private static final int MAX_PASSES = 2 * SteinBloom.MAX_LEVELS + 2;

    private static final int RING_REGIONS = 4;

    private static volatile int bloom;
    private static volatile boolean edges;
    private static boolean failed;

    /** The depth of the game's target could not be copied: no edge smoothing (logged once). */
    private static boolean noDepth;

    private static SteinPipeline bloomFirst;
    private static SteinPipeline bloomDown;
    private static SteinPipeline bloomUp;
    private static SteinPipeline composite;
    private static SteinPipeline luma;
    private static SteinRenderTarget lumaTarget;
    private static SteinSampler mipmapped;
    private static SteinSampler linear;
    private static SteinSampler nearest;
    private static SteinBuffer blocks;

    /** The copy of the game's image (colour RGBA8, depth only with the edges). */
    private static SteinRenderTarget scene;

    private static SteinTexture sceneColor;

    /** The depth at the end of the world: only the hand's (the game clears the rest before it). */
    private static SteinTexture sceneDepth;

    /** The world's depth, copied before that clear ({@link #worldClear}), as a target. */
    private static SteinRenderTarget world;

    private static SteinTexture worldDepth;

    /** Frames seen by {@link #afterWorld}, and the one whose world depth was copied. */
    private static long frame;

    private static long worldFrame = -1;
    private static final SteinBloom BLOOM = new SteinBloom();

    private static final String[] DOWN_NAMES = names("post bloom down ");
    private static final String[] UP_NAMES = names("post bloom up ");

    /** Offsets of this frame's blocks in {@link #blocks}. */
    private static final long[] AT = new long[MAX_PASSES];

    private SteinPostFx() {}

    private static String[] names(String prefix) {
        String[] n = new String[SteinBloom.MAX_LEVELS];
        for (int i = 0; i < n.length; i++) {
            n[i] = prefix + i;
        }
        return n;
    }

    /** The Lens options (any thread; read on the render thread at the next frame). */
    public static void configure(int bloomLevel, boolean smoothEdges) {
        bloom = Math.max(BLOOM_OFF, Math.min(BLOOM_HIGH, bloomLevel));
        edges = smoothEdges;
    }

    /** Whether the options can act: Stein GFX draws (not the fixed-function fallback). */
    public static boolean available() {
        return SteinGfxOptions.ENABLED && !failed;
    }

    /**
     * In place of GlStateManager.clear in renderWorldPass: before the depth clear that precedes the
     * hand, the world's depth is copied for the edge smoothing.
     */
    public static void worldClear(int mask) {
        if (mask == GL11.GL_DEPTH_BUFFER_BIT && edges && !noDepth && available()) {
            try {
                SteinDevice dev = SteinDevice.get();
                SteinRenderTarget game = dev.gameTarget();
                sceneSize(dev, game.width(), game.height(), true);
                if (dev.copyTarget(game, world, true)) {
                    worldFrame = frame;
                }
            } catch (RuntimeException | LinkageError e) {
                failed = true;
                Log.warn("gfx", "post-processing turned off for this session: " + e);
            }
        }
        GlStateManager.clear(mask);
    }

    /** After EntityRenderer.renderWorld, inside updateCameraAndRender. */
    public static void afterWorld() {
        long thisFrame = frame++;
        int level = bloom;
        boolean smooth = edges && !noDepth;
        boolean hdr = SteinHdr.active();
        if (!available() || level == BLOOM_OFF && !smooth && !hdr) {
            return;
        }
        try {
            run(level, smooth, hdr, worldFrame == thisFrame);
        } catch (RuntimeException | LinkageError e) {
            failed = true;
            Log.warn("gfx", "post-processing turned off for this session: " + e);
        }
    }

    private static void run(int level, boolean smooth, boolean hdr, boolean worldCopied) {
        SteinDevice dev = SteinDevice.get();
        if (composite == null) {
            create(dev);
        }
        SteinRenderTarget game = dev.gameTarget();
        int w = game.width();
        int h = game.height();
        sceneSize(dev, w, h, smooth);
        if (!dev.copyTarget(game, scene, smooth) && smooth) {
            noDepth = true;
            smooth = false;
            Log.info(
                    "gfx", "post-processing: the game's depth cannot be copied, no edge smoothing");
        }
        if (smooth && !worldCopied) {
            // no hand this frame (no clear before it): the depth now is the world's
            dev.copyTarget(game, world, true);
        }
        if (level != BLOOM_OFF || hdr) {
            BLOOM.size(dev, w, h);
        }
        // with HDR the bloom is always built: it says where the lights are, even when not added
        int levels = level == BLOOM_OFF && !hdr ? 0 : BLOOM.levels();
        writeBlocks(dev, w, h, levels, level, smooth, hdr);
        if (levels > 0 || hdr) {
            draw(dev, "post luma", lumaTarget, luma, AT[0], sceneColor, null, null, null);
            lumaTarget.color(0).generateMips();
        }
        for (int i = 0; i < levels; i++) {
            SteinRenderTarget src = i == 0 ? scene : BLOOM.down(i - 1);
            draw(
                    dev,
                    DOWN_NAMES[i],
                    BLOOM.down(i),
                    i == 0 ? bloomFirst : bloomDown,
                    AT[1 + i],
                    src.color(0),
                    i == 0 ? lumaTarget.color(0) : null,
                    null,
                    null);
        }
        for (int i = levels - 2; i >= 0; i--) {
            SteinTexture below =
                    i == levels - 2 ? BLOOM.down(i + 1).color(0) : BLOOM.up(i + 1).color(0);
            draw(
                    dev,
                    UP_NAMES[i],
                    BLOOM.up(i),
                    bloomUp,
                    AT[1 + levels + i],
                    below,
                    BLOOM.down(i).color(0),
                    null,
                    null);
        }
        SteinTexture bloomed = levels == 0 ? sceneColor : BLOOM.result();
        draw(
                dev,
                "post composite",
                game,
                composite,
                AT[1 + 2 * levels],
                sceneColor,
                bloomed,
                smooth ? worldDepth : sceneColor,
                smooth ? sceneDepth : sceneColor,
                // (only read with HDR; never bound before it was first drawn)
                levels > 0 || hdr ? lumaTarget.color(0) : sceneColor);
    }

    /** One full-screen pass with up to four textures (see the five-texture one). */
    private static void draw(
            SteinDevice dev,
            String name,
            SteinRenderTarget target,
            SteinPipeline pipeline,
            long at,
            SteinTexture a,
            SteinTexture b,
            SteinTexture c,
            SteinTexture d) {
        draw(dev, name, target, pipeline, at, a, b, c, d, null);
    }

    /**
     * One full-screen pass: {@code a} in slot 0 (linear), {@code b} in slot 1 (linear; the mean
     * luma's mipmaps for the bloom's first level), {@code c} and {@code d} in slots 2 and 3 (per
     * pixel: the depths), {@code e} in slot 4 (the mean luma's mipmaps). The composite keeps the
     * target's alpha.
     */
    private static void draw(
            SteinDevice dev,
            String name,
            SteinRenderTarget target,
            SteinPipeline pipeline,
            long at,
            SteinTexture a,
            SteinTexture b,
            SteinTexture c,
            SteinTexture d,
            SteinTexture e) {
        SteinRenderPass pass =
                pipeline == composite
                        ? SteinRenderPass.keep(name, target)
                        : SteinRenderPass.discard(name, target);
        SteinCommands cmd = dev.beginPass(pass);
        try {
            cmd.bindPipeline(pipeline);
            cmd.bindUniforms(0, blocks, at, BLOCK_BYTES);
            cmd.bindTexture(0, a, linear);
            if (b != null) {
                cmd.bindTexture(1, b, pipeline == bloomFirst ? mipmapped : linear);
            }
            if (c != null) {
                cmd.bindTexture(2, c, nearest);
            }
            if (d != null) {
                cmd.bindTexture(3, d, nearest);
            }
            if (e != null) {
                cmd.bindTexture(4, e, mipmapped);
            }
            cmd.draw(3, 1, 0, 0);
        } finally {
            cmd.end();
        }
    }

    /**
     * The frame's Post blocks, all written before the first pass: mean luma, bloom down (one per
     * level), bloom up (one per level but the smallest), composite. Each is texel (1 / size read, 1
     * / size drawn), params, params2, params3 (see the shaders).
     */
    private static void writeBlocks(
            SteinDevice dev, int w, int h, int levels, int level, boolean smooth, boolean hdr) {
        int align = dev.caps().uniformAlignment;
        texel(w, h, LUMA_SIZE, LUMA_SIZE);
        AT[0] = block(align);
        int sw = w;
        int sh = h;
        for (int i = 0; i < levels; i++) {
            SteinTexture d = BLOOM.down(i).color(0);
            texel(sw, sh, d.width(), d.height());
            threshold(4);
            B[8] = BLOOM_KNEE;
            AT[1 + i] = block(align);
            sw = d.width();
            sh = d.height();
        }
        for (int i = 0; i < levels - 1; i++) {
            SteinTexture below = BLOOM.down(i + 1).color(0);
            SteinTexture here = BLOOM.down(i).color(0);
            texel(below.width(), below.height(), here.width(), here.height());
            AT[1 + levels + i] = block(align);
        }
        texel(w, h, w, h);
        B[4] =
                levels == 0 || level == BLOOM_OFF
                        ? 0f
                        : BLOOM_STRENGTH[level]
                                * (float) SteinGfxOptions.POST_BLOOM_STRENGTH
                                / levels;
        B[7] = smooth ? 1f : 0f;
        B[8] = SteinGfxOptions.POST_EDGE_VIEW ? 1f : 0f;
        B[9] = (float) SteinGfxOptions.POST_EDGE_RATIO;
        B[10] = (float) SteinGfxOptions.POST_EDGE_FLOOR;
        B[11] = hdr ? (float) SteinGfxOptions.HDR_BOOST : 0f;
        threshold(12);
        B[16] = hdr ? (float) SteinGfxOptions.HDR_DARKNESS : 1f;
        B[17] = HDR_GLOW_GAIN;
        B[18] = EDGE_DISTANCE;
        AT[1 + 2 * levels] = block(align);
        blocks.commitRing();
    }

    private static void texel(int readW, int readH, int drawW, int drawH) {
        B[0] = 1f / readW;
        B[1] = 1f / readH;
        B[2] = 1f / drawW;
        B[3] = 1f / drawH;
    }

    /**
     * The adaptive threshold at {@code B[at..at+3]}: the lowest, the factor on the mean luma, the
     * highest, and the mean's mipmap level.
     */
    private static void threshold(int at) {
        B[at] = BLOOM_MIN_THRESHOLD;
        B[at + 1] = BLOOM_MEAN_FACTOR;
        B[at + 2] = BLOOM_MAX_THRESHOLD;
        B[at + 3] = LUMA_MIPS - 1;
    }

    /** Writes {@link #B} as one block in the ring, clears it and returns the block's offset. */
    private static long block(int align) {
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

    /** The copy of the game's image at its size; the depth only while the edges need it. */
    private static void sceneSize(SteinDevice dev, int w, int h, boolean depth) {
        boolean resized = sceneColor == null || sceneColor.width() != w || sceneColor.height() != h;
        if (!resized && depth == (sceneDepth != null)) {
            return;
        }
        if (scene != null) {
            scene.destroy();
        }
        if (resized && sceneColor != null) {
            sceneColor.destroy();
            sceneColor = null;
        }
        if (sceneDepth != null && (resized || !depth)) {
            sceneDepth.destroy();
            sceneDepth = null;
            world.destroy();
            world = null;
            worldDepth.destroy();
            worldDepth = null;
        }
        if (sceneColor == null) {
            sceneColor =
                    dev.createTexture(
                            SteinTexture.Desc.target("post scene", SteinFormat.RGBA8, w, h));
        }
        if (depth && sceneDepth == null) {
            sceneDepth =
                    dev.createTexture(
                            SteinTexture.Desc.target(
                                    "post scene depth", SteinFormat.DEPTH24, w, h));
            worldDepth =
                    dev.createTexture(
                            SteinTexture.Desc.target(
                                    "post world depth", SteinFormat.DEPTH24, w, h));
            world = dev.createTarget(new SteinTexture[0], worldDepth);
        }
        scene = dev.createTarget(new SteinTexture[] {sceneColor}, sceneDepth);
    }

    private static void create(SteinDevice dev) {
        SteinShader vertex = SteinShader.load(SteinShader.Stage.VERTEX, "post.vert");
        bloomFirst =
                pipeline(
                        dev,
                        "post bloom first",
                        vertex,
                        "post_bloom_first.frag",
                        false,
                        "src",
                        "average");
        bloomDown = pipeline(dev, "post bloom down", vertex, "post_bloom_down.frag", false, "src");
        bloomUp =
                pipeline(dev, "post bloom up", vertex, "post_bloom_up.frag", false, "src", "level");
        composite =
                pipeline(
                        dev,
                        "post composite",
                        vertex,
                        "post_composite.frag",
                        true,
                        "scene",
                        "bloom",
                        "depth",
                        "handDepth",
                        "average");
        luma = pipeline(dev, "post luma", vertex, "post_luma.frag", false, "src");
        SteinTexture lumaTexture =
                dev.createTexture(
                        new SteinTexture.Desc(
                                "post luma",
                                SteinTexture.Kind.TEXTURE_2D,
                                SteinFormat.RGBA16F,
                                LUMA_SIZE,
                                LUMA_SIZE,
                                1,
                                LUMA_MIPS,
                                true));
        lumaTarget = dev.createTarget(new SteinTexture[] {lumaTexture}, null);
        linear = dev.createSampler(SteinSampler.Desc.linearClamp());
        mipmapped =
                dev.createSampler(
                        new SteinSampler.Desc(
                                SteinSampler.Filter.LINEAR,
                                SteinSampler.Filter.LINEAR,
                                SteinSampler.Mip.NEAREST,
                                SteinSampler.Wrap.CLAMP,
                                SteinSampler.Wrap.CLAMP,
                                1f,
                                false));
        nearest = dev.createSampler(SteinSampler.Desc.nearestClamp());
        blocks =
                dev.createBuffer(
                                new SteinBuffer.Desc(
                                        "post blocks",
                                        16384L * RING_REGIONS,
                                        SteinBuffer.Memory.UPLOAD,
                                        SteinBuffer.Usage.UNIFORM))
                        .ring(RING_REGIONS);
    }

    /** A full-screen pipeline whose samplers take slots 0, 1, 2... in the order given. */
    private static SteinPipeline pipeline(
            SteinDevice dev,
            String name,
            SteinShader vertex,
            String fragment,
            boolean keepAlpha,
            String... samplers) {
        SteinPipeline.Builder b =
                SteinPipeline.builder(name)
                        .vertex(vertex)
                        .fragment(SteinShader.load(SteinShader.Stage.FRAGMENT, fragment))
                        .depth(null, false)
                        .cull(SteinPipeline.Cull.NONE)
                        .alphaWrite(!keepAlpha)
                        .uniforms("Post", 0);
        for (int i = 0; i < samplers.length; i++) {
            b.texture(samplers[i], i);
        }
        SteinPipeline p = dev.createPipeline(b.build());
        if (!p.valid()) {
            throw new IllegalStateException("pipeline " + name + " did not link");
        }
        return p;
    }
}
