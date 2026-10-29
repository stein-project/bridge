/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import dev.xavier.stein.bridge.core.Log;
import dev.xavier.stein.bridge.engine.DirectRender;

import net.minecraft.client.Minecraft;

import org.lwjgl.system.MemoryUtil;

/**
 * HDR output (the Lens "HDR" option): on Vulkan, with a window that offers scRGB (the colour space
 * Windows composes HDR in), the game's image is kept at 16 bits per channel and reaches the screen
 * brighter than white where the world has light: the post-processing ({@link SteinPostFx}) raises
 * the light sources above the paper white, and the bloom adds instead of blending.
 *
 * <p>Everything up to the screen stays in the game's own terms: 1 is the white of the HUD and the
 * menus (the paper white, in nits, from the Lens calibration) and more than 1 is a light. Only at
 * the end ({@link #encode}) the image becomes scRGB — linear, 1 = 80 nits — for the swapchain.
 *
 * <p>What changes with it: the game's framebuffer colour and the window's screen are RGBA16F (the
 * game recreates its framebuffer when the option changes), and the swapchain is R16G16B16A16_SFLOAT
 * in EXTENDED_SRGB_LINEAR. Without HDR on in Windows the surface may still offer scRGB; the system
 * then shows it as SDR, clipped at white.
 *
 * <p>Pipeline: Stein GFX on Vulkan only.
 */
public final class SteinHdr {

    private static volatile boolean requested;

    /**
     * The Encode block: scale (paper white, peak, test mode, shadows), the test's rectangle, look.
     */
    private static final int ENCODE_BYTES = 48;

    /**
     * The peak test of the HDR calibration: inside {@link #testRect} the screen shows the chosen
     * peak, and on it a symbol far brighter than any screen. With the peak right, the symbol just
     * disappears into the patch.
     */
    private static boolean peakTest;

    /** The test's rectangle, in the screen's pixels, rows from the bottom (x0, y0, x1, y1). */
    private static final float[] testRect = new float[4];

    /**
     * The Lens calibration: the paper white and the screen's peak, in nits. Plain numbers: the Lens
     * configures before the window exists, when SteinGfxOptions must not load yet.
     */
    private static volatile int whiteNits = 200;

    private static volatile int peakNits = 1000;

    /** The shadows, 0 to 100 (50: as drawn); see {@link #configure}. */
    private static volatile int black = 50;

    /** Stein's look (soft, vivid, moody colours) instead of the image as drawn. */
    private static volatile boolean look = true;

    /** The calibration is being made: the look stays off, each step judges the plain image. */
    private static volatile boolean calibrating;

    /**
     * Quick quality (the option; the default): the game's images stay 8 bits and only the lights
     * above white go through a 16-bit layer ({@link #lights}). Off (maximum quality): the whole
     * frame in 16 bits.
     */
    private static volatile boolean quick = true;

    /** The quality the framebuffers were made for (changes between frames). */
    private static boolean quickNow = true;

    /** The quick quality's lights layer, and the frame it was last written in. */
    private static SteinTexture lightsLayer;

    private static long lightsFrame = -1;

    /** Frames presented (the lights layer counts only in the frame that wrote it). */
    private static long frame;

    private static boolean shot;

    /** The window offers scRGB (the swapchain says so at each recreation). */
    private static volatile boolean supported;

    /** What the frames are being drawn for now (changes only between frames). */
    private static boolean active;

    private static boolean recreateFramebuffer;

    private static SteinPipeline encode;
    private static SteinRenderTarget target;
    private static SteinTexture targetColor;
    private static SteinSampler nearest;
    private static SteinBuffer block;
    private static boolean failed;

    private SteinHdr() {}

    /**
     * The Lens options (any thread; take effect at the next frame): on/off, and the calibration —
     * the paper white and the peak in nits, and the shadows from 0 to 100. The shadows bend only
     * the darkest part of the image (below 18% of the paper white, a mid grey) by a power, from 1.6
     * (deeper) through 1 (50, as drawn) to 0.7 (lifted): black stays black, the mid tones and the
     * white do not move, so a screen that swallows the darkest greys can show them again without
     * the picture's contrast changing. The {@code -D} keys of {@link SteinGfxOptions}, when given,
     * win over the calibration.
     */
    public static void configure(
            boolean on,
            int paperWhite,
            int peak,
            int shadows,
            boolean steinLook,
            boolean quickQuality) {
        requested = on;
        whiteNits = paperWhite;
        peakNits = peak;
        black = shadows;
        look = steinLook;
        quick = quickQuality;
    }

    /** Whether the game's framebuffers are 16 bits now: HDR at maximum quality. */
    public static boolean wide() {
        return active && !quickNow;
    }

    /** The scene past white (the Stein light, SteinPostFx.wideScene) as of the last frame. */
    private static boolean sceneWide;

    /**
     * Whether the game's framebuffer colour is 16 bits: HDR at maximum quality, or the Stein light
     * (its lights go past white until the post-processing's exposure).
     */
    public static boolean framebufferWide() {
        return wide() || sceneWide;
    }

    /**
     * Whether the post-processing writes the lights layer this frame: HDR at quick quality (the
     * composite then draws the game's image clipped at white and the full light into the layer).
     */
    public static boolean layered() {
        return active && quickNow;
    }

    /** The lights layer at the game's size (16 bits), made or remade when the size changes. */
    public static SteinTexture lights(SteinDevice dev, int w, int h) {
        if (lightsLayer == null || lightsLayer.width() != w || lightsLayer.height() != h) {
            if (lightsLayer != null) {
                lightsLayer.destroy();
            }
            lightsLayer =
                    dev.createTexture(
                            SteinTexture.Desc.target("hdr lights", SteinFormat.RGBA16F, w, h));
        }
        return lightsLayer;
    }

    /** The composite wrote the lights layer: it counts for this frame's encode. */
    public static void lightsWritten() {
        lightsFrame = frame;
    }

    /**
     * The shadows' power for the slider (0 to 100): 1 at 50, up to 1.6 at 0 (deeper, for screens
     * whose black goes all the way down) and down to 0.7 at 100 (lifted).
     */
    private static float shadowPower(int v) {
        return v <= 50 ? 1f + 0.6f * (50 - v) / 50f : 1f - 0.3f * (v - 50) / 50f;
    }

    /**
     * While the calibration's adjusting steps are shown: Stein's look off, so the white, the black
     * and the brightness are judged on the plain image and every screen ends up the same.
     */
    public static void calibrating(boolean on) {
        calibrating = on;
    }

    /**
     * The calibration's peak test over a rectangle of the screen (in pixels, rows from the top, as
     * the GUI places it), or off. Render thread; only while HDR is on.
     */
    public static void peakTest(boolean on, int x, int y, int w, int h, int screenHeight) {
        peakTest = on;
        testRect[0] = x;
        testRect[1] = screenHeight - (y + h);
        testRect[2] = x + w;
        testRect[3] = screenHeight - y;
    }

    /** Whether the option can act: Vulkan draws and the window offers scRGB. */
    public static boolean available() {
        return SteinGfxOptions.VULKAN && supported && !failed;
    }

    /**
     * The swapchain, after asking the window for its formats. When HDR is wanted and offered at the
     * very first swapchain (the game opening), it is on from that first one: no frame goes out in
     * SDR first, which the system would take for an SDR game (and offer its own HDR for it).
     */
    public static void surfaceSupports(boolean scRgb) {
        if (scRgb != supported) {
            supported = scRgb;
            Log.info("gfx", "HDR output " + (scRgb ? "offered" : "not offered") + " by the window");
        }
        if (!firstSwapchainSeen) {
            firstSwapchainSeen = true;
            // (not available(): this runs while the window is being made, before SteinGfxOptions
            // may load; a window that offers scRGB is a Vulkan one)
            if (scRgb && requested && !failed && !active) {
                active = true;
                recreateFramebuffer = true;
                Log.info("gfx", "HDR output on from the first frame");
            }
        }
    }

    /** {@link #surfaceSupports} has seen the first swapchain. */
    private static boolean firstSwapchainSeen;

    /** The calibration's peak in nits (the system is told about it). */
    public static int peakNits() {
        return SteinGfxOptions.HDR_PEAK_SET ? (int) SteinGfxOptions.HDR_PEAK : peakNits;
    }

    /** The calibration's paper white in nits. */
    public static int whiteNits() {
        return SteinGfxOptions.HDR_PAPER_WHITE_SET
                ? (int) SteinGfxOptions.HDR_PAPER_WHITE
                : Math.max(80, whiteNits);
    }

    /** Whether this frame is drawn for HDR (16-bit image, scRGB swapchain). Render thread. */
    public static boolean active() {
        return active;
    }

    /**
     * Between frames (after the present): follows the option. On a change the game's framebuffer is
     * recreated, so its colour comes back in the new format, and the next present rebuilds the
     * swapchain.
     */
    public static void betweenFrames() {
        frame++;
        boolean want = requested && available();
        if (want != active) {
            active = want;
            recreateFramebuffer = true;
            Log.info("gfx", "HDR output " + (want ? "on" : "off"));
        }
        if (quick != quickNow) {
            quickNow = quick;
            recreateFramebuffer |= active;
            Log.info("gfx", "HDR quality " + (quickNow ? "quick" : "maximum"));
        }
        boolean scene = SteinPostFx.wideScene();
        if (scene != sceneWide) {
            sceneWide = scene;
            recreateFramebuffer = true;
            // on OpenGL the window holds 8 bits: the game's framebuffer comes back for the scene
            if (!SteinGfxOptions.VULKAN) {
                DirectRender.suspend(scene);
            }
        }
        if (recreateFramebuffer) {
            recreateFramebuffer = false;
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.getFramebuffer() != null) {
                mc.getFramebuffer().createBindFramebuffer(mc.displayWidth, mc.displayHeight);
            }
        }
    }

    /**
     * The screen (RGBA16F, 1 = paper white, gamma-encoded like the game's colours) as scRGB for the
     * swapchain: linear, 1 = 80 nits, bending softly towards the screen's peak instead of clipping
     * at it. Returns {@code screen} itself when the pass cannot run (HDR then turns off).
     */
    public static SteinTexture encode(SteinTexture screen) {
        try {
            SteinDevice dev = SteinDevice.get();
            if (encode == null) {
                create(dev);
            }
            if (targetColor == null
                    || targetColor.width() != screen.width()
                    || targetColor.height() != screen.height()) {
                if (target != null) {
                    target.destroy();
                    targetColor.destroy();
                }
                targetColor =
                        dev.createTexture(
                                SteinTexture.Desc.target(
                                        "hdr present",
                                        SteinFormat.RGBA16F,
                                        screen.width(),
                                        screen.height()));
                target = dev.createTarget(new SteinTexture[] {targetColor}, null);
            }
            long at = block.reserve(ENCODE_BYTES, dev.caps().uniformAlignment);
            if (at == -2) {
                block.nextRegion();
                at = block.reserve(ENCODE_BYTES, dev.caps().uniformAlignment);
            }
            long p = block.address(at);
            float white =
                    SteinGfxOptions.HDR_PAPER_WHITE_SET
                            ? (float) SteinGfxOptions.HDR_PAPER_WHITE
                            : Math.max(80, whiteNits);
            float top = SteinGfxOptions.HDR_PEAK_SET ? (float) SteinGfxOptions.HDR_PEAK : peakNits;
            // the curve towards the peak needs room above the white
            top = Math.max(top, white * 1.5f);
            MemoryUtil.memPutFloat(p, white / 80f);
            MemoryUtil.memPutFloat(p + 4, top / 80f);
            MemoryUtil.memPutFloat(p + 8, peakTest ? 1f : 0f);
            MemoryUtil.memPutFloat(p + 12, shadowPower(black));
            for (int i = 0; i < 4; i++) {
                MemoryUtil.memPutFloat(p + 16 + 4L * i, testRect[i]);
            }
            boolean layer =
                    quickNow
                            && lightsFrame == frame
                            && lightsLayer != null
                            && lightsLayer.width() == screen.width()
                            && lightsLayer.height() == screen.height();
            MemoryUtil.memPutFloat(p + 32, look && !calibrating ? 1f : 0f);
            MemoryUtil.memPutFloat(p + 36, layer ? 1f : 0f);
            MemoryUtil.memPutFloat(p + 40, quickNow ? 1f : 0f);
            MemoryUtil.memPutFloat(p + 44, SteinGfxOptions.HDR_LAYER_VIEW ? 1f : 0f);
            block.commitRing();
            SteinCommands c = dev.beginPass(SteinRenderPass.discard("hdr encode", target));
            try {
                c.bindPipeline(encode);
                c.bindUniforms(0, block, at, ENCODE_BYTES);
                c.bindTexture(0, screen, nearest);
                // (unused without the layer: the screen stands in)
                c.bindTexture(1, layer ? lightsLayer : screen, nearest);
                c.draw(3, 1, 0, 0);
            } finally {
                c.end();
            }
            if (SteinGfxOptions.HDR_SHOT_AT >= 0 && !shot) {
                shotIfDue(dev);
            }
            return targetColor;
        } catch (RuntimeException | LinkageError e) {
            failed = true;
            Log.warn("gfx", "HDR output turned off for this session: " + e);
            return screen;
        }
    }

    private static final long STARTED = System.nanoTime();

    /**
     * {@link SteinGfxOptions#HDR_SHOT_AT}: the encode's output, read back clipped to 8 bits, to
     * screenshots/hdr-encode.png — what goes to the screen, which the game's own capture never
     * sees.
     */
    private static void shotIfDue(SteinDevice dev) {
        if (System.nanoTime() - STARTED < SteinGfxOptions.HDR_SHOT_AT * 1_000_000_000L) {
            return;
        }
        shot = true;
        int w = targetColor.width();
        int h = targetColor.height();
        java.nio.ByteBuffer px = MemoryUtil.memAlloc(w * h * 4);
        try {
            dev.readPixels(targetColor, 0, 0, w, h, px);
            java.awt.image.BufferedImage img =
                    new java.awt.image.BufferedImage(
                            w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int i = ((h - 1 - y) * w + x) * 4;
                    img.setRGB(
                            x,
                            y,
                            (px.get(i) & 255) << 16
                                    | (px.get(i + 1) & 255) << 8
                                    | (px.get(i + 2) & 255));
                }
            }
            java.io.File dir = new java.io.File(Minecraft.getMinecraft().mcDataDir, "screenshots");
            dir.mkdirs();
            javax.imageio.ImageIO.write(img, "png", new java.io.File(dir, "hdr-encode.png"));
            Log.info("gfx", "HDR encode saved to screenshots/hdr-encode.png");
        } catch (java.io.IOException | RuntimeException e) {
            Log.warn("gfx", "HDR encode capture failed: " + e);
        } finally {
            MemoryUtil.memFree(px);
        }
    }

    private static void create(SteinDevice dev) {
        encode =
                dev.createPipeline(
                        SteinPipeline.builder("hdr encode")
                                .vertex(SteinShader.load(SteinShader.Stage.VERTEX, "post.vert"))
                                .fragment(
                                        SteinShader.load(
                                                SteinShader.Stage.FRAGMENT, "hdr_encode.frag"))
                                .depth(null, false)
                                .cull(SteinPipeline.Cull.NONE)
                                .uniforms("Encode", 0)
                                .texture("screen", 0)
                                .texture("lights", 1)
                                .build());
        if (!encode.valid()) {
            throw new IllegalStateException("pipeline hdr encode did not link");
        }
        nearest = dev.createSampler(SteinSampler.Desc.nearestClamp());
        block =
                dev.createBuffer(
                                new SteinBuffer.Desc(
                                        "hdr encode",
                                        4096L * 4,
                                        SteinBuffer.Memory.UPLOAD,
                                        SteinBuffer.Usage.UNIFORM))
                        .ring(4);
    }
}
