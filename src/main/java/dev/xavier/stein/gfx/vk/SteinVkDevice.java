/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx.vk;

import static org.lwjgl.vulkan.EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_EXT_DEBUG_UTILS_EXTENSION_NAME;
import static org.lwjgl.vulkan.EXTDebugUtils.vkCreateDebugUtilsMessengerEXT;
import static org.lwjgl.vulkan.EXTDebugUtils.vkDestroyDebugUtilsMessengerEXT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_HOST_READ_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_TRANSFER_WRITE_BIT;
import static org.lwjgl.vulkan.VK10.VK_API_VERSION_1_0;
import static org.lwjgl.vulkan.VK10.VK_API_VERSION_MAJOR;
import static org.lwjgl.vulkan.VK10.VK_API_VERSION_MINOR;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_COMMAND_BUFFER_LEVEL_PRIMARY;
import static org.lwjgl.vulkan.VK10.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
import static org.lwjgl.vulkan.VK10.VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_COMMAND_POOL_CREATE_TRANSIENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_ERROR_OUT_OF_DEVICE_MEMORY;
import static org.lwjgl.vulkan.VK10.VK_FALSE;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_X8_D24_UNORM_PACK32;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_HOST_CACHED_BIT;
import static org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT;
import static org.lwjgl.vulkan.VK10.VK_NULL_HANDLE;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_HOST_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_TRANSFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHARING_MODE_EXCLUSIVE;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;
import static org.lwjgl.vulkan.VK10.VK_TIMEOUT;
import static org.lwjgl.vulkan.VK10.VK_WHOLE_SIZE;
import static org.lwjgl.vulkan.VK10.vkAllocateCommandBuffers;
import static org.lwjgl.vulkan.VK10.vkAllocateMemory;
import static org.lwjgl.vulkan.VK10.vkBeginCommandBuffer;
import static org.lwjgl.vulkan.VK10.vkBindBufferMemory;
import static org.lwjgl.vulkan.VK10.vkCmdCopyBuffer;
import static org.lwjgl.vulkan.VK10.vkCmdCopyImage;
import static org.lwjgl.vulkan.VK10.vkCmdCopyImageToBuffer;
import static org.lwjgl.vulkan.VK10.vkCmdPipelineBarrier;
import static org.lwjgl.vulkan.VK10.vkCreateBuffer;
import static org.lwjgl.vulkan.VK10.vkCreateCommandPool;
import static org.lwjgl.vulkan.VK10.vkCreateDevice;
import static org.lwjgl.vulkan.VK10.vkCreateInstance;
import static org.lwjgl.vulkan.VK10.vkCreatePipelineCache;
import static org.lwjgl.vulkan.VK10.vkCreateSemaphore;
import static org.lwjgl.vulkan.VK10.vkDestroyBuffer;
import static org.lwjgl.vulkan.VK10.vkDestroyCommandPool;
import static org.lwjgl.vulkan.VK10.vkDestroyDevice;
import static org.lwjgl.vulkan.VK10.vkDestroyInstance;
import static org.lwjgl.vulkan.VK10.vkDestroyPipelineCache;
import static org.lwjgl.vulkan.VK10.vkDestroySemaphore;
import static org.lwjgl.vulkan.VK10.vkDeviceWaitIdle;
import static org.lwjgl.vulkan.VK10.vkEndCommandBuffer;
import static org.lwjgl.vulkan.VK10.vkFreeMemory;
import static org.lwjgl.vulkan.VK10.vkGetBufferMemoryRequirements;
import static org.lwjgl.vulkan.VK10.vkGetDeviceQueue;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceFormatProperties;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceMemoryProperties;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceProperties;
import static org.lwjgl.vulkan.VK10.vkMapMemory;
import static org.lwjgl.vulkan.VK10.vkQueueSubmit;
import static org.lwjgl.vulkan.VK10.vkUnmapMemory;
import static org.lwjgl.vulkan.VK11.vkEnumerateInstanceVersion;
import static org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceFeatures2;
import static org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceProperties2;
import static org.lwjgl.vulkan.VK12.VK_API_VERSION_1_2;
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

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.EXTDepthClipControl;
import org.lwjgl.vulkan.KHRDynamicRendering;
import org.lwjgl.vulkan.KHRPortabilityEnumeration;
import org.lwjgl.vulkan.KHRPortabilitySubset;
import org.lwjgl.vulkan.KHRPushDescriptor;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkApplicationInfo;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCallbackDataEXT;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCallbackEXT;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCreateInfoEXT;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkDeviceQueueCreateInfo;
import org.lwjgl.vulkan.VkFormatProperties;
import org.lwjgl.vulkan.VkImageCopy;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryBarrier;
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
import java.nio.IntBuffer;
import java.nio.LongBuffer;
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
 * <p>Not yet: frame and presentation (swapchain), the game's target and imported texture (the game
 * draws in OpenGL), on-disk pipeline cache, memory suballocation (each resource has its own
 * allocation).
 *
 * <p>Pipeline: Stein GFX only (experimental Vulkan backend, not used by the game).
 */
public final class SteinVkDevice extends SteinDevice {

    /**
     * Vulkan SDK validation layers ({@code -Dsteinbridge.gfx.vkValidation=true}; needs the SDK
     * installed).
     */
    static final boolean VALIDATION = SteinVkOptions.VALIDATION;

    private final VkInstance instance;
    private long messenger = VK_NULL_HANDLE;
    private VkDebugUtilsMessengerCallbackEXT messengerCallback;
    private final VkPhysicalDevice physical;
    private final VkDevice device;
    private final VkQueue queue;
    private final int family;
    private final SteinCaps caps;
    private final VkPhysicalDeviceMemoryProperties memory;

    /** The device is 1.3 (core dynamic rendering) or 1.2 with VK_KHR_dynamic_rendering. */
    final boolean core13;

    final boolean pushDescriptors;
    final boolean depthClipControl;

    /** Logical operations on the colour (SteinPipeline.LogicOp): an optional feature. */
    final boolean logicOp;

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
    private SteinVkCommands openPass;
    private boolean destroyed;

    private record Pending(long value, VkCommandBuffer cmd, Runnable action) {}

    public SteinVkDevice() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // ---- instance ----
            IntBuffer ip = stack.mallocInt(1);
            int instanceVersion =
                    vkEnumerateInstanceVersion(ip) == VK_SUCCESS ? ip.get(0) : VK_API_VERSION_1_0;
            if (VK_API_VERSION_MAJOR(instanceVersion) == 1
                    && VK_API_VERSION_MINOR(instanceVersion) < 2) {
                throw new IllegalStateException(
                        "Vulkan " + SteinVkProbe.version(instanceVersion) + ": Stein needs 1.2");
            }
            int apiVersion =
                    VK_API_VERSION_MINOR(instanceVersion) >= 3
                            ? VK13.VK_API_VERSION_1_3
                            : VK_API_VERSION_1_2;
            Set<String> instExt = SteinVkProbe.instanceExtensions(stack);
            Set<String> layers = SteinVkProbe.instanceLayers(stack);
            PointerBuffer enable = stack.mallocPointer(8);
            int flags = 0;
            if (instExt.contains(
                    KHRPortabilityEnumeration.VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME)) {
                // MoltenVK (Mac): without this its device does not even show up
                enable.put(
                        stack.UTF8(
                                KHRPortabilityEnumeration
                                        .VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME));
                flags |= KHRPortabilityEnumeration.VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR;
            }
            boolean debug = VALIDATION && instExt.contains(VK_EXT_DEBUG_UTILS_EXTENSION_NAME);
            if (debug) {
                enable.put(stack.UTF8(VK_EXT_DEBUG_UTILS_EXTENSION_NAME));
            }
            enable.flip();
            PointerBuffer layerNames = null;
            if (VALIDATION) {
                if (layers.contains("VK_LAYER_KHRONOS_validation")) {
                    layerNames = stack.pointers(stack.UTF8("VK_LAYER_KHRONOS_validation"));
                } else {
                    Log.info(
                            "gfx",
                            "vulkan: validation requested, but the layer is not installed"
                                    + " (Vulkan SDK)");
                }
            }
            VkApplicationInfo app =
                    VkApplicationInfo.calloc(stack)
                            .sType$Default()
                            .pApplicationName(stack.UTF8("Stein"))
                            .pEngineName(stack.UTF8("Stein GFX"))
                            .apiVersion(apiVersion);
            VkInstanceCreateInfo ici =
                    VkInstanceCreateInfo.calloc(stack)
                            .sType$Default()
                            .flags(flags)
                            .pApplicationInfo(app)
                            .ppEnabledExtensionNames(enable)
                            .ppEnabledLayerNames(layerNames);
            PointerBuffer pp = stack.mallocPointer(1);
            check(vkCreateInstance(ici, null, pp), "instance");
            instance = new VkInstance(pp.get(0), ici);
            if (debug) {
                messenger = createMessenger(stack);
            }

            // ---- GPU ----
            physical = SteinVkProbe.pickPhysical(instance, stack, apiVersion);
            VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc(stack);
            vkGetPhysicalDeviceProperties(physical, props);
            int deviceVersion = Math.min(props.apiVersion(), apiVersion);
            core13 = VK_API_VERSION_MINOR(deviceVersion) >= 3;
            family = SteinVkProbe.graphicsFamily(stack, physical);
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
                            .logicOp(has.logicOp());
            PointerBuffer dext = stack.mallocPointer(8);
            if (!core13) {
                dext.put(stack.UTF8(KHRDynamicRendering.VK_KHR_DYNAMIC_RENDERING_EXTENSION_NAME));
            }
            if (pushDescriptors) {
                dext.put(stack.UTF8(KHRPushDescriptor.VK_KHR_PUSH_DESCRIPTOR_EXTENSION_NAME));
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
            vkGetDeviceQueue(device, family, 0, pp);
            queue = new VkQueue(pp.get(0), device);

            memory = VkPhysicalDeviceMemoryProperties.calloc();
            vkGetPhysicalDeviceMemoryProperties(physical, memory);
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
                            has.multiDrawIndirect(),
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
            check(
                    vkCreatePipelineCache(
                            device,
                            VkPipelineCacheCreateInfo.calloc(stack).sType$Default(),
                            null,
                            lp),
                    "pipeline cache");
            pipelineCache = lp.get(0);
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

    /** The validation-layer callback body: prints the message and lets the call carry on. */
    private static int logMessage(int severity, long data) {
        VkDebugUtilsMessengerCallbackDataEXT d = VkDebugUtilsMessengerCallbackDataEXT.create(data);
        boolean error = (severity & VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT) != 0;
        String level = error ? "ERROR" : "warning";
        Log.info("gfx", "vulkan " + level + ": " + d.pMessageString());
        return VK_FALSE;
    }

    private long createMessenger(MemoryStack stack) {
        VkDebugUtilsMessengerCreateInfoEXT info =
                VkDebugUtilsMessengerCreateInfoEXT.calloc(stack)
                        .sType$Default()
                        .messageSeverity(
                                VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT
                                        | VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT)
                        .messageType(
                                VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT
                                        | VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT
                                        | VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT)
                        .pfnUserCallback(
                                messengerCallback =
                                        VkDebugUtilsMessengerCallbackEXT.create(
                                                (severity, types, data, user) ->
                                                        logMessage(severity, data)));
        LongBuffer lp = stack.mallocLong(1);
        check(vkCreateDebugUtilsMessengerEXT(instance, info, null, lp), "validation messenger");
        return lp.get(0);
    }

    // ---- resource access ----

    VkDevice vk() {
        return device;
    }

    long pipelineCache() {
        return pipelineCache;
    }

    @Override
    public SteinCaps caps() {
        return caps;
    }

    // ---- memory ----

    /** A device memory allocation (one per resource for now), mapped if visible to the CPU. */
    static final class Allocation {
        final long memory;
        final long mapped;

        Allocation(long memory, long mapped) {
            this.memory = memory;
            this.mapped = mapped;
        }
    }

    private int memoryType(int bits, int flags) {
        for (int i = 0; i < memory.memoryTypeCount(); i++) {
            if ((bits & (1 << i)) != 0
                    && (memory.memoryTypes(i).propertyFlags() & flags) == flags) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Allocates for {@code req} with {@code required}, first trying {@code required | preferred}.
     */
    Allocation allocate(VkMemoryRequirements req, int required, int preferred, String name) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer lp = stack.mallocLong(1);
            int result = VK_ERROR_OUT_OF_DEVICE_MEMORY;
            int type = -1;
            for (int flags : new int[] {required | preferred, required}) {
                type = memoryType(req.memoryTypeBits(), flags);
                if (type < 0) {
                    continue;
                }
                VkMemoryAllocateInfo info =
                        VkMemoryAllocateInfo.calloc(stack)
                                .sType$Default()
                                .allocationSize(req.size())
                                .memoryTypeIndex(type);
                result = vkAllocateMemory(device, info, null, lp);
                if (result == VK_SUCCESS) {
                    break;
                }
                if (preferred == 0) {
                    break;
                }
            }
            check(result, "memory of '" + name + "' (" + req.size() + " bytes)");
            long mem = lp.get(0);
            long mapped = 0L;
            if ((memory.memoryTypes(type).propertyFlags() & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT)
                    != 0) {
                PointerBuffer pp = stack.mallocPointer(1);
                check(vkMapMemory(device, mem, 0L, VK_WHOLE_SIZE, 0, pp), "map '" + name + "'");
                mapped = pp.get(0);
            }
            return new Allocation(mem, mapped);
        }
    }

    void free(Allocation a) {
        if (a.mapped != 0L) {
            vkUnmapMemory(device, a.memory);
        }
        vkFreeMemory(device, a.memory, null);
    }

    /** Raw buffer with its memory: {@code [buffer]} and the allocation in {@code out}. */
    long createRawBuffer(
            long size, int usage, int required, int preferred, String name, Allocation[] out) {
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
            Allocation a = allocate(req, required, preferred, name);
            check(
                    vkBindBufferMemory(device, buffer, a.memory, 0L),
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
        return cmd();
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
        if (openPass != null) {
            openPass.suspendForSubmit();
        }
        VkCommandBuffer c = cmd();
        check(vkEndCommandBuffer(c), "end command buffer");
        long value = submitted + 1;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkTimelineSemaphoreSubmitInfo t =
                    VkTimelineSemaphoreSubmitInfo.calloc(stack)
                            .sType$Default()
                            .pSignalSemaphoreValues(stack.longs(value));
            VkSubmitInfo s =
                    VkSubmitInfo.calloc(stack)
                            .sType$Default()
                            .pNext(t.address())
                            .pCommandBuffers(stack.pointers(c))
                            .pSignalSemaphores(stack.longs(timeline));
            check(vkQueueSubmit(queue, s, VK_NULL_HANDLE), "submit");
        }
        inflight.add(new Pending(value, c, null));
        submitted = value;
        cmd = null;
        descriptors.onSubmit(value);
        reclaim();
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

    /** Samplers for whoever binds a texture without a sampler: [linear][with mipmap]. */
    private final SteinVkSampler[][] defaultSamplers = new SteinVkSampler[2][2];

    private final SteinVkTexture[] emptyTextures = new SteinVkTexture[2];
    private SteinVkBuffer emptyBuffer;

    /**
     * The sampler for whoever binds {@code t} without a sampler: the filter the texture would have
     * on its own in the OpenGL backend (linear on targets and depth, pixel by pixel on the rest;
     * with mipmap, linear mip), repeating.
     */
    SteinVkSampler defaultSampler(SteinTexture t) {
        boolean linear = t.desc().format.isDepth() || t.desc().renderTarget;
        boolean mips = t.desc().mips > 1;
        SteinVkSampler s = defaultSamplers[linear ? 1 : 0][mips ? 1 : 0];
        if (s == null) {
            SteinSampler.Filter f =
                    linear ? SteinSampler.Filter.LINEAR : SteinSampler.Filter.NEAREST;
            s =
                    new SteinVkSampler(
                            this,
                            new SteinSampler.Desc(
                                    f,
                                    f,
                                    mips ? SteinSampler.Mip.LINEAR : SteinSampler.Mip.NONE,
                                    SteinSampler.Wrap.REPEAT,
                                    SteinSampler.Wrap.REPEAT,
                                    1f,
                                    false));
            defaultSamplers[linear ? 1 : 0][mips ? 1 : 0] = s;
        }
        return s;
    }

    /**
     * 1x1 black texture (alpha 1) for the slot with no resource: what OpenGL's texture 0 returns.
     */
    SteinVkTexture emptyTexture(boolean arrayed) {
        SteinVkTexture t = emptyTextures[arrayed ? 1 : 0];
        if (t == null) {
            t =
                    new SteinVkTexture(
                            this,
                            arrayed
                                    ? SteinTexture.Desc.array(
                                            "empty", SteinFormat.RGBA8, 1, 1, 1, 1)
                                    : SteinTexture.Desc.texture2D(
                                            "empty", SteinFormat.RGBA8, 1, 1, 1));
            ByteBuffer px =
                    MemoryUtil.memAlloc(4)
                            .put(0, (byte) 0)
                            .put(1, (byte) 0)
                            .put(2, (byte) 0)
                            .put(3, (byte) 255);
            t.upload(0, 0, 0, 0, 1, 1, px);
            MemoryUtil.memFree(px);
            emptyTextures[arrayed ? 1 : 0] = t;
        }
        return t;
    }

    /** Buffer of zeros for the uniform or storage block with no resource. */
    SteinVkBuffer emptyBuffer() {
        if (emptyBuffer == null) {
            emptyBuffer =
                    new SteinVkBuffer(
                            this,
                            new SteinBuffer.Desc(
                                    "empty",
                                    EMPTY_BUFFER,
                                    SteinBuffer.Memory.UPLOAD,
                                    SteinBuffer.Usage.UNIFORM,
                                    SteinBuffer.Usage.STORAGE));
            MemoryUtil.memSet(emptyBuffer.address(0), 0, EMPTY_BUFFER);
        }
        return emptyBuffer;
    }

    static final long EMPTY_BUFFER = 64L * 1024;

    @Override
    public SteinTexture importTexture(SteinTexture.Desc desc, long handle) {
        throw new UnsupportedOperationException(
                "vulkan: a game (OpenGL) texture cannot enter Vulkan");
    }

    @Override
    public void copyTexture(
            SteinTexture src, int srcLayer, SteinTexture dst, int dstLayer, int w, int h) {
        SteinVkTexture s = (SteinVkTexture) src;
        SteinVkTexture d = (SteinVkTexture) dst;
        VkCommandBuffer c = outside();
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
                    .srcSubresource(
                            r ->
                                    r.aspectMask(s.aspect())
                                            .mipLevel(0)
                                            .baseArrayLayer(srcLayer)
                                            .layerCount(1))
                    .dstSubresource(
                            r ->
                                    r.aspectMask(d.aspect())
                                            .mipLevel(0)
                                            .baseArrayLayer(dstLayer)
                                            .layerCount(1))
                    .extent(e -> e.set(w, h, 1));
            vkCmdCopyImage(c, s.image(), srcLayout, d.image(), dstLayout, region);
        }
        s.toRest(c);
        if (!same) {
            d.toRest(c);
        }
    }

    @Override
    public void copyBuffer(
            SteinBuffer src, long srcOffset, SteinBuffer dst, long dstOffset, long bytes) {
        VkCommandBuffer c = outside();
        SteinVkBarriers.memoryBarrier(c);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack);
            region.get(0).srcOffset(srcOffset).dstOffset(dstOffset).size(bytes);
            vkCmdCopyBuffer(c, src.handle(), dst.handle(), region);
        }
        SteinVkBarriers.memoryBarrier(c);
    }

    @Override
    public SteinPipeline createPipeline(SteinPipeline.Desc desc) {
        return new SteinVkPipeline(this, desc);
    }

    @Override
    public SteinRenderTarget createTarget(SteinTexture[] colors, SteinTexture depth) {
        return new SteinVkRenderTarget(colors, depth);
    }

    @Override
    public SteinRenderTarget gameTarget() {
        throw new UnsupportedOperationException(
                "vulkan: the game target only exists when the whole frame goes through "
                        + "Stein GFX (the frame is missing from the API)");
    }

    // ---- work ----

    @Override
    public SteinCommands beginPass(SteinRenderPass pass) {
        return new SteinVkCommands(this, pass);
    }

    @Override
    public SteinFence fence() {
        return new SteinVkFence(this, recordingValue());
    }

    @Override
    public void readBuffer(SteinBuffer src, long offset, ByteBuffer out) {
        long bytes = out.remaining();
        if (bytes == 0) {
            return;
        }
        // Copy to a CPU-visible buffer (preferably cached) after everything that came before, as
        // in readPixels; the buffer's own mapping (UPLOAD) will not do: it may be write-combined
        // memory, with no ordering against the GPU, and the GPU has no mapping at all.
        Allocation[] a = new Allocation[1];
        long buffer =
                createRawBuffer(
                        bytes,
                        VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                        VK_MEMORY_PROPERTY_HOST_CACHED_BIT,
                        "buffer readback",
                        a);
        try {
            VkCommandBuffer c = outside();
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
            long value = recordingValue();
            if (!waitValue(value, Long.MAX_VALUE)) {
                throw new IllegalStateException("vulkan: buffer readback did not finish");
            }
            MemoryUtil.memCopy(a[0].mapped, MemoryUtil.memAddress(out), bytes);
        } finally {
            vkDestroyBuffer(device, buffer, null);
            free(a[0]);
        }
    }

    @Override
    public void readPixels(SteinTexture texture, int x, int y, int w, int h, ByteBuffer out) {
        SteinVkTexture t = (SteinVkTexture) texture;
        if (texture.desc().format != SteinFormat.RGBA8) {
            throw new IllegalArgumentException(
                    "readPixels supports RGBA8 only: " + texture.desc().format);
        }
        long bytes = (long) w * h * 4;
        Allocation[] a = new Allocation[1];
        long buffer =
                createRawBuffer(
                        bytes,
                        VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                        VK_MEMORY_PROPERTY_HOST_CACHED_BIT,
                        "readback",
                        a);
        try {
            VkCommandBuffer c = outside();
            t.toLayout(c, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
                region.get(0)
                        .bufferOffset(0L)
                        .imageSubresource(
                                r ->
                                        r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                                                .mipLevel(0)
                                                .baseArrayLayer(0)
                                                .layerCount(1))
                        .imageOffset(o -> o.set(x, y, 0))
                        .imageExtent(e -> e.set(w, h, 1));
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
            long value = recordingValue();
            if (!waitValue(value, Long.MAX_VALUE)) {
                throw new IllegalStateException("vulkan: pixel readback did not finish");
            }
            MemoryUtil.memCopy(
                    a[0].mapped, MemoryUtil.memAddress(out), Math.min(bytes, out.remaining()));
        } finally {
            vkDestroyBuffer(device, buffer, null);
            free(a[0]);
        }
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
        for (SteinVkSampler[] row : defaultSamplers) {
            for (SteinVkSampler s : row) {
                if (s != null) {
                    s.destroy();
                }
            }
        }
        for (SteinVkTexture t : emptyTextures) {
            if (t != null) {
                t.destroy();
            }
        }
        if (emptyBuffer != null) {
            emptyBuffer.destroy();
        }
        completedCache = Long.MAX_VALUE;
        reclaim();
        while (!retired.isEmpty()) {
            retired.poll().action.run();
        }
        staging.destroyAll();
        descriptors.destroyAll();
        vkDestroyPipelineCache(device, pipelineCache, null);
        vkDestroyCommandPool(device, commandPool, null);
        vkDestroySemaphore(device, timeline, null);
        vkDestroyDevice(device, null);
        if (messenger != VK_NULL_HANDLE) {
            vkDestroyDebugUtilsMessengerEXT(instance, messenger, null);
            messengerCallback.free();
        }
        vkDestroyInstance(instance, null);
        memory.free();
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
