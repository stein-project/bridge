/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import org.lwjgl.system.MemoryUtil;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * The loading screen's drawing ({@code engine.LoadingScreen}) through the API: one full-target pass
 * of {@code loading.frag} over the game's draw target — the window while a load holds the game, the
 * game's framebuffer during the fade at the end. Works the same on OpenGL and Vulkan, where the
 * game's own immediate drawing would not reach the screen before the renderer is ready.
 *
 * <p>Pipeline: Stein GFX only.
 */
public final class SteinLoadingScreen {

    private static final int BLOCK_BYTES = 48;
    private static final int RING_REGIONS = 4;

    private static SteinPipeline pipeline;
    private static SteinBuffer blocks;
    private static SteinSampler sampler;

    /** The logo's textures, one per height drawn (a window size or two per session). */
    private static final Map<Integer, SteinTexture> LOGOS = new HashMap<>();

    private SteinLoadingScreen() {}

    /**
     * Draws the screen over the game's current draw target, {@code w} by {@code h} pixels: the logo
     * (scaled already to its size) at {@code lx, ly} from the top left, the bar under it, {@code
     * bg}, {@code logoAlpha} and {@code barAlpha} the layers' alphas.
     */
    public static void draw(
            int w,
            int h,
            BufferedImage logo,
            int lx,
            int ly,
            int barX,
            int barY,
            int barW,
            int barH,
            float bg,
            float progress,
            float logoAlpha,
            float barAlpha) {
        SteinDevice dev = SteinDevice.get();
        if (pipeline == null) {
            create(dev);
        }
        SteinTexture texture = texture(dev, logo);
        long at = blocks.reserve(BLOCK_BYTES, dev.caps().uniformAlignment);
        if (at == -2) {
            blocks.nextRegion();
            at = blocks.reserve(BLOCK_BYTES, dev.caps().uniformAlignment);
        }
        long p = blocks.address(at);
        int lw = logo.getWidth();
        int lh = logo.getHeight();
        // rows from the bottom, as the shaders read the target
        MemoryUtil.memPutFloat(p, lx);
        MemoryUtil.memPutFloat(p + 4, h - ly - lh);
        MemoryUtil.memPutFloat(p + 8, lw);
        MemoryUtil.memPutFloat(p + 12, lh);
        MemoryUtil.memPutFloat(p + 16, barX);
        MemoryUtil.memPutFloat(p + 20, h - barY - barH);
        MemoryUtil.memPutFloat(p + 24, barX + barW);
        MemoryUtil.memPutFloat(p + 28, h - barY);
        MemoryUtil.memPutFloat(p + 32, bg);
        MemoryUtil.memPutFloat(p + 36, progress);
        MemoryUtil.memPutFloat(p + 40, logoAlpha);
        MemoryUtil.memPutFloat(p + 44, barAlpha);
        blocks.commitRing();
        SteinCommands c =
                dev.beginPass(
                        SteinRenderPass.keep("loading screen", dev.gameTarget())
                                .viewport(0, 0, w, h));
        try {
            c.bindPipeline(pipeline);
            c.bindUniforms(0, blocks, at, BLOCK_BYTES);
            c.bindTexture(0, texture, sampler);
            c.draw(3, 1, 0, 0);
        } finally {
            c.end();
        }
    }

    /** The logo's texture at this image's size, made the first time. */
    private static SteinTexture texture(SteinDevice dev, BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        SteinTexture t = LOGOS.get(h);
        if (t != null) {
            return t;
        }
        t =
                dev.createTexture(
                        SteinTexture.Desc.texture2D("stein logo", SteinFormat.RGBA8, w, h, 1));
        ByteBuffer px = MemoryUtil.memAlloc(w * h * 4);
        try {
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int argb = img.getRGB(x, y);
                    px.put((byte) (argb >> 16)).put((byte) (argb >> 8)).put((byte) argb);
                    px.put((byte) (argb >>> 24));
                }
            }
            px.flip();
            t.upload(0, 0, 0, 0, w, h, px);
        } finally {
            MemoryUtil.memFree(px);
        }
        LOGOS.put(h, t);
        return t;
    }

    private static void create(SteinDevice dev) {
        pipeline =
                dev.createPipeline(
                        SteinPipeline.builder("loading screen")
                                .vertex(SteinShader.load(SteinShader.Stage.VERTEX, "post.vert"))
                                .fragment(
                                        SteinShader.load(
                                                SteinShader.Stage.FRAGMENT, "loading.frag"))
                                .depth(null, false)
                                .cull(SteinPipeline.Cull.NONE)
                                .blend(SteinPipeline.Blend.ALPHA)
                                .uniforms("Loading", 0)
                                .texture("logo", 0)
                                .build());
        if (!pipeline.valid()) {
            throw new IllegalStateException("the loading screen's pipeline did not link");
        }
        blocks =
                dev.createBuffer(
                                new SteinBuffer.Desc(
                                        "loading screen",
                                        4096L * RING_REGIONS,
                                        SteinBuffer.Memory.UPLOAD,
                                        SteinBuffer.Usage.UNIFORM))
                        .ring(RING_REGIONS);
        sampler = dev.createSampler(SteinSampler.Desc.linearClamp());
    }
}
