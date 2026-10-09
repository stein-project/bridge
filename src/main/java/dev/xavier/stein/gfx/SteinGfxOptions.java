/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import dev.xavier.stein.bridge.core.CoreOptions;
import dev.xavier.stein.bridge.core.Opt;
import dev.xavier.stein.bridge.core.Win;

import org.lwjgl.opengl.GL11;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The new renderer's keys, read only at startup (a launch argument, never an in-game option).
 *
 * <p>Stein GFX draws whenever the window got an OpenGL core context (4.6 or 4.1); otherwise, or
 * with {@code -Dsteinbridge.gfx=false}, the game draws with its own fixed-function pipeline and
 * nothing here acts. The fine-grained keys ({@code -Dsteinbridge.gfx.lazyState=false}, {@code
 * ...ownPose=false}...) turn one piece of Stein GFX off at a time, for testing.
 *
 * <p>Pipeline: both — chooses the pipeline; in the fixed pipeline every feature key reads false.
 */
public final class SteinGfxOptions {

    /**
     * Stein GFX draws: asked for ({@code -Dsteinbridge.gfx}, on by default) and the window has a
     * core context ({@link Win#coreContext}, set by Display before this class loads).
     */
    public static final boolean ENABLED = CoreOptions.GFX && Win.coreContext;

    /**
     * Core context (OpenGL 4.6, fallback 4.1): exactly when Stein GFX draws. In it the driver has
     * no fixed-function pipeline, so nothing draws through it, not even by mistake.
     */
    public static final boolean CORE = ENABLED;

    /**
     * {@code -Dsteinbridge.gfx.passStateCheck=true} (diagnostics): on every tracked pass, the
     * queries to the driver, compared with what was tracked at opening and with the game's state at
     * closing; counts and prints the divergences.
     */
    public static final boolean PASS_STATE_CHECK = Opt.flag("steinbridge.gfx.passStateCheck");

    /**
     * Repeated bindings kept away from the driver: pipelines with the same layout share the VAO,
     * and vertex, index, uniform and storage buffers already bound at the same point are not bound
     * again. {@code -Dsteinbridge.gfx.bindCache=false} turns it off.
     */
    public static final boolean BIND_CACHE = feature("bindCache", true);

    /**
     * {@code -Dsteinbridge.gfx.gpuPassTiming=true} (diagnostics): GPU time of each pass (OpenGL
     * timestamps), read a few frames later and printed every 5 s.
     */
    public static final boolean GPU_PASS_TIMING = Opt.flag("steinbridge.gfx.gpuPassTiming");

    /**
     * The recorded frame is sent to the driver at once ({@code gl.SteinGlFrame}): the OpenGL passes
     * become command lists, replayed at the end of the frame (Display.update) or earlier, at a
     * synchronisation point (the game uploads a texture, reads the screen, deletes a texture or
     * framebuffer...). Needs the core context with GL 4.5 and a persistent buffer; without them (or
     * with {@code -Dsteinbridge.gfx.recordFrame=false}) the passes go to the driver immediately, as
     * before.
     */
    public static final boolean RECORD_FRAME = feature("recordFrame", true);

    /**
     * {@code -Dsteinbridge.gfx.recordFrameCheck=true} (diagnostics): on one frame in 300, each
     * command is described at recording and again at replay, compared one by one, and the resources
     * it uses are checked in the driver at replay time (buffer, texture, sampler and framebuffer
     * still exist).
     */
    public static final boolean RECORD_FRAME_CHECK = Opt.flag("steinbridge.gfx.recordFrameCheck");

    /**
     * {@code -Dsteinbridge.gfx.frameSubmitStats=true} (diagnostics): submissions to the driver per
     * frame and the reason (and the caller) of each submission outside the end of the frame, every
     * 5 s.
     */
    public static final boolean FRAME_SUBMIT_STATS = Opt.flag("steinbridge.gfx.frameSubmitStats");

    /**
     * {@code -Dsteinbridge.gfx.passTiming=true}: (diagnostics) measures the CPU time to open and
     * close each pass and prints it periodically (default: off).
     */
    public static final boolean PASS_TIMING = Opt.flag("steinbridge.gfx.passTiming");

    /**
     * {@code -Dsteinbridge.gfx.chunkFade=...}: turns the chunk fade-in animation on or off
     * (default: on).
     */
    static final boolean CHUNK_FADE = feature("chunkFade", true);

    /**
     * {@code -Dsteinbridge.gfx.chunkFadeMs=...}: duration of the chunk fade-in in milliseconds
     * (default 700).
     */
    static final int CHUNK_FADE_MS = Opt.integer("steinbridge.gfx.chunkFadeMs", 700);

    /**
     * {@code -Dsteinbridge.gfx.deferredDynamic=false}: only model parts in the deferred entities,
     * no dynamic geometry (Tessellator, font, items); only the exact value {@code false} turns it
     * off (default: on).
     */
    static final boolean DEFERRED_DYNAMIC =
            ENABLED && Opt.unlessFalse("steinbridge.gfx.deferredDynamic");

    /**
     * {@code -Dsteinbridge.gfx.deferredStats=...}: (diagnostics) prints the deferred entity, label
     * and text cache numbers periodically (default: off).
     */
    static final boolean DEFERRED_STATS = Opt.flag("steinbridge.gfx.deferredStats");

    /**
     * {@code -Dsteinbridge.gfx.deferredTiles=...}: tile entities in the deferred pass (default:
     * on).
     */
    static final boolean DEFERRED_TILES = feature("deferredTiles", true);

    /**
     * {@code -Dsteinbridge.gfx.instancedModels=true}: (experimental) in the fixed-function
     * pipeline, draws the model parts per instance with SteinModelInstancer's own indirect draw (GL
     * 4.4; default: off). With Stein GFX the option changes nothing: the parts' geometry is always
     * on the GPU and the deferred entities draw it.
     */
    static final boolean INSTANCED_MODELS = Opt.flag("steinbridge.gfx.instancedModels");

    /**
     * {@code -Dsteinbridge.gfx.selfTest=true}: (diagnostics) runs the Stein GFX self-test at
     * startup; only with Stein GFX on, since its passes need the core context (default: off).
     */
    static final boolean SELF_TEST = feature("selfTest", false);

    /** {@code -Dsteinbridge.gfx.gpuHeldItem=...}: held items drawn on the GPU (default: on). */
    static final boolean GPU_HELD_ITEM = feature("gpuHeldItem", true);

    /**
     * {@code -Dsteinbridge.gfx.gpuHeldItemCheck=...}: (diagnostics) compares the GPU held item with
     * the game's (default: off).
     */
    static final boolean GPU_HELD_ITEM_CHECK = Opt.flag("steinbridge.gfx.gpuHeldItemCheck");

    /** {@code -Dsteinbridge.gfx.gpuLabel=...}: name labels on the GPU (default: on). */
    static final boolean GPU_LABEL = feature("gpuLabel", true);

    /**
     * {@code -Dsteinbridge.gfx.gpuLabelCheck=...}: (diagnostics) compares the GPU name labels with
     * the game's (default: off).
     */
    static final boolean GPU_LABEL_CHECK = Opt.flag("steinbridge.gfx.gpuLabelCheck");

    /** {@code -Dsteinbridge.gfx.ownTextCache=...}: Stein's own text cache (default: on). */
    static final boolean OWN_TEXT_CACHE = feature("ownTextCache", true);

    /**
     * {@code -Dsteinbridge.gfx.particles=false}: the particles drawn call by call, as the game
     * does: no fast particle path and no recorded particle pass (default: on).
     */
    static final boolean PARTICLES = feature("particles", true);

    /**
     * {@code -Dsteinbridge.gfx.particleCheck=...}: (diagnostics) checks the fast particle path
     * against the game's (default: off).
     */
    static final boolean PARTICLE_CHECK = Opt.flag("steinbridge.gfx.particleCheck");

    /**
     * {@code -Dsteinbridge.gfx.poseCheck=...}: (diagnostics) checks Stein's player pose against the
     * game's (default: off).
     */
    static final boolean POSE_CHECK = Opt.flag("steinbridge.gfx.poseCheck");

    /** {@code -Dsteinbridge.gfx.ownPose=...}: Stein's own player pose computation (default: on). */
    static final boolean OWN_POSE = feature("ownPose", true);

    /**
     * {@code -Dsteinbridge.gfx.poseStats=...}: (diagnostics) prints the player pose numbers
     * (default: off).
     */
    static final boolean POSE_STATS = Opt.flag("steinbridge.gfx.poseStats");

    /** {@code -Dsteinbridge.gfx.gpuPose=...}: player pose in the compute shader (default: on). */
    static final boolean GPU_POSE = feature("gpuPose", true);

    /**
     * {@code -Dsteinbridge.gfx.gpuPoseCheck=...}: (diagnostics) reads back the GPU pose and
     * compares it with the CPU's (default: off).
     */
    static final boolean GPU_POSE_CHECK = Opt.flag("steinbridge.gfx.gpuPoseCheck");

    /**
     * {@code -Dsteinbridge.gfx.poseThreads=...}: number of player pose threads (default: half the
     * processors minus one, between 1 and 4).
     */
    static final int POSE_THREADS =
            Opt.integer(
                    "steinbridge.gfx.poseThreads",
                    Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2 - 1)));

    /**
     * {@code -Dsteinbridge.gfx.segmentCheck=...}: (diagnostics) checks the segment assembly
     * (default: off).
     */
    static final boolean SEGMENT_CHECK = Opt.flag("steinbridge.gfx.segmentCheck");

    /**
     * {@code -Dsteinbridge.gfx.terrainStats=...}: (diagnostics) prints the terrain numbers
     * periodically (default: off).
     */
    static final boolean TERRAIN_STATS = Opt.flag("steinbridge.gfx.terrainStats");

    /**
     * {@code -Dsteinbridge.gfx.terrainOneBuffer=...}: each terrain layer in one buffer instead of
     * 128-block regions (default: on).
     */
    static final boolean TERRAIN_ONE_BUFFER = feature("terrainOneBuffer", true);

    /**
     * {@code -Dsteinbridge.gfx.terrainCache=...}: each terrain layer's draw kept between frames
     * (default: on).
     */
    static final boolean TERRAIN_CACHE = feature("terrainCache", true);

    /**
     * {@code -Dsteinbridge.gfx.terrainCacheCheck=...}: (diagnostics) checks the terrain cache
     * against a reassembly (default: off).
     */
    static final boolean TERRAIN_CACHE_CHECK = Opt.flag("steinbridge.gfx.terrainCacheCheck");

    /**
     * {@code -Dsteinbridge.gfx.terrainCacheCheckEvery=...}: uses of the terrain cache between
     * checks (default 30).
     */
    static final int TERRAIN_CACHE_CHECK_EVERY =
            Opt.integer("steinbridge.gfx.terrainCacheCheckEvery", 30);

    /** {@code -Dsteinbridge.gfx.fastQuads=...}: dedicated quad capture loop (default: on). */
    static final boolean FAST_QUADS = feature("fastQuads", true);

    /**
     * {@code -Dsteinbridge.gfx.captureCheck=...}: (diagnostics) checks the dedicated quad loop
     * against the generic one (default: off).
     */
    static final boolean CAPTURE_CHECK = Opt.flag("steinbridge.gfx.captureCheck");

    /**
     * {@code -Dsteinbridge.gfx.textCache=...}: the text cache inside the deferred pass (default:
     * on).
     */
    static final boolean TEXT_CACHE = feature("textCache", true);

    /**
     * {@code -Dsteinbridge.gfx.textCacheCheck=...}: (diagnostics) checks every repeated cached text
     * (default: off).
     */
    static final boolean TEXT_CACHE_CHECK = Opt.flag("steinbridge.gfx.textCacheCheck");

    /**
     * {@code -Dsteinbridge.gfx.gpuText=...}: cached text commands pointing at GPU glyphs (default:
     * on).
     */
    static final boolean GPU_TEXT = feature("gpuText", true);

    /** {@code -Dsteinbridge.gfx.gpuTextHud=...}: GPU text for the HUD too (default: on). */
    static final boolean GPU_TEXT_HUD = feature("gpuTextHud", true);

    /**
     * {@code -Dsteinbridge.gfx.retainTiles=...}: tile entities retained between frames (default:
     * on).
     */
    static final boolean RETAIN_TILES = feature("retainTiles", true);

    /**
     * {@code -Dsteinbridge.gfx.retainTilesCheck=...}: (diagnostics) checks the retained tiles
     * against the game's renderer (default: off).
     */
    static final boolean RETAIN_TILES_CHECK = Opt.flag("steinbridge.gfx.retainTilesCheck");

    /**
     * {@code -Dsteinbridge.gfx.retainTilesCheckEvery=...}: frames between retained tile checks
     * (default 30).
     */
    static final int RETAIN_TILES_CHECK_EVERY =
            Opt.integer("steinbridge.gfx.retainTilesCheckEvery", 30);

    /**
     * {@code -Dsteinbridge.gfx.retainTilesStats=...}: (diagnostics) prints only the retained tile
     * numbers (default: off).
     */
    static final boolean RETAIN_TILES_STATS = Opt.flag("steinbridge.gfx.retainTilesStats");

    /**
     * {@code -Dsteinbridge.gfx.retainTilesPredict=false}: the retained tiles read the state for
     * every block instead of predicting it between blocks of a sequence; only the exact value
     * {@code false} turns the prediction off (default: on).
     */
    static final boolean RETAIN_TILES_PREDICT =
            ENABLED && Opt.unlessFalse("steinbridge.gfx.retainTilesPredict");

    private static final Pattern GL_VERSION = Pattern.compile("^(\\d+\\.\\d+)");

    /** Cached F3 line; written and read on the render thread only. */
    private static String described;

    private SteinGfxOptions() {}

    /**
     * The F3 line: who draws (Stein GFX or the game's fixed-function pipeline) and the context's
     * OpenGL version (read from the driver, once). On the render thread.
     */
    public static String describe() {
        if (described == null) {
            // the version from the driver string ("4.6.0 Compatibility Profile ...", "4.6.0 Core
            // Profile ..."): it also works in the context the game opens without the renderer
            String version = GL11.glGetString(GL11.GL_VERSION);
            Matcher m = GL_VERSION.matcher(version == null ? "" : version);
            String gl = "OpenGL " + (m.find() ? m.group(1) : "?");
            described = (ENABLED ? "Stein GFX, " + gl + " core" : "Fixed-function pipeline, " + gl);
        }
        return described;
    }

    /**
     * The key {@code steinbridge.gfx.<name>}: off whenever Stein GFX does not draw; otherwise the
     * value given, if any, or {@code defaultValue}.
     */
    public static boolean feature(String name, boolean defaultValue) {
        return ENABLED && Opt.flag("steinbridge.gfx." + name, defaultValue);
    }
}
