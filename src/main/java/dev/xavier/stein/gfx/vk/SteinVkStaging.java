/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx.vk;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT;
import static org.lwjgl.vulkan.VK10.vkDestroyBuffer;

import java.util.ArrayDeque;

/**
 * The Vulkan backend's staging, in mapped 8 MB chunks: each upload takes the next slice of the
 * current chunk; a full chunk is only reused once the GPU has finished the last submission that
 * read from it. An upload bigger than half a chunk gets its own buffer, released by the timeline.
 *
 * <p>Pipeline: Stein GFX only (experimental Vulkan backend, not used by the game).
 */
final class SteinVkStaging {
    private final SteinVkDevice dev;

    SteinVkStaging(SteinVkDevice dev) {
        this.dev = dev;
    }

    static final long CHUNK = 8L << 20;

    final class Chunk {
        final long buffer;
        final SteinVkDevice.Allocation mem;
        long used;
        long lastValue;

        Chunk() {
            SteinVkDevice.Allocation[] a = new SteinVkDevice.Allocation[1];
            buffer =
                    dev.createRawBuffer(
                            CHUNK,
                            VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT
                                    | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                            0,
                            "staging",
                            a);
            mem = a[0];
        }
    }

    final ArrayDeque<Chunk> free = new ArrayDeque<>();
    final ArrayDeque<Chunk> busy = new ArrayDeque<>();
    Chunk current;

    SteinVkDevice.StagingSlice alloc(long bytes, long alignment) {
        if (bytes > CHUNK / 2) {
            SteinVkDevice.Allocation[] a = new SteinVkDevice.Allocation[1];
            long b =
                    dev.createRawBuffer(
                            bytes,
                            VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT
                                    | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                            0,
                            "large staging",
                            a);
            SteinVkDevice.Allocation mem = a[0];
            dev.retire(
                    () -> {
                        vkDestroyBuffer(dev.vk(), b, null);
                        dev.free(mem);
                    });
            return new SteinVkDevice.StagingSlice(b, 0L, mem.mapped);
        }
        long at = current == null ? 0L : align(current.used, alignment);
        if (current == null || at + bytes > CHUNK) {
            if (current != null) {
                busy.add(current);
            }
            current = free.poll();
            if (current == null) {
                current = new Chunk();
            }
            current.used = 0L;
            at = 0L;
        }
        current.used = at + bytes;
        current.lastValue = dev.recordingValue();
        return new SteinVkDevice.StagingSlice(current.buffer, at, current.mem.mapped + at);
    }

    void reclaim(long done) {
        while (!busy.isEmpty() && busy.peek().lastValue <= done) {
            free.add(busy.poll());
        }
    }

    void destroyAll() {
        if (current != null) {
            busy.add(current);
            current = null;
        }
        busy.addAll(free);
        for (Chunk c : busy) {
            vkDestroyBuffer(dev.vk(), c.buffer, null);
            dev.free(c.mem);
        }
        busy.clear();
        free.clear();
    }

    static long align(long v, long alignment) {
        return alignment <= 1 ? v : (v + alignment - 1) / alignment * alignment;
    }
}
