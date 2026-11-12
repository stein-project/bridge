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
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.shader.Framebuffer;

import org.lwjgl.system.MemoryUtil;

/**
 * The world's resolution apart from the window's (the "Render resolution" option): from half of it,
 * lighter on a weak graphics card, to twice it, sharper and without jagged edges. Automatic (the
 * default) is the window's own, as the game draws.
 *
 * <p>Only the world changes: in place of EntityRenderer.renderWorld, the game draws into a
 * framebuffer of its own kind at the chosen size — the game's framebuffer and its idea of the
 * window's size are swapped for that one call and given back right after — and one pass brings the
 * picture to the window's size ({@code scale.frag}: a smooth enlargement, or a reduction that
 * averages every pixel under the target's). The HUD, the menus and the text come after, at the
 * window's resolution, sharp. The proportion is the window's, in a window or full screen.
 *
 * <p>In direct drawing (no intermediate framebuffer) the game's framebuffer is allowed for the
 * world alone ({@link DirectRender#whileScaled}). The edge smoothing, which reads the world's depth
 * at the window's size, is off while the scale is (bloom and sharpening stay).
 *
 * <p>Any failure turns it off for the session with one line in the log.
 *
 * <p>Pipeline: Stein GFX only (OpenGL and Vulkan).
 */
public final class SteinRenderScale {

    /** The scale, in percent of the window; 0 is automatic. */
    private static volatile int percent;

    /** The framebuffer the world is drawn in while the scale is on. */
    private static Framebuffer world;

    /** On OpenGL the game's target exposes no texture: its colour is copied here to be read. */
    private static SteinTexture copy;

    private static SteinRenderTarget copyTarget;
    private static SteinPipeline pipeline;
    private static SteinSampler linear;
    private static SteinBuffer block;
    private static boolean failed;

    /** Bytes of the Scale block: two vec4. */
    private static final int BLOCK_BYTES = 32;

    private SteinRenderScale() {}

    /** The option (any thread): 0 automatic, else 50 to 200 percent of the window. */
    public static void configure(int pct) {
        percent = pct == 0 ? 0 : Math.max(50, Math.min(200, pct));
    }

    /** Whether the option can act: Stein GFX draws (not the fixed-function fallback). */
    public static boolean available() {
        return SteinGfxOptions.ENABLED && !failed;
    }

    /** Whether the world is drawn at another size than the window's now. */
    public static boolean active() {
        int p = percent;
        return p != 0 && p != 100 && available() && OpenGlHelper.isFramebufferEnabled();
    }

    /** In place of EntityRenderer.renderWorld (through the showcase, which may swap the world). */
    public static void renderWorld(EntityRenderer er, float partialTicks, long finishNanos) {
        if (!active()) {
            er.renderWorld(partialTicks, finishNanos);
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        int fullW = mc.displayWidth;
        int fullH = mc.displayHeight;
        int p = percent;
        int w = Math.max(1, Math.round(fullW * p / 100f));
        int h = Math.max(1, Math.round(fullH * p / 100f));
        // the frame goes through the game's framebuffer, or straight to the window (direct)
        boolean viaFramebuffer = DirectRender.framebufferEnabled();
        Framebuffer main = mc.framebufferMc;
        try {
            if (world == null) {
                world = new Framebuffer(w, h, true);
            } else if (world.framebufferWidth != w || world.framebufferHeight != h) {
                world.createBindFramebuffer(w, h);
            }
        } catch (RuntimeException | LinkageError e) {
            failed = true;
            Log.warn("gfx", "render resolution turned off for this session: " + e);
            restore(main, viaFramebuffer, fullW, fullH);
            er.renderWorld(partialTicks, finishNanos);
            return;
        }
        SteinRenderTarget scaled;
        DirectRender.whileScaled(true);
        try {
            mc.framebufferMc = world;
            mc.displayWidth = w;
            mc.displayHeight = h;
            world.bindFramebuffer(true);
            er.renderWorld(partialTicks, finishNanos);
            scaled = SteinDevice.get().gameTarget();
        } finally {
            mc.framebufferMc = main;
            mc.displayWidth = fullW;
            mc.displayHeight = fullH;
            DirectRender.whileScaled(false);
        }
        restore(main, viaFramebuffer, fullW, fullH);
        try {
            bringBack(scaled, w, h, p);
        } catch (RuntimeException | LinkageError e) {
            failed = true;
            Log.warn("gfx", "render resolution turned off for this session: " + e);
        }
    }

    /** The target the frame was going to before: the game's framebuffer, or the window. */
    private static void restore(Framebuffer main, boolean viaFramebuffer, int fullW, int fullH) {
        if (viaFramebuffer) {
            main.bindFramebuffer(true);
        } else {
            OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, 0);
            GlStateManager.viewport(0, 0, fullW, fullH);
        }
    }

    /** The world's picture onto the target bound now (the game's framebuffer or the window). */
    private static void bringBack(SteinRenderTarget scaled, int w, int h, int p) {
        SteinDevice dev = SteinDevice.get();
        if (pipeline == null) {
            create(dev);
        }
        SteinTexture source = scaled.color(0);
        if (source == null) {
            if (copy == null || copy.width() != w || copy.height() != h) {
                if (copyTarget != null) {
                    copyTarget.destroy();
                    copy.destroy();
                }
                copy =
                        dev.createTexture(
                                SteinTexture.Desc.target("scale copy", SteinFormat.RGBA8, w, h));
                copyTarget = dev.createTarget(new SteinTexture[] {copy}, null);
            }
            dev.copyTarget(scaled, copyTarget, false);
            source = copy;
        }
        SteinRenderTarget target = dev.gameTarget();
        long at = block.reserve(BLOCK_BYTES, dev.caps().uniformAlignment);
        if (at == -2) {
            block.nextRegion();
            at = block.reserve(BLOCK_BYTES, dev.caps().uniformAlignment);
        }
        long a = block.address(at);
        MemoryUtil.memPutFloat(a, 1f / w);
        MemoryUtil.memPutFloat(a + 4, 1f / h);
        MemoryUtil.memPutFloat(a + 8, 1f / target.width());
        MemoryUtil.memPutFloat(a + 12, 1f / target.height());
        // larger than the window: enough taps that none of the source's pixels is skipped
        MemoryUtil.memPutFloat(a + 16, p > 100 ? (float) Math.ceil(p / 100.0) : 1f);
        block.commitRing();
        SteinCommands c = dev.beginPass(SteinRenderPass.discard("render scale", target));
        try {
            c.bindPipeline(pipeline);
            c.bindUniforms(0, block, at, BLOCK_BYTES);
            c.bindTexture(0, source, linear);
            c.draw(3, 1, 0, 0);
        } finally {
            c.end();
        }
    }

    private static void create(SteinDevice dev) {
        pipeline =
                dev.createPipeline(
                        SteinPipeline.builder("render scale")
                                .vertex(SteinShader.load(SteinShader.Stage.VERTEX, "post.vert"))
                                .fragment(
                                        SteinShader.load(SteinShader.Stage.FRAGMENT, "scale.frag"))
                                .depth(null, false)
                                .cull(SteinPipeline.Cull.NONE)
                                .uniforms("Scale", 0)
                                .texture("src", 0)
                                .build());
        if (!pipeline.valid()) {
            throw new IllegalStateException("pipeline render scale did not link");
        }
        linear = dev.createSampler(SteinSampler.Desc.linearClamp());
        block =
                dev.createBuffer(
                                new SteinBuffer.Desc(
                                        "render scale",
                                        4096L * 4,
                                        SteinBuffer.Memory.UPLOAD,
                                        SteinBuffer.Usage.UNIFORM))
                        .ring(4);
    }
}
