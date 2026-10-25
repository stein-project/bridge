/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx.game;

import dev.xavier.stein.bridge.core.Log;
import dev.xavier.stein.gfx.SteinDevice;
import dev.xavier.stein.gfx.SteinFormat;
import dev.xavier.stein.gfx.SteinTexture;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.Arrays;

/**
 * The game's OpenGL textures on the API: names, the texture bound on each unit, the uploads and
 * reads, the parameters and the unpack state, as OpenGL would keep them. Each name is a {@link
 * SteinGameTexture}.
 *
 * <p>Names are never reused (OpenGL may give a deleted name to the next texture): a renderer that
 * still holds the old one finds it deleted, not someone else's pixels.
 *
 * <p>Pixels arrive the way the game sends them: BGRA with {@code UNSIGNED_INT_8_8_8_8_REV} from
 * TextureUtil (the texture is then {@link SteinFormat#BGRA8}, uploaded with no conversion) or RGBA
 * bytes (framebuffers, the Loader). An upload in the other order is swizzled on the way.
 *
 * <p>Pipeline: Stein GFX only (the game's textures in the Vulkan backend).
 */
public final class SteinGameTextures {

    private static final int UNITS = 8;

    /** By name (names are never reused, so the array only grows): no boxing on each call. */
    private static SteinGameTexture[] textures = new SteinGameTexture[256];

    private static int nextName = 1;

    private static int activeUnit;
    private static final int[] BOUND = new int[UNITS];

    private static int unpackRowLength;
    private static int unpackSkipRows;
    private static int unpackSkipPixels;

    /** The size the last glTexImage2D on GL_PROXY_TEXTURE_2D would have had (0: refused). */
    private static int proxyWidth;

    private static int proxyHeight;
    private static boolean warnedFormat;
    private static boolean warnedOffset;

    private SteinGameTextures() {}

    // ---- names and bindings ----

    public static int gen() {
        int name = nextName++;
        put(name, new SteinGameTexture(name));
        return name;
    }

    private static void put(int name, SteinGameTexture t) {
        if (name >= textures.length) {
            textures = Arrays.copyOf(textures, Math.max(name + 1, textures.length * 2));
        }
        textures[name] = t;
    }

    /** The texture of this name, or null (never generated, or deleted). */
    public static SteinGameTexture get(int name) {
        return name > 0 && name < textures.length ? textures[name] : null;
    }

    /**
     * The texture of this name for the renderer (its {@code importTexture}): created empty when the
     * name is unknown, so binding it samples black, like an undefined OpenGL texture.
     */
    public static SteinGameTexture reference(int name) {
        SteinGameTexture t = get(name);
        if (t == null) {
            t = new SteinGameTexture(name);
            if (name > 0 && name < nextName) {
                return t; // deleted: a loose reference, not registered again
            }
            put(name, t);
        }
        return t;
    }

    public static void activeTexture(int unit) {
        activeUnit = Math.clamp(unit - GL13.GL_TEXTURE0, 0, UNITS - 1);
    }

    public static void bind(int target, int name) {
        if (target == GL11.GL_TEXTURE_2D) {
            BOUND[activeUnit] = name;
        }
    }

    /** The texture bound to GL_TEXTURE_2D on {@code unit} (0 to 7). */
    public static int bound(int unit) {
        return BOUND[unit];
    }

    public static int activeUnit() {
        return activeUnit;
    }

    private static SteinGameTexture current() {
        return get(BOUND[activeUnit]);
    }

    public static void delete(int name) {
        SteinGameTexture t = get(name);
        if (t != null) {
            textures[name] = null;
            t.delete();
        }
        for (int u = 0; u < UNITS; u++) {
            if (BOUND[u] == name) {
                BOUND[u] = 0;
            }
        }
    }

    // ---- pixels ----

    public static void pixelStore(int pname, int value) {
        switch (pname) {
            case GL12.GL_UNPACK_ROW_LENGTH -> unpackRowLength = value;
            case GL12.GL_UNPACK_SKIP_ROWS -> unpackSkipRows = value;
            case GL12.GL_UNPACK_SKIP_PIXELS -> unpackSkipPixels = value;
            default -> {
                // alignment: every format here has 4 bytes per pixel, rows are always aligned
            }
        }
    }

    /** glTexImage2D, with the pixels as bytes (null: only defines the level). */
    public static void texImage2D(
            int target,
            int level,
            int internalFormat,
            int w,
            int h,
            int format,
            int type,
            Buffer pixels) {
        if (target == GL11.GL_PROXY_TEXTURE_2D) {
            int max = SteinDevice.get().caps().maxTextureSize;
            boolean fits = w <= max && h <= max;
            proxyWidth = fits ? w : 0;
            proxyHeight = fits ? h : 0;
            return;
        }
        SteinGameTexture t = current();
        if (t == null || target != GL11.GL_TEXTURE_2D) {
            return;
        }
        SteinFormat f = textureFormat(internalFormat, format);
        ByteBuffer data = pixels == null ? null : bytes(pixels, w, h, format, f);
        t.define(level, f, w, h, data, unpackRowLength);
    }

    /** glTexSubImage2D. */
    public static void texSubImage2D(
            int target,
            int level,
            int x,
            int y,
            int w,
            int h,
            int format,
            int type,
            Buffer pixels) {
        SteinGameTexture t = current();
        if (t == null || target != GL11.GL_TEXTURE_2D || pixels == null) {
            return;
        }
        if (t.format() == null) {
            return; // level 0 undefined: OpenGL refuses it (INVALID_OPERATION)
        }
        ByteBuffer data = bytes(pixels, w, h, format, t.format());
        t.upload(level, x, y, w, h, data, unpackRowLength);
    }

    /** glTexParameteri / glTexParameterf on the bound texture. */
    public static void texParameter(int target, int pname, int value) {
        SteinGameTexture t = current();
        if (t != null && target == GL11.GL_TEXTURE_2D) {
            t.parameter(pname, value);
        }
    }

    /**
     * Sets the min and mag filter of texture {@code name} without binding it (the renderer's
     * recorded draws switch the atlas between item and world filtering).
     */
    public static void setFilter(int name, int min, int mag) {
        SteinGameTexture t = get(name);
        if (t != null) {
            t.parameter(GL11.GL_TEXTURE_MIN_FILTER, min);
            t.parameter(GL11.GL_TEXTURE_MAG_FILTER, mag);
        }
    }

    public static int getTexParameter(int target, int pname) {
        SteinGameTexture t = current();
        return t == null || target != GL11.GL_TEXTURE_2D ? 0 : t.parameter(pname);
    }

    /** glGetTexLevelParameteri (and the proxy of Minecraft.getGLMaximumTextureSize). */
    public static int getTexLevelParameter(int target, int level, int pname) {
        if (target == GL11.GL_PROXY_TEXTURE_2D) {
            return switch (pname) {
                case GL11.GL_TEXTURE_WIDTH -> proxyWidth;
                case GL11.GL_TEXTURE_HEIGHT -> proxyHeight;
                default -> 0;
            };
        }
        SteinGameTexture t = current();
        return t == null ? 0 : t.levelParameter(level, pname);
    }

    /**
     * glGetTexImage of level 0 (the screenshot of the game's framebuffer, the Loader): waits for
     * the GPU. Delivered in the order asked for (BGRA or RGBA).
     */
    public static void getTexImage(int target, int level, int format, int type, Buffer out) {
        SteinGameTexture t = current();
        SteinTexture b = t == null ? null : t.backing();
        if (b == null || level != 0 || b.desc().format.isDepth()) {
            return; // what OpenGL would refuse with an error, which the game does not read
        }
        int w = b.width();
        int h = b.height();
        ByteBuffer bytes = MemoryUtil.memAlloc(w * h * 4);
        try {
            SteinDevice.get().readPixels(b, 0, 0, w, h, bytes);
            if (order(format) != b.desc().format) {
                swizzle(bytes, 0, w * h);
            }
            ByteBuffer dst = asBytes(out);
            int n = Math.min(dst.remaining(), w * h * 4);
            MemoryUtil.memCopy(MemoryUtil.memAddress(bytes), MemoryUtil.memAddress(dst), n);
        } finally {
            MemoryUtil.memFree(bytes);
        }
    }

    /**
     * glCopyTexSubImage2D: copies a rectangle of the framebuffer being read (the menu panorama
     * reads the screen into its 256x256 texture). Starting at (0, 0) on both sides, as the game
     * does.
     */
    public static void copyTexSubImage2D(
            int target, int level, int xoff, int yoff, int x, int y, int w, int h) {
        SteinGameTexture t = current();
        SteinTexture dst = t == null ? null : t.backing();
        SteinTexture src = SteinGameFramebuffers.readColor();
        if (dst == null || src == null || level != 0) {
            return;
        }
        if ((xoff != 0 || yoff != 0 || x != 0 || y != 0) && !warnedOffset) {
            warnedOffset = true;
            Log.warn("gfx", "vulkan: glCopyTexSubImage2D with an offset, copied from (0, 0)");
        }
        int cw = Math.min(w, Math.min(src.width(), dst.width()));
        int ch = Math.min(h, Math.min(src.height(), dst.height()));
        SteinDevice.get().copyTexture(src, 0, dst, 0, cw, ch);
    }

    // ---- formats ----

    /** The API format for what the game asks: BGRA for TextureUtil, RGBA for the rest. */
    private static SteinFormat textureFormat(int internalFormat, int format) {
        if (internalFormat == GL11.GL_DEPTH_COMPONENT
                || internalFormat == GL14.GL_DEPTH_COMPONENT24
                || internalFormat == GL14.GL_DEPTH_COMPONENT16
                || internalFormat == GL30.GL_DEPTH_COMPONENT32F) {
            return SteinFormat.DEPTH24;
        }
        if (format == GL12.GL_BGRA) {
            return SteinFormat.BGRA8;
        }
        if (format != GL11.GL_RGBA && !warnedFormat) {
            warnedFormat = true;
            Log.warn(
                    "gfx",
                    "vulkan: texture with pixel format 0x"
                            + Integer.toHexString(format)
                            + " taken as RGBA");
        }
        return SteinFormat.RGBA8;
    }

    /** The byte order of pixels in {@code format}: BGRA8 or RGBA8. */
    private static SteinFormat order(int format) {
        return format == GL12.GL_BGRA ? SteinFormat.BGRA8 : SteinFormat.RGBA8;
    }

    /**
     * The pixels as bytes in the texture's order, from the unpack position (skip rows and pixels):
     * the same memory when the order matches, a swizzled copy when it does not.
     */
    private static ByteBuffer bytes(Buffer pixels, int w, int h, int format, SteinFormat into) {
        ByteBuffer b = asBytes(pixels);
        int rowLength = unpackRowLength > 0 ? unpackRowLength : w;
        int skip = (unpackSkipRows * rowLength + unpackSkipPixels) * 4;
        if (skip > 0) {
            b = b.duplicate().order(ByteOrder.nativeOrder());
            b.position(Math.min(b.limit(), b.position() + skip));
        }
        if (into.isDepth() || order(format) == into) {
            return b;
        }
        int n = Math.min(b.remaining() / 4, rowLength * (h - 1) + w);
        ByteBuffer copy = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder());
        MemoryUtil.memCopy(MemoryUtil.memAddress(b), MemoryUtil.memAddress(copy), (long) n * 4);
        swizzle(copy, 0, n);
        return copy;
    }

    /** Swaps the first and third byte of each pixel (BGRA and RGBA into each other). */
    static void swizzle(ByteBuffer b, int from, int pixels) {
        for (int i = from; i < from + pixels; i++) {
            int at = i * 4;
            byte r = b.get(at);
            b.put(at, b.get(at + 2));
            b.put(at + 2, r);
        }
    }

    /** A byte view of the game's buffer (IntBuffer from TextureUtil, ByteBuffer elsewhere). */
    private static ByteBuffer asBytes(Buffer pixels) {
        if (pixels instanceof ByteBuffer bb) {
            if (bb.isDirect()) {
                return bb;
            }
            ByteBuffer d = ByteBuffer.allocateDirect(bb.remaining()).order(ByteOrder.nativeOrder());
            d.put(bb.duplicate()).flip();
            return d;
        }
        if (pixels instanceof IntBuffer ib) {
            if (ib.isDirect()) {
                return MemoryUtil.memByteBuffer(MemoryUtil.memAddress(ib), ib.remaining() * 4);
            }
            ByteBuffer d =
                    ByteBuffer.allocateDirect(ib.remaining() * 4).order(ByteOrder.nativeOrder());
            d.asIntBuffer().put(ib.duplicate());
            return d;
        }
        throw new IllegalArgumentException("pixels in a " + pixels.getClass().getSimpleName());
    }
}
