/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx.gl;

import dev.xavier.stein.gfx.SteinSampler;

import org.lwjgl.opengl.EXTTextureFilterAnisotropic;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;

/**
 * OpenGL sampler (GL 3.3 sampler object): bound together with the texture, it applies in place of
 * the texture's own filter.
 *
 * <p>Pipeline: Stein GFX only.
 */
final class SteinGlSampler extends SteinSampler {

    private int id;

    SteinGlSampler(SteinGlDevice device, Desc desc) {
        super(desc);
        id = GL33.glGenSamplers();
        GL33.glSamplerParameteri(id, GL11.GL_TEXTURE_MIN_FILTER, minFilter(desc.min, desc.mip));
        GL33.glSamplerParameteri(
                id,
                GL11.GL_TEXTURE_MAG_FILTER,
                desc.mag == Filter.LINEAR ? GL11.GL_LINEAR : GL11.GL_NEAREST);
        GL33.glSamplerParameteri(id, GL11.GL_TEXTURE_WRAP_S, wrap(desc.wrapU));
        GL33.glSamplerParameteri(id, GL11.GL_TEXTURE_WRAP_T, wrap(desc.wrapV));
        float maxAniso = device.caps().maxAnisotropy;
        if (maxAniso > 1f && desc.anisotropy > 1f) {
            GL33.glSamplerParameterf(
                    id,
                    EXTTextureFilterAnisotropic.GL_TEXTURE_MAX_ANISOTROPY_EXT,
                    Math.min(desc.anisotropy, maxAniso));
        }
        if (desc.compare) {
            GL33.glSamplerParameteri(
                    id, GL14.GL_TEXTURE_COMPARE_MODE, GL30.GL_COMPARE_REF_TO_TEXTURE);
            GL33.glSamplerParameteri(id, GL14.GL_TEXTURE_COMPARE_FUNC, GL11.GL_LEQUAL);
        }
    }

    private static int minFilter(Filter min, Mip mip) {
        boolean linear = min == Filter.LINEAR;
        return switch (mip) {
            case NONE -> linear ? GL11.GL_LINEAR : GL11.GL_NEAREST;
            case NEAREST -> linear ? GL11.GL_LINEAR_MIPMAP_NEAREST : GL11.GL_NEAREST_MIPMAP_NEAREST;
            case LINEAR -> linear ? GL11.GL_LINEAR_MIPMAP_LINEAR : GL11.GL_NEAREST_MIPMAP_LINEAR;
        };
    }

    private static int wrap(Wrap w) {
        return w == Wrap.CLAMP ? GL12.GL_CLAMP_TO_EDGE : GL11.GL_REPEAT;
    }

    @Override
    public long handle() {
        return id;
    }

    @Override
    public void destroy() {
        if (id != 0) {
            SteinGlFrame.sync("sampler deleted");
            GL33.glDeleteSamplers(id);
            id = 0;
        }
    }
}
