/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx.vk;

import static org.lwjgl.vulkan.VK10.VK_ACCESS_HOST_READ_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_TRANSFER_WRITE_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_FILTER_NEAREST;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_HOST_CACHED_BIT;
import static org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_HOST_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_TRANSFER_BIT;
import static org.lwjgl.vulkan.VK10.vkCmdBlitImage;
import static org.lwjgl.vulkan.VK10.vkCmdCopyBuffer;
import static org.lwjgl.vulkan.VK10.vkCmdCopyImage;
import static org.lwjgl.vulkan.VK10.vkCmdCopyImageToBuffer;
import static org.lwjgl.vulkan.VK10.vkCmdPipelineBarrier;
import static org.lwjgl.vulkan.VK10.vkDestroyBuffer;

import dev.xavier.stein.gfx.SteinBuffer;
import dev.xavier.stein.gfx.SteinFormat;
import dev.xavier.stein.gfx.SteinTexture;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkImageCopy;
import org.lwjgl.vulkan.VkMemoryBarrier;

import java.nio.ByteBuffer;

/**
 * The Vulkan backend's copies and reads: texture to texture (a blit when the formats differ, as the
 * game's BGRA into the renderer's RGBA arrays), buffer to buffer, and the reads back to the CPU,
 * which wait for the GPU. Recorded into the device's command buffer, outside any pass, in the order
 * they are asked for.
 *
 * <p>Pipeline: Stein GFX only (the Vulkan backend).
 */
final class SteinVkTransfers {

    private SteinVkTransfers() {}

    static void copyTexture(
            SteinVkDevice dev,
            SteinTexture src,
            int srcLayer,
            SteinTexture dst,
            int dstLayer,
            int w,
            int h) {
        SteinVkTexture s = SteinVkDevice.resolve(src);
        SteinVkTexture d = SteinVkDevice.resolve(dst);
        if (s == null || d == null) {
            return; // a game texture the game has not defined yet
        }
        VkCommandBuffer c = dev.outside();
        if (s.format() != d.format()) {
            // the game's BGRA into an RGBA array (or the screen into a BGRA texture): a copy
            // keeps the bits, a blit converts
            blitConvert(c, s, srcLayer, d, dstLayer, w, h);
            return;
        }
        boolean same = s == d;
        if (same && srcLayer == dstLayer) {
            throw new IllegalArgumentException(
                    "copyTexture from '" + src.desc().name + "' to the same layer");
        }
        int srcLayout = same ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
        int dstLayout = same ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
        s.toLayout(c, srcLayout);
        if (!same) {
            d.toLayout(c, dstLayout);
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageCopy.Buffer region = VkImageCopy.calloc(1, stack);
            region.get(0)
                    .srcSubresource()
                    .aspectMask(s.aspect())
                    .mipLevel(0)
                    .baseArrayLayer(srcLayer)
                    .layerCount(1);
            region.get(0)
                    .dstSubresource()
                    .aspectMask(d.aspect())
                    .mipLevel(0)
                    .baseArrayLayer(dstLayer)
                    .layerCount(1);
            region.get(0).extent().set(w, h, 1);
            vkCmdCopyImage(c, s.image(), srcLayout, d.image(), dstLayout, region);
        }
        s.toRest(c);
        if (!same) {
            d.toRest(c);
        }
    }

    private static void blitConvert(
            VkCommandBuffer c,
            SteinVkTexture s,
            int srcLayer,
            SteinVkTexture d,
            int dstLayer,
            int w,
            int h) {
        s.toLayout(c, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
        d.toLayout(c, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageBlit.Buffer region = VkImageBlit.calloc(1, stack);
            region.get(0)
                    .srcSubresource()
                    .aspectMask(s.aspect())
                    .mipLevel(0)
                    .baseArrayLayer(srcLayer)
                    .layerCount(1);
            region.get(0)
                    .dstSubresource()
                    .aspectMask(d.aspect())
                    .mipLevel(0)
                    .baseArrayLayer(dstLayer)
                    .layerCount(1);
            region.get(0).srcOffsets(1).set(w, h, 1);
            region.get(0).dstOffsets(1).set(w, h, 1);
            vkCmdBlitImage(
                    c,
                    s.image(),
                    VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    d.image(),
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    region,
                    VK_FILTER_NEAREST);
        }
        s.toRest(c);
        d.toRest(c);
    }

    static void copyBuffer(
            SteinVkDevice dev,
            SteinBuffer src,
            long srcOffset,
            SteinBuffer dst,
            long dstOffset,
            long bytes) {
        VkCommandBuffer c = dev.outside();
        SteinVkBarriers.memoryBarrier(c);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack);
            region.get(0).srcOffset(srcOffset).dstOffset(dstOffset).size(bytes);
            vkCmdCopyBuffer(c, src.handle(), dst.handle(), region);
        }
        SteinVkBarriers.memoryBarrier(c);
    }

    static void readBuffer(SteinVkDevice dev, SteinBuffer src, long offset, ByteBuffer out) {
        long bytes = out.remaining();
        if (bytes == 0) {
            return;
        }
        // Copy to a CPU-visible buffer (preferably cached) after everything that came before, as
        // in readPixels; the buffer's own mapping (UPLOAD) will not do: it may be write-combined
        // memory, with no ordering against the GPU, and the GPU has no mapping at all.
        SteinVkMemory.Allocation[] a = new SteinVkMemory.Allocation[1];
        long buffer =
                dev.createRawBuffer(
                        bytes,
                        VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                        VK_MEMORY_PROPERTY_HOST_CACHED_BIT,
                        "buffer readback",
                        a);
        try {
            VkCommandBuffer c = dev.outside();
            SteinVkBarriers.memoryBarrier(
                    c); // what a compute or a copy wrote before becomes visible to the copy
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack);
                region.get(0).srcOffset(offset).dstOffset(0L).size(bytes);
                vkCmdCopyBuffer(c, src.handle(), buffer, region);
                VkMemoryBarrier.Buffer host = VkMemoryBarrier.calloc(1, stack);
                host.get(0)
                        .sType$Default()
                        .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                        .dstAccessMask(VK_ACCESS_HOST_READ_BIT);
                vkCmdPipelineBarrier(
                        c,
                        VK_PIPELINE_STAGE_TRANSFER_BIT,
                        VK_PIPELINE_STAGE_HOST_BIT,
                        0,
                        host,
                        null,
                        null);
            }
            long value = dev.recordingValue();
            if (!dev.waitValue(value, Long.MAX_VALUE)) {
                throw new IllegalStateException("vulkan: buffer readback did not finish");
            }
            MemoryUtil.memCopy(a[0].mapped, MemoryUtil.memAddress(out), bytes);
        } finally {
            vkDestroyBuffer(dev.vk(), buffer, null);
            dev.free(a[0]);
        }
    }

    static void readPixels(
            SteinVkDevice dev, SteinTexture texture, int x, int y, int w, int h, ByteBuffer out) {
        SteinVkTexture t = SteinVkDevice.resolve(texture);
        if (t == null) {
            return;
        }
        SteinFormat f = t.desc().format;
        if (f == SteinFormat.RGBA16F) {
            // the screen with HDR output (a screenshot): through an RGBA8 copy, clipped at white
            SteinTexture bytes8 =
                    dev.createTexture(
                            SteinTexture.Desc.target(
                                    "readback", SteinFormat.RGBA8, t.width(), t.height()));
            try {
                copyTexture(dev, t, 0, bytes8, 0, t.width(), t.height());
                readPixels(dev, bytes8, x, y, w, h, out);
            } finally {
                bytes8.destroy();
            }
            return;
        }
        if (f != SteinFormat.RGBA8 && f != SteinFormat.BGRA8) {
            throw new IllegalArgumentException("readPixels supports RGBA8 and BGRA8 only: " + f);
        }
        long bytes = (long) w * h * 4;
        SteinVkMemory.Allocation[] a = new SteinVkMemory.Allocation[1];
        long buffer =
                dev.createRawBuffer(
                        bytes,
                        VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                        VK_MEMORY_PROPERTY_HOST_CACHED_BIT,
                        "readback",
                        a);
        try {
            VkCommandBuffer c = dev.outside();
            t.toLayout(c, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
                region.get(0).bufferOffset(0L);
                region.get(0)
                        .imageSubresource()
                        .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .mipLevel(0)
                        .baseArrayLayer(0)
                        .layerCount(1);
                region.get(0).imageOffset().set(x, y, 0);
                region.get(0).imageExtent().set(w, h, 1);
                vkCmdCopyImageToBuffer(
                        c, t.image(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, buffer, region);
                VkMemoryBarrier.Buffer host = VkMemoryBarrier.calloc(1, stack);
                host.get(0)
                        .sType$Default()
                        .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                        .dstAccessMask(VK_ACCESS_HOST_READ_BIT);
                vkCmdPipelineBarrier(
                        c,
                        VK_PIPELINE_STAGE_TRANSFER_BIT,
                        VK_PIPELINE_STAGE_HOST_BIT,
                        0,
                        host,
                        null,
                        null);
            }
            t.toRest(c);
            long value = dev.recordingValue();
            if (!dev.waitValue(value, Long.MAX_VALUE)) {
                throw new IllegalStateException("vulkan: pixel readback did not finish");
            }
            MemoryUtil.memCopy(
                    a[0].mapped, MemoryUtil.memAddress(out), Math.min(bytes, out.remaining()));
        } finally {
            vkDestroyBuffer(dev.vk(), buffer, null);
            dev.free(a[0]);
        }
    }
}
