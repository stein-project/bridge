/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx.vk;

import static org.lwjgl.glfw.GLFW.GLFW_CLIENT_API;
import static org.lwjgl.glfw.GLFW.GLFW_FALSE;
import static org.lwjgl.glfw.GLFW.GLFW_FOCUS_ON_SHOW;
import static org.lwjgl.glfw.GLFW.GLFW_NO_API;
import static org.lwjgl.glfw.GLFW.GLFW_VISIBLE;
import static org.lwjgl.glfw.GLFW.glfwCreateWindow;
import static org.lwjgl.glfw.GLFW.glfwDefaultWindowHints;
import static org.lwjgl.glfw.GLFW.glfwDestroyWindow;
import static org.lwjgl.glfw.GLFW.glfwMakeContextCurrent;
import static org.lwjgl.glfw.GLFW.glfwWindowHint;
import static org.lwjgl.system.MemoryUtil.NULL;

import dev.xavier.stein.bridge.core.CoreOptions;
import dev.xavier.stein.bridge.core.Log;
import dev.xavier.stein.bridge.core.Win;
import dev.xavier.stein.gfx.SteinBackend;
import dev.xavier.stein.gfx.SteinDevice;

import org.lwjgl.glfw.GLFWVulkan;
import org.lwjgl.opengl.GL;

/**
 * The game's window when Stein GFX draws with Vulkan (for the LWJGL 2 Display shim): created
 * without an OpenGL context, with the device that presents to it created right away, and closed
 * with the device.
 *
 * <p>If Vulkan does not work here (no loader, a window refused, no GPU with Vulkan 1.2 that
 * presents to it), nothing of it is left and {@link #create} returns 0 with the reason in {@link
 * SteinBackend}: the caller then creates the OpenGL window.
 *
 * <p>Pipeline: Stein GFX only (the Vulkan backend).
 */
public final class SteinVkWindow {

    /** The hidden OpenGL context of {@link #createGlSink}, or 0. */
    private static long glSink;

    private SteinVkWindow() {}

    /**
     * A window of {@code w x h} for Vulkan, with the window hints already applied by the caller; 0
     * when Vulkan does not start here.
     */
    public static long create(int w, int h, String title) {
        if (!GLFWVulkan.glfwVulkanSupported()) {
            SteinBackend.fellBack(SteinBackend.Fallback.NO_DRIVER, "no Vulkan loader or driver");
            return NULL;
        }
        glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);
        long win = glfwCreateWindow(w, h, title, NULL, NULL);
        if (win == NULL) {
            SteinBackend.fellBack(
                    SteinBackend.Fallback.NO_WINDOW, "GLFW refused a window without OpenGL");
            return NULL;
        }
        SteinBackend.vulkanStarting();
        try {
            SteinVkDevice device = new SteinVkDevice(win, SteinBackend.pipelineCacheFile());
            SteinDevice.use(device);
            Log.info("gfx", String.valueOf(device.caps()));
        } catch (RuntimeException | LinkageError e) {
            SteinBackend.vulkanFailed();
            SteinBackend.fellBack(SteinBackend.Fallback.NO_DEVICE, String.valueOf(e.getMessage()));
            glfwDestroyWindow(win);
            return NULL;
        }
        Win.vulkan = true;
        createGlSink();
        return win;
    }

    /**
     * A hidden 1x1 window with an OpenGL context, current on the game thread. The game and the
     * Loader reach OpenGL only through the Bridge (which, with Vulkan, never calls the driver), but
     * a mod may call OpenGL directly in a way no rule covers; without a context that call would
     * abort the JVM. Here it lands on a context nobody sees, and what it draws does not show up —
     * as the fixed-function pipeline's calls in the core context.
     */
    private static void createGlSink() {
        if (!CoreOptions.VK_GL_SINK) {
            return;
        }
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_FOCUS_ON_SHOW, GLFW_FALSE);
        long sink = glfwCreateWindow(1, 1, "", NULL, NULL);
        if (sink == NULL) {
            Log.warn("gfx", "vulkan: no hidden OpenGL context for mods that call OpenGL directly");
            return;
        }
        glfwMakeContextCurrent(sink);
        GL.createCapabilities();
        glSink = sink;
    }

    /**
     * Before the window is destroyed: the compiled pipelines saved, the GPU idle, the device (with
     * its swapchain and surface) gone, then the hidden context.
     */
    public static void destroy() {
        if (SteinDevice.get() instanceof SteinVkDevice vk) {
            vk.savePipelineCache();
            vk.destroy();
        }
        if (glSink != NULL) {
            glfwDestroyWindow(glSink);
            glSink = NULL;
        }
    }
}
