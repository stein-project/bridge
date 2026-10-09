/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

package dev.xavier.stein.gfx.vk;

import static org.lwjgl.vulkan.VK10.VK_BORDER_COLOR_FLOAT_TRANSPARENT_BLACK;
import static org.lwjgl.vulkan.VK10.VK_COMPARE_OP_LESS_OR_EQUAL;
import static org.lwjgl.vulkan.VK10.VK_LOD_CLAMP_NONE;
import static org.lwjgl.vulkan.VK10.VK_NULL_HANDLE;
import static org.lwjgl.vulkan.VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
import static org.lwjgl.vulkan.VK10.VK_SAMPLER_MIPMAP_MODE_LINEAR;
import static org.lwjgl.vulkan.VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST;
import static org.lwjgl.vulkan.VK10.vkCreateSampler;
import static org.lwjgl.vulkan.VK10.vkDestroySampler;

import dev.xavier.stein.gfx.SteinSampler;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

import java.nio.LongBuffer;

/**
 * Vulkan sampler. Without mipmaps ({@link Mip#NONE}): maximum lod 0.25, which only reads level 0
 * and still chooses min or mag.
 *
 * <p>Pipeline: Stein GFX only (experimental Vulkan backend, not used by the game).
 */
final class SteinVkSampler extends SteinSampler {

    /** Maximum lod without mipmaps: reads only level 0 and still chooses min or mag. */
    private static final float NO_MIP_MAX_LOD = 0.25f;

    private final SteinVkDevice device;
    private long sampler;

    SteinVkSampler(SteinVkDevice device, Desc desc) {
        super(desc);
        this.device = device;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            float maxAniso = device.caps().maxAnisotropy;
            boolean aniso = maxAniso > 1f && desc.anisotropy > 1f;
            VkSamplerCreateInfo info =
                    VkSamplerCreateInfo.calloc(stack)
                            .sType$Default()
                            .magFilter(SteinVkFormats.filter(desc.mag))
                            .minFilter(SteinVkFormats.filter(desc.min))
                            .mipmapMode(
                                    desc.mip == Mip.LINEAR
                                            ? VK_SAMPLER_MIPMAP_MODE_LINEAR
                                            : VK_SAMPLER_MIPMAP_MODE_NEAREST)
                            .addressModeU(SteinVkFormats.wrap(desc.wrapU))
                            .addressModeV(SteinVkFormats.wrap(desc.wrapV))
                            .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                            .anisotropyEnable(aniso)
                            .maxAnisotropy(aniso ? Math.min(desc.anisotropy, maxAniso) : 1f)
                            .compareEnable(desc.compare)
                            .compareOp(VK_COMPARE_OP_LESS_OR_EQUAL)
                            .minLod(0f)
                            .maxLod(desc.mip == Mip.NONE ? NO_MIP_MAX_LOD : VK_LOD_CLAMP_NONE)
                            .borderColor(VK_BORDER_COLOR_FLOAT_TRANSPARENT_BLACK);
            LongBuffer p = stack.mallocLong(1);
            SteinVkDevice.check(vkCreateSampler(device.vk(), info, null, p), "sampler");
            sampler = p.get(0);
        }
    }

    @Override
    public long handle() {
        return sampler;
    }

    @Override
    public void destroy() {
        if (sampler != VK_NULL_HANDLE) {
            long s = sampler;
            sampler = VK_NULL_HANDLE;
            device.retire(() -> vkDestroySampler(device.vk(), s, null));
        }
    }
}
