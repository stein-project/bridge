/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx.vk;

import static org.lwjgl.vulkan.VK10.VK_ERROR_OUT_OF_DEVICE_MEMORY;
import static org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;
import static org.lwjgl.vulkan.VK10.VK_WHOLE_SIZE;
import static org.lwjgl.vulkan.VK10.vkAllocateMemory;
import static org.lwjgl.vulkan.VK10.vkFreeMemory;
import static org.lwjgl.vulkan.VK10.vkMapMemory;
import static org.lwjgl.vulkan.VK10.vkUnmapMemory;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;

import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The Vulkan backend's device memory: small resources share blocks, large ones get their own
 * allocation.
 *
 * <p>Drivers allow a few thousand allocations in all (maxMemoryAllocationCount, 4096 on the common
 * desktop ones), and a crowded server brings hundreds of skins, each a texture: one allocation per
 * resource would run out. Resources under {@link #DEDICATED_BYTES} come from {@link #BLOCK_BYTES}
 * blocks, one list of blocks per memory type, apart for images and buffers — so an image (optimal
 * tiling) and a buffer never share a block, and the buffer-image granularity never applies. Each
 * block keeps its free ranges by offset and joins neighbours when a range comes back; an empty
 * block is released, except the last of its list.
 *
 * <p>A block of host-visible memory is mapped once, whole; each allocation's address is the block's
 * plus its offset.
 *
 * <p>Pipeline: Stein GFX only (the Vulkan backend).
 */
final class SteinVkMemory {

    /** Resources from this size up get an allocation of their own. */
    static final long DEDICATED_BYTES = 16L << 20;

    private static final long BLOCK_BYTES = 64L << 20;

    /** A device memory allocation, or a range of one, mapped if visible to the CPU. */
    static final class Allocation {
        final long memory;
        final long offset;
        final long size;

        /** CPU address of the range ({@code offset} included), or 0. */
        final long mapped;

        /** The block it came from; null when the allocation is its own. */
        final Block block;

        Allocation(long memory, long offset, long size, long mapped, Block block) {
            this.memory = memory;
            this.offset = offset;
            this.size = size;
            this.mapped = mapped;
            this.block = block;
        }
    }

    /** A block shared by small resources: its free ranges, offset -> size. */
    static final class Block {
        final long memory;
        final long mapped;
        final List<Block> list;
        final TreeMap<Long, Long> free = new TreeMap<>();
        long used;

        Block(long memory, long mapped, List<Block> list) {
            this.memory = memory;
            this.mapped = mapped;
            this.list = list;
            free.put(0L, BLOCK_BYTES);
        }

        /** The offset of an aligned range of {@code size}, taken from the free ones; -1 if none. */
        long take(long size, long alignment) {
            for (Map.Entry<Long, Long> e : free.entrySet()) {
                long start = e.getKey();
                long end = start + e.getValue();
                long at = (start + alignment - 1) / alignment * alignment;
                if (at + size > end) {
                    continue;
                }
                free.remove(start);
                if (at > start) {
                    free.put(start, at - start);
                }
                if (at + size < end) {
                    free.put(at + size, end - at - size);
                }
                used += size;
                return at;
            }
            return -1L;
        }

        /** Gives the range back, joined with the free neighbours. */
        void give(long at, long size) {
            used -= size;
            long start = at;
            long end = at + size;
            Map.Entry<Long, Long> before = free.floorEntry(at);
            if (before != null && before.getKey() + before.getValue() == at) {
                start = before.getKey();
                free.remove(before.getKey());
            }
            Long after = free.get(end);
            if (after != null) {
                free.remove(end);
                end += after;
            }
            free.put(start, end - start);
        }
    }

    private final VkDevice device;
    private final VkPhysicalDeviceMemoryProperties props;

    /** Per memory type, two lists of blocks: [0] buffers, [1] images. */
    private final Map<Integer, List<Block>[]> pools = new HashMap<>();

    SteinVkMemory(VkDevice device, VkPhysicalDeviceMemoryProperties props) {
        this.device = device;
        this.props = props;
    }

    private int memoryType(int bits, int flags) {
        for (int i = 0; i < props.memoryTypeCount(); i++) {
            if ((bits & (1 << i)) != 0 && (props.memoryTypes(i).propertyFlags() & flags) == flags) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Memory for {@code req} with the {@code required} properties, first trying {@code required |
     * preferred}; {@code image}: for an image (it never shares a block with a buffer).
     */
    Allocation allocate(
            VkMemoryRequirements req, int required, int preferred, boolean image, String name) {
        int result = VK_ERROR_OUT_OF_DEVICE_MEMORY;
        for (int flags :
                preferred == 0
                        ? new int[] {required}
                        : new int[] {required | preferred, required}) {
            int type = memoryType(req.memoryTypeBits(), flags);
            if (type < 0) {
                continue;
            }
            if (req.size() < DEDICATED_BYTES) {
                Allocation a = fromBlock(type, req.size(), req.alignment(), image);
                if (a != null) {
                    return a;
                }
            }
            long[] mem = new long[1];
            result = raw(type, req.size(), mem);
            if (result == VK_SUCCESS) {
                return new Allocation(mem[0], 0L, req.size(), map(type, mem[0], name), null);
            }
        }
        SteinVkDevice.check(result, "memory of '" + name + "' (" + req.size() + " bytes)");
        throw new IllegalStateException("vulkan: no memory type for '" + name + "'");
    }

    private Allocation fromBlock(int type, long size, long alignment, boolean image) {
        @SuppressWarnings("unchecked")
        List<Block> list =
                pools.computeIfAbsent(type, t -> new List[] {new ArrayList<>(), new ArrayList<>()})[
                        image ? 1 : 0];
        for (Block b : list) {
            long at = b.take(size, Math.max(1L, alignment));
            if (at >= 0) {
                return new Allocation(b.memory, at, size, b.mapped == 0L ? 0L : b.mapped + at, b);
            }
        }
        long[] mem = new long[1];
        if (raw(type, BLOCK_BYTES, mem) != VK_SUCCESS) {
            return null; // the caller tries an allocation of its own
        }
        Block b = new Block(mem[0], map(type, mem[0], "memory block"), list);
        list.add(b);
        long at = b.take(size, Math.max(1L, alignment));
        return new Allocation(b.memory, at, size, b.mapped == 0L ? 0L : b.mapped + at, b);
    }

    private int raw(int type, long size, long[] out) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer lp = stack.mallocLong(1);
            VkMemoryAllocateInfo info =
                    VkMemoryAllocateInfo.calloc(stack)
                            .sType$Default()
                            .allocationSize(size)
                            .memoryTypeIndex(type);
            int r = vkAllocateMemory(device, info, null, lp);
            if (r == VK_SUCCESS) {
                out[0] = lp.get(0);
            }
            return r;
        }
    }

    private long map(int type, long memory, String name) {
        if ((props.memoryTypes(type).propertyFlags() & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) == 0) {
            return 0L;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pp = stack.mallocPointer(1);
            SteinVkDevice.check(
                    vkMapMemory(device, memory, 0L, VK_WHOLE_SIZE, 0, pp), "map '" + name + "'");
            return pp.get(0);
        }
    }

    void free(Allocation a) {
        Block b = a.block;
        if (b == null) {
            release(a.memory, a.mapped != 0L);
            return;
        }
        b.give(a.offset, a.size);
        if (b.used == 0L && b.list.size() > 1) {
            b.list.remove(b);
            release(b.memory, b.mapped != 0L);
        }
    }

    private void release(long memory, boolean mapped) {
        if (mapped) {
            vkUnmapMemory(device, memory);
        }
        vkFreeMemory(device, memory, null);
    }

    /** At the device's end: every block (the resources were destroyed before). */
    void destroyAll() {
        for (List<Block>[] lists : pools.values()) {
            for (List<Block> list : lists) {
                for (Block b : list) {
                    release(b.memory, b.mapped != 0L);
                }
                list.clear();
            }
        }
    }
}
