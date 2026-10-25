/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx.vk;

import static org.lwjgl.vulkan.VK10.VK_NOT_READY;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
import static org.lwjgl.vulkan.VK10.VK_QUERY_RESULT_64_BIT;
import static org.lwjgl.vulkan.VK10.VK_QUERY_RESULT_WITH_AVAILABILITY_BIT;
import static org.lwjgl.vulkan.VK10.VK_QUERY_TYPE_TIMESTAMP;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;
import static org.lwjgl.vulkan.VK10.vkCmdWriteTimestamp;
import static org.lwjgl.vulkan.VK10.vkCreateQueryPool;
import static org.lwjgl.vulkan.VK10.vkDestroyQueryPool;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceProperties;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceQueueFamilyProperties;
import static org.lwjgl.vulkan.VK10.vkGetQueryPoolResults;
import static org.lwjgl.vulkan.VK12.vkResetQueryPool;

import dev.xavier.stein.bridge.core.Log;
import dev.xavier.stein.gfx.SteinGpuPassTiming;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkQueryPoolCreateInfo;
import org.lwjgl.vulkan.VkQueueFamilyProperties;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

/**
 * GPU time per pass on Vulkan ({@code -Dsteinbridge.gfx.gpuPassTiming=true}, diagnostic): a
 * timestamp query ({@code vkCmdWriteTimestamp}) when each pass opens and another when it closes;
 * the labels, the ring and the report are {@link SteinGpuPassTiming}'s, the same as OpenGL's, so
 * the two APIs can be compared pass by pass.
 *
 * <p>Each ring slot owns two queries of one pool (start, end). A slot is reset from the CPU when it
 * is claimed again ({@code hostQueryReset}, Vulkan 1.2): a reset command would not be allowed
 * inside a render, and a pass may start inside the render the previous one left open. Results are
 * read without waiting, only for frames whose last timestamp is already available; ticks become
 * nanoseconds with the GPU's timestampPeriod, masked to the queue's valid bits.
 *
 * <p>Pipeline: Stein GFX only.
 */
public final class SteinVkGpuTiming {

    static final boolean ON = SteinGpuPassTiming.ON;

    private static final int SLOTS = 4096;

    private static SteinVkDevice device;
    private static long pool;
    private static SteinGpuPassTiming timing;
    private static boolean broken;
    private static double nanosPerTick;
    private static long validMask;

    /** Two longs per query (value, availability), reused for every read. */
    private static final LongBuffer RESULT = MemoryUtil.memAllocLong(2);

    private static final SteinGpuPassTiming.Timestamps READ =
            new SteinGpuPassTiming.Timestamps() {
                @Override
                public boolean arrived(int slot) {
                    return read(2 * slot + 1) && RESULT.get(1) != 0;
                }

                @Override
                public long begin(int slot) {
                    return read(2 * slot) ? nanos(RESULT.get(0)) : 0L;
                }

                @Override
                public long end(int slot) {
                    return read(2 * slot + 1) ? nanos(RESULT.get(0)) : 0L;
                }
            };

    private SteinVkGpuTiming() {}

    private static boolean ready(SteinVkDevice d) {
        if (broken) {
            return false;
        }
        if (pool != 0L) {
            return true;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer n = stack.mallocInt(1);
            vkGetPhysicalDeviceQueueFamilyProperties(d.physical(), n, null);
            VkQueueFamilyProperties.Buffer families =
                    VkQueueFamilyProperties.malloc(n.get(0), stack);
            vkGetPhysicalDeviceQueueFamilyProperties(d.physical(), n, families);
            int bits = families.get(d.family()).timestampValidBits();
            if (bits == 0 || !d.hostQueryReset) {
                broken = true;
                Log.info(
                        "gfx",
                        "GPU time per pass: this Vulkan queue has no timestamps or no host query"
                                + " reset");
                return false;
            }
            VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.malloc(stack);
            vkGetPhysicalDeviceProperties(d.physical(), props);
            nanosPerTick = props.limits().timestampPeriod();
            validMask = bits >= 64 ? -1L : (1L << bits) - 1;
            VkQueryPoolCreateInfo info =
                    VkQueryPoolCreateInfo.calloc(stack)
                            .sType$Default()
                            .queryType(VK_QUERY_TYPE_TIMESTAMP)
                            .queryCount(2 * SLOTS);
            LongBuffer lp = stack.mallocLong(1);
            SteinVkDevice.check(vkCreateQueryPool(d.vk(), info, null, lp), "timestamp pool");
            pool = lp.get(0);
            vkResetQueryPool(d.vk(), pool, 0, 2 * SLOTS);
        }
        device = d;
        timing = new SteinGpuPassTiming(SLOTS);
        return true;
    }

    /** On pass open: the start timestamp into {@code c}. Returns the slot (-1: no measurement). */
    static int begin(SteinVkDevice d, VkCommandBuffer c, String name) {
        if (!ready(d)) {
            return -1;
        }
        int slot = timing.claim(name);
        if (slot >= 0) {
            // the slot's previous use was read already (it left the ring): its queries are free
            vkResetQueryPool(d.vk(), pool, 2 * slot, 2);
            vkCmdWriteTimestamp(c, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, pool, 2 * slot);
        }
        return slot;
    }

    /** On close: the end timestamp into {@code c}. */
    static void end(VkCommandBuffer c, int slot) {
        if (slot >= 0) {
            vkCmdWriteTimestamp(c, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, pool, 2 * slot + 1);
        }
    }

    /** At the end of each frame (Display): reads what has already arrived and closes the frame. */
    public static void frame() {
        if (!ON || broken || pool == 0L) {
            return;
        }
        timing.frame(READ, "Vulkan");
    }

    /** With the device idle, before it goes (its destroy). */
    static void destroy(SteinVkDevice d) {
        if (pool != 0L && device == d) {
            vkDestroyQueryPool(d.vk(), pool, null);
            pool = 0L;
            broken = true;
        }
    }

    private static boolean read(int query) {
        int r =
                vkGetQueryPoolResults(
                        device.vk(),
                        pool,
                        query,
                        1,
                        RESULT,
                        16L,
                        VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WITH_AVAILABILITY_BIT);
        return r == VK_SUCCESS || r == VK_NOT_READY;
    }

    private static long nanos(long ticks) {
        return (long) ((ticks & validMask) * nanosPerTick);
    }
}
