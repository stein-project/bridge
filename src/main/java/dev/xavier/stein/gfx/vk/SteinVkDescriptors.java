/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

package dev.xavier.stein.gfx.vk;

import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
import static org.lwjgl.vulkan.VK10.VK_NULL_HANDLE;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;
import static org.lwjgl.vulkan.VK10.vkAllocateDescriptorSets;
import static org.lwjgl.vulkan.VK10.vkCreateDescriptorPool;
import static org.lwjgl.vulkan.VK10.vkDestroyDescriptorPool;
import static org.lwjgl.vulkan.VK10.vkResetDescriptorPool;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;

import java.nio.LongBuffer;
import java.util.ArrayDeque;

/**
 * Descriptor sets without push descriptors: one pool per submission, reset and reused when the GPU
 * finishes it.
 *
 * <p>Pipeline: Stein GFX only (experimental Vulkan backend, not used by the game).
 */
final class SteinVkDescriptors {
    private final SteinVkDevice dev;

    SteinVkDescriptors(SteinVkDevice dev) {
        this.dev = dev;
    }

    final ArrayDeque<long[]> busy = new ArrayDeque<>(); // {pool, value}
    final ArrayDeque<Long> free = new ArrayDeque<>();
    long current = VK_NULL_HANDLE;
    final java.util.List<Long> used = new java.util.ArrayList<>();

    long allocate(long layout) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer lp = stack.mallocLong(1);
            for (int attempt = 0; attempt < 2; attempt++) {
                if (current == VK_NULL_HANDLE) {
                    current = free.isEmpty() ? createPool(stack) : free.poll();
                    used.add(current);
                }
                int r =
                        vkAllocateDescriptorSets(
                                dev.vk(),
                                VkDescriptorSetAllocateInfo.calloc(stack)
                                        .sType$Default()
                                        .descriptorPool(current)
                                        .pSetLayouts(stack.longs(layout)),
                                lp);
                if (r == VK_SUCCESS) {
                    return lp.get(0);
                }
                current = VK_NULL_HANDLE; // full: the next pool
            }
            throw new IllegalStateException("vulkan: descriptor set did not fit in a new pool");
        }
    }

    private long createPool(MemoryStack stack) {
        VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(3, stack);
        sizes.get(0).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(4096);
        sizes.get(1).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(4096);
        sizes.get(2).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1024);
        LongBuffer lp = stack.mallocLong(1);
        SteinVkDevice.check(
                vkCreateDescriptorPool(
                        dev.vk(),
                        VkDescriptorPoolCreateInfo.calloc(stack)
                                .sType$Default()
                                .maxSets(1024)
                                .pPoolSizes(sizes),
                        null,
                        lp),
                "descriptor pool");
        return lp.get(0);
    }

    /** The pools used in the submission that just went out come back when the GPU finishes it. */
    void onSubmit(long value) {
        for (long p : used) {
            busy.add(new long[] {p, value});
        }
        used.clear();
        current = VK_NULL_HANDLE;
    }

    void reclaim(long done) {
        while (!busy.isEmpty() && busy.peek()[1] <= done) {
            long p = busy.poll()[0];
            vkResetDescriptorPool(dev.vk(), p, 0);
            free.add(p);
        }
    }

    void destroyAll() {
        for (long p : used) {
            vkDestroyDescriptorPool(dev.vk(), p, null);
        }
        for (long[] p : busy) {
            vkDestroyDescriptorPool(dev.vk(), p[0], null);
        }
        for (long p : free) {
            vkDestroyDescriptorPool(dev.vk(), p, null);
        }
        used.clear();
        busy.clear();
        free.clear();
    }
}
