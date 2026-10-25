/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx.vk;

import static org.lwjgl.vulkan.VK10.VK_API_VERSION_MINOR;
import static org.lwjgl.vulkan.VK10.VK_COMMAND_BUFFER_LEVEL_PRIMARY;
import static org.lwjgl.vulkan.VK10.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
import static org.lwjgl.vulkan.VK10.VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_COMMAND_POOL_CREATE_TRANSIENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_X8_D24_UNORM_PACK32;
import static org.lwjgl.vulkan.VK10.VK_NULL_HANDLE;
import static org.lwjgl.vulkan.VK10.VK_SHARING_MODE_EXCLUSIVE;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;
import static org.lwjgl.vulkan.VK10.VK_TIMEOUT;
import static org.lwjgl.vulkan.VK10.vkAllocateCommandBuffers;
import static org.lwjgl.vulkan.VK10.vkBeginCommandBuffer;
import static org.lwjgl.vulkan.VK10.vkBindBufferMemory;
import static org.lwjgl.vulkan.VK10.vkCreateBuffer;
import static org.lwjgl.vulkan.VK10.vkCreateCommandPool;
import static org.lwjgl.vulkan.VK10.vkCreateDevice;
import static org.lwjgl.vulkan.VK10.vkCreatePipelineCache;
import static org.lwjgl.vulkan.VK10.vkCreateSemaphore;
import static org.lwjgl.vulkan.VK10.vkDestroyCommandPool;
import static org.lwjgl.vulkan.VK10.vkDestroyDevice;
import static org.lwjgl.vulkan.VK10.vkDestroyPipelineCache;
import static org.lwjgl.vulkan.VK10.vkDestroySemaphore;
import static org.lwjgl.vulkan.VK10.vkDeviceWaitIdle;
import static org.lwjgl.vulkan.VK10.vkEndCommandBuffer;
import static org.lwjgl.vulkan.VK10.vkGetBufferMemoryRequirements;
import static org.lwjgl.vulkan.VK10.vkGetDeviceQueue;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceFormatProperties;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceMemoryProperties;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceProperties;
import static org.lwjgl.vulkan.VK10.vkQueueSubmit;
import static org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceFeatures2;
import static org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceProperties2;
import static org.lwjgl.vulkan.VK12.VK_SEMAPHORE_TYPE_TIMELINE;
import static org.lwjgl.vulkan.VK12.vkGetSemaphoreCounterValue;
import static org.lwjgl.vulkan.VK12.vkWaitSemaphores;

import dev.xavier.stein.bridge.core.Log;
import dev.xavier.stein.gfx.SteinBuffer;
import dev.xavier.stein.gfx.SteinCaps;
import dev.xavier.stein.gfx.SteinCommands;
import dev.xavier.stein.gfx.SteinDevice;
import dev.xavier.stein.gfx.SteinFence;
import dev.xavier.stein.gfx.SteinFormat;
import dev.xavier.stein.gfx.SteinPipeline;
import dev.xavier.stein.gfx.SteinRenderPass;
import dev.xavier.stein.gfx.SteinRenderTarget;
import dev.xavier.stein.gfx.SteinSampler;
import dev.xavier.stein.gfx.SteinTexture;
import dev.xavier.stein.gfx.game.SteinGameFramebuffers;
import dev.xavier.stein.gfx.game.SteinGameTexture;
import dev.xavier.stein.gfx.game.SteinGameTextures;

import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFWVulkan;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.EXTDepthClipControl;
import org.lwjgl.vulkan.KHRDynamicRendering;
import org.lwjgl.vulkan.KHRPortabilitySubset;
import org.lwjgl.vulkan.KHRPushDescriptor;
import org.lwjgl.vulkan.KHRSurface;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkDeviceQueueCreateInfo;
import org.lwjgl.vulkan.VkFormatProperties;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceDepthClipControlFeaturesEXT;
import org.lwjgl.vulkan.VkPhysicalDeviceDynamicRenderingFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.lwjgl.vulkan.VkPhysicalDeviceLimits;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;
import org.lwjgl.vulkan.VkPhysicalDevicePushDescriptorPropertiesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Features;
import org.lwjgl.vulkan.VkPipelineCacheCreateInfo;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkRenderingInfo;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreTypeCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreWaitInfo;
import org.lwjgl.vulkan.VkSubmitInfo;
import org.lwjgl.vulkan.VkTimelineSemaphoreSubmitInfo;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Set;

/**
 * The Vulkan backend of Stein's graphics API. It implements the same API as {@code SteinGlDevice};
 * the renderer does not know which of the two is underneath.
 *
 * <p>What on OpenGL runs immediately is recorded here into a single command buffer, the one of the
 * current submission ({@link #cmd()}): copies, staging uploads, layout changes and the passes, in
 * the order they were requested — the same order OpenGL would give. The submission ({@link
 * #submit()}) happens when someone needs to wait for the GPU (a fence being waited on, a pixel
 * read) and, once the API has a frame, at frame delivery. Each submission signals the next value of
 * a timeline semaphore: fences, staging, command buffers and destroyed resources are released by
 * that value.
 *
 * <p>OpenGL conventions kept so the renderer and the shaders stay the same: the image sits in
 * memory in the same row order as in OpenGL (without flipping the viewport; row 0 is the one at NDC
 * y = -1), so viewport, scissor, pixel reads and sampling of what was drawn use the same numbers.
 * The front-face winding flips because of this (OpenGL's counter-clockwise is clockwise here), and
 * the NDC depth stays from -1 to 1 through VK_EXT_depth_clip_control. Without that extension, the
 * near half of the view volume is clipped: the warning goes to the log.
 *
 * <p>Resting state of textures: outside an operation, every image is in {@code
 * SHADER_READ_ONLY_OPTIMAL}. Whoever changes the layout (upload, copy, pass) returns it to that
 * when done. Wide barriers (all stages, all memory): correct first; tune after measuring.
 *
 * <p>With a window ({@link #SteinVkDevice(long, Path)}) the device presents: the frame's work goes
 * in one submission at {@link #present} ({@link SteinVkSwapchain}). Without one (the self-test) it
 * only draws offscreen.
 *
 * <p>Pipeline: Stein GFX only (the Vulkan backend).
 */
public final class SteinVkDevice extends SteinDevice {

    /**
     * Vulkan SDK validation layers ({@code -Dsteinbridge.gfx.vkValidation=true}; needs the SDK
     * installed).
     */
    static final boolean VALIDATION = SteinVkOptions.VALIDATION;

    private final VkInstance instance;

    /** The window's surface, or 0 offscreen. */
    private final long surface;

    private SteinVkSwapchain swapchain;

    /** The instance and the validation messenger ({@link SteinVkInstance}). */
    private final SteinVkInstance vkInstance;

    private final VkPhysicalDevice physical;
    private final VkDevice device;
    private final VkQueue queue;
    private final int family;
    private final SteinCaps caps;
    private final VkPhysicalDeviceMemoryProperties memory;

    /** Device memory, shared in blocks by the small resources. */
    private final SteinVkMemory heap;

    /** The device is 1.3 (core dynamic rendering) or 1.2 with VK_KHR_dynamic_rendering. */
    final boolean core13;

    final boolean pushDescriptors;
    final boolean depthClipControl;

    /** Logical operations on the colour (SteinPipeline.LogicOp): an optional feature. */
    final boolean logicOp;

    /** Lines wider than one pixel (the outline of the targeted block): an optional feature. */
    final boolean wideLines;

    /** The VkFormat of {@link SteinFormat#DEPTH24} on this GPU. */
    final int depth24;

    final boolean multiDrawIndirect;
    final int maxDrawIndirectCount;

    /** How many bindings a push descriptor set accepts (above that the pipeline uses the pool). */
    final int maxPushDescriptors;

    private final long timeline;

    /** The last value submitted; the submission being recorded signals {@code submitted + 1}. */
    private long submitted;

    private long completedCache;
    private final long commandPool;
    private VkCommandBuffer cmd;
    private final ArrayDeque<Pending> inflight = new ArrayDeque<>();
    private final ArrayDeque<VkCommandBuffer> freeCmds = new ArrayDeque<>();
    private final ArrayDeque<Pending> retired = new ArrayDeque<>();
    private final SteinVkStaging staging = new SteinVkStaging(this);
    private final SteinVkDescriptors descriptors = new SteinVkDescriptors(this);
    private final long pipelineCache;
    private final Path pipelineCacheFile;
    private SteinVkCommands openPass;

    /** The pass with its render open, possibly already ended (see {@code SteinVkCommands#end}). */
    private SteinVkCommands renderOwner;

    private final SteinVkDynamic dynamic = new SteinVkDynamic();

    private boolean destroyed;

    private record Pending(long value, VkCommandBuffer cmd, Runnable action) {}

    /** An offscreen device (the self-test): no window, nothing presented. */
    public SteinVkDevice() {
        this(0L, null);
    }

    /**
     * A device that presents to the GLFW {@code window} (created without an OpenGL context), or
     * offscreen with 0. {@code pipelineCacheFile}: where the compiled pipelines are kept between
     * sessions ({@link #savePipelineCache}), or null. Throws when Vulkan is not usable here: no
     * loader, no GPU with Vulkan 1.2 and dynamic rendering, or none that presents to this window.
     */
    public SteinVkDevice(long window, Path pipelineCacheFile) {
        this.pipelineCacheFile = pipelineCacheFile;
        // what was created so far, undone in reverse if a later step throws: the caller then
        // destroys the window (the surface must go first) and falls back to OpenGL
        ArrayDeque<Runnable> undo = new ArrayDeque<>();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            SteinVkInstance vi = SteinVkInstance.create(window);
            undo.push(vi::destroy);
            vkInstance = vi;
            instance = vi.instance;
            int apiVersion = vi.apiVersion;
            PointerBuffer pp = stack.mallocPointer(1);
            if (window != 0L) {
                LongBuffer sp = stack.mallocLong(1);
                check(
                        GLFWVulkan.glfwCreateWindowSurface(instance, window, null, sp),
                        "window surface");
                surface = sp.get(0);
                long s = surface;
                undo.push(() -> KHRSurface.vkDestroySurfaceKHR(vi.instance, s, null));
            } else {
                surface = VK_NULL_HANDLE;
            }

            // ---- GPU ----
            physical = SteinVkProbe.pickPhysical(instance, stack, apiVersion, surface);
            VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc(stack);
            vkGetPhysicalDeviceProperties(physical, props);
            int deviceVersion = Math.min(props.apiVersion(), apiVersion);
            core13 = VK_API_VERSION_MINOR(deviceVersion) >= 3;
            family = SteinVkProbe.graphicsFamily(stack, physical, surface);
            Set<String> devExt = SteinVkProbe.deviceExtensions(stack, physical);
            pushDescriptors =
                    devExt.contains(KHRPushDescriptor.VK_KHR_PUSH_DESCRIPTOR_EXTENSION_NAME)
                            && !SteinVkOptions.NO_PUSH;
            boolean clipExt =
                    devExt.contains(EXTDepthClipControl.VK_EXT_DEPTH_CLIP_CONTROL_EXTENSION_NAME);

            // ---- resources the GPU has ----
            VkPhysicalDeviceDepthClipControlFeaturesEXT clipQ =
                    VkPhysicalDeviceDepthClipControlFeaturesEXT.calloc(stack).sType$Default();
            VkPhysicalDeviceDynamicRenderingFeatures dynQ =
                    VkPhysicalDeviceDynamicRenderingFeatures.calloc(stack)
                            .sType$Default()
                            .pNext(clipExt ? clipQ.address() : 0L);
            VkPhysicalDeviceVulkan12Features v12Q =
                    VkPhysicalDeviceVulkan12Features.calloc(stack)
                            .sType$Default()
                            .pNext(dynQ.address());
            VkPhysicalDeviceFeatures2 f2 =
                    VkPhysicalDeviceFeatures2.calloc(stack).sType$Default().pNext(v12Q.address());
            vkGetPhysicalDeviceFeatures2(physical, f2);
            VkPhysicalDeviceFeatures has = f2.features();
            if (!v12Q.timelineSemaphore() || !dynQ.dynamicRendering()) {
                throw new IllegalStateException(
                        "GPU without timeline semaphores or dynamic rendering");
            }
            depthClipControl = clipExt && clipQ.depthClipControl();
            if (!depthClipControl) {
                Log.info(
                        "gfx",
                        "vulkan: no VK_EXT_depth_clip_control: the near half of OpenGL's"
                                + " NDC depth (-1 to 1) is lost");
            }
            multiDrawIndirect = has.multiDrawIndirect();
            logicOp = has.logicOp();
            wideLines = has.wideLines();

            // ---- device ----
            VkPhysicalDeviceDepthClipControlFeaturesEXT clipOn =
                    VkPhysicalDeviceDepthClipControlFeaturesEXT.calloc(stack)
                            .sType$Default()
                            .depthClipControl(true);
            VkPhysicalDeviceDynamicRenderingFeatures dynOn =
                    VkPhysicalDeviceDynamicRenderingFeatures.calloc(stack)
                            .sType$Default()
                            .dynamicRendering(true)
                            .pNext(depthClipControl ? clipOn.address() : 0L);
            VkPhysicalDeviceVulkan12Features v12On =
                    VkPhysicalDeviceVulkan12Features.calloc(stack)
                            .sType$Default()
                            .timelineSemaphore(true)
                            .drawIndirectCount(v12Q.drawIndirectCount())
                            .pNext(dynOn.address());
            VkPhysicalDeviceFeatures on =
                    VkPhysicalDeviceFeatures.calloc(stack)
                            .multiDrawIndirect(has.multiDrawIndirect())
                            .drawIndirectFirstInstance(has.drawIndirectFirstInstance())
                            .samplerAnisotropy(has.samplerAnisotropy())
                            .depthClamp(has.depthClamp())
                            .logicOp(has.logicOp())
                            .wideLines(has.wideLines());
            PointerBuffer dext = stack.mallocPointer(8);
            if (!core13) {
                dext.put(stack.UTF8(KHRDynamicRendering.VK_KHR_DYNAMIC_RENDERING_EXTENSION_NAME));
            }
            if (pushDescriptors) {
                dext.put(stack.UTF8(KHRPushDescriptor.VK_KHR_PUSH_DESCRIPTOR_EXTENSION_NAME));
            }
            if (surface != VK_NULL_HANDLE) {
                dext.put(stack.UTF8(KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME));
            }
            if (depthClipControl) {
                dext.put(stack.UTF8(EXTDepthClipControl.VK_EXT_DEPTH_CLIP_CONTROL_EXTENSION_NAME));
            }
            if (devExt.contains(KHRPortabilitySubset.VK_KHR_PORTABILITY_SUBSET_EXTENSION_NAME)) {
                dext.put(stack.UTF8(KHRPortabilitySubset.VK_KHR_PORTABILITY_SUBSET_EXTENSION_NAME));
            }
            dext.flip();
            VkDeviceQueueCreateInfo.Buffer qci = VkDeviceQueueCreateInfo.calloc(1, stack);
            qci.get(0).sType$Default().queueFamilyIndex(family).pQueuePriorities(stack.floats(1f));
            VkDeviceCreateInfo dci =
                    VkDeviceCreateInfo.calloc(stack)
                            .sType$Default()
                            .pNext(v12On.address())
                            .pQueueCreateInfos(qci)
                            .ppEnabledExtensionNames(dext)
                            .pEnabledFeatures(on);
            check(vkCreateDevice(physical, dci, null, pp), "device");
            device = new VkDevice(pp.get(0), physical, dci, deviceVersion);
            VkDevice d = device;
            undo.push(() -> vkDestroyDevice(d, null));
            vkGetDeviceQueue(device, family, 0, pp);
            queue = new VkQueue(pp.get(0), device);

            memory = VkPhysicalDeviceMemoryProperties.calloc();
            undo.push(memory::free);
            vkGetPhysicalDeviceMemoryProperties(physical, memory);
            heap = new SteinVkMemory(device, memory);
            depth24 =
                    depthAttachable(stack, VK_FORMAT_X8_D24_UNORM_PACK32)
                            ? VK_FORMAT_X8_D24_UNORM_PACK32
                            : VK_FORMAT_D32_SFLOAT;

            VkPhysicalDeviceLimits limits = props.limits();
            // uint32: on AMD and NVIDIA it is 0xFFFFFFFF, which in a Java int is -1
            int mdi = limits.maxDrawIndirectCount();
            maxDrawIndirectCount = mdi <= 0 ? Integer.MAX_VALUE : mdi;
            if (pushDescriptors) {
                VkPhysicalDevicePushDescriptorPropertiesKHR pdp =
                        VkPhysicalDevicePushDescriptorPropertiesKHR.calloc(stack).sType$Default();
                vkGetPhysicalDeviceProperties2(
                        physical,
                        VkPhysicalDeviceProperties2.calloc(stack)
                                .sType$Default()
                                .pNext(pdp.address()));
                maxPushDescriptors = pdp.maxPushDescriptors();
            } else {
                maxPushDescriptors = 0;
            }
            caps =
                    new SteinCaps(
                            "Vulkan " + SteinVkProbe.version(deviceVersion),
                            props.deviceNameString(),
                            true,
                            // the renderer's indirect commands carry a base instance: without
                            // it in the indirect command (an optional feature here, implied by
                            // GL 4.3 there) it draws directly
                            has.multiDrawIndirect() && has.drawIndirectFirstInstance(),
                            has.drawIndirectFirstInstance(),
                            true,
                            true,
                            true,
                            true,
                            v12Q.drawIndirectCount(),
                            has.samplerAnisotropy() ? limits.maxSamplerAnisotropy() : 1f,
                            limits.maxImageArrayLayers(),
                            limits.maxImageDimension2D(),
                            (int) Math.max(1L, limits.minUniformBufferOffsetAlignment()));

            // ---- timeline, commands, pipeline cache ----
            VkSemaphoreTypeCreateInfo type =
                    VkSemaphoreTypeCreateInfo.calloc(stack)
                            .sType$Default()
                            .semaphoreType(VK_SEMAPHORE_TYPE_TIMELINE)
                            .initialValue(0L);
            LongBuffer lp = stack.mallocLong(1);
            check(
                    vkCreateSemaphore(
                            device,
                            VkSemaphoreCreateInfo.calloc(stack)
                                    .sType$Default()
                                    .pNext(type.address()),
                            null,
                            lp),
                    "semaphore");
            timeline = lp.get(0);
            long t = timeline;
            undo.push(() -> vkDestroySemaphore(d, t, null));
            check(
                    vkCreateCommandPool(
                            device,
                            VkCommandPoolCreateInfo.calloc(stack)
                                    .sType$Default()
                                    .flags(
                                            VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT
                                                    | VK_COMMAND_POOL_CREATE_TRANSIENT_BIT)
                                    .queueFamilyIndex(family),
                            null,
                            lp),
                    "command pool");
            commandPool = lp.get(0);
            long pool = commandPool;
            undo.push(() -> vkDestroyCommandPool(d, pool, null));
            // the last session's pipelines: the driver checks the header (vendor, device, driver)
            // and starts empty when the data is not its own
            ByteBuffer saved = SteinVkPipelineCacheFile.read(pipelineCacheFile);
            VkPipelineCacheCreateInfo pci = VkPipelineCacheCreateInfo.calloc(stack).sType$Default();
            if (saved != null) {
                pci.pInitialData(saved);
            }
            int made = vkCreatePipelineCache(device, pci, null, lp);
            if (made != VK_SUCCESS && saved != null) {
                made =
                        vkCreatePipelineCache(
                                device,
                                VkPipelineCacheCreateInfo.calloc(stack).sType$Default(),
                                null,
                                lp);
            }
            if (saved != null) {
                MemoryUtil.memFree(saved);
            }
            check(made, "pipeline cache");
            pipelineCache = lp.get(0);
            long cache = pipelineCache;
            undo.push(() -> vkDestroyPipelineCache(d, cache, null));
            if (surface != VK_NULL_HANDLE) {
                swapchain = new SteinVkSwapchain(this, surface, window);
            }
        } catch (RuntimeException | LinkageError e) {
            while (!undo.isEmpty()) {
                undo.pop().run();
            }
            throw e;
        }
        Log.info(
                "gfx",
                "vulkan: push descriptor "
                        + pushDescriptors
                        + ", depth clip control "
                        + depthClipControl
                        + ", DEPTH24 "
                        + (depth24 == VK_FORMAT_D32_SFLOAT ? "as D32F" : "X8_D24"));
    }

    static void check(int result, String what) {
        if (result != VK_SUCCESS) {
            throw new IllegalStateException("vulkan: " + what + " failed (" + result + ")");
        }
    }

    private boolean depthAttachable(MemoryStack stack, int format) {
        VkFormatProperties fp = VkFormatProperties.calloc(stack);
        vkGetPhysicalDeviceFormatProperties(physical, format, fp);
        // linear too: LINEAR or comparison (shadow) sampler on depth
        int need =
                VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT
                        | VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT
                        | VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT;
        return (fp.optimalTilingFeatures() & need) == need;
    }

    /**
     * Whether the format filters linearly in sampling and in blit (the mipmaps: otherwise the blit
     * goes with NEAREST).
     */
    boolean linearFilter(int format) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkFormatProperties fp = VkFormatProperties.calloc(stack);
            vkGetPhysicalDeviceFormatProperties(physical, format, fp);
            return (fp.optimalTilingFeatures() & VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT)
                    != 0;
        }
    }

    // ---- resource access ----

    VkDevice vk() {
        return device;
    }

    VkPhysicalDevice physical() {
        return physical;
    }

    VkQueue queue() {
        return queue;
    }

    long pipelineCache() {
        return pipelineCache;
    }

    @Override
    public SteinCaps caps() {
        return caps;
    }

    // ---- memory ----

    /**
     * Memory for {@code req} with {@code required}, first trying {@code required | preferred}
     * ({@link SteinVkMemory}); {@code image}: for an image.
     */
    SteinVkMemory.Allocation allocate(
            VkMemoryRequirements req, int required, int preferred, boolean image, String name) {
        return heap.allocate(req, required, preferred, image, name);
    }

    void free(SteinVkMemory.Allocation a) {
        heap.free(a);
    }

    /** Raw buffer with its memory: {@code [buffer]} and the allocation in {@code out}. */
    long createRawBuffer(
            long size,
            int usage,
            int required,
            int preferred,
            String name,
            SteinVkMemory.Allocation[] out) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer lp = stack.mallocLong(1);
            check(
                    vkCreateBuffer(
                            device,
                            VkBufferCreateInfo.calloc(stack)
                                    .sType$Default()
                                    .size(size)
                                    .usage(usage)
                                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE),
                            null,
                            lp),
                    "buffer '" + name + "'");
            long buffer = lp.get(0);
            VkMemoryRequirements req = VkMemoryRequirements.calloc(stack);
            vkGetBufferMemoryRequirements(device, buffer, req);
            SteinVkMemory.Allocation a = allocate(req, required, preferred, false, name);
            check(
                    vkBindBufferMemory(device, buffer, a.memory, a.offset),
                    "bind memory of '" + name + "'");
            out[0] = a;
            return buffer;
        }
    }

    // ---- submission and timeline ----

    /** The current submission's command buffer (starts one if there is none). */
    VkCommandBuffer cmd() {
        if (cmd == null) {
            VkCommandBuffer c = freeCmds.poll();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                if (c == null) {
                    PointerBuffer pp = stack.mallocPointer(1);
                    check(
                            vkAllocateCommandBuffers(
                                    device,
                                    VkCommandBufferAllocateInfo.calloc(stack)
                                            .sType$Default()
                                            .commandPool(commandPool)
                                            .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                                            .commandBufferCount(1),
                                    pp),
                            "command buffer");
                    c = new VkCommandBuffer(pp.get(0), device);
                }
                dynamic.forget(); // a begun command buffer holds no dynamic state yet
                check(
                        vkBeginCommandBuffer(
                                c,
                                VkCommandBufferBeginInfo.calloc(stack)
                                        .sType$Default()
                                        .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)),
                        "begin command buffer");
            }
            cmd = c;
        }
        return cmd;
    }

    /**
     * The command buffer outside a render pass: copies and layout changes cannot sit inside an open
     * pass, so it is suspended (the render closes and reopens, keeping what was already drawn on
     * the next draw).
     */
    VkCommandBuffer outside() {
        if (openPass != null) {
            openPass.suspend();
        }
        if (renderOwner != null) {
            renderOwner.suspend(); // the render left open by the last pass
        }
        return cmd();
    }

    /** The dynamic state the command buffer holds, for {@code c}. */
    SteinVkDynamic dynamic(VkCommandBuffer c) {
        return dynamic.of(c);
    }

    /** The pass whose render is open now (its target's images are attachments), or null. */
    SteinVkCommands renderOwner() {
        return renderOwner;
    }

    void renderOwnerIs(SteinVkCommands pass) {
        renderOwner = pass;
    }

    /** The value that the submission being recorded will signal. */
    long recordingValue() {
        return submitted + 1;
    }

    /**
     * Submits what was recorded (the open pass is suspended and continues in a new command buffer).
     * Public for whoever wants the GPU working before waiting for it; once the API has a frame,
     * delivery submits.
     */
    public void submit() {
        submit(VK_NULL_HANDLE, 0, VK_NULL_HANDLE);
    }

    /**
     * Submits what was recorded, waiting for the binary semaphore {@code wait} (at {@code
     * waitStage}) before it and signalling {@code signal} after it, besides the timeline (the
     * swapchain image: acquired before, presented after). Either may be 0. Returns the timeline
     * value the submission signals.
     */
    long submit(long wait, int waitStage, long signal) {
        if (openPass != null) {
            openPass.suspendForSubmit();
        }
        if (renderOwner != null) {
            renderOwner.suspendForSubmit();
        }
        VkCommandBuffer c = cmd();
        check(vkEndCommandBuffer(c), "end command buffer");
        long value = submitted + 1;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // the value of a binary semaphore is ignored, but the counts must match
            boolean two = signal != VK_NULL_HANDLE;
            VkTimelineSemaphoreSubmitInfo t =
                    VkTimelineSemaphoreSubmitInfo.calloc(stack)
                            .sType$Default()
                            .pSignalSemaphoreValues(
                                    two ? stack.longs(value, 0L) : stack.longs(value));
            VkSubmitInfo s =
                    VkSubmitInfo.calloc(stack)
                            .sType$Default()
                            .pNext(t.address())
                            .pCommandBuffers(stack.pointers(c))
                            .pSignalSemaphores(
                                    two ? stack.longs(timeline, signal) : stack.longs(timeline));
            if (wait != VK_NULL_HANDLE) {
                s.waitSemaphoreCount(1)
                        .pWaitSemaphores(stack.longs(wait))
                        .pWaitDstStageMask(stack.ints(waitStage));
            }
            check(vkQueueSubmit(queue, s, VK_NULL_HANDLE), "submit");
        }
        inflight.add(new Pending(value, c, null));
        submitted = value;
        cmd = null;
        descriptors.onSubmit(value);
        reclaim();
        return value;
    }

    /** The timeline counter (how far the GPU has finished). */
    long completed() {
        if (completedCache < submitted) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                LongBuffer lp = stack.mallocLong(1);
                check(vkGetSemaphoreCounterValue(device, timeline, lp), "counter");
                completedCache = lp.get(0);
            }
        }
        return completedCache;
    }

    /**
     * Waits for the GPU to reach {@code value} (submits first, if the value is the one of the
     * submission being recorded).
     */
    boolean waitValue(long value, long nanos) {
        if (value > submitted) {
            submit();
        }
        if (completed() >= value) {
            return true;
        }
        int r;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSemaphoreWaitInfo info =
                    VkSemaphoreWaitInfo.calloc(stack)
                            .sType$Default()
                            .semaphoreCount(1)
                            .pSemaphores(stack.longs(timeline))
                            .pValues(stack.longs(value));
            r = vkWaitSemaphores(device, info, nanos);
        }
        if (r != VK_SUCCESS && r != VK_TIMEOUT) {
            check(r, "timeline wait");
        }
        completedCache = 0L;
        reclaim();
        return r == VK_SUCCESS;
    }

    /**
     * Releases what the GPU has finished using: command buffers, destroyed resources, staging,
     * descriptors.
     */
    private void reclaim() {
        long done = completed();
        while (!inflight.isEmpty() && inflight.peek().value <= done) {
            freeCmds.add(inflight.poll().cmd);
        }
        while (!retired.isEmpty() && retired.peek().value <= done) {
            retired.poll().action.run();
        }
        staging.reclaim(done);
        descriptors.reclaim(done);
    }

    /**
     * Runs {@code action} when the GPU finishes everything that was already requested (the
     * destruction of a resource).
     */
    void retire(Runnable action) {
        if (destroyed) {
            action.run();
            return;
        }
        retired.add(new Pending(recordingValue(), null, action));
    }

    void passOpened(SteinVkCommands pass) {
        if (openPass != null) {
            throw new IllegalStateException(
                    "pass '" + pass.name() + "' opened inside '" + openPass.name() + "'");
        }
        openPass = pass;
    }

    void passEnded(SteinVkCommands pass) {
        if (openPass == pass) {
            openPass = null;
        }
    }

    // ---- staging ----

    /** A staging slice: the buffer, the offset in it and the CPU address. */
    record StagingSlice(long buffer, long offset, long address) {}

    /** Requests {@code bytes} of staging (aligned) for an upload recorded now. */
    StagingSlice stage(long bytes, long alignment) {
        return staging.alloc(bytes, alignment);
    }

    // ---- descriptors (without push descriptor: one pool per submission) ----

    /**
     * A descriptor set of {@code layout} valid until the end of the current submission (only
     * without push descriptor).
     */
    long descriptorSet(long layout) {
        return descriptors.allocate(layout);
    }

    // ---- resources ----

    @Override
    public SteinBuffer createBuffer(SteinBuffer.Desc desc) {
        return new SteinVkBuffer(this, desc);
    }

    @Override
    public SteinTexture createTexture(SteinTexture.Desc desc) {
        return new SteinVkTexture(this, desc);
    }

    @Override
    public SteinSampler createSampler(SteinSampler.Desc desc) {
        return new SteinVkSampler(this, desc);
    }

    /** Samplers, a black texture and a zero buffer for the slots with nothing bound. */
    final SteinVkDefaults defaults = new SteinVkDefaults(this);

    /**
     * The game's texture of this name ({@link SteinGameTextures}): there is no OpenGL here, the
     * game's textures already live on the API. Binding it binds its current pixels with its own
     * sampler.
     */
    @Override
    public SteinTexture importTexture(SteinTexture.Desc desc, long handle) {
        return SteinGameTextures.reference((int) handle);
    }

    @Override
    public boolean copiesGameTextures() {
        return SteinVkOptions.GAME_COPY;
    }

    /** The texture with the pixels: a game texture's current backing, or {@code t} itself. */
    static SteinVkTexture resolve(SteinTexture t) {
        if (t instanceof SteinGameTexture g) {
            return (SteinVkTexture) g.backing();
        }
        return (SteinVkTexture) t;
    }

    @Override
    public void copyTexture(
            SteinTexture src, int srcLayer, SteinTexture dst, int dstLayer, int w, int h) {
        SteinVkTransfers.copyTexture(this, src, srcLayer, dst, dstLayer, w, h);
    }

    @Override
    public void copyBuffer(
            SteinBuffer src, long srcOffset, SteinBuffer dst, long dstOffset, long bytes) {
        SteinVkTransfers.copyBuffer(this, src, srcOffset, dst, dstOffset, bytes);
    }

    @Override
    public SteinPipeline createPipeline(SteinPipeline.Desc desc) {
        return new SteinVkPipeline(this, desc);
    }

    @Override
    public SteinRenderTarget createTarget(SteinTexture[] colors, SteinTexture depth) {
        return new SteinVkRenderTarget(colors, depth);
    }

    /** The framebuffer the game has bound, or its screen ({@link SteinGameFramebuffers}). */
    @Override
    public SteinRenderTarget gameTarget() {
        return SteinGameFramebuffers.drawTarget();
    }

    // ---- work ----

    @Override
    public SteinCommands beginPass(SteinRenderPass pass) {
        return new SteinVkCommands(this, pass);
    }

    @Override
    public void present(SteinTexture color) {
        if (swapchain == null) {
            submit();
            return;
        }
        swapchain.present((SteinVkTexture) color);
    }

    @Override
    public void vsync(boolean on) {
        if (swapchain != null) {
            swapchain.vsync(on);
        }
    }

    @Override
    public SteinFence fence() {
        return new SteinVkFence(this, recordingValue());
    }

    @Override
    public void readBuffer(SteinBuffer src, long offset, ByteBuffer out) {
        SteinVkTransfers.readBuffer(this, src, offset, out);
    }

    @Override
    public void readPixels(SteinTexture texture, int x, int y, int w, int h, ByteBuffer out) {
        SteinVkTransfers.readPixels(this, texture, x, y, w, h, out);
    }

    /**
     * Waits for the GPU to finish everything and releases the device (the self-test; in the game,
     * at close).
     */
    public void destroy() {
        if (destroyed) {
            return;
        }
        if (cmd != null) {
            submit();
        }
        vkDeviceWaitIdle(device);
        destroyed = true;
        defaults.destroy();
        completedCache = Long.MAX_VALUE;
        reclaim();
        while (!retired.isEmpty()) {
            retired.poll().action.run();
        }
        staging.destroyAll();
        descriptors.destroyAll();
        heap.destroyAll();
        if (swapchain != null) {
            swapchain.destroy();
            swapchain = null;
        }
        vkDestroyPipelineCache(device, pipelineCache, null);
        vkDestroyCommandPool(device, commandPool, null);
        vkDestroySemaphore(device, timeline, null);
        vkDestroyDevice(device, null);
        if (surface != VK_NULL_HANDLE) {
            KHRSurface.vkDestroySurfaceKHR(instance, surface, null);
        }
        vkInstance.destroy();
        memory.free();
    }

    // ---- pipeline cache on disk ----

    /**
     * Writes the compiled pipelines to the file given at creation (the game's close): the next
     * session creates them without compiling again, without the stutter of the first frames.
     */
    public void savePipelineCache() {
        if (pipelineCacheFile != null && !destroyed) {
            SteinVkPipelineCacheFile.save(device, pipelineCache, pipelineCacheFile);
        }
    }

    // ---- dynamic rendering (core in 1.3, extension in 1.2) ----

    void beginRendering(VkCommandBuffer c, VkRenderingInfo info) {
        if (core13) {
            VK13.vkCmdBeginRendering(c, info);
        } else {
            KHRDynamicRendering.vkCmdBeginRenderingKHR(c, info);
        }
    }

    void endRendering(VkCommandBuffer c) {
        if (core13) {
            VK13.vkCmdEndRendering(c);
        } else {
            KHRDynamicRendering.vkCmdEndRenderingKHR(c);
        }
    }
}
