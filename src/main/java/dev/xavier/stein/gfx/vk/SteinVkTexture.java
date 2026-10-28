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
import java.util.Arrays;

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
    private SteinVkMemory.Allocation mem;
    private long view;
    private long attachView;

    /**
     * The current layout (recorded; it is what the card will see at this point of the command
     * buffer).
     */
    private int layout = VK_IMAGE_LAYOUT_UNDEFINED;

    /**
     * The submission (its timeline value) whose main command buffer last recorded anything on this
     * image: a layout change, a copy, a draw sampling it. -1 = none yet.
     */
    private long usedIn = -1;

    /**
     * The submission whose early command buffer ({@link SteinVkDevice#early}) holds uploads to this
     * image, in TRANSFER_DST there until the submission ({@link #endEarly}); -1 = none.
     */
    private long earlyIn = -1;

    /** The rectangles uploaded early in {@link #earlyIn}: level, layer, x, y, w, h each. */
    private int[] earlyRects = new int[6 * 16];

    private int earlyRectCount;

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
            mem = device.allocate(req, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, 0, true, desc.name);
            SteinVkDevice.check(
                    vkBindImageMemory(device.vk(), image, mem.memory, mem.offset),
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
        SteinVkBarriers.layout(
                c, image, aspect, 0, desc.mips, desc.layers, VK_IMAGE_LAYOUT_UNDEFINED, REST);
        layout = REST;
        // the creation's transition is in the main buffer: no upload may go before it
        usedIn = device.recordingValue();
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

    /** The main command buffer is recording something on this image now. */
    void noteUse() {
        usedIn = device.recordingValue();
    }

    /** Takes the whole image from its current layout to {@code to}. */
    void toLayout(VkCommandBuffer c, int to) {
        usedIn = device.recordingValue();
        if (layout != to) {
            SteinVkBarriers.layout(c, image, aspect, 0, desc.mips, desc.layers, layout, to);
            layout = to;
        }
    }

    /**
     * For drawing: with {@code discard} the previous contents do not matter (the pass clears or
     * covers everything) and the transition starts from undefined, without the card preserving
     * anything.
     */
    void toAttachment(VkCommandBuffer c, int to, boolean discard) {
        usedIn = device.recordingValue();
        // only discards when the drawing view is the whole image: the other levels and layers
        // keep what they had
        if (discard && desc.mips == 1 && desc.layers == 1) {
            // discarded, but the previous users of the image (in its tracked layout) finish first
            SteinVkBarriers.layout(
                    c,
                    image,
                    aspect,
                    0,
                    desc.mips,
                    desc.layers,
                    VK_IMAGE_LAYOUT_UNDEFINED,
                    to,
                    layout);
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
        long recording = device.recordingValue();
        VkCommandBuffer c;
        boolean early = SteinVkOptions.EARLY_UPLOADS && usedIn != recording && layout == REST;
        if (early) {
            // Nothing recorded so far in this submission touches the image: the copy can run
            // before all of it, in the early command buffer, with no render interrupted (the
            // animated textures, every client tick). Whatever reads it later sees the same pixels.
            c = device.early();
            beginEarly(c, recording, layer, level, x, y, w, h);
        } else {
            c = device.outside();
            toLayout(c, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        }
        copyIn(c, s, layer, level, x, y, w, h);
        if (!early) {
            toRest(c);
        }
    }

    /** The staging slice into the rectangle, the image already in TRANSFER_DST on {@code c}. */
    private void copyIn(
            VkCommandBuffer c,
            SteinVkDevice.StagingSlice s,
            int layer,
            int level,
            int x,
            int y,
            int w,
            int h) {
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
    }

    /**
     * Before an early copy: the first one of the submission takes the image to TRANSFER_DST in the
     * early buffer (one barrier for all of the tick's sprites and levels); a later one over a
     * rectangle already written waits for that copy (two catch-up ticks in one frame).
     */
    private void beginEarly(
            VkCommandBuffer c, long recording, int layer, int level, int x, int y, int w, int h) {
        if (earlyIn != recording) {
            SteinVkBarriers.layout(
                    c,
                    image,
                    aspect,
                    0,
                    desc.mips,
                    desc.layers,
                    REST,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            earlyIn = recording;
            earlyRectCount = 0;
            device.earlyTexture(this);
        } else if (earlyOverlaps(layer, level, x, y, w, h)) {
            SteinVkBarriers.memoryBarrier(c);
            earlyRectCount = 0;
        }
        if ((earlyRectCount + 1) * 6 > earlyRects.length) {
            earlyRects = Arrays.copyOf(earlyRects, earlyRects.length * 2);
        }
        int[] r = earlyRects;
        int k = earlyRectCount++ * 6;
        r[k] = level;
        r[k + 1] = layer;
        r[k + 2] = x;
        r[k + 3] = y;
        r[k + 4] = w;
        r[k + 5] = h;
    }

    private boolean earlyOverlaps(int layer, int level, int x, int y, int w, int h) {
        int[] r = earlyRects;
        for (int i = 0, k = 0; i < earlyRectCount; i++, k += 6) {
            if (r[k] == level
                    && r[k + 1] == layer
                    && x < r[k + 2] + r[k + 4]
                    && r[k + 2] < x + w
                    && y < r[k + 3] + r[k + 5]
                    && r[k + 3] < y + h) {
                return true;
            }
        }
        return false;
    }

    /**
     * At the submission, at the end of the early buffer: the image goes back to rest, which is the
     * layout the main buffer counted on.
     */
    void endEarly(VkCommandBuffer c) {
        if (earlyIn != device.recordingValue() || image == VK_NULL_HANDLE) {
            return;
        }
        SteinVkBarriers.layout(
                c,
                image,
                aspect,
                0,
                desc.mips,
                desc.layers,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                REST);
        earlyIn = -1;
        earlyRectCount = 0;
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
                SteinVkBarriers.layout(
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
        SteinVkBarriers.layout(
                c,
                image,
                aspect,
                0,
                desc.mips - 1,
                desc.layers,
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                REST);
        SteinVkBarriers.layout(
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
        if (earlyIn == device.recordingValue()) {
            endEarly(device.early()); // the image handle is still valid now, not at the submission
        }
        long i = image;
        long v = view;
        long av = attachView;
        SteinVkMemory.Allocation m = mem;
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
