/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import dev.xavier.stein.bridge.core.Log;
import dev.xavier.stein.bridge.engine.GLMatrix;
import dev.xavier.stein.gfx.gl.SteinCoreGl;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.RenderManager;

import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;

/**
 * The Lens motion blur, on the world's image after the other image options and before the HUD (the
 * HUD, the screens and the chat never blur). Two looks:
 *
 * <ul>
 *   <li><b>Motion</b> ({@link #MOTION}): each pixel blurs along the way the camera moved it on the
 *       screen. The world's depth (before the hand) gives each pixel's place in the world, which is
 *       projected with the previous frame's camera: turning blurs everything alike, walking blurs
 *       what is near more than what is far, and a still camera leaves the image exactly as it is
 *       (the pass does not even run). The previous camera is projected with this frame's field of
 *       view, so a FOV change (sprinting, a bow, the zoom) does not smear the screen.
 *   <li><b>Trail</b> ({@link #TRAIL}): the classic look of other clients, each frame mixed with the
 *       ones before it, so what moves leaves a short trail.
 * </ul>
 *
 * <p>Both are the same at any frame rate: the motion blur is a fixed shutter time (the movement per
 * second times {@link #SHUTTER_SECONDS_PER_STEP} per strength step), and the trail's share of the
 * past is scaled by the frame time ({@code k60 ^ (dt * 60)}). The hand stays sharp: the world's
 * depth and the hand's tell them apart exactly. A jump of the camera (a teleport, F5, a respawn, a
 * new world, a pause) starts over instead of smearing the frame before it.
 *
 * <p>Off, nothing is allocated and nothing runs. Any failure turns it off for the session with one
 * line in the log.
 *
 * <p>Pipeline: Stein GFX only (OpenGL and Vulkan), through {@link SteinPostFx}.
 */
public final class SteinMotionBlur {

    public static final int OFF = 0;
    public static final int MOTION = 1;
    public static final int TRAIL = 2;

    public static final int STRENGTH_MIN = 1;
    public static final int STRENGTH_MAX = 10;
    public static final int STRENGTH_DEFAULT = 4;

    /**
     * The shutter per strength step: 4 is 24 ms, about 1/40 s (a film camera's look; 8 ms read as
     * almost nothing while turning in play).
     */
    private static final double SHUTTER_SECONDS_PER_STEP = 0.006;

    /** The longest blur per strength step, as a share of the screen's width (4: 8%, 10: 20%). */
    private static final float MAX_LENGTH_PER_STEP = 0.02f;

    /**
     * Samples at most; the shader takes one per 3 pixels of blur (4 at least), and the per-pixel
     * jitter makes that read as smooth.
     */
    private static final int SAMPLES_MAX = 12;

    /** The trail's share of the past at 60 fps, per strength step (4: 0.28, 10: 0.7). */
    private static final double TRAIL_PER_STEP = 0.07;

    /** A frame later than this (a stall, a pause, a loading) starts over. */
    private static final long GAP_NANOS = 250_000_000L;

    /** The camera moving more than this in one frame is a teleport: start over. */
    private static final double TELEPORT_BLOCKS = 4.0;

    /** The view turning more than this in one frame (cosine of 60 degrees) is a cut: start over. */
    private static final float CUT_COS = 0.5f;

    /** Below this the camera did not move (matrix elements, blocks). */
    private static final float STILL = 1e-6f;

    /** Bytes of the Blur block: texel, params, params2, then two matrices. */
    private static final int BLOCK_BYTES = 3 * 16 + 2 * 64;

    private static final int RING_REGIONS = 4;

    private static volatile int mode = OFF;
    private static volatile int strength = STRENGTH_DEFAULT;

    private static boolean failed;

    private static SteinPipeline motion;
    private static SteinPipeline trail;
    private static SteinSampler linear;
    private static SteinSampler nearest;
    private static SteinBuffer blocks;

    /** The image the pass reads: the game's, copied (or swapped) just before. */
    private static SteinTexture source;

    /** The hand's depth copied with it (OpenGL: the game's depth is not ours to read). */
    private static SteinTexture sourceDepth;

    private static SteinRenderTarget sourceTarget;

    /** The trail's past, in two images that take turns (read the last, write the new). */
    private static final SteinTexture[] HISTORY = new SteinTexture[2];

    private static int historyNow;
    private static boolean historyFresh = true;

    /** The game's colour with the trail's next past, and what it was made of. */
    private static SteinRenderTarget trailTarget;

    private static SteinTexture trailGame;
    private static SteinTexture trailHistory;

    // ---- the camera, captured before the hand (captureCamera) ----

    private static final float[] PROJECTION = new float[16];
    private static final float[] VIEW = new float[16];
    private static final float[] PREVIOUS_VIEW = new float[16];
    private static final float[] VIEW_PROJECTION = new float[16];
    private static final float[] INVERSE = new float[16];
    private static final float[] PREVIOUS = new float[16];
    private static FloatBuffer read;

    private static double cameraX;
    private static double cameraY;
    private static double cameraZ;
    private static double previousX;
    private static double previousY;
    private static double previousZ;

    /** The camera was captured this frame, and a previous frame exists to compare with. */
    private static boolean captured;

    private static boolean hasPrevious;

    /** What makes the previous frame another scene: the perspective (F5) and the world. */
    private static int perspective = -1;

    private static Object world;

    private static long lastFrameAt;
    private static int lastWidth;
    private static int lastHeight;

    /** One block being written (reused: no allocation per frame). */
    private static final float[] B = new float[BLOCK_BYTES / 4];

    private SteinMotionBlur() {}

    /** The options (any thread; read on the render thread at the next frame). */
    public static void configure(int blurMode, int blurStrength) {
        mode = blurMode < OFF || blurMode > TRAIL ? OFF : blurMode;
        strength = Math.max(STRENGTH_MIN, Math.min(STRENGTH_MAX, blurStrength));
    }

    /** On, and able to run (Stein GFX draws). */
    static boolean active() {
        return mode != OFF && !failed;
    }

    /** The motion blur reads the world's depth: the post-processing copies it before the hand. */
    static boolean wantsDepth() {
        return mode == MOTION && !failed;
    }

    /**
     * Before the depth clear that precedes the hand (SteinPostFx.worldClear): the world's camera,
     * still in place. The view matrix holds the turn and the bobbing; the place is the viewer's,
     * which the world was drawn around.
     */
    static void captureCamera() {
        if (mode == OFF || failed) {
            return;
        }
        try {
            if (read == null) {
                read = MemoryUtil.memAllocFloat(16);
            }
            read.clear();
            SteinCoreGl.glGetFloatv(GL11.GL_PROJECTION_MATRIX, read);
            read.get(0, PROJECTION);
            System.arraycopy(GLMatrix.current(), 0, VIEW, 0, 16);
            RenderManager rm = Minecraft.getMinecraft().getRenderManager();
            cameraX = rm.viewerPosX;
            cameraY = rm.viewerPosY;
            cameraZ = rm.viewerPosZ;
            captured = true;
        } catch (RuntimeException | LinkageError e) {
            fail(e);
        }
    }

    /**
     * After the other image options, with the game's colour final: the blur over it.
     *
     * @param dev the device
     * @param game the game's target
     * @param out where the result goes: the game's colour (alone on Vulkan)
     * @param keep whether {@code out} keeps what it held (it has the game's depth on OpenGL)
     * @param worldDepth the world's depth before the hand, or null when not copied this frame
     * @param handApart the hand was drawn on its own depth this frame (the game's depth is the
     *     hand's alone)
     */
    static void apply(
            SteinDevice dev,
            SteinRenderTarget game,
            SteinRenderTarget out,
            boolean keep,
            SteinTexture worldDepth,
            boolean handApart) {
        if (!active()) {
            return;
        }
        try {
            run(dev, game, out, keep, worldDepth, handApart);
        } catch (RuntimeException | LinkageError e) {
            fail(e);
        }
    }

    private static void fail(Throwable e) {
        failed = true;
        Log.warn("gfx", "motion blur turned off for this session: " + e);
    }

    private static void run(
            SteinDevice dev,
            SteinRenderTarget game,
            SteinRenderTarget out,
            boolean keep,
            SteinTexture worldDepth,
            boolean handApart) {
        int w = game.width();
        int h = game.height();
        long now = System.nanoTime();
        float dt = (now - lastFrameAt) / 1e9f;
        boolean cut = startsOver(now, w, h);
        lastFrameAt = now;
        boolean wasCaptured = captured;
        captured = false;
        if (cut || !wasCaptured) {
            remember();
            historyFresh = true;
            hasPrevious = wasCaptured;
            return;
        }
        int m = mode;
        if (m == MOTION && (worldDepth == null || still() || !matrices())) {
            remember();
            return; // a still camera (or a degenerate one): the image as it is, no pass
        }
        if (motion == null) {
            create(dev);
        }
        // decided above whether to draw: from here on the game's image may be swapped away, and
        // the pass covers every pixel
        boolean direct = SteinGfxOptions.VULKAN && game.depth() != null;
        SteinTexture src = copyOf(dev, game, w, h, handApart && !direct);
        SteinTexture handDepth = !handApart ? null : direct ? game.depth() : sourceDepth;
        long at = writeBlock(dev, w, h, dt, m, handDepth != null);
        if (m == MOTION) {
            draw(dev, out, keep, motion, at, src, worldDepth, handDepth);
        } else {
            SteinTexture past = historyFresh ? src : history(dev, historyNow, w, h);
            int next = historyNow ^ 1;
            draw(
                    dev,
                    trailTarget(dev, game, history(dev, next, w, h)),
                    false,
                    trail,
                    at,
                    src,
                    past,
                    handDepth);
            historyNow = next;
            historyFresh = false;
        }
        remember();
    }

    /**
     * Another scene than the previous frame (start over): the first frame, a stall, a new size, a
     * new world, F5, a teleport or a cut in the view.
     */
    private static boolean startsOver(long now, int w, int h) {
        Minecraft mc = Minecraft.getMinecraft();
        int view = mc.gameSettings.thirdPersonView;
        boolean cut =
                !hasPrevious
                        || now - lastFrameAt > GAP_NANOS
                        || w != lastWidth
                        || h != lastHeight
                        || view != perspective
                        || mc.theWorld != world;
        lastWidth = w;
        lastHeight = h;
        perspective = view;
        world = mc.theWorld;
        if (cut || !captured) {
            return cut;
        }
        double dx = cameraX - previousX;
        double dy = cameraY - previousY;
        double dz = cameraZ - previousZ;
        if (dx * dx + dy * dy + dz * dz > TELEPORT_BLOCKS * TELEPORT_BLOCKS) {
            return true;
        }
        // the view's forward axis (third row of the rotation) now and before
        float dot =
                VIEW[2] * PREVIOUS_VIEW[2]
                        + VIEW[6] * PREVIOUS_VIEW[6]
                        + VIEW[10] * PREVIOUS_VIEW[10];
        return dot < CUT_COS;
    }

    /** The camera did not move nor turn since the previous frame. */
    private static boolean still() {
        if (Math.abs(cameraX - previousX) > STILL
                || Math.abs(cameraY - previousY) > STILL
                || Math.abs(cameraZ - previousZ) > STILL) {
            return false;
        }
        for (int i = 0; i < 16; i++) {
            if (Math.abs(VIEW[i] - PREVIOUS_VIEW[i]) > STILL) {
                return false;
            }
        }
        return true;
    }

    /** This frame's camera becomes the previous one. */
    private static void remember() {
        System.arraycopy(VIEW, 0, PREVIOUS_VIEW, 0, 16);
        previousX = cameraX;
        previousY = cameraY;
        previousZ = cameraZ;
        hasPrevious = true;
    }

    /**
     * The Blur block. texel: 1 / size. params: x samples at most, y the longest blur (share of the
     * width), z the shutter over the frame time, w the trail's share of the past. params2: x hand
     * apart (1) or no hand (0), yzw the camera's move since the previous frame, in blocks. Then
     * this frame's inverse view-projection, and the previous view with this frame's projection.
     */
    private static long writeBlock(
            SteinDevice dev, int w, int h, float dt, int m, boolean handApart) {
        int s = strength;
        B[0] = 1f / w;
        B[1] = 1f / h;
        B[2] = 1f / w;
        B[3] = 1f / h;
        B[4] = SAMPLES_MAX;
        B[5] = MAX_LENGTH_PER_STEP * s;
        B[6] = (float) (SHUTTER_SECONDS_PER_STEP * s / Math.max(dt, 1e-4f));
        // the first frame of a trail has no past yet
        B[7] =
                m == TRAIL && !historyFresh
                        ? (float) Math.pow(TRAIL_PER_STEP * s, Math.min(dt, 0.25f) * 60.0)
                        : 0f;
        B[8] = handApart ? 1f : 0f;
        // the point is world-relative to the camera: the previous camera sees it moved by this
        B[9] = (float) (cameraX - previousX);
        B[10] = (float) (cameraY - previousY);
        B[11] = (float) (cameraZ - previousZ);
        if (m == MOTION) {
            System.arraycopy(INVERSE, 0, B, 12, 16);
            System.arraycopy(PREVIOUS, 0, B, 28, 16);
        }
        long at = blocks.reserve(BLOCK_BYTES, dev.caps().uniformAlignment);
        if (at == -2) {
            blocks.nextRegion();
            at = blocks.reserve(BLOCK_BYTES, dev.caps().uniformAlignment);
        }
        long p = blocks.address(at);
        for (int i = 0; i < B.length; i++) {
            MemoryUtil.memPutFloat(p + 4L * i, B[i]);
            B[i] = 0f;
        }
        blocks.commitRing();
        return at;
    }

    /**
     * This frame's inverse view-projection and the previous view with this frame's projection (so a
     * change of the field of view alone does not blur).
     *
     * @return false for a camera that cannot be inverted
     */
    private static boolean matrices() {
        multiply(PROJECTION, VIEW, VIEW_PROJECTION);
        if (!SteinGlTrack.invert(VIEW_PROJECTION, INVERSE)) {
            return false;
        }
        multiply(PROJECTION, PREVIOUS_VIEW, PREVIOUS);
        return true;
    }

    /** {@code out = a * b}, column-major (OpenGL's order). */
    private static void multiply(float[] a, float[] b, float[] out) {
        for (int c = 0; c < 4; c++) {
            for (int r = 0; r < 4; r++) {
                out[c * 4 + r] =
                        a[r] * b[c * 4]
                                + a[4 + r] * b[c * 4 + 1]
                                + a[8 + r] * b[c * 4 + 2]
                                + a[12 + r] * b[c * 4 + 3];
            }
        }
    }

    private static void draw(
            SteinDevice dev,
            SteinRenderTarget target,
            boolean keep,
            SteinPipeline pipeline,
            long at,
            SteinTexture scene,
            SteinTexture second,
            SteinTexture hand) {
        String name = pipeline == motion ? "post motion blur" : "post trail";
        SteinRenderPass pass =
                keep ? SteinRenderPass.keep(name, target) : SteinRenderPass.discard(name, target);
        SteinCommands cmd = dev.beginPass(pass);
        try {
            cmd.bindPipeline(pipeline);
            cmd.bindUniforms(0, blocks, at, BLOCK_BYTES);
            cmd.bindTexture(0, scene, linear);
            // a missing texture is never read (the block says so): the scene stands in
            cmd.bindTexture(
                    1, second != null ? second : scene, pipeline == trail ? linear : nearest);
            cmd.bindTexture(2, hand != null ? hand : scene, nearest);
            cmd.draw(3, 1, 0, 0);
        } finally {
            cmd.end();
        }
    }

    /**
     * The game's image copied, with its depth when the hand's is needed (swapped on Vulkan, where
     * the game's depth is read in place: the pass covers every pixel).
     */
    private static SteinTexture copyOf(
            SteinDevice dev, SteinRenderTarget game, int w, int h, boolean depth) {
        SteinTexture color = game.color(0);
        // OpenGL's game target does not describe its colour, and holds 8 bits
        SteinFormat format = color != null ? color.desc().format : SteinFormat.RGBA8;
        if (source == null
                || source.width() != w
                || source.height() != h
                || source.desc().format != format
                || depth != (sourceDepth != null)) {
            if (sourceTarget != null) {
                sourceTarget.destroy();
                source.destroy();
                if (sourceDepth != null) {
                    sourceDepth.destroy();
                    sourceDepth = null;
                }
            }
            source =
                    dev.createTexture(SteinTexture.Desc.target("motion blur source", format, w, h));
            if (depth) {
                sourceDepth =
                        dev.createTexture(
                                SteinTexture.Desc.target(
                                        "motion blur hand depth", SteinFormat.DEPTH24, w, h));
            }
            sourceTarget = dev.createTarget(new SteinTexture[] {source}, sourceDepth);
        }
        if (depth || color == null || !dev.swapImages(color, source)) {
            dev.copyTarget(game, sourceTarget, depth);
        }
        return source;
    }

    /** One of the trail's two pasts, at the screen's size (16 bits: no bands in the dark). */
    private static SteinTexture history(SteinDevice dev, int i, int w, int h) {
        SteinTexture t = HISTORY[i];
        if (t == null || t.width() != w || t.height() != h) {
            if (t != null) {
                t.destroy();
            }
            t = dev.createTexture(SteinTexture.Desc.target("trail", SteinFormat.RGBA16F, w, h));
            HISTORY[i] = t;
            historyFresh = true;
        }
        return t;
    }

    /** The game's colour and the next past as one target, remade when either changes. */
    private static SteinRenderTarget trailTarget(
            SteinDevice dev, SteinRenderTarget game, SteinTexture next) {
        SteinTexture color = game.color(0);
        if (trailTarget == null || trailGame != color || trailHistory != next) {
            if (trailTarget != null) {
                trailTarget.destroy();
            }
            trailTarget = dev.createTarget(new SteinTexture[] {color, next}, null);
            trailGame = color;
            trailHistory = next;
        }
        return trailTarget;
    }

    private static void create(SteinDevice dev) {
        SteinShader vertex = SteinShader.load(SteinShader.Stage.VERTEX, "post.vert");
        motion = pipeline(dev, "post motion blur", vertex, "post_motion.frag");
        trail = pipeline(dev, "post trail", vertex, "post_trail.frag");
        linear = dev.createSampler(SteinSampler.Desc.linearClamp());
        nearest = dev.createSampler(SteinSampler.Desc.nearestClamp());
        blocks =
                dev.createBuffer(
                                new SteinBuffer.Desc(
                                        "motion blur blocks",
                                        4096L * RING_REGIONS,
                                        SteinBuffer.Memory.UPLOAD,
                                        SteinBuffer.Usage.UNIFORM))
                        .ring(RING_REGIONS);
    }

    private static SteinPipeline pipeline(
            SteinDevice dev, String name, SteinShader vertex, String fragment) {
        SteinPipeline p =
                dev.createPipeline(
                        SteinPipeline.builder(name)
                                .vertex(vertex)
                                .fragment(SteinShader.load(SteinShader.Stage.FRAGMENT, fragment))
                                .depth(null, false)
                                .cull(SteinPipeline.Cull.NONE)
                                .uniforms("Blur", 0)
                                .texture("scene", 0)
                                .texture("second", 1)
                                .texture("hand", 2)
                                .build());
        if (!p.valid()) {
            throw new IllegalStateException("pipeline " + name + " did not link");
        }
        return p;
    }
}
