/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx.vk;

import static org.lwjgl.vulkan.VK10.VK_ACCESS_MEMORY_READ_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_MEMORY_WRITE_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
import static org.lwjgl.vulkan.VK10.VK_QUEUE_FAMILY_IGNORED;
import static org.lwjgl.vulkan.VK10.vkCmdPipelineBarrier;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkMemoryBarrier;

/**
 * The Vulkan backend's barriers: wide ones (all stages, all memory), correct first; tuned after
 * measuring.
 *
 * <p>Pipeline: Stein GFX only (experimental Vulkan backend, not used by the game).
 */
final class SteinVkBarriers {

    private SteinVkBarriers() {}

    /**
     * Every earlier write becomes visible to every later read and write (wide barrier, always
     * correct).
     */
    static void memoryBarrier(VkCommandBuffer c) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryBarrier.Buffer b = VkMemoryBarrier.calloc(1, stack);
            b.get(0)
                    .sType$Default()
                    .srcAccessMask(VK_ACCESS_MEMORY_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT);
            vkCmdPipelineBarrier(
                    c,
                    VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                    VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                    0,
                    b,
                    null,
                    null);
        }
    }

    /** Changes the layout of {@code mips} levels (from {@code mip}) of all the image's layers. */
    static void layout(
            VkCommandBuffer c,
            long image,
            int aspect,
            int mip,
            int mips,
            int layers,
            int from,
            int to) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier.Buffer b = VkImageMemoryBarrier.calloc(1, stack);
            b.get(0)
                    .sType$Default()
                    .srcAccessMask(VK_ACCESS_MEMORY_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT)
                    .oldLayout(from)
                    .newLayout(to)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(image)
                    .subresourceRange(
                            r ->
                                    r.aspectMask(aspect)
                                            .baseMipLevel(mip)
                                            .levelCount(mips)
                                            .baseArrayLayer(0)
                                            .layerCount(layers));
            vkCmdPipelineBarrier(
                    c,
                    VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                    VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                    0,
                    null,
                    null,
                    b);
        }
    }
}
