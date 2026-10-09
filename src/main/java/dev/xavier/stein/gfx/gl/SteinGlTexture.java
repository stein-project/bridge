/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx.gl;

import dev.xavier.stein.gfx.SteinTexture;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL42;

import java.nio.ByteBuffer;

/**
 * OpenGL texture: {@code GL_TEXTURE_2D} or {@code GL_TEXTURE_2D_ARRAY}, with immutable storage when
 * the GPU supports it ({@code glTexStorage}), otherwise level by level without data.
 *
 * <p>The game caches the texture bound on each unit: to create and write, the texture is bound on
 * the active unit and the one that was there goes back right afterwards — no other one is ever left
 * bound.
 *
 * <p>Pipeline: Stein GFX only.
 */
final class SteinGlTexture extends SteinTexture {

    private final int target;
    private final int binding;
    private int id;
    private boolean owned = true;

    SteinGlTexture(SteinGlDevice device, Desc desc) {
        super(desc);
        boolean array = desc.kind == Kind.TEXTURE_2D_ARRAY;
        target = targetFor(desc);
        binding = bindingFor(desc);
        int internal = SteinGlFormats.internalFormat(desc.format);
        int format = SteinGlFormats.pixelFormat(desc.format);
        int type = SteinGlFormats.pixelType(desc.format);
        id = GL11.glGenTextures();
        int previous = bind();
        try {
            if (device.caps().textureStorage) {
                if (array) {
                    GL42.glTexStorage3D(
                            target, desc.mips, internal, desc.width, desc.height, desc.layers);
                } else {
                    GL42.glTexStorage2D(target, desc.mips, internal, desc.width, desc.height);
                }
            } else {
                int w = desc.width;
                int h = desc.height;
                for (int level = 0; level < desc.mips; level++) {
                    if (array) {
                        GL12.glTexImage3D(
                                target,
                                level,
                                internal,
                                w,
                                h,
                                desc.layers,
                                0,
                                format,
                                type,
                                (ByteBuffer) null);
                    } else {
                        GL11.glTexImage2D(
                                target, level, internal, w, h, 0, format, type, (ByteBuffer) null);
                    }
                    w = Math.max(1, w / 2);
                    h = Math.max(1, h / 2);
                }
            }
            GL11.glTexParameteri(target, GL12.GL_TEXTURE_BASE_LEVEL, 0);
            GL11.glTexParameteri(target, GL12.GL_TEXTURE_MAX_LEVEL, desc.mips - 1);
            // The texture's own filter, only so it is complete without a sampler; whoever samples
            // is the SteinSampler.
            int filter =
                    desc.format.isDepth() || desc.renderTarget ? GL11.GL_LINEAR : GL11.GL_NEAREST;
            GL11.glTexParameteri(
                    target,
                    GL11.GL_TEXTURE_MIN_FILTER,
                    desc.mips > 1 ? GL11.GL_NEAREST_MIPMAP_LINEAR : filter);
            GL11.glTexParameteri(target, GL11.GL_TEXTURE_MAG_FILTER, filter);
        } finally {
            GL11.glBindTexture(target, previous);
        }
    }

    private static int targetFor(Desc desc) {
        return desc.kind == Kind.TEXTURE_2D_ARRAY ? GL30.GL_TEXTURE_2D_ARRAY : GL11.GL_TEXTURE_2D;
    }

    private static int bindingFor(Desc desc) {
        return desc.kind == Kind.TEXTURE_2D_ARRAY
                ? GL30.GL_TEXTURE_BINDING_2D_ARRAY
                : GL11.GL_TEXTURE_BINDING_2D;
    }

    /** External texture (the game's): it only binds and copies; it is never deleted from here. */
    SteinGlTexture(Desc desc, int external) {
        super(desc);
        target = targetFor(desc);
        binding = bindingFor(desc);
        id = external;
        owned = false;
    }

    /** Binds this texture on the active unit and returns the one that was bound there. */
    private int bind() {
        int previous = GL11.glGetInteger(binding);
        GL11.glBindTexture(target, id);
        return previous;
    }

    @Override
    public long handle() {
        return id;
    }

    /** The OpenGL target ({@code GL_TEXTURE_2D} or {@code GL_TEXTURE_2D_ARRAY}). */
    int target() {
        return target;
    }

    @Override
    public void upload(int layer, int level, int x, int y, int w, int h, ByteBuffer pixels) {
        SteinGlFrame.sync("API texture upload");
        int format = SteinGlFormats.pixelFormat(desc.format);
        int type = SteinGlFormats.pixelType(desc.format);
        int previousUnpack = GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
        int align = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT);
        int rowLength = GL11.glGetInteger(GL12.GL_UNPACK_ROW_LENGTH);
        int skipPixels = GL11.glGetInteger(GL12.GL_UNPACK_SKIP_PIXELS);
        int skipRows = GL11.glGetInteger(GL12.GL_UNPACK_SKIP_ROWS);
        int previous = bind();
        try {
            if (previousUnpack != 0) {
                GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
            }
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
            GL11.glPixelStorei(GL12.GL_UNPACK_ROW_LENGTH, 0);
            GL11.glPixelStorei(GL12.GL_UNPACK_SKIP_PIXELS, 0);
            GL11.glPixelStorei(GL12.GL_UNPACK_SKIP_ROWS, 0);
            if (desc.kind == Kind.TEXTURE_2D_ARRAY) {
                GL12.glTexSubImage3D(target, level, x, y, layer, w, h, 1, format, type, pixels);
            } else {
                GL11.glTexSubImage2D(target, level, x, y, w, h, format, type, pixels);
            }
        } finally {
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, align);
            GL11.glPixelStorei(GL12.GL_UNPACK_ROW_LENGTH, rowLength);
            GL11.glPixelStorei(GL12.GL_UNPACK_SKIP_PIXELS, skipPixels);
            GL11.glPixelStorei(GL12.GL_UNPACK_SKIP_ROWS, skipRows);
            if (previousUnpack != 0) {
                GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, previousUnpack);
            }
            GL11.glBindTexture(target, previous);
        }
    }

    @Override
    public void generateMips() {
        SteinGlFrame.sync("API texture mipmap");
        if (desc.mips <= 1) {
            return;
        }
        int previous = bind();
        try {
            GL30.glGenerateMipmap(target);
        } finally {
            GL11.glBindTexture(target, previous);
        }
    }

    @Override
    public void destroy() {
        if (owned && id != 0) {
            SteinGlFrame.sync("API texture deleted");
            // glDeleteTextures releases the texture from wherever it is bound; the game's cache
            // never holds it, because it is never left bound outside here and SteinCommands.
            GL11.glDeleteTextures(id);
            id = 0;
        }
    }
}
