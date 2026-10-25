/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx.vk;

import static org.lwjgl.glfw.GLFW.glfwGetFramebufferSize;
import static org.lwjgl.vulkan.KHRSurface.VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
import static org.lwjgl.vulkan.KHRSurface.VK_PRESENT_MODE_FIFO_KHR;
import static org.lwjgl.vulkan.KHRSurface.VK_PRESENT_MODE_FIFO_RELAXED_KHR;
import static org.lwjgl.vulkan.KHRSurface.VK_PRESENT_MODE_IMMEDIATE_KHR;
import static org.lwjgl.vulkan.KHRSurface.VK_PRESENT_MODE_MAILBOX_KHR;
import static org.lwjgl.vulkan.KHRSurface.vkGetPhysicalDeviceSurfaceCapabilitiesKHR;
import static org.lwjgl.vulkan.KHRSurface.vkGetPhysicalDeviceSurfaceFormatsKHR;
import static org.lwjgl.vulkan.KHRSurface.vkGetPhysicalDeviceSurfacePresentModesKHR;
import static org.lwjgl.vulkan.KHRSwapchain.VK_ERROR_OUT_OF_DATE_KHR;
import static org.lwjgl.vulkan.KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
import static org.lwjgl.vulkan.KHRSwapchain.VK_SUBOPTIMAL_KHR;
import static org.lwjgl.vulkan.KHRSwapchain.vkAcquireNextImageKHR;
import static org.lwjgl.vulkan.KHRSwapchain.vkCreateSwapchainKHR;
import static org.lwjgl.vulkan.KHRSwapchain.vkDestroySwapchainKHR;
import static org.lwjgl.vulkan.KHRSwapchain.vkGetSwapchainImagesKHR;
import static org.lwjgl.vulkan.KHRSwapchain.vkQueuePresentKHR;
import static org.lwjgl.vulkan.VK10.VK_FILTER_LINEAR;
import static org.lwjgl.vulkan.VK10.VK_FILTER_NEAREST;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_B8G8R8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_FEATURE_BLIT_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_NULL_HANDLE;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_TRANSFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHARING_MODE_EXCLUSIVE;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;
import static org.lwjgl.vulkan.VK10.vkCmdBlitImage;
import static org.lwjgl.vulkan.VK10.vkCreateSemaphore;
import static org.lwjgl.vulkan.VK10.vkDestroySemaphore;
import static org.lwjgl.vulkan.VK10.vkDeviceWaitIdle;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceFormatProperties;

import dev.xavier.stein.bridge.core.CoreOptions;
import dev.xavier.stein.bridge.core.Log;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkFormatProperties;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkSurfaceCapabilitiesKHR;
import org.lwjgl.vulkan.VkSurfaceFormatKHR;
import org.lwjgl.vulkan.VkSwapchainCreateInfoKHR;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayDeque;

/**
 * The window's swapchain: where the game's screen goes at the end of each frame.
 *
 * <p>The screen is a texture of ours in OpenGL's row order (row 0 at the bottom, see {@link
 * SteinVkDevice}); the swapchain image has row 0 at the top. Presenting is a blit that flips the
 * rows (and scales, in the frame where the window has already changed size and the game has not
 * rebuilt its screen yet), after which the frame's whole work goes in one submission that waits for
 * the acquired image and signals the semaphore the presentation waits for.
 *
 * <p>VSync is the present mode: FIFO with VSync (FIFO_RELAXED when the latency fix is on, the
 * counterpart of OpenGL's adaptive swap interval: a late frame goes out at once instead of waiting
 * a whole refresh), IMMEDIATE without it (MAILBOX, then FIFO, where the driver has no IMMEDIATE).
 * At most {@link #FRAMES} frames in flight: before reusing a slot, the CPU waits for the GPU to
 * finish the frame that used it.
 *
 * <p>Pipeline: Stein GFX only (the Vulkan backend).
 */
final class SteinVkSwapchain {

    /** Frames the CPU may record ahead of the GPU. */
    private static final int FRAMES = 2;

    /**
     * FIFO_RELAXED with VSync; {@code -Dsteinbridge.opt.noVsyncLatencyFix=true} goes back to plain
     * FIFO (the same option as OpenGL's adaptive swap interval).
     */
    private static final boolean LATENCY_FIX = !CoreOptions.NO_VSYNC_LATENCY_FIX;

    /** Presentations an old swapchain and its semaphores are kept for after being replaced. */
    private static final int KEEP_OLD = FRAMES + 1;

    private static final long NO_TIMEOUT = -1L;

    private final SteinVkDevice device;
    private final long surface;
    private final long window;

    private long swapchain = VK_NULL_HANDLE;
    private long[] images = new long[0];
    private long[] presentReady = new long[0];
    private int width;
    private int height;
    private int format;
    private boolean blitLinear;

    private final long[] acquired = new long[FRAMES];
    private final long[] frameValue = new long[FRAMES];
    private int slot;

    private boolean vsync = true;
    private boolean stale = true;
    private int modeLogged = -1;

    /** The last surface query was refused (logged once per refusal). */
    private boolean capsRefused;

    /** The window's size, asked once per frame (reused: no allocation per frame). */
    private final int[] sizeW = new int[1];

    private final int[] sizeH = new int[1];

    /**
     * Swapchains replaced and the semaphores of their images, with the presentation count when they
     * were: a present may still wait on them after the device goes idle (nothing in Vulkan 1.2 says
     * when it is done), so they go a few presentations later.
     */
    private final ArrayDeque<long[]> old = new ArrayDeque<>();

    private long presents;

    SteinVkSwapchain(SteinVkDevice device, long surface, long window) {
        this.device = device;
        this.surface = surface;
        this.window = window;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            for (int i = 0; i < FRAMES; i++) {
                acquired[i] = semaphore(stack);
            }
        }
    }

    void vsync(boolean on) {
        if (vsync != on) {
            vsync = on;
            stale = true;
        }
    }

    /**
     * Presents {@code screen} and submits the frame. Minimized (no area) or with the window out of
     * date, it only submits: the frame was drawn, there is nowhere to show it.
     */
    void present(SteinVkTexture screen) {
        SteinVkDebug.frame();
        // the slot's previous frame must be done before its semaphore is used again
        device.waitValue(frameValue[slot], Long.MAX_VALUE);
        if (stale || sizeChanged()) {
            if (!recreate()) {
                frameValue[slot] = device.submit(VK_NULL_HANDLE, 0, VK_NULL_HANDLE);
                return;
            }
        }
        int index;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer ip = stack.mallocInt(1);
            int r =
                    vkAcquireNextImageKHR(
                            device.vk(), swapchain, NO_TIMEOUT, acquired[slot], VK_NULL_HANDLE, ip);
            if (r == VK_ERROR_OUT_OF_DATE_KHR) {
                stale = true;
                frameValue[slot] = device.submit(VK_NULL_HANDLE, 0, VK_NULL_HANDLE);
                return;
            }
            if (r == VK_SUBOPTIMAL_KHR) {
                stale = true; // still presentable: this frame goes out, the next one recreates
            } else {
                SteinVkDevice.check(r, "acquire swapchain image");
            }
            index = ip.get(0);
        }
        blit(screen, images[index]);
        long ready = presentReady[index];
        frameValue[slot] = device.submit(acquired[slot], VK_PIPELINE_STAGE_TRANSFER_BIT, ready);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPresentInfoKHR info =
                    VkPresentInfoKHR.calloc(stack)
                            .sType$Default()
                            .pWaitSemaphores(stack.longs(ready))
                            .swapchainCount(1)
                            .pSwapchains(stack.longs(swapchain))
                            .pImageIndices(stack.ints(index));
            int r = vkQueuePresentKHR(device.queue(), info);
            if (r == VK_ERROR_OUT_OF_DATE_KHR || r == VK_SUBOPTIMAL_KHR) {
                stale = true;
            } else {
                SteinVkDevice.check(r, "present");
            }
        }
        slot = (slot + 1) % FRAMES;
        presents++;
        while (!old.isEmpty() && presents - old.peek()[0] >= KEEP_OLD) {
            destroyOld(old.poll());
        }
    }

    /** Copies the screen into the swapchain image, flipping the rows. */
    private void blit(SteinVkTexture screen, long image) {
        VkCommandBuffer c = device.outside();
        screen.toLayout(c, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
        SteinVkBarriers.layout(
                c,
                image,
                VK_IMAGE_ASPECT_COLOR_BIT,
                0,
                1,
                1,
                VK_IMAGE_LAYOUT_UNDEFINED,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                // after the presentation engine: chained to the acquire wait (transfer stage)
                VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);
        int sw = screen.width();
        int sh = screen.height();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageBlit.Buffer region = VkImageBlit.calloc(1, stack);
            region.get(0)
                    .srcSubresource(
                            r ->
                                    r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                                            .mipLevel(0)
                                            .baseArrayLayer(0)
                                            .layerCount(1))
                    .dstSubresource(
                            r ->
                                    r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                                            .mipLevel(0)
                                            .baseArrayLayer(0)
                                            .layerCount(1));
            region.get(0).srcOffsets(0).set(0, 0, 0);
            region.get(0).srcOffsets(1).set(sw, sh, 1);
            // OpenGL's row 0 (the bottom) becomes the swapchain's last row
            region.get(0).dstOffsets(0).set(0, height, 0);
            region.get(0).dstOffsets(1).set(width, 0, 1);
            boolean scaled = sw != width || sh != height;
            vkCmdBlitImage(
                    c,
                    screen.image(),
                    VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    image,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    region,
                    scaled && blitLinear ? VK_FILTER_LINEAR : VK_FILTER_NEAREST);
        }
        SteinVkBarriers.layout(
                c,
                image,
                VK_IMAGE_ASPECT_COLOR_BIT,
                0,
                1,
                1,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);
        screen.toRest(c);
    }

    private boolean sizeChanged() {
        glfwGetFramebufferSize(window, sizeW, sizeH);
        return sizeW[0] != width || sizeH[0] != height;
    }

    /**
     * Builds the swapchain for the window's current size and the VSync choice. False when the
     * window has no area (minimized): nothing to present until it comes back.
     */
    private boolean recreate() {
        // minimized: no area to present to, so the surface is not even asked (the query each frame
        // was most of the Vulkan frame's native time)
        glfwGetFramebufferSize(window, sizeW, sizeH);
        if (sizeW[0] <= 0 || sizeH[0] <= 0) {
            return false;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSurfaceCapabilitiesKHR caps = VkSurfaceCapabilitiesKHR.calloc(stack);
            int r = vkGetPhysicalDeviceSurfaceCapabilitiesKHR(device.physical(), surface, caps);
            if (r != VK_SUCCESS) {
                // a minimized window with the display off or the session locked: some drivers
                // refuse to describe its surface. Nothing to present now; the next frame retries.
                if (!capsRefused) {
                    capsRefused = true;
                    Log.warn("gfx", "vulkan: the window's surface is not available (" + r + ")");
                }
                return false;
            }
            capsRefused = false;
            int[] fw = new int[1];
            int[] fh = new int[1];
            glfwGetFramebufferSize(window, fw, fh);
            int w = caps.currentExtent().width();
            int h = caps.currentExtent().height();
            if (w == -1) { // 0xFFFFFFFF: the surface takes the swapchain's size
                w = Math.clamp(fw[0], caps.minImageExtent().width(), caps.maxImageExtent().width());
                h =
                        Math.clamp(
                                fh[0],
                                caps.minImageExtent().height(),
                                caps.maxImageExtent().height());
            }
            if (w <= 0 || h <= 0) {
                return false;
            }
            vkDeviceWaitIdle(device.vk());
            int count = caps.minImageCount() + 1;
            if (caps.maxImageCount() > 0) {
                count = Math.min(count, caps.maxImageCount());
            }
            int mode = presentMode(stack);
            if (mode != modeLogged) {
                modeLogged = mode;
                Log.info(
                        "gfx", "vulkan: present mode " + modeName(mode) + " (vsync " + vsync + ")");
            }
            format = surfaceFormat(stack);
            if (format == VK_FORMAT_UNDEFINED) {
                throw new IllegalStateException("vulkan: no window format that accepts the copy");
            }
            VkSwapchainCreateInfoKHR info =
                    VkSwapchainCreateInfoKHR.calloc(stack)
                            .sType$Default()
                            .surface(surface)
                            .minImageCount(count)
                            .imageFormat(format)
                            .imageColorSpace(0) // SRGB_NONLINEAR, as the window's OpenGL buffer
                            .imageArrayLayers(1)
                            .imageUsage(
                                    VK_IMAGE_USAGE_TRANSFER_DST_BIT
                                            | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT)
                            .imageSharingMode(VK_SHARING_MODE_EXCLUSIVE)
                            .preTransform(caps.currentTransform())
                            .compositeAlpha(compositeAlpha(caps.supportedCompositeAlpha()))
                            .presentMode(mode)
                            .clipped(true)
                            .oldSwapchain(swapchain);
            info.imageExtent().set(w, h);
            LongBuffer lp = stack.mallocLong(1);
            SteinVkDevice.check(vkCreateSwapchainKHR(device.vk(), info, null, lp), "swapchain");
            if (swapchain != VK_NULL_HANDLE) {
                // [presents, swapchain, semaphores...]: destroyed a few presentations later
                long[] gone = new long[2 + presentReady.length];
                gone[0] = presents;
                gone[1] = swapchain;
                System.arraycopy(presentReady, 0, gone, 2, presentReady.length);
                old.add(gone);
            }
            swapchain = lp.get(0);
            width = w;
            height = h;
            IntBuffer n = stack.mallocInt(1);
            vkGetSwapchainImagesKHR(device.vk(), swapchain, n, null);
            LongBuffer list = stack.mallocLong(n.get(0));
            vkGetSwapchainImagesKHR(device.vk(), swapchain, n, list);
            images = new long[n.get(0)];
            presentReady = new long[n.get(0)];
            for (int i = 0; i < images.length; i++) {
                images[i] = list.get(i);
                presentReady[i] = semaphore(stack);
            }
            // the screen is RGBA8: linear when scaling only if that format filters linearly
            blitLinear = device.linearFilter(VK_FORMAT_R8G8B8A8_UNORM);
            stale = false;
            return true;
        }
    }

    /**
     * BGRA8 or RGBA8 UNORM, like the window's OpenGL buffer (no sRGB conversion); otherwise any
     * format that accepts the blit; {@code VK_FORMAT_UNDEFINED} if none does.
     */
    private int surfaceFormat(MemoryStack stack) {
        IntBuffer n = stack.mallocInt(1);
        vkGetPhysicalDeviceSurfaceFormatsKHR(device.physical(), surface, n, null);
        VkSurfaceFormatKHR.Buffer list = VkSurfaceFormatKHR.malloc(n.get(0), stack);
        vkGetPhysicalDeviceSurfaceFormatsKHR(device.physical(), surface, n, list);
        VkFormatProperties fp = VkFormatProperties.calloc(stack);
        int fallback = VK_FORMAT_UNDEFINED;
        for (VkSurfaceFormatKHR f : list) {
            vkGetPhysicalDeviceFormatProperties(device.physical(), f.format(), fp);
            if ((fp.optimalTilingFeatures() & VK_FORMAT_FEATURE_BLIT_DST_BIT) == 0) {
                continue;
            }
            if (f.colorSpace() == 0
                    && (f.format() == VK_FORMAT_B8G8R8A8_UNORM
                            || f.format() == VK_FORMAT_R8G8B8A8_UNORM)) {
                return f.format();
            }
            if (fallback == VK_FORMAT_UNDEFINED) {
                fallback = f.format();
            }
        }
        return fallback;
    }

    private int presentMode(MemoryStack stack) {
        IntBuffer n = stack.mallocInt(1);
        vkGetPhysicalDeviceSurfacePresentModesKHR(device.physical(), surface, n, null);
        IntBuffer modes = stack.mallocInt(n.get(0));
        vkGetPhysicalDeviceSurfacePresentModesKHR(device.physical(), surface, n, modes);
        int[] wanted =
                vsync
                        ? LATENCY_FIX ? new int[] {VK_PRESENT_MODE_FIFO_RELAXED_KHR} : new int[0]
                        : new int[] {VK_PRESENT_MODE_IMMEDIATE_KHR, VK_PRESENT_MODE_MAILBOX_KHR};
        for (int want : wanted) {
            for (int i = 0; i < modes.capacity(); i++) {
                if (modes.get(i) == want) {
                    return want;
                }
            }
        }
        return VK_PRESENT_MODE_FIFO_KHR; // the only one every driver has
    }

    private static String modeName(int mode) {
        return switch (mode) {
            case VK_PRESENT_MODE_IMMEDIATE_KHR -> "IMMEDIATE";
            case VK_PRESENT_MODE_MAILBOX_KHR -> "MAILBOX";
            case VK_PRESENT_MODE_FIFO_RELAXED_KHR -> "FIFO_RELAXED";
            default -> "FIFO";
        };
    }

    private static int compositeAlpha(int supported) {
        if ((supported & VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR) != 0) {
            return VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
        }
        return Integer.lowestOneBit(supported);
    }

    private long semaphore(MemoryStack stack) {
        LongBuffer lp = stack.mallocLong(1);
        SteinVkDevice.check(
                vkCreateSemaphore(
                        device.vk(), VkSemaphoreCreateInfo.calloc(stack).sType$Default(), null, lp),
                "semaphore");
        return lp.get(0);
    }

    private void destroyOld(long[] gone) {
        for (int i = 2; i < gone.length; i++) {
            vkDestroySemaphore(device.vk(), gone[i], null);
        }
        vkDestroySwapchainKHR(device.vk(), gone[1], null);
    }

    /** With the device idle (its {@code destroy}). */
    void destroy() {
        while (!old.isEmpty()) {
            destroyOld(old.poll());
        }
        for (long s : presentReady) {
            vkDestroySemaphore(device.vk(), s, null);
        }
        presentReady = new long[0];
        for (long s : acquired) {
            vkDestroySemaphore(device.vk(), s, null);
        }
        if (swapchain != VK_NULL_HANDLE) {
            vkDestroySwapchainKHR(device.vk(), swapchain, null);
            swapchain = VK_NULL_HANDLE;
        }
    }
}
