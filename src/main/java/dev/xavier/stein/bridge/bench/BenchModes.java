/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.bench;

import static org.lwjgl.vulkan.VK10.VK_SUCCESS;
import static org.lwjgl.vulkan.VK10.vkCreateInstance;
import static org.lwjgl.vulkan.VK10.vkDestroyInstance;
import static org.lwjgl.vulkan.VK10.vkEnumerateDeviceExtensionProperties;
import static org.lwjgl.vulkan.VK10.vkEnumeratePhysicalDevices;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceProperties;

import dev.xavier.stein.bridge.core.CoreOptions;
import dev.xavier.stein.bridge.core.Win;
import dev.xavier.stein.gfx.SteinBackend;
import dev.xavier.stein.gfx.SteinGfxOptions;

import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWVulkan;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkApplicationInfo;
import org.lwjgl.vulkan.VkExtensionProperties;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Which graphics modes this machine can run, and the benchmark's plan over them. The fixed-function
 * pipeline always runs; Stein GFX on OpenGL needs a 4.1 core context, asked of the driver with a
 * hidden window that is closed at once; Stein GFX on Vulkan needs a Vulkan 1.2 device with dynamic
 * rendering, asked of a Vulkan instance made only for the question. The mode the game runs in now
 * is known without asking.
 *
 * <p>The window is created for one API, so each mode is a run of its own: when a run ends and modes
 * are left, the game is opened again with the same Java, arguments and folder, in the next mode
 * ({@link #relaunch}), and the last run joins every mode's report ({@link BenchSessions}). On the
 * render thread (GLFW asks for the main thread).
 */
final class BenchModes {

    private BenchModes() {}

    static final String FIXED = "fixed";
    static final String OPENGL = "opengl";
    static final String VULKAN = "vulkan";

    /** The mode this run is in. */
    static String current() {
        return Win.vulkan ? VULKAN : SteinGfxOptions.ENABLED ? OPENGL : FIXED;
    }

    /** What each mode is on this machine ("not available: why" when it cannot run). */
    static Map<String, String> detect() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(VULKAN, vulkan());
        m.put(OPENGL, opengl());
        m.put(FIXED, "available");
        return m;
    }

    static boolean available(String description) {
        return description != null && !description.startsWith("not available");
    }

    private static String opengl() {
        String cur = current();
        if (OPENGL.equals(cur)) {
            return "available: " + SteinGfxOptions.describe() + " (running)";
        }
        if (FIXED.equals(cur) && CoreOptions.GFX && !Win.coreContext) {
            return "not available: the driver gave no OpenGL 4.1 core context";
        }
        // running on Vulkan, or in the fixed pipeline by choice: ask the driver
        long prev = GLFW.glfwGetCurrentContext();
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_OPENGL_API);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 4);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 1);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);
        long w = GLFW.glfwCreateWindow(16, 16, "", 0L, 0L);
        GLFW.glfwDefaultWindowHints();
        if (w == 0L) {
            return "not available: the driver gave no OpenGL 4.1 core context";
        }
        int major = GLFW.glfwGetWindowAttrib(w, GLFW.GLFW_CONTEXT_VERSION_MAJOR);
        int minor = GLFW.glfwGetWindowAttrib(w, GLFW.GLFW_CONTEXT_VERSION_MINOR);
        GLFW.glfwDestroyWindow(w);
        if (GLFW.glfwGetCurrentContext() != prev) {
            GLFW.glfwMakeContextCurrent(prev);
        }
        return "available: OpenGL " + major + "." + minor + " core or newer (asked of the driver)";
    }

    private static String vulkan() {
        if (VULKAN.equals(current())) {
            return "available: " + SteinGfxOptions.describe() + " (running)";
        }
        if (SteinBackend.fallback() != null) {
            return "not available: " + SteinBackend.fallback();
        }
        try {
            if (!GLFWVulkan.glfwVulkanSupported()) {
                return "not available: no Vulkan driver";
            }
            return vulkanDevice();
        } catch (Throwable e) {
            return "not available: " + e.getClass().getSimpleName();
        }
    }

    /** The first Vulkan 1.2 device with dynamic rendering (core in 1.3, or the extension). */
    private static String vulkanDevice() {
        try (MemoryStack st = MemoryStack.stackPush()) {
            VkApplicationInfo app =
                    VkApplicationInfo.calloc(st)
                            .sType$Default()
                            .pApplicationName(st.UTF8("Stein benchmark probe"))
                            .apiVersion(VK10.VK_MAKE_VERSION(1, 2, 0));
            VkInstanceCreateInfo ci =
                    VkInstanceCreateInfo.calloc(st).sType$Default().pApplicationInfo(app);
            PointerBuffer pp = st.mallocPointer(1);
            if (vkCreateInstance(ci, null, pp) != VK_SUCCESS) {
                return "not available: no Vulkan 1.2 instance";
            }
            VkInstance inst = new VkInstance(pp.get(0), ci);
            try {
                IntBuffer n = st.mallocInt(1);
                vkEnumeratePhysicalDevices(inst, n, null);
                PointerBuffer devs = st.mallocPointer(n.get(0));
                vkEnumeratePhysicalDevices(inst, n, devs);
                for (int i = 0; i < n.get(0); i++) {
                    VkPhysicalDevice pd = new VkPhysicalDevice(devs.get(i), inst);
                    VkPhysicalDeviceProperties p = VkPhysicalDeviceProperties.calloc(st);
                    vkGetPhysicalDeviceProperties(pd, p);
                    int v = p.apiVersion();
                    int major = VK10.VK_VERSION_MAJOR(v);
                    int minor = VK10.VK_VERSION_MINOR(v);
                    boolean ok =
                            major > 1
                                    || (major == 1 && minor >= 3)
                                    || (major == 1 && minor == 2 && dynamicRendering(st, pd));
                    if (ok) {
                        return "available: Vulkan "
                                + major
                                + "."
                                + minor
                                + ", "
                                + p.deviceNameString()
                                + " (asked of the driver)";
                    }
                }
                return "not available: no Vulkan 1.2 device with dynamic rendering";
            } finally {
                vkDestroyInstance(inst, null);
            }
        }
    }

    private static boolean dynamicRendering(MemoryStack st, VkPhysicalDevice pd) {
        IntBuffer n = st.mallocInt(1);
        vkEnumerateDeviceExtensionProperties(pd, (String) null, n, null);
        VkExtensionProperties.Buffer ext = VkExtensionProperties.malloc(n.get(0), st);
        vkEnumerateDeviceExtensionProperties(pd, (String) null, n, ext);
        for (int i = 0; i < ext.capacity(); i++) {
            if ("VK_KHR_dynamic_rendering".equals(ext.get(i).extensionNameString())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The modes this run still has to do after the current one: the {@code bench.modes} of a
     * reopened run, or, at the first run, the available modes ({@code auto}), only this one ({@code
     * current}) or the ones listed.
     */
    static List<String> plan(Map<String, String> detected) {
        String left = BenchOptions.MODES_LEFT;
        if (left != null) {
            return split(left);
        }
        String asked = BenchOptions.MODES.trim().toLowerCase(Locale.ROOT);
        List<String> modes = new ArrayList<>();
        if (asked.equals("current")) {
            return modes;
        }
        List<String> wanted =
                asked.equals("auto") || asked.isEmpty()
                        ? List.of(VULKAN, OPENGL, FIXED)
                        : split(asked);
        for (String m : wanted) {
            if (!m.equals(current()) && available(detected.get(m)) && !modes.contains(m)) {
                modes.add(m);
            }
        }
        return modes;
    }

    private static List<String> split(String s) {
        List<String> out = new ArrayList<>();
        for (String m : s.split(",")) {
            if (!m.isBlank()) {
                out.add(m.trim());
            }
        }
        return out;
    }

    /**
     * Opens the game again in {@code mode}: the same Java, its arguments (without the ones that
     * pick the mode or carry the session), the classpath, the game's own arguments (kept by the
     * launch class) and the folder. The new game's output is not kept here: its benchmark keeps its
     * own console from the first line. False when the game was not started through the Bridge's
     * launch class (the arguments are unknown).
     */
    static boolean relaunch(String mode, String session, List<String> after) {
        String[] gameArgs = launchArguments();
        if (gameArgs == null || gameArgs.length == 0) {
            return false;
        }
        String java =
                ProcessHandle.current()
                        .info()
                        .command()
                        .orElse(
                                System.getProperty("java.home")
                                        + File.separator
                                        + "bin"
                                        + File.separator
                                        + "java");
        List<String> cmd = new ArrayList<>();
        cmd.add(java);
        for (String a : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (a.startsWith("-Dsteinbridge.gfx=")
                    || a.startsWith("-Dsteinbridge.gfx.api=")
                    || a.startsWith("-Dsteinbridge.bench.session=")
                    || a.startsWith("-Dsteinbridge.bench.modesLeft=")
                    || a.startsWith("-Dsteinbridge.bench.setByBench=")) {
                continue;
            }
            cmd.add(a);
        }
        switch (mode) {
            case VULKAN -> cmd.add("-Dsteinbridge.gfx.api=vulkan");
            case OPENGL -> cmd.add("-Dsteinbridge.gfx.api=opengl");
            default -> cmd.add("-Dsteinbridge.gfx=false");
        }
        cmd.add("-Dsteinbridge.bench.session=" + session);
        cmd.add("-Dsteinbridge.bench.modesLeft=" + String.join(",", after));
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.add("dev.xavier.stein.bridge.launch.Main");
        cmd.addAll(List.of(gameArgs));
        try {
            new ProcessBuilder(cmd)
                    .directory(new File(System.getProperty("user.dir")))
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            return true;
        } catch (java.io.IOException e) {
            Bench.event("could not reopen the game: " + e);
            return false;
        }
    }

    /** The game's arguments, from the launch class (null when it did not start the game). */
    private static String[] launchArguments() {
        try {
            Class<?> main = Class.forName("dev.xavier.stein.bridge.launch.Main");
            return (String[]) main.getField("arguments").get(null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }
}
