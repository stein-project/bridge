/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx.vk;

import dev.xavier.stein.gfx.SteinBuffer;
import dev.xavier.stein.gfx.SteinFormat;
import dev.xavier.stein.gfx.SteinSampler;
import dev.xavier.stein.gfx.SteinTexture;

import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/**
 * What the Vulkan backend binds where nothing is: a sampler for a texture bound without one (the
 * filter it would have on its own in the OpenGL backend), a 1x1 black texture (what OpenGL's
 * texture 0 returns) and a buffer of zeros for an empty uniform or storage block — so no descriptor
 * is ever left unwritten.
 *
 * <p>Pipeline: Stein GFX only (the Vulkan backend).
 */
final class SteinVkDefaults {

    static final long EMPTY_BUFFER = 64L * 1024;

    private final SteinVkDevice device;

    /** Samplers for whoever binds a texture without a sampler: [linear][with mipmap]. */
    private final SteinVkSampler[][] defaultSamplers = new SteinVkSampler[2][2];

    private final SteinVkTexture[] emptyTextures = new SteinVkTexture[2];
    private SteinVkBuffer emptyBuffer;

    SteinVkDefaults(SteinVkDevice device) {
        this.device = device;
    }

    /**
     * The sampler for whoever binds {@code t} without a sampler: the filter the texture would have
     * on its own in the OpenGL backend (linear on targets and depth, pixel by pixel on the rest;
     * with mipmap, linear mip), repeating.
     */
    SteinVkSampler defaultSampler(SteinTexture t) {
        boolean linear = t.desc().format.isDepth() || t.desc().renderTarget;
        boolean mips = t.desc().mips > 1;
        SteinVkSampler s = defaultSamplers[linear ? 1 : 0][mips ? 1 : 0];
        if (s == null) {
            SteinSampler.Filter f =
                    linear ? SteinSampler.Filter.LINEAR : SteinSampler.Filter.NEAREST;
            s =
                    new SteinVkSampler(
                            device,
                            new SteinSampler.Desc(
                                    f,
                                    f,
                                    mips ? SteinSampler.Mip.LINEAR : SteinSampler.Mip.NONE,
                                    SteinSampler.Wrap.REPEAT,
                                    SteinSampler.Wrap.REPEAT,
                                    1f,
                                    false));
            defaultSamplers[linear ? 1 : 0][mips ? 1 : 0] = s;
        }
        return s;
    }

    /**
     * 1x1 black texture (alpha 1) for the slot with no resource: what OpenGL's texture 0 returns.
     */
    SteinVkTexture emptyTexture(boolean arrayed) {
        SteinVkTexture t = emptyTextures[arrayed ? 1 : 0];
        if (t == null) {
            t =
                    new SteinVkTexture(
                            device,
                            arrayed
                                    ? SteinTexture.Desc.array(
                                            "empty", SteinFormat.RGBA8, 1, 1, 1, 1)
                                    : SteinTexture.Desc.texture2D(
                                            "empty", SteinFormat.RGBA8, 1, 1, 1));
            ByteBuffer px =
                    MemoryUtil.memAlloc(4)
                            .put(0, (byte) 0)
                            .put(1, (byte) 0)
                            .put(2, (byte) 0)
                            .put(3, (byte) 255);
            t.upload(0, 0, 0, 0, 1, 1, px);
            MemoryUtil.memFree(px);
            emptyTextures[arrayed ? 1 : 0] = t;
        }
        return t;
    }

    /** Buffer of zeros for the uniform or storage block with no resource. */
    SteinVkBuffer emptyBuffer() {
        if (emptyBuffer == null) {
            emptyBuffer =
                    new SteinVkBuffer(
                            device,
                            new SteinBuffer.Desc(
                                    "empty",
                                    EMPTY_BUFFER,
                                    SteinBuffer.Memory.UPLOAD,
                                    SteinBuffer.Usage.UNIFORM,
                                    SteinBuffer.Usage.STORAGE));
            MemoryUtil.memSet(emptyBuffer.address(0), 0, EMPTY_BUFFER);
        }
        return emptyBuffer;
    }

    void destroy() {
        for (SteinVkSampler[] row : defaultSamplers) {
            for (SteinVkSampler s : row) {
                if (s != null) {
                    s.destroy();
                }
            }
        }
        for (SteinVkTexture t : emptyTextures) {
            if (t != null) {
                t.destroy();
            }
        }
        if (emptyBuffer != null) {
            emptyBuffer.destroy();
        }
    }
}
