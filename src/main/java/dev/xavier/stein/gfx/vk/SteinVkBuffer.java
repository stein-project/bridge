/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx.vk;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_INDEX_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_VERTEX_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT;
import static org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT;
import static org.lwjgl.vulkan.VK10.VK_NULL_HANDLE;
import static org.lwjgl.vulkan.VK10.vkCmdCopyBuffer;
import static org.lwjgl.vulkan.VK10.vkDestroyBuffer;

import dev.xavier.stein.gfx.SteinBuffer;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteBuffer;

/**
 * Vulkan buffer. {@link Memory#UPLOAD}: CPU-visible, coherent memory, mapped for its whole life
 * (OpenGL 4.4 persistent mapping, here always); with resizable BAR the memory is also the GPU
 * memory. {@link Memory#GPU}: GPU memory, and {@link #upload} goes through staging and a copy
 * recorded in command order.
 *
 * <p>On {@link Memory#UPLOAD} a write (through {@link #address} or {@link #upload}) takes effect
 * immediately, as with OpenGL persistent coherent mapping: what was already written and the GPU has
 * not read yet sees the new value. In OpenGL the window is about one frame; here it lasts until
 * submission, so anyone rewriting the same range between draws needs the ring with a fence ({@link
 * #ring}) — with no frame in the API, submission only happens when someone waits for the GPU.
 *
 * <p>Pipeline: Stein GFX only (experimental Vulkan backend, not used by the game).
 */
final class SteinVkBuffer extends SteinBuffer {

    private final SteinVkDevice device;
    private long buffer;
    private SteinVkMemory.Allocation mem;

    /**
     * Where the CPU writes directly ({@link Memory#UPLOAD}); 0 on {@link Memory#GPU} even if the
     * memory is visible (integrated GPU): there the upload is a recorded copy, and what the GPU is
     * still going to read from the earlier request reads the earlier contents.
     */
    private final long mapped;

    SteinVkBuffer(SteinVkDevice device, Desc desc) {
        super(desc);
        this.device = device;
        int usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
        for (Usage u : desc.usage) {
            usage |=
                    switch (u) {
                        case VERTEX -> VK_BUFFER_USAGE_VERTEX_BUFFER_BIT;
                        case INDEX -> VK_BUFFER_USAGE_INDEX_BUFFER_BIT;
                        case UNIFORM -> VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT;
                        case STORAGE -> VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
                        case INDIRECT -> VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT;
                    };
        }
        // indirect and storage go together: the on-GPU draw compute writes the commands that the
        // draw reads
        if (desc.usage.contains(Usage.INDIRECT) || desc.usage.contains(Usage.STORAGE)) {
            usage |= VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT;
        }
        SteinVkMemory.Allocation[] a = new SteinVkMemory.Allocation[1];
        if (desc.memory == Memory.UPLOAD) {
            buffer =
                    device.createRawBuffer(
                            desc.size,
                            usage,
                            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT
                                    | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                            VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                            desc.name,
                            a);
        } else {
            buffer =
                    device.createRawBuffer(
                            desc.size, usage, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, 0, desc.name, a);
        }
        mem = a[0];
        mapped = desc.memory == Memory.UPLOAD ? mem.mapped : 0L;
    }

    @Override
    public long handle() {
        return buffer;
    }

    @Override
    public void upload(long offset, ByteBuffer data) {
        int n = data.remaining();
        checkRange(offset, n);
        if (n == 0) {
            return;
        }
        if (mapped != 0L) {
            MemoryUtil.memCopy(MemoryUtil.memAddress(data), mapped + offset, n);
            return;
        }
        SteinVkDevice.StagingSlice s = device.stage(n, 4);
        MemoryUtil.memCopy(MemoryUtil.memAddress(data), s.address(), n);
        VkCommandBuffer c = device.outside();
        SteinVkBarriers.memoryBarrier(c); // whoever read the range before finishes before the copy
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack);
            region.get(0).srcOffset(s.offset()).dstOffset(offset).size(n);
            vkCmdCopyBuffer(c, s.buffer(), buffer, region);
        }
        SteinVkBarriers.memoryBarrier(c); // and whatever comes after reads the new one
    }

    @Override
    public void upload(long offset, float[] data, int count) {
        int bytes = count * Float.BYTES;
        if (mapped != 0L) {
            checkRange(offset, bytes);
            MemoryUtil.memFloatBuffer(mapped + offset, count).put(data, 0, count);
            return;
        }
        ByteBuffer b = MemoryUtil.memAlloc(bytes);
        try {
            b.asFloatBuffer().put(data, 0, count);
            upload(offset, b);
        } finally {
            MemoryUtil.memFree(b);
        }
    }

    private void checkRange(long offset, long bytes) {
        if (offset < 0 || offset + bytes > desc.size) {
            throw new IndexOutOfBoundsException(
                    "upload outside buffer '" + desc.name + "': " + offset + "+" + bytes);
        }
    }

    @Override
    public long address(long offset) {
        return mapped == 0L ? 0L : mapped + offset;
    }

    @Override
    public boolean mapped() {
        return mapped != 0L;
    }

    @Override
    public void destroy() {
        if (buffer == VK_NULL_HANDLE) {
            return;
        }
        long b = buffer;
        SteinVkMemory.Allocation m = mem;
        buffer = VK_NULL_HANDLE;
        device.retire(
                () -> {
                    vkDestroyBuffer(device.vk(), b, null);
                    device.free(m);
                });
    }
}
