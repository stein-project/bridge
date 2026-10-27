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
     * How fast the mean luma follows the scene, in seconds (the time to go about two thirds of the
     * way): slow enough that turning the camera does not make a bright surface pulse.
     */
    private static final double ADAPT_SECONDS = 0.6;

    /** A pause longer than this (a screen in front, a loading) starts the mean over at once. */
    private static final long ADAPT_RESET_NANOS = 1_000_000_000L;

    /**
     * How much bloom luma around a pixel makes it a full light with HDR output: a lone bright texel
     * spreads to almost nothing, a glowing block to well past this.
     */
    private static final float HDR_GLOW_GAIN = 4f;

    /** The sharpening levels and their strength. */
    public static final int SHARPEN_MAX = 3;

    private static final float[] SHARPEN_STRENGTH = {0f, 0.35f, 0.5f, 0.65f};

    /** The edge smoothing fades out between half of this and this, in blocks. */
    private static final float EDGE_DISTANCE = 32f;

    /** Bytes of one pass's Post block (nine vec4). */
    private static final int BLOCK_BYTES = 176;

    /** One block being written (reused: no allocation per frame). */
    private static final float[] B = new float[BLOCK_BYTES / 4];

    /**
     * Passes per frame at most: the mean luma and its adaptation, the bloom's way down and up, the
     * rays and the composite.
     */
    private static final int MAX_PASSES = 2 * SteinBloom.MAX_LEVELS + 4;

    private static final int RING_REGIONS = 4;

    private static volatile int bloom;
    private static volatile boolean edges;

    /** Sharpening, 0 (off) to {@link #SHARPEN_MAX}. */
    private static volatile int sharpen;

    private static boolean failed;

    /** The depth of the game's target could not be copied: no edge smoothing (logged once). */
    private static boolean noDepth;

    private static SteinPipeline bloomFirst;
    private static SteinPipeline bloomDown;
    private static SteinPipeline bloomUp;
    private static SteinPipeline composite;

    /** The composite for HDR's quick quality: also writes the lights layer (SteinHdr). */
    private static SteinPipeline compositeLayered;

    /** The game's colour and the lights layer as one target, and what it was made of. */
    private static SteinRenderTarget layeredTarget;

    private static SteinTexture layeredGame;
    private static SteinTexture layeredLights;
    private static SteinPipeline luma;
    private static SteinRenderTarget lumaTarget;
    private static SteinPipeline adapt;

    /** The atmosphere's light shafts (SteinAtmosphere), at half the size. */
    private static SteinPipeline rays;

    private static SteinRenderTarget raysTarget;

    /**
     * The adapted mean luma, 1x1, in two targets that take turns (read the last, write the new).
     */
    private static final SteinRenderTarget[] ADAPTED = new SteinRenderTarget[2];

    private static int adaptedNow;
    private static boolean adaptedFresh;
    private static long adaptedAt;
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
    public static void configure(int bloomLevel, boolean smoothEdges, int sharpening) {
        bloom = Math.max(BLOOM_OFF, Math.min(BLOOM_HIGH, bloomLevel));
        edges = smoothEdges;
        sharpen = Math.max(0, Math.min(SHARPEN_MAX, sharpening));
    }

    /** Whether the options can act: Stein GFX draws (not the fixed-function fallback). */
    public static boolean available() {
        return SteinGfxOptions.ENABLED && !failed;
    }

    /**
     * The Stein light is on (sun's shadows or glowing blocks): the world is drawn in 16 bits, its
     * lights past white, and the composite brings it back with an exposure that follows the scene
     * like an eye and a soft shoulder — the sun blinds, a torch in the dark burns.
     */
    public static boolean wideScene() {
        return available() && (SteinShadows.level() > 0 || SteinGlow.level() > 0);
    }

    /**
     * In place of GlStateManager.clear in renderWorldPass: before the depth clear that precedes the
     * hand, the world's depth is copied for the edge smoothing.
     */
    public static void worldClear(int mask) {
        if (mask == GL11.GL_DEPTH_BUFFER_BIT
                && (edges || SteinAtmosphere.wanted())
                && !noDepth
                && available()) {
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
        SteinDynamicLights.worldEnd(); // the world is drawn: the HUD and screens have none
        SteinShadows.worldEnd();
        long thisFrame = frame++;
        int level = bloom;
        boolean smooth = edges && !noDepth;
        boolean hdr = SteinHdr.active();
        boolean atmosphere = SteinAtmosphere.active() && !noDepth;
        boolean wide = wideScene();
        if (!available()
                || level == BLOOM_OFF && !smooth && !hdr && !wide && sharpen == 0 && !atmosphere) {
            SteinAtmosphere.worldEnd();
            return;
        }
        try {
            run(level, smooth, hdr, atmosphere, worldFrame == thisFrame);
        } catch (RuntimeException | LinkageError e) {
            failed = true;
            Log.warn("gfx", "post-processing turned off for this session: " + e);
        } finally {
            SteinAtmosphere.worldEnd();
        }
    }

    private static void run(
            int level, boolean smooth, boolean hdr, boolean atmosphere, boolean worldCopied) {
        SteinDevice dev = SteinDevice.get();
        if (composite == null) {
            create(dev);
        }
        SteinRenderTarget game = dev.gameTarget();
        int w = game.width();
        int h = game.height();
        // the depths: for the edges and for the atmosphere
        boolean depth = smooth || atmosphere;
        sceneSize(dev, w, h, depth);
        if (!dev.copyTarget(game, scene, depth) && depth) {
            noDepth = true;
            smooth = false;
            atmosphere = false;
            depth = false;
            Log.info(
                    "gfx",
                    "post-processing: the game's depth cannot be copied, no edge smoothing nor"
                            + " atmosphere");
        }
        if (depth && !worldCopied) {
            // no hand this frame (no clear before it): the depth now is the world's
            dev.copyTarget(game, world, true);
        }
        boolean wide = wideScene() && sceneColor.desc().format == SteinFormat.RGBA16F;
        if (level != BLOOM_OFF || hdr || wide) {
            BLOOM.size(dev, w, h);
        }
        // with HDR the bloom is always built: it says where the lights are, even when not added
        int levels = level == BLOOM_OFF && !hdr && !wide ? 0 : BLOOM.levels();
        boolean shafts = atmosphere && raysBlock(dev, w, h);
        writeBlocks(dev, w, h, levels, level, smooth, hdr, atmosphere, shafts, wide);
        SteinTexture mean = sceneColor;
        if (levels > 0 || hdr || wide) {
            draw(dev, "post luma", lumaTarget, luma, AT[0], sceneColor, null, null, null);
            lumaTarget.color(0).generateMips();
            // the first time the other target was never drawn: the luma image stands in (its value
            // is not used, the first adaptation takes the mean whole)
            SteinTexture before = adaptedFresh ? lumaTarget.color(0) : ADAPTED[adaptedNow].color(0);
            adaptedFresh = false;
            adaptedNow ^= 1;
            draw(
                    dev,
                    "post adapt",
                    ADAPTED[adaptedNow],
                    adapt,
                    AT[MAX_PASSES - 1],
                    lumaTarget.color(0),
                    before,
                    null,
                    null);
            mean = ADAPTED[adaptedNow].color(0);
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
                    i == 0 ? mean : null,
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
        if (shafts) {
            draw(
                    dev,
                    "post rays",
                    raysTarget,
                    rays,
                    AT[MAX_PASSES - 2],
                    worldDepth,
                    null,
                    null,
                    null);
        }
        SteinTexture bloomed = levels == 0 ? sceneColor : BLOOM.result();
        boolean layered = hdr && SteinHdr.layered();
        draw(
                dev,
                "post composite",
                layered ? layered(dev, game) : game,
                layered ? compositeLayered : composite,
                AT[1 + 2 * levels],
                sceneColor,
                bloomed,
                depth ? worldDepth : sceneColor,
                depth ? sceneDepth : sceneColor,
                // (only read with HDR; never bound before it was first drawn)
                mean,
                shafts ? raysTarget.color(0) : sceneColor);
        if (layered) {
            SteinHdr.lightsWritten();
        }
    }

    /** The game's colour and the lights layer together, remade when either changes. */
    private static SteinRenderTarget layered(SteinDevice dev, SteinRenderTarget game) {
        SteinTexture color = game.color(0);
        SteinTexture lights = SteinHdr.lights(dev, color.width(), color.height());
        if (layeredTarget == null || layeredGame != color || layeredLights != lights) {
            if (layeredTarget != null) {
                layeredTarget.destroy();
            }
            layeredTarget = dev.createTarget(new SteinTexture[] {color, lights}, null);
            layeredGame = color;
            layeredLights = lights;
        }
        return layeredTarget;
    }

    /** One full-screen pass with up to four textures (see the six-texture one). */
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
        draw(dev, name, target, pipeline, at, a, b, c, d, null, null);
    }

    /**
     * The rays' pass: its target at a quarter of the size and its block (written apart from the
     * others, before them); false when there are no rays to draw.
     */
    private static boolean raysBlock(SteinDevice dev, int w, int h) {
        int rw = Math.max(1, w / 2);
        int rh = Math.max(1, h / 2);
        if (!SteinAtmosphere.rays(B, w, h)) {
            java.util.Arrays.fill(B, 0f);
            return false;
        }
        if (raysTarget == null
                || raysTarget.color(0).width() != rw
                || raysTarget.color(0).height() != rh) {
            if (raysTarget != null) {
                raysTarget.color(0).destroy();
                raysTarget.destroy();
            }
            SteinTexture t =
                    dev.createTexture(
                            SteinTexture.Desc.target("post rays", SteinFormat.RGBA16F, rw, rh));
            raysTarget = dev.createTarget(new SteinTexture[] {t}, null);
        }
        texel(w, h, rw, rh);
        AT[MAX_PASSES - 2] = block(dev.caps().uniformAlignment);
        return true;
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
            SteinTexture e,
            SteinTexture f) {
        SteinRenderPass pass =
                pipeline == composite || pipeline == compositeLayered
                        ? SteinRenderPass.keep(name, target)
                        : SteinRenderPass.discard(name, target);
        SteinCommands cmd = dev.beginPass(pass);
        try {
            cmd.bindPipeline(pipeline);
            cmd.bindUniforms(0, blocks, at, BLOCK_BYTES);
            // the adaptation reads the luma image's last mipmap; the rays, the world's depth
            cmd.bindTexture(
                    0, a, pipeline == adapt ? mipmapped : pipeline == rays ? nearest : linear);
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
            if (f != null) {
                cmd.bindTexture(5, f, linear);
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
            SteinDevice dev,
            int w,
            int h,
            int levels,
            int level,
            boolean smooth,
            boolean hdr,
            boolean atmosphere,
            boolean shafts,
            boolean wide) {
        int align = dev.caps().uniformAlignment;
        texel(w, h, LUMA_SIZE, LUMA_SIZE);
        AT[0] = block(align);
        // the adaptation: the share of the way to the mean now, from the time since the last frame
        long now = System.nanoTime();
        long since = now - adaptedAt;
        adaptedAt = now;
        B[4] =
                since > ADAPT_RESET_NANOS
                        ? 1f
                        : (float) (1.0 - Math.exp(-since / 1e9 / ADAPT_SECONDS));
        B[5] = LUMA_MIPS - 1;
        AT[MAX_PASSES - 1] = block(align);
        int sw = w;
        int sh = h;
        for (int i = 0; i < levels; i++) {
            SteinTexture d = BLOOM.down(i).color(0);
            texel(sw, sh, d.width(), d.height());
            threshold(4, wide);
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
                levels == 0 || level == BLOOM_OFF && !wide
                        ? 0f
                        : BLOOM_STRENGTH[Math.max(level, BLOOM_LOW)]
                                * (float) SteinGfxOptions.POST_BLOOM_STRENGTH
                                / levels;
        B[5] = SHARPEN_STRENGTH[sharpen];
        B[7] = smooth ? 1f : 0f;
        B[8] = SteinGfxOptions.POST_EDGE_VIEW ? 1f : 0f;
        B[9] = (float) SteinGfxOptions.POST_EDGE_RATIO;
        B[10] = (float) SteinGfxOptions.POST_EDGE_FLOOR;
        B[11] = hdr ? (float) SteinGfxOptions.HDR_BOOST : 0f;
        threshold(12, wide);
        B[17] = HDR_GLOW_GAIN;
        B[18] = EDGE_DISTANCE;
        B[19] = wide ? 1f : 0f;
        if (atmosphere) {
            SteinAtmosphere.composite(B, shafts);
        }
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
     * highest, and the mean's mipmap level (0: the adapted mean is 1x1).
     */
    private static void threshold(int at, boolean wide) {
        // a scene past white: what goes past it glows (the sun, the lights), the sky and sand not
        B[at] = wide ? 0.9f : BLOOM_MIN_THRESHOLD;
        B[at + 1] = wide ? 1.2f : BLOOM_MEAN_FACTOR;
        B[at + 2] = wide ? 1.4f : BLOOM_MAX_THRESHOLD;
        B[at + 3] = 0f;
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
        // the copy in the game image's own format (16 bits with HDR or the Stein light)
        SteinTexture gameColor = dev.gameTarget().color(0);
        SteinFormat sceneFormat = gameColor != null ? gameColor.desc().format : SteinFormat.RGBA8;
        boolean resized =
                sceneColor == null
                        || sceneColor.width() != w
                        || sceneColor.height() != h
                        || sceneColor.desc().format != sceneFormat;
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
                    dev.createTexture(SteinTexture.Desc.target("post scene", sceneFormat, w, h));
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
                        "average",
                        "rays");
        // the layered one writes alpha: the layer's, and the game's own alpha given back unchanged
        compositeLayered =
                pipeline(
                        dev,
                        "post composite hdr",
                        vertex,
                        "post_composite_hdr.frag",
                        false,
                        "scene",
                        "bloom",
                        "depth",
                        "handDepth",
                        "average",
                        "rays");
        luma = pipeline(dev, "post luma", vertex, "post_luma.frag", false, "src");
        rays = pipeline(dev, "post rays", vertex, "post_rays.frag", false, "depth");
        adapt = pipeline(dev, "post adapt", vertex, "post_adapt.frag", false, "src", "previous");
        for (int i = 0; i < ADAPTED.length; i++) {
            SteinTexture t =
                    dev.createTexture(
                            SteinTexture.Desc.target("post adapted", SteinFormat.RGBA16F, 1, 1));
            ADAPTED[i] = dev.createTarget(new SteinTexture[] {t}, null);
        }
        adaptedAt = 0L; // the first frame takes the mean as it is
        adaptedFresh = true;
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
