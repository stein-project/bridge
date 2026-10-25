/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import dev.xavier.stein.bridge.core.Log;

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

    /** The Encode block: scale (paper white, peak, test mode) and the test's rectangle. */
    private static final int ENCODE_BYTES = 32;

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
     * The Lens options (any thread; take effect at the next frame): on/off, and the calibration in
     * nits. The {@code -D} keys of {@link SteinGfxOptions}, when given, win over the calibration.
     */
    public static void configure(boolean on, int paperWhite, int peak) {
        requested = on;
        whiteNits = paperWhite;
        peakNits = peak;
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

    /** The swapchain, after asking the window for its formats. */
    public static void surfaceSupports(boolean scRgb) {
        if (scRgb != supported) {
            supported = scRgb;
            Log.info("gfx", "HDR output " + (scRgb ? "offered" : "not offered") + " by the window");
        }
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
        boolean want = requested && available();
        if (want != active) {
            active = want;
            recreateFramebuffer = true;
            Log.info("gfx", "HDR output " + (want ? "on" : "off"));
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
            MemoryUtil.memPutFloat(p + 12, 0f);
            for (int i = 0; i < 4; i++) {
                MemoryUtil.memPutFloat(p + 16 + 4L * i, testRect[i]);
            }
            block.commitRing();
            SteinCommands c = dev.beginPass(SteinRenderPass.discard("hdr encode", target));
            try {
                c.bindPipeline(encode);
                c.bindUniforms(0, block, at, ENCODE_BYTES);
                c.bindTexture(0, screen, nearest);
                c.draw(3, 1, 0, 0);
            } finally {
                c.end();
            }
            return targetColor;
        } catch (RuntimeException | LinkageError e) {
            failed = true;
            Log.warn("gfx", "HDR output turned off for this session: " + e);
            return screen;
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
