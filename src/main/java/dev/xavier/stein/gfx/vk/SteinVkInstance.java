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
import static org.lwjgl.vulkan.VK10.VK_API_VERSION_1_0;
import static org.lwjgl.vulkan.VK10.VK_API_VERSION_MAJOR;
import static org.lwjgl.vulkan.VK10.VK_API_VERSION_MINOR;
import static org.lwjgl.vulkan.VK10.VK_FALSE;
import static org.lwjgl.vulkan.VK10.VK_NULL_HANDLE;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;
import static org.lwjgl.vulkan.VK10.vkCreateInstance;
import static org.lwjgl.vulkan.VK10.vkDestroyInstance;
import static org.lwjgl.vulkan.VK11.vkEnumerateInstanceVersion;
import static org.lwjgl.vulkan.VK12.VK_API_VERSION_1_2;

import dev.xavier.stein.bridge.core.Log;

import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFWVulkan;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.EXTSwapchainColorspace;
import org.lwjgl.vulkan.KHRPortabilityEnumeration;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkApplicationInfo;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCallbackDataEXT;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCallbackEXT;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCreateInfoEXT;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.Set;

/**
 * The Vulkan instance of the device: the API version (1.3 when the loader has it, else 1.2), the
 * extensions the window needs to present (GLFW's), portability enumeration for MoltenVK, and the
 * validation layer with its messenger when {@code -Dsteinbridge.gfx.vkValidation=true} and the SDK
 * is installed.
 *
 * <p>Pipeline: Stein GFX only (the Vulkan backend).
 */
final class SteinVkInstance {

    final VkInstance instance;
    final int apiVersion;

    /**
     * VK_EXT_swapchain_colorspace is on: the window may list colour spaces beyond sRGB (the HDR
     * output, {@code SteinHdr}).
     */
    static boolean colorSpaces;

    private final long messenger;
    private final VkDebugUtilsMessengerCallbackEXT messengerCallback;

    private SteinVkInstance(
            VkInstance instance,
            int apiVersion,
            long messenger,
            VkDebugUtilsMessengerCallbackEXT messengerCallback) {
        this.instance = instance;
        this.apiVersion = apiVersion;
        this.messenger = messenger;
        this.messengerCallback = messengerCallback;
    }

    /**
     * The instance for a device that presents to {@code window} (0: offscreen). Throws when the
     * loader is older than 1.2 or GLFW cannot present with Vulkan here.
     */
    static SteinVkInstance create(long window) {
        boolean validation = SteinVkDevice.VALIDATION;
        VkInstance instance;
        long messenger = VK_NULL_HANDLE;
        VkDebugUtilsMessengerCallbackEXT[] callback = new VkDebugUtilsMessengerCallbackEXT[1];
        int apiVersion;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer ip = stack.mallocInt(1);
            int instanceVersion =
                    vkEnumerateInstanceVersion(ip) == VK_SUCCESS ? ip.get(0) : VK_API_VERSION_1_0;
            if (VK_API_VERSION_MAJOR(instanceVersion) == 1
                    && VK_API_VERSION_MINOR(instanceVersion) < 2) {
                throw new IllegalStateException(
                        "Vulkan " + SteinVkProbe.version(instanceVersion) + ": Stein needs 1.2");
            }
            apiVersion =
                    VK_API_VERSION_MINOR(instanceVersion) >= 3
                            ? VK13.VK_API_VERSION_1_3
                            : VK_API_VERSION_1_2;
            Set<String> instExt = SteinVkProbe.instanceExtensions(stack);
            Set<String> layers = SteinVkProbe.instanceLayers(stack);
            PointerBuffer windowExt =
                    window != 0L ? GLFWVulkan.glfwGetRequiredInstanceExtensions() : null;
            if (window != 0L && windowExt == null) {
                throw new IllegalStateException("vulkan: GLFW cannot present with Vulkan here");
            }
            PointerBuffer enable =
                    stack.mallocPointer(8 + (windowExt == null ? 0 : windowExt.remaining()));
            if (windowExt != null) {
                for (int i = windowExt.position(); i < windowExt.limit(); i++) {
                    enable.put(windowExt.get(i));
                }
            }
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
            colorSpaces =
                    window != 0L
                            && instExt.contains(
                                    EXTSwapchainColorspace
                                            .VK_EXT_SWAPCHAIN_COLOR_SPACE_EXTENSION_NAME);
            if (colorSpaces) {
                // the window's other colour spaces (scRGB for HDR): only listed with it on
                enable.put(
                        stack.UTF8(
                                EXTSwapchainColorspace
                                        .VK_EXT_SWAPCHAIN_COLOR_SPACE_EXTENSION_NAME));
            }
            boolean debug = validation && instExt.contains(VK_EXT_DEBUG_UTILS_EXTENSION_NAME);
            if (debug) {
                enable.put(stack.UTF8(VK_EXT_DEBUG_UTILS_EXTENSION_NAME));
            }
            enable.flip();
            PointerBuffer layerNames = null;
            if (validation) {
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
            SteinVkDevice.check(vkCreateInstance(ici, null, pp), "instance");
            instance = new VkInstance(pp.get(0), ici);
            if (debug) {
                messenger = createMessenger(instance, stack, callback);
            }
        }
        return new SteinVkInstance(instance, apiVersion, messenger, callback[0]);
    }

    /** The validation-layer callback body: prints the message and lets the call carry on. */
    private static int logMessage(int severity, long data) {
        VkDebugUtilsMessengerCallbackDataEXT d = VkDebugUtilsMessengerCallbackDataEXT.create(data);
        boolean error = (severity & VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT) != 0;
        String level = error ? "ERROR" : "warning";
        if (error) {
            Log.warn("gfx", "vulkan " + level + ": " + d.pMessageString());
        } else {
            Log.info("gfx", "vulkan " + level + ": " + d.pMessageString());
        }
        return VK_FALSE;
    }

    private static long createMessenger(
            VkInstance instance, MemoryStack stack, VkDebugUtilsMessengerCallbackEXT[] callback) {
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
                                callback[0] =
                                        VkDebugUtilsMessengerCallbackEXT.create(
                                                (severity, types, data, user) ->
                                                        logMessage(severity, data)));
        LongBuffer lp = stack.mallocLong(1);
        SteinVkDevice.check(
                vkCreateDebugUtilsMessengerEXT(instance, info, null, lp), "validation messenger");
        return lp.get(0);
    }

    void destroy() {
        if (messenger != VK_NULL_HANDLE) {
            vkDestroyDebugUtilsMessengerEXT(instance, messenger, null);
            messengerCallback.free();
        }
        vkDestroyInstance(instance, null);
    }
}
