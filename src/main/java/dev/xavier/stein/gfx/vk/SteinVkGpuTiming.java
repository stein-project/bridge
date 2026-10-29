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
import static org.lwjgl.vulkan.VK10.nvkGetQueryPoolResults;
import static org.lwjgl.vulkan.VK10.vkCmdWriteTimestamp;
import static org.lwjgl.vulkan.VK10.vkCreateQueryPool;
import static org.lwjgl.vulkan.VK10.vkDestroyQueryPool;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceProperties;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceQueueFamilyProperties;
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
 * <p>Each ring slot owns two queries of one pool (start, end), so a frame's slots are one
 * contiguous range of queries (two when the ring wraps). The oldest frame is read with one {@code
 * vkGetQueryPoolResults} over its whole range (64-bit values with availability) into memory
 * allocated once, and its slots are reset from the CPU as one range when they leave the ring
 * ({@code hostQueryReset}, Vulkan 1.2): a reset command would not be allowed inside a render, and a
 * pass may start inside the render the previous one left open. A call costs about the same for one
 * query as for the whole range, and the benchmark turns this timer on, so one call per query (two
 * reads and a reset per pass) was itself a visible part of the frame. Results are read without
 * waiting, only for frames whose last timestamp is already available; a query of that frame not yet
 * available reads as 0 (an impossible result, counted). Ticks become nanoseconds with the GPU's
 * timestampPeriod, masked to the queue's valid bits.
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

    /** Bytes per query in {@link #results}: the value and its availability, two longs. */
    private static final long STRIDE = 16L;

    private static final int FLAGS = VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WITH_AVAILABILITY_BIT;

    /**
     * Native memory for every query of the pool, at the query's own index (allocated with the
     * pool): a frame's range lands where its slots are, wrapped or not, with no copy.
     */
    private static long results;

    private static final SteinGpuPassTiming.Timestamps READ =
            new SteinGpuPassTiming.Timestamps() {
                @Override
                public void fetch(int first, int n) {
                    int q = 2 * first;
                    int count = 2 * n;
                    int untilEnd = 2 * SLOTS - q;
                    if (count <= untilEnd) {
                        read(q, count);
                    } else {
                        read(q, untilEnd);
                        read(0, count - untilEnd);
                    }
                }

                @Override
                public boolean arrived(int slot) {
                    return available(2 * slot + 1);
                }

                @Override
                public long begin(int slot) {
                    return value(2 * slot);
                }

                @Override
                public long end(int slot) {
                    return value(2 * slot + 1);
                }

                @Override
                public void released(int first, int n) {
                    // read already, and the GPU is past them: free for their next claim
                    int untilEnd = SLOTS - first;
                    if (n <= untilEnd) {
                        vkResetQueryPool(device.vk(), pool, 2 * first, 2 * n);
                    } else {
                        vkResetQueryPool(device.vk(), pool, 2 * first, 2 * untilEnd);
                        vkResetQueryPool(device.vk(), pool, 0, 2 * (n - untilEnd));
                    }
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
            results = MemoryUtil.nmemCallocChecked(2L * SLOTS, STRIDE);
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
            // the slot's queries were reset when its previous use left the ring (released)
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
            MemoryUtil.nmemFree(results);
            results = 0L;
        }
    }

    /**
     * {@code count} queries from {@code query} into {@link #results}, without waiting. With the
     * availability bit every query's availability is written, available or not, so a value left
     * from the slot's previous lap is never taken as this one's.
     */
    private static void read(int query, int count) {
        long at = results + query * STRIDE;
        int r =
                nvkGetQueryPoolResults(
                        device.vk(), pool, query, count, count * STRIDE, at, STRIDE, FLAGS);
        if (r != VK_SUCCESS && r != VK_NOT_READY) {
            // an error (device lost): nothing of the range counts as arrived
            MemoryUtil.memSet(at, 0, count * STRIDE);
        }
    }

    private static boolean available(int query) {
        return MemoryUtil.memGetLong(results + query * STRIDE + 8L) != 0L;
    }

    /** The query's time in nanoseconds; 0 (an impossible result) if it has not arrived. */
    private static long value(int query) {
        return available(query) ? nanos(MemoryUtil.memGetLong(results + query * STRIDE)) : 0L;
    }

    private static long nanos(long ticks) {
        return (long) ((ticks & validMask) * nanosPerTick);
    }
}
