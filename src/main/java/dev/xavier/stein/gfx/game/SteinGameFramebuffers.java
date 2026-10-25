/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx.game;

import dev.xavier.stein.bridge.core.Log;
import dev.xavier.stein.gfx.SteinBackend;
import dev.xavier.stein.gfx.SteinCommands;
import dev.xavier.stein.gfx.SteinDevice;
import dev.xavier.stein.gfx.SteinFormat;
import dev.xavier.stein.gfx.SteinRenderPass;
import dev.xavier.stein.gfx.SteinRenderTarget;
import dev.xavier.stein.gfx.SteinTexture;

import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Arrays;

/**
 * The game's framebuffers on the API: framebuffer objects (colour texture and depth renderbuffer,
 * as the game's Framebuffer builds them), renderbuffers, and the window's framebuffer 0 — the
 * screen, a colour and a depth texture of ours the size of the window, presented at the end of the
 * frame ({@link #present()}).
 *
 * <p>Each framebuffer object becomes an API render target when something draws into it, rebuilt
 * when an attachment changes or a texture attached to it is reallocated. What draws into the bound
 * one asks for it through {@link #drawTarget()} (the device's {@code gameTarget()}).
 *
 * <p>The game's glClear clears the bound target inside the scissor, with the clear colour and depth
 * of the moment ({@link #clear}); glReadPixels reads it.
 *
 * <p>Pipeline: Stein GFX only (the game's framebuffers in the Vulkan backend).
 */
public final class SteinGameFramebuffers {

    /** A framebuffer object: what is attached to it, and the target built from that. */
    private static final class Framebuffer {
        int color;
        int depthRenderbuffer;
        int depthTexture;
        SteinRenderTarget target;
        SteinTexture targetColor;
        SteinTexture targetDepth;
    }

    /** A renderbuffer: the game only uses them for the depth of its framebuffer. */
    private static final class Renderbuffer {
        SteinTexture texture;
    }

    /** By name (names are never reused, so the arrays only grow): no boxing on each call. */
    private static Framebuffer[] framebuffers = new Framebuffer[16];

    private static Renderbuffer[] renderbuffers = new Renderbuffer[16];
    private static int nextFramebuffer = 1;
    private static int nextRenderbuffer = 1;

    private static int drawBound;
    private static int readBound;
    private static int renderbufferBound;

    private static SteinTexture screenColor;
    private static SteinTexture screenDepth;
    private static SteinRenderTarget screen;

    private static final float[] CLEAR_COLOR = new float[4];
    private static float clearDepth = 1f;
    private static boolean depthMask = true;
    private static boolean colorMaskAll = true;
    private static boolean colorMaskNone;

    private SteinGameFramebuffers() {}

    // ---- framebuffer objects ----

    public static int genFramebuffer() {
        int name = nextFramebuffer++;
        if (name >= framebuffers.length) {
            framebuffers = Arrays.copyOf(framebuffers, name * 2);
        }
        framebuffers[name] = new Framebuffer();
        return name;
    }

    private static Framebuffer framebuffer(int name) {
        return name > 0 && name < framebuffers.length ? framebuffers[name] : null;
    }

    private static Renderbuffer renderbuffer(int name) {
        return name > 0 && name < renderbuffers.length ? renderbuffers[name] : null;
    }

    public static void bindFramebuffer(int target, int name) {
        if (target == GL30.GL_FRAMEBUFFER || target == GL30.GL_DRAW_FRAMEBUFFER) {
            drawBound = name;
        }
        if (target == GL30.GL_FRAMEBUFFER || target == GL30.GL_READ_FRAMEBUFFER) {
            readBound = name;
        }
    }

    /** The draw framebuffer bound now (0: the screen). */
    public static int drawBound() {
        return drawBound;
    }

    public static void deleteFramebuffer(int name) {
        if (framebuffer(name) != null) {
            framebuffers[name] = null;
        }
        if (drawBound == name) {
            drawBound = 0;
        }
        if (readBound == name) {
            readBound = 0;
        }
    }

    public static void framebufferTexture2D(
            int target, int attachment, int textarget, int texture, int level) {
        Framebuffer fb = framebuffer(bound(target));
        if (fb == null) {
            return;
        }
        if (attachment == GL30.GL_COLOR_ATTACHMENT0) {
            fb.color = texture;
        } else if (attachment == GL30.GL_DEPTH_ATTACHMENT) {
            fb.depthTexture = texture;
            fb.depthRenderbuffer = 0;
        }
        SteinGameTexture t = SteinGameTextures.get(texture);
        if (t != null) {
            t.attach();
        }
        fb.target = null;
    }

    public static void framebufferRenderbuffer(
            int target, int attachment, int renderbufferTarget, int renderbuffer) {
        Framebuffer fb = framebuffer(bound(target));
        if (fb == null) {
            return;
        }
        if (attachment == GL30.GL_DEPTH_ATTACHMENT
                || attachment == GL30.GL_DEPTH_STENCIL_ATTACHMENT) {
            fb.depthRenderbuffer = renderbuffer;
            fb.depthTexture = 0;
        }
        fb.target = null;
    }

    /** Complete when it has something attached that exists. */
    public static int checkFramebufferStatus(int target) {
        int name = bound(target);
        if (name == 0) {
            return GL30.GL_FRAMEBUFFER_COMPLETE;
        }
        Framebuffer fb = framebuffer(name);
        if (fb == null || fb.color == 0 && fb.depthRenderbuffer == 0 && fb.depthTexture == 0) {
            return GL30.GL_FRAMEBUFFER_INCOMPLETE_MISSING_ATTACHMENT;
        }
        return GL30.GL_FRAMEBUFFER_COMPLETE;
    }

    private static int bound(int target) {
        return target == GL30.GL_READ_FRAMEBUFFER ? readBound : drawBound;
    }

    // ---- renderbuffers ----

    public static int genRenderbuffer() {
        int name = nextRenderbuffer++;
        if (name >= renderbuffers.length) {
            renderbuffers = Arrays.copyOf(renderbuffers, name * 2);
        }
        renderbuffers[name] = new Renderbuffer();
        return name;
    }

    public static void bindRenderbuffer(int target, int name) {
        renderbufferBound = name;
    }

    /** glRenderbufferStorage: the game asks for depth (GL_DEPTH_COMPONENT24) at its size. */
    public static void renderbufferStorage(int target, int internalFormat, int w, int h) {
        Renderbuffer rb = renderbuffer(renderbufferBound);
        if (rb == null) {
            return;
        }
        if (rb.texture != null) {
            rb.texture.destroy();
        }
        SteinFormat f = isDepth(internalFormat) ? SteinFormat.DEPTH24 : SteinFormat.RGBA8;
        rb.texture =
                SteinDevice.get()
                        .createTexture(
                                SteinTexture.Desc.target(
                                        "game renderbuffer " + renderbufferBound, f, w, h));
        for (Framebuffer fb : framebuffers) {
            if (fb != null && fb.depthRenderbuffer == renderbufferBound) {
                fb.target = null;
            }
        }
    }

    public static void deleteRenderbuffer(int name) {
        Renderbuffer rb = renderbuffer(name);
        if (rb != null) {
            renderbuffers[name] = null;
        }
        if (rb != null && rb.texture != null) {
            rb.texture.destroy();
        }
        if (renderbufferBound == name) {
            renderbufferBound = 0;
        }
    }

    private static boolean isDepth(int internalFormat) {
        return internalFormat == GL11.GL_DEPTH_COMPONENT
                || internalFormat == GL14.GL_DEPTH_COMPONENT16
                || internalFormat == GL14.GL_DEPTH_COMPONENT24
                || internalFormat == GL14.GL_DEPTH_COMPONENT32
                || internalFormat == GL30.GL_DEPTH_COMPONENT32F
                || internalFormat == GL30.GL_DEPTH24_STENCIL8;
    }

    // ---- targets ----

    /** The target the game draws into now: its bound framebuffer, or the screen. */
    public static SteinRenderTarget drawTarget() {
        return target(drawBound);
    }

    /**
     * Whether {@code t} is one of the game's targets bound now (its screen or the framebuffer bound
     * to draw or read): passes on them draw in the game's viewport, as on OpenGL. Creates nothing.
     */
    public static boolean isGameTarget(SteinRenderTarget t) {
        if (t == null) {
            return false;
        }
        if (t == screen) {
            return true;
        }
        Framebuffer draw = framebuffer(drawBound);
        Framebuffer read = framebuffer(readBound);
        return draw != null && draw.target == t || read != null && read.target == t;
    }

    /** The colour of the framebuffer being read (glReadPixels, glCopyTexSubImage2D). */
    public static SteinTexture readColor() {
        SteinRenderTarget t = target(readBound);
        return t == null ? null : t.color(0);
    }

    private static SteinRenderTarget target(int name) {
        if (name == 0) {
            return screen();
        }
        Framebuffer fb = framebuffer(name);
        if (fb == null) {
            return screen();
        }
        SteinGameTexture ct = SteinGameTextures.get(fb.color);
        SteinTexture color = ct == null ? null : ct.backing();
        SteinTexture depth = null;
        if (fb.depthRenderbuffer != 0) {
            Renderbuffer rb = renderbuffer(fb.depthRenderbuffer);
            depth = rb == null ? null : rb.texture;
        } else if (fb.depthTexture != 0) {
            SteinGameTexture dt = SteinGameTextures.get(fb.depthTexture);
            depth = dt == null ? null : dt.backing();
        }
        if (fb.target == null || fb.targetColor != color || fb.targetDepth != depth) {
            fb.target =
                    SteinDevice.get()
                            .createTarget(
                                    color == null
                                            ? new SteinTexture[0]
                                            : new SteinTexture[] {color},
                                    depth);
            fb.targetColor = color;
            fb.targetDepth = depth;
        }
        return fb.target;
    }

    /** The window's framebuffer: ours, the size the game sees (Display.getWidth/getHeight). */
    private static SteinRenderTarget screen() {
        int w = Math.max(1, Display.getWidth());
        int h = Math.max(1, Display.getHeight());
        if (screen == null || screenColor.width() != w || screenColor.height() != h) {
            if (screenColor != null) {
                screenColor.destroy();
                screenDepth.destroy();
            }
            SteinDevice dev = SteinDevice.get();
            screenColor =
                    dev.createTexture(SteinTexture.Desc.target("screen", SteinFormat.RGBA8, w, h));
            screenDepth =
                    dev.createTexture(
                            SteinTexture.Desc.target("screen depth", SteinFormat.DEPTH24, w, h));
            screen = dev.createTarget(new SteinTexture[] {screenColor}, screenDepth);
            // a fresh screen is black, as a new window's
            SteinCommands c =
                    dev.beginPass(SteinRenderPass.clear("screen created", screen, 0f, 0f, 0f, 1f));
            c.end();
        }
        return screen;
    }

    /** End of the frame: the screen goes to the window. */
    public static void present() {
        SteinDevice.get().present(screen().color(0));
        SteinBackend.vulkanPresented();
    }

    // ---- clear and read ----

    public static void clearColor(float r, float g, float b, float a) {
        CLEAR_COLOR[0] = r;
        CLEAR_COLOR[1] = g;
        CLEAR_COLOR[2] = b;
        CLEAR_COLOR[3] = a;
    }

    public static void clearDepth(double d) {
        clearDepth = (float) d;
    }

    /**
     * glColorMask: as in OpenGL, a clear writes only the channels that are on. A clear with some
     * channels off (only the anaglyph mode, which the video options take away) leaves the colour.
     */
    public static void colorMask(boolean r, boolean g, boolean b, boolean a) {
        colorMaskAll = r && g && b && a;
        colorMaskNone = !r && !g && !b && !a;
        if (!colorMaskAll && !colorMaskNone) {
            warnPartialMask();
        }
    }

    private static boolean warnedMask;

    private static void warnPartialMask() {
        if (!warnedMask) {
            warnedMask = true;
            Log.warn("gfx", "vulkan: a clear with a partial colour mask leaves the colour alone");
        }
    }

    /** glDepthMask: with it off, glClear leaves the depth alone (as OpenGL does). */
    public static void depthMask(boolean on) {
        depthMask = on;
    }

    /** glClear on the bound target, inside the scissor when it is on. */
    public static void clear(int mask, int[] scissor) {
        boolean color = (mask & GL11.GL_COLOR_BUFFER_BIT) != 0 && colorMaskAll;
        boolean depth = (mask & GL11.GL_DEPTH_BUFFER_BIT) != 0 && depthMask;
        if (!color && !depth) {
            return;
        }
        SteinCommands c =
                SteinDevice.get().beginPass(SteinRenderPass.keep("game clear", drawTarget()));
        try {
            c.clear(
                    color,
                    CLEAR_COLOR[0],
                    CLEAR_COLOR[1],
                    CLEAR_COLOR[2],
                    CLEAR_COLOR[3],
                    depth,
                    clearDepth,
                    scissor);
        } finally {
            c.end();
        }
    }

    /**
     * glReadPixels of the framebuffer being read (the screenshot without framebuffers): RGBA or
     * BGRA bytes, rows from the bottom as OpenGL gives them. Waits for the GPU.
     */
    public static void readPixels(int x, int y, int w, int h, int format, Buffer out) {
        SteinTexture color = readColor();
        if (color == null) {
            return;
        }
        // inside the texture, as OpenGL leaves what falls outside it alone
        w = Math.min(w, color.width() - x);
        h = Math.min(h, color.height() - y);
        if (x < 0 || y < 0 || w <= 0 || h <= 0) {
            return;
        }
        ByteBuffer bytes = MemoryUtil.memAlloc(w * h * 4);
        try {
            SteinDevice.get().readPixels(color, x, y, w, h, bytes);
            boolean bgra = format == GL12.GL_BGRA;
            if (bgra != (color.desc().format == SteinFormat.BGRA8)) {
                SteinGameTextures.swizzle(bytes, 0, w * h);
            }
            long dst =
                    out instanceof IntBuffer ib
                            ? MemoryUtil.memAddress(ib)
                            : MemoryUtil.memAddress((ByteBuffer) out);
            long room = out instanceof IntBuffer ib ? ib.remaining() * 4L : out.remaining();
            MemoryUtil.memCopy(MemoryUtil.memAddress(bytes), dst, Math.min(room, w * h * 4L));
        } finally {
            MemoryUtil.memFree(bytes);
        }
    }
}
