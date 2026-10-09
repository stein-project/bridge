/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

package dev.xavier.stein.gfx.vk;

import static org.lwjgl.vulkan.VK10.VK_API_VERSION_MAJOR;
import static org.lwjgl.vulkan.VK10.VK_API_VERSION_MINOR;
import static org.lwjgl.vulkan.VK10.VK_API_VERSION_PATCH;
import static org.lwjgl.vulkan.VK10.VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU;
import static org.lwjgl.vulkan.VK10.VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU;
import static org.lwjgl.vulkan.VK10.VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU;
import static org.lwjgl.vulkan.VK10.VK_QUEUE_COMPUTE_BIT;
import static org.lwjgl.vulkan.VK10.VK_QUEUE_GRAPHICS_BIT;
import static org.lwjgl.vulkan.VK10.vkEnumerateDeviceExtensionProperties;
import static org.lwjgl.vulkan.VK10.vkEnumerateInstanceExtensionProperties;
import static org.lwjgl.vulkan.VK10.vkEnumerateInstanceLayerProperties;
import static org.lwjgl.vulkan.VK10.vkEnumeratePhysicalDevices;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceProperties;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceQueueFamilyProperties;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRDynamicRendering;
import org.lwjgl.vulkan.VkExtensionProperties;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkLayerProperties;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkQueueFamilyProperties;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.HashSet;
import java.util.Set;

/**
 * What the Vulkan backend asks the loader and the driver before creating the device: versions,
 * instance and device extensions, layers, the GPU and its graphics queue.
 *
 * <p>Pipeline: Stein GFX only (experimental Vulkan backend, not used by the game).
 */
final class SteinVkProbe {

    private SteinVkProbe() {}

    static String version(int v) {
        return VK_API_VERSION_MAJOR(v)
                + "."
                + VK_API_VERSION_MINOR(v)
                + "."
                + VK_API_VERSION_PATCH(v);
    }

    static Set<String> instanceExtensions(MemoryStack stack) {
        IntBuffer n = stack.mallocInt(1);
        vkEnumerateInstanceExtensionProperties((ByteBuffer) null, n, null);
        Set<String> out = new HashSet<>();
        try (VkExtensionProperties.Buffer p = VkExtensionProperties.malloc(n.get(0))) {
            vkEnumerateInstanceExtensionProperties((ByteBuffer) null, n, p);
            for (VkExtensionProperties e : p) {
                out.add(e.extensionNameString());
            }
        }
        return out;
    }

    static Set<String> instanceLayers(MemoryStack stack) {
        IntBuffer n = stack.mallocInt(1);
        vkEnumerateInstanceLayerProperties(n, null);
        Set<String> out = new HashSet<>();
        try (VkLayerProperties.Buffer p = VkLayerProperties.malloc(n.get(0))) {
            vkEnumerateInstanceLayerProperties(n, p);
            for (VkLayerProperties l : p) {
                out.add(l.layerNameString());
            }
        }
        return out;
    }

    static Set<String> deviceExtensions(MemoryStack stack, VkPhysicalDevice pd) {
        IntBuffer n = stack.mallocInt(1);
        vkEnumerateDeviceExtensionProperties(pd, (ByteBuffer) null, n, null);
        Set<String> out = new HashSet<>();
        try (VkExtensionProperties.Buffer p = VkExtensionProperties.malloc(n.get(0))) {
            vkEnumerateDeviceExtensionProperties(pd, (ByteBuffer) null, n, p);
            for (VkExtensionProperties e : p) {
                out.add(e.extensionNameString());
            }
        }
        return out;
    }

    /**
     * The GPU: the discrete one before the integrated one, only those with Vulkan 1.2 and a
     * graphics queue with compute. {@code -Dsteinbridge.gfx.vkDevice=N} chooses by driver order.
     */
    static VkPhysicalDevice pickPhysical(VkInstance instance, MemoryStack stack, int apiVersion) {
        IntBuffer n = stack.mallocInt(1);
        SteinVkDevice.check(vkEnumeratePhysicalDevices(instance, n, null), "GPUs");
        if (n.get(0) == 0) {
            throw new IllegalStateException("vulkan: no GPU");
        }
        PointerBuffer list = stack.mallocPointer(n.get(0));
        SteinVkDevice.check(vkEnumeratePhysicalDevices(instance, n, list), "GPUs");
        int forced = SteinVkOptions.DEVICE;
        VkPhysicalDevice best = null;
        int bestScore = -1;
        for (int i = 0; i < list.capacity(); i++) {
            VkPhysicalDevice pd = new VkPhysicalDevice(list.get(i), instance);
            VkPhysicalDeviceProperties p = VkPhysicalDeviceProperties.calloc(stack);
            vkGetPhysicalDeviceProperties(pd, p);
            int v = Math.min(p.apiVersion(), apiVersion);
            boolean ok = VK_API_VERSION_MINOR(v) >= 2 && graphicsFamily(stack, pd) >= 0;
            if (ok && VK_API_VERSION_MINOR(v) < 3) {
                ok =
                        deviceExtensions(stack, pd)
                                .contains(
                                        KHRDynamicRendering
                                                .VK_KHR_DYNAMIC_RENDERING_EXTENSION_NAME);
            }
            if (!ok) {
                continue;
            }
            int score =
                    switch (p.deviceType()) {
                        case VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU -> 3;
                        case VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU -> 2;
                        case VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU -> 1;
                        default -> 0;
                    };
            if (i == forced) {
                score = 100;
            }
            if (score > bestScore) {
                best = pd;
                bestScore = score;
            }
        }
        if (best == null) {
            throw new IllegalStateException("vulkan: no GPU with Vulkan 1.2 and dynamic rendering");
        }
        return best;
    }

    static int graphicsFamily(MemoryStack stack, VkPhysicalDevice pd) {
        IntBuffer n = stack.mallocInt(1);
        vkGetPhysicalDeviceQueueFamilyProperties(pd, n, null);
        VkQueueFamilyProperties.Buffer q = VkQueueFamilyProperties.malloc(n.get(0), stack);
        vkGetPhysicalDeviceQueueFamilyProperties(pd, n, q);
        int want = VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT;
        for (int i = 0; i < q.capacity(); i++) {
            if ((q.get(i).queueFlags() & want) == want) {
                return i;
            }
        }
        return -1;
    }
}
