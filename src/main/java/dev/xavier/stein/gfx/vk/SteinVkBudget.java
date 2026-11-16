/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx.vk;

import static org.lwjgl.vulkan.VK10.VK_MEMORY_HEAP_DEVICE_LOCAL_BIT;
import static org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceMemoryProperties2;

import dev.xavier.stein.bridge.core.Log;
import dev.xavier.stein.bridge.core.SteinLog;
import dev.xavier.stein.bridge.core.SteinLog.Topic;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryBudgetPropertiesEXT;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties2;

/**
 * The graphics card's memory as the system sees it (VK_EXT_memory_budget): how much of the video
 * memory the process uses and how much the system gives it.
 *
 * <p>On Windows, going past the budget raises no error: the system moves part of the video memory
 * to the computer's RAM and every frame gets slower, with nothing in the log. This puts the use in
 * the log — once at the start, whenever it passes {@link #HIGH} of the budget, and every {@link
 * #SAMPLE_NANOS} in the performance log.
 */
final class SteinVkBudget {

    /** How often the use is read (between frames; the read is a driver call, not a GPU wait). */
    private static final long SAMPLE_NANOS = 10_000_000_000L;

    /** Above this share of the budget, a line in the log (again only after going back below). */
    private static final double HIGH = 0.9;

    private final VkPhysicalDevice physical;
    private long sampledAt;
    private boolean logged;
    private boolean high;

    SteinVkBudget(VkPhysicalDevice physical) {
        this.physical = physical;
    }

    /** Once per frame: cheap until a sample is due. */
    void frame() {
        long now = System.nanoTime();
        if (sampledAt != 0L && now - sampledAt < SAMPLE_NANOS) {
            return;
        }
        sampledAt = now;
        long usage = 0;
        long budget = 0;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceMemoryBudgetPropertiesEXT b =
                    VkPhysicalDeviceMemoryBudgetPropertiesEXT.calloc(stack).sType$Default();
            VkPhysicalDeviceMemoryProperties2 p =
                    VkPhysicalDeviceMemoryProperties2.calloc(stack)
                            .sType$Default()
                            .pNext(b.address());
            vkGetPhysicalDeviceMemoryProperties2(physical, p);
            VkPhysicalDeviceMemoryProperties mp = p.memoryProperties();
            for (int i = 0; i < mp.memoryHeapCount(); i++) {
                if ((mp.memoryHeaps(i).flags() & VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) != 0) {
                    usage += b.heapUsage(i);
                    budget += b.heapBudget(i);
                }
            }
        }
        if (budget <= 0) {
            return;
        }
        String line =
                "vulkan: video memory in use "
                        + (usage >> 20)
                        + " of "
                        + (budget >> 20)
                        + " MB the system gives the game";
        boolean over = usage > budget * HIGH;
        if (!logged || over && !high) {
            logged = true;
            Log.info("gfx", over ? line + " (near the limit: past it, frames slow down)" : line);
        }
        high = over;
        SteinLog.debug(Topic.PERF, "gfx", line);
    }
}
