/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx.game;

import dev.xavier.stein.gfx.SteinDevice;
import dev.xavier.stein.gfx.SteinFormat;
import dev.xavier.stein.gfx.SteinHdr;
import dev.xavier.stein.gfx.SteinSampler;
import dev.xavier.stein.gfx.SteinTexture;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL14;

import java.nio.ByteBuffer;

/**
 * One of the game's OpenGL texture names, on the API: what OpenGL keeps for it (the levels the game
 * defined, the parameters) and the API texture that holds the pixels ({@link #backing()}).
 *
 * <p>An OpenGL texture is mutable — glTexImage2D on level 0 with another size reallocates it under
 * the same name — and an API texture is not: the backing is recreated when the shape changes, and
 * whoever keeps this object (the renderer's {@code importTexture}) always reaches the current one.
 * The parameters become a sampler from a cache shared by every texture ({@link #sampler()}).
 *
 * <p>As an API texture (it extends {@link SteinTexture}) it is a reference: {@link #handle()} is
 * the game's name, binding it binds the current backing with its sampler, and {@link #destroy()}
 * does nothing — only the game's glDeleteTextures releases it.
 *
 * <p>Pipeline: Stein GFX only (the game's textures in the Vulkan backend).
 */
public final class SteinGameTexture extends SteinTexture {

    /** OpenGL's default for GL_TEXTURE_MAX_LEVEL: every level the game defines counts. */
    private static final int GL_DEFAULT_MAX_LEVEL = 1000;

    final int name;

    // ---- what OpenGL keeps ----

    private SteinFormat format;
    private int width;
    private int height;

    /** One bit per level defined by glTexImage2D. */
    private int levels;

    private int minFilter = GL11.GL_NEAREST_MIPMAP_LINEAR;
    private int magFilter = GL11.GL_LINEAR;
    private int wrapS = GL11.GL_REPEAT;
    private int wrapT = GL11.GL_REPEAT;
    private int maxLevel = GL_DEFAULT_MAX_LEVEL;

    /** Some framebuffer draws into it: the backing must be able to be a target. */
    private boolean attached;

    // ---- on the API ----

    private SteinTexture backing;
    private SteinSampler sampler;

    /** Bumped when the backing is replaced (the framebuffers rebuild their targets). */
    int generation;

    SteinGameTexture(int name) {
        super(SteinTexture.Desc.texture2D("game " + name, SteinFormat.BGRA8, 1, 1, 1));
        this.name = name;
    }

    /** The API texture with the pixels, or null while the game has not defined level 0. */
    public SteinTexture backing() {
        return ensure();
    }

    /**
     * The sampler of the texture's current parameters (min and mag filter, mipmap, wrap), from the
     * shared cache.
     */
    public SteinSampler sampler() {
        if (sampler == null) {
            sampler = SteinGameSamplers.get(minFilter, magFilter, wrapS, wrapT, levelCount() > 1);
        }
        return sampler;
    }

    public SteinFormat format() {
        return format;
    }

    /** The minification filter the game set (GL_NEAREST, GL_LINEAR_MIPMAP_LINEAR...). */
    public int minFilter() {
        return minFilter;
    }

    public int magFilter() {
        return magFilter;
    }

    public int wrapS() {
        return wrapS;
    }

    public int wrapT() {
        return wrapT;
    }

    /** Size of level 0 (0 before the game defines it). */
    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }

    @Override
    public long handle() {
        return name;
    }

    // ---- what the game does to it ----

    /**
     * glTexImage2D on {@code level}: defines the level's size and format, and with {@code pixels}
     * uploads it. Level 0 with another size or format reallocates the texture.
     */
    void define(int level, SteinFormat f, int w, int h, ByteBuffer pixels, int rowLength) {
        if (level == 0) {
            if (f != format || w != width || h != height) {
                // another shape: the old levels no longer count (the game defines them again)
                release();
                levels = 0;
            }
            format = f;
            width = w;
            height = h;
            levels |= 1;
        } else {
            levels |= 1 << level;
            sampler = null; // mipmapped or not decides the sampler
            if (backing != null && backing.desc().mips != levelCount()) {
                grow();
            }
        }
        if (pixels != null) {
            upload(level, 0, 0, w, h, pixels, rowLength);
        }
    }

    /** glTexSubImage2D: uploads a rectangle of {@code level}. */
    void upload(int level, int x, int y, int w, int h, ByteBuffer pixels, int rowLength) {
        SteinTexture t = ensure();
        if (t == null || level >= t.desc().mips || w <= 0 || h <= 0) {
            return;
        }
        int texel = t.desc().format.bytes;
        if (rowLength > 0 && rowLength != w) {
            // tightly packed rows, as the API wants them
            ByteBuffer packed = ByteBuffer.allocateDirect(w * h * texel);
            int at = pixels.position();
            for (int row = 0; row < h; row++) {
                ByteBuffer src = pixels.duplicate();
                src.position(at + row * rowLength * texel)
                        .limit(at + (row * rowLength + w) * texel);
                packed.put(src);
            }
            packed.flip();
            pixels = packed;
        }
        t.upload(0, level, x, y, w, h, pixels);
    }

    /** glTexParameteri. */
    void parameter(int pname, int value) {
        switch (pname) {
            case GL11.GL_TEXTURE_MIN_FILTER -> minFilter = value;
            case GL11.GL_TEXTURE_MAG_FILTER -> magFilter = value;
            case GL11.GL_TEXTURE_WRAP_S -> wrapS = value;
            case GL11.GL_TEXTURE_WRAP_T -> wrapT = value;
            case GL12.GL_TEXTURE_MAX_LEVEL -> {
                maxLevel = Math.max(0, value);
                if (backing != null && backing.desc().mips != levelCount()) {
                    grow();
                }
            }
            default -> {
                // MIN_LOD, MAX_LOD, LOD_BIAS: the game sets OpenGL's defaults (0 levels of bias)
                return;
            }
        }
        sampler = null;
    }

    /** glGetTexLevelParameteri. */
    int levelParameter(int level, int pname) {
        if ((levels & 1 << level) == 0) {
            return 0;
        }
        int w = Math.max(1, width >> level);
        int h = Math.max(1, height >> level);
        return switch (pname) {
            case GL11.GL_TEXTURE_WIDTH -> w;
            case GL11.GL_TEXTURE_HEIGHT -> h;
            case GL11.GL_TEXTURE_INTERNAL_FORMAT ->
                    format == null
                            ? GL11.GL_RGBA
                            : format == SteinFormat.DEPTH24
                                    ? GL14.GL_DEPTH_COMPONENT24
                                    : GL11.GL_RGBA8;
            default -> 0;
        };
    }

    /** glTexParameteri / glGetTexParameteri. */
    int parameter(int pname) {
        return switch (pname) {
            case GL11.GL_TEXTURE_MIN_FILTER -> minFilter;
            case GL11.GL_TEXTURE_MAG_FILTER -> magFilter;
            case GL11.GL_TEXTURE_WRAP_S -> wrapS;
            case GL11.GL_TEXTURE_WRAP_T -> wrapT;
            case GL12.GL_TEXTURE_MAX_LEVEL -> maxLevel;
            default -> 0;
        };
    }

    /** A framebuffer draws into it: from now on the backing is a render target. */
    void attach() {
        if (!attached) {
            attached = true;
            if (backing != null && !backing.desc().renderTarget) {
                grow();
            }
        }
    }

    /** The backing, created on first need (null while level 0 is undefined). */
    SteinTexture ensure() {
        if (backing == null && (levels & 1) != 0 && width > 0 && height > 0) {
            backing = SteinDevice.get().createTexture(desc(levelCount()));
            generation++;
        }
        return backing;
    }

    /** glDeleteTextures. */
    void delete() {
        release();
        levels = 0;
        width = 0;
        height = 0;
    }

    /** Levels the backing has: the defined ones from 0, up to GL_TEXTURE_MAX_LEVEL. */
    int levelCount() {
        int n = 0;
        while (n < 31 && (levels & 1 << n) != 0 && n <= maxLevel) {
            n++;
        }
        return Math.max(1, n);
    }

    private Desc desc(int mips) {
        String label = "game texture " + name;
        if (attached) {
            // a framebuffer's colour holds the lights above white with HDR output (SteinHdr: the
            // game recreates its framebuffer when that changes)
            SteinFormat f =
                    SteinHdr.framebufferWide() && format != SteinFormat.DEPTH24
                            ? SteinFormat.RGBA16F
                            : format;
            return new Desc(label, Kind.TEXTURE_2D, f, width, height, 1, mips, true);
        }
        return SteinTexture.Desc.texture2D(label, format, width, height, mips);
    }

    /**
     * Recreates the backing with the current shape (more levels, or as a target), keeping level 0.
     */
    private void grow() {
        SteinTexture old = backing;
        backing = null;
        sampler = null;
        SteinTexture now = ensure();
        if (old != null && now != null && SteinDevice.get().caps().copyImage) {
            int w = Math.min(old.width(), now.width());
            int h = Math.min(old.height(), now.height());
            SteinDevice.get().copyTexture(old, 0, now, 0, w, h);
        }
        if (old != null) {
            old.destroy();
        }
    }

    private void release() {
        if (backing != null) {
            backing.destroy();
            backing = null;
            generation++;
        }
        sampler = null;
    }

    // ---- as an API texture: a reference ----

    @Override
    public void upload(int layer, int level, int x, int y, int w, int h, ByteBuffer pixels) {
        upload(level, x, y, w, h, pixels, 0);
    }

    @Override
    public void generateMips() {
        SteinTexture t = ensure();
        if (t != null) {
            t.generateMips();
        }
    }

    /** Not ours to delete: only the game's glDeleteTextures releases it. */
    @Override
    public void destroy() {}
}
