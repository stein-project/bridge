/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx.vk;

import static org.lwjgl.vulkan.VK10.VK_FILTER_LINEAR;
import static org.lwjgl.vulkan.VK10.VK_FILTER_NEAREST;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_DEPTH_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_TILING_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_TYPE_2D;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_SAMPLED_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_VIEW_TYPE_2D;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_VIEW_TYPE_2D_ARRAY;
import static org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT;
import static org.lwjgl.vulkan.VK10.VK_NULL_HANDLE;
import static org.lwjgl.vulkan.VK10.VK_SAMPLE_COUNT_1_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHARING_MODE_EXCLUSIVE;
import static org.lwjgl.vulkan.VK10.vkBindImageMemory;
import static org.lwjgl.vulkan.VK10.vkCmdBlitImage;
import static org.lwjgl.vulkan.VK10.vkCmdCopyBufferToImage;
import static org.lwjgl.vulkan.VK10.vkCreateImage;
import static org.lwjgl.vulkan.VK10.vkCreateImageView;
import static org.lwjgl.vulkan.VK10.vkDestroyImage;
import static org.lwjgl.vulkan.VK10.vkDestroyImageView;
import static org.lwjgl.vulkan.VK10.vkGetImageMemoryRequirements;

import dev.xavier.stein.gfx.SteinTexture;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

/**
 * Vulkan texture: a 2D image with one or more layers, in the card's memory. At rest it is always in
 * {@code SHADER_READ_ONLY_OPTIMAL} (ready to sample); upload, copy, mipmap and pass change the
 * layout and put it back. Two views: the sampling one (all layers and levels) and, if it is a
 * target, the drawing one (layer 0, level 0).
 *
 * <p>Pipeline: Stein GFX only (experimental Vulkan backend, not used by the game).
 */
final class SteinVkTexture extends SteinTexture {

    static final int REST = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;

    private final SteinVkDevice device;
    private final int format;
    private final int aspect;
    private long image;
    private SteinVkDevice.Allocation mem;
    private long view;
    private long attachView;

    /**
     * The current layout (recorded; it is what the card will see at this point of the command
     * buffer).
     */
    private int layout = VK_IMAGE_LAYOUT_UNDEFINED;

    SteinVkTexture(SteinVkDevice device, Desc desc) {
        super(desc);
        this.device = device;
        this.format = SteinVkFormats.texture(desc.format, device.depth24);
        boolean depth = SteinVkFormats.isDepth(format);
        this.aspect = depth ? VK_IMAGE_ASPECT_DEPTH_BIT : VK_IMAGE_ASPECT_COLOR_BIT;
        int usage =
                VK_IMAGE_USAGE_SAMPLED_BIT
                        | VK_IMAGE_USAGE_TRANSFER_SRC_BIT
                        | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        if (desc.renderTarget) {
            usage |=
                    depth
                            ? VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT
                            : VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageCreateInfo info =
                    VkImageCreateInfo.calloc(stack)
                            .sType$Default()
                            .imageType(VK_IMAGE_TYPE_2D)
                            .format(format)
                            .extent(e -> e.set(desc.width, desc.height, 1))
                            .mipLevels(desc.mips)
                            .arrayLayers(desc.layers)
                            .samples(VK_SAMPLE_COUNT_1_BIT)
                            .tiling(VK_IMAGE_TILING_OPTIMAL)
                            .usage(usage)
                            .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                            .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            LongBuffer lp = stack.mallocLong(1);
            SteinVkDevice.check(
                    vkCreateImage(device.vk(), info, null, lp), "image '" + desc.name + "'");
            image = lp.get(0);
            VkMemoryRequirements req = VkMemoryRequirements.calloc(stack);
            vkGetImageMemoryRequirements(device.vk(), image, req);
            mem = device.allocate(req, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, 0, desc.name);
            SteinVkDevice.check(
                    vkBindImageMemory(device.vk(), image, mem.memory, 0L),
                    "memory of '" + desc.name + "'");
            boolean array = desc.kind == Kind.TEXTURE_2D_ARRAY;
            view =
                    createView(
                            stack,
                            array ? VK_IMAGE_VIEW_TYPE_2D_ARRAY : VK_IMAGE_VIEW_TYPE_2D,
                            desc.mips,
                            desc.layers);
            if (desc.renderTarget) {
                attachView = createView(stack, VK_IMAGE_VIEW_TYPE_2D, 1, 1);
            }
        }
        // starts out ready to sample (undefined contents, like glTexStorage)
        VkCommandBuffer c = device.outside();
        SteinVkDevice.layout(
                c, image, aspect, 0, desc.mips, desc.layers, VK_IMAGE_LAYOUT_UNDEFINED, REST);
        layout = REST;
    }

    private long createView(MemoryStack stack, int type, int mips, int layers) {
        VkImageViewCreateInfo info =
                VkImageViewCreateInfo.calloc(stack)
                        .sType$Default()
                        .image(image)
                        .viewType(type)
                        .format(format)
                        .subresourceRange(
                                r ->
                                        r.aspectMask(aspect)
                                                .baseMipLevel(0)
                                                .levelCount(mips)
                                                .baseArrayLayer(0)
                                                .layerCount(layers));
        LongBuffer lp = stack.mallocLong(1);
        SteinVkDevice.check(
                vkCreateImageView(device.vk(), info, null, lp), "view of '" + desc.name + "'");
        return lp.get(0);
    }

    long image() {
        return image;
    }

    /** The sampling view. */
    long view() {
        return view;
    }

    /** The drawing view (targets only). */
    long attachView() {
        return attachView;
    }

    int format() {
        return format;
    }

    int aspect() {
        return aspect;
    }

    /** Takes the whole image from its current layout to {@code to}. */
    void toLayout(VkCommandBuffer c, int to) {
        if (layout != to) {
            SteinVkDevice.layout(c, image, aspect, 0, desc.mips, desc.layers, layout, to);
            layout = to;
        }
    }

    /**
     * For drawing: with {@code discard} the previous contents do not matter (the pass clears or
     * covers everything) and the transition starts from undefined, without the card preserving
     * anything.
     */
    void toAttachment(VkCommandBuffer c, int to, boolean discard) {
        // only discards when the drawing view is the whole image: the other levels and layers
        // keep what they had
        if (discard && desc.mips == 1 && desc.layers == 1) {
            SteinVkDevice.layout(
                    c, image, aspect, 0, desc.mips, desc.layers, VK_IMAGE_LAYOUT_UNDEFINED, to);
            layout = to;
        } else {
            toLayout(c, to);
        }
    }

    /** Goes back to rest. */
    void toRest(VkCommandBuffer c) {
        toLayout(c, REST);
    }

    @Override
    public long handle() {
        return image;
    }

    @Override
    public void upload(int layer, int level, int x, int y, int w, int h, ByteBuffer pixels) {
        if (desc.format.isDepth()) {
            throw new UnsupportedOperationException("vulkan: pixel upload to a depth texture");
        }
        int texel = desc.format.bytes;
        long bytes = (long) w * h * texel;
        if (pixels.remaining() < bytes) {
            throw new IllegalArgumentException(
                    "upload of '"
                            + desc.name
                            + "': "
                            + pixels.remaining()
                            + " bytes, "
                            + bytes
                            + " expected");
        }
        SteinVkDevice.StagingSlice s = device.stage(bytes, Math.max(4, texel));
        MemoryUtil.memCopy(MemoryUtil.memAddress(pixels), s.address(), bytes);
        VkCommandBuffer c = device.outside();
        toLayout(c, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
            region.get(0)
                    .bufferOffset(s.offset())
                    .imageSubresource(
                            r ->
                                    r.aspectMask(aspect)
                                            .mipLevel(level)
                                            .baseArrayLayer(layer)
                                            .layerCount(1))
                    .imageOffset(o -> o.set(x, y, 0))
                    .imageExtent(e -> e.set(w, h, 1));
            vkCmdCopyBufferToImage(
                    c, s.buffer(), image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
        }
        toRest(c);
    }

    @Override
    public void generateMips() {
        if (desc.mips <= 1) {
            return;
        }
        VkCommandBuffer c = device.outside();
        toLayout(c, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        int filter =
                device.linearFilter(format) && !desc.format.isDepth()
                        ? VK_FILTER_LINEAR
                        : VK_FILTER_NEAREST;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageBlit.Buffer blit = VkImageBlit.calloc(1, stack);
            int w = desc.width;
            int h = desc.height;
            for (int level = 1; level < desc.mips; level++) {
                // the upper level becomes the source; the lower one remains the destination
                SteinVkDevice.layout(
                        c,
                        image,
                        aspect,
                        level - 1,
                        1,
                        desc.layers,
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
                int sw = w;
                int sh = h;
                int dw = Math.max(1, w / 2);
                int dh = Math.max(1, h / 2);
                int src = level - 1;
                int dst = level;
                blit.get(0)
                        .srcSubresource(
                                r ->
                                        r.aspectMask(aspect)
                                                .mipLevel(src)
                                                .baseArrayLayer(0)
                                                .layerCount(desc.layers))
                        .dstSubresource(
                                r ->
                                        r.aspectMask(aspect)
                                                .mipLevel(dst)
                                                .baseArrayLayer(0)
                                                .layerCount(desc.layers));
                blit.get(0).srcOffsets(0).set(0, 0, 0);
                blit.get(0).srcOffsets(1).set(sw, sh, 1);
                blit.get(0).dstOffsets(0).set(0, 0, 0);
                blit.get(0).dstOffsets(1).set(dw, dh, 1);
                vkCmdBlitImage(
                        c,
                        image,
                        VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        image,
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        blit,
                        filter);
                w = dw;
                h = dh;
            }
        }
        // levels 0..n-2 are the source, the last one the destination: all back to rest
        SteinVkDevice.layout(
                c,
                image,
                aspect,
                0,
                desc.mips - 1,
                desc.layers,
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                REST);
        SteinVkDevice.layout(
                c,
                image,
                aspect,
                desc.mips - 1,
                1,
                desc.layers,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                REST);
        layout = REST;
    }

    @Override
    public void destroy() {
        if (image == VK_NULL_HANDLE) {
            return;
        }
        long i = image;
        long v = view;
        long av = attachView;
        SteinVkDevice.Allocation m = mem;
        image = VK_NULL_HANDLE;
        device.retire(
                () -> {
                    if (av != VK_NULL_HANDLE) {
                        vkDestroyImageView(device.vk(), av, null);
                    }
                    vkDestroyImageView(device.vk(), v, null);
                    vkDestroyImage(device.vk(), i, null);
                    device.free(m);
                });
    }
}
