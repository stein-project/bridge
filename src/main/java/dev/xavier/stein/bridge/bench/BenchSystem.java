/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.bench;

import dev.xavier.stein.bridge.SteinBridge;
import dev.xavier.stein.bridge.compat.GLCompat;
import dev.xavier.stein.bridge.core.Win;
import dev.xavier.stein.gfx.SteinBackend;
import dev.xavier.stein.gfx.SteinDevice;
import dev.xavier.stein.gfx.SteinGfxOptions;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.ResourcePackRepository;
import net.minecraft.client.settings.GameSettings;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The environment of a run, for the report: Stein's versions and installed mods, the graphics API
 * and the GPU it drew with, the window, the video settings the player had, Java and its arguments,
 * the operating system, every Stein option set on the command line and which of them are
 * diagnostics (a run with one of those on does not measure the game as players get it). Read on the
 * render thread at the start (the graphics strings need the context).
 */
final class BenchSystem {

    private BenchSystem() {}

    /** Options whose name says they check, trace or time more than a player's game does. */
    private static final String[] DIAGNOSTIC = {
        "diag.", "Check", "check", "glDebug", "Timing", "timing", "audit", "Stats", "stats", "dump",
        "Dump", "trace", "perf."
    };

    /** Options the benchmark sets itself (they are not counted as the player's diagnostics). */
    static final List<String> SET_BY_BENCH =
            System.getProperty("steinbridge.bench.setByBench", "").isEmpty()
                    ? List.of()
                    : List.of(System.getProperty("steinbridge.bench.setByBench").split(","));

    static Map<String, Object> snapshot(Minecraft mc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stein", stein(mc));
        m.put("graphics", graphics());
        m.put("window", window(mc));
        m.put("player_settings", settings(mc.gameSettings));
        m.put("java", java());
        Map<String, Object> os = new LinkedHashMap<>();
        os.put("name", System.getProperty("os.name"));
        os.put("version", System.getProperty("os.version"));
        os.put("arch", System.getProperty("os.arch"));
        os.put("processors", Runtime.getRuntime().availableProcessors());
        os.put("locale", Locale.getDefault().toString());
        m.put("os", os);
        Map<String, Object> props = new TreeMap<>();
        List<String> diagnostics = new ArrayList<>();
        for (String k : System.getProperties().stringPropertyNames()) {
            if (k.startsWith("stein") || k.startsWith("patcher.")) {
                props.put(k, System.getProperty(k));
                if (isDiagnostic(k) && !SET_BY_BENCH.contains(k) && !isOff(k)) {
                    diagnostics.add(k + "=" + System.getProperty(k));
                }
            }
        }
        m.put("stein_options", props);
        m.put("diagnostic_options_on", diagnostics);
        m.put("options_set_by_benchmark", SET_BY_BENCH);
        return m;
    }

    private static boolean isDiagnostic(String key) {
        if (key.startsWith("steinbridge.bench") || key.equals("steinbridge.diag.background")) {
            return false;
        }
        for (String d : DIAGNOSTIC) {
            if (key.contains(d)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isOff(String key) {
        String v = System.getProperty(key, "");
        return v.equalsIgnoreCase("false") || v.equals("0");
    }

    private static Map<String, Object> stein(Minecraft mc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("bridge", SteinBridge.VERSION);
        m.put("loader", loaderVersion());
        List<String> mods = new ArrayList<>();
        File[] files = new File(mc.mcDataDir, "mods").listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isFile()) {
                    mods.add(f.getName() + " (" + f.length() + " bytes)");
                }
            }
        }
        mods.sort(null);
        m.put("mods_folder", mods);
        List<String> packs = new ArrayList<>();
        for (ResourcePackRepository.Entry e :
                mc.getResourcePackRepository().getRepositoryEntries()) {
            packs.add(e.getResourcePackName());
        }
        m.put("resource_packs", packs);
        return m;
    }

    private static String loaderVersion() {
        try {
            Class<?> k =
                    Class.forName(
                            "dev.xavier.stein.loader.Loader",
                            false,
                            Thread.currentThread().getContextClassLoader());
            return String.valueOf(k.getField("VERSION").get(null));
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            return ""; // no Loader
        }
    }

    private static Map<String, Object> graphics() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pipeline", SteinGfxOptions.describe());
        m.put("stein_gfx", SteinGfxOptions.ENABLED);
        m.put("api_requested", SteinBackend.requested().key);
        m.put("api_running", SteinBackend.running().key);
        m.put("vulkan_fallback", String.valueOf(SteinBackend.fallback()));
        if (SteinGfxOptions.ENABLED) {
            try {
                SteinDevice d = SteinDevice.get();
                m.put("device_backend", d.caps().backend);
                m.put("device_renderer", d.caps().renderer);
            } catch (RuntimeException e) {
                m.put("device", "unavailable: " + e);
            }
        }
        if (!Win.vulkan) {
            m.put("gl_vendor", GLCompat.glGetString(GL11.GL_VENDOR));
            m.put("gl_renderer", GLCompat.glGetString(GL11.GL_RENDERER));
            m.put("gl_version", GLCompat.glGetString(GL11.GL_VERSION));
        }
        return m;
    }

    private static Map<String, Object> window(Minecraft mc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("width", Display.getWidth());
        m.put("height", Display.getHeight());
        m.put("fullscreen", mc.isFullScreen());
        long monitor = GLFW.glfwGetPrimaryMonitor();
        GLFWVidMode mode = monitor == 0L ? null : GLFW.glfwGetVideoMode(monitor);
        if (mode != null) {
            m.put("monitor_width", mode.width());
            m.put("monitor_height", mode.height());
            m.put("monitor_refresh_hz", mode.refreshRate());
        }
        float[] sx = new float[1];
        float[] sy = new float[1];
        if (Win.handle != 0L) {
            GLFW.glfwGetWindowContentScale(Win.handle, sx, sy);
            m.put("content_scale", sx[0]);
        }
        return m;
    }

    static Map<String, Object> settings(GameSettings s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("render_distance", s.renderDistanceChunks);
        m.put("fancy_graphics", s.fancyGraphics);
        m.put("smooth_lighting", s.ambientOcclusion);
        m.put("particles", s.particleSetting);
        m.put("clouds", s.clouds);
        m.put("mipmap_levels", s.mipmapLevels);
        m.put("max_fps", s.limitFramerate);
        m.put("vsync", s.enableVsync);
        m.put("fov", s.fovSetting);
        m.put("gui_scale", s.guiScale);
        m.put("view_bobbing", s.viewBobbing);
        m.put("entity_shadows", s.entityShadows);
        m.put("vbo", s.useVbo);
        m.put("language", s.language);
        return m;
    }

    private static Map<String, Object> java() {
        Map<String, Object> m = new LinkedHashMap<>();
        RuntimeMXBean rt = ManagementFactory.getRuntimeMXBean();
        m.put("version", System.getProperty("java.version"));
        m.put("vendor", System.getProperty("java.vendor"));
        m.put("vm", rt.getVmName() + " " + rt.getVmVersion());
        List<String> args = new ArrayList<>();
        String home = System.getProperty("user.home", "");
        for (String a : rt.getInputArguments()) {
            args.add(home.isEmpty() ? a : a.replace(home, "~"));
        }
        m.put("arguments", args);
        m.put("max_heap_mb", Runtime.getRuntime().maxMemory() >> 20);
        List<String> gcs = new ArrayList<>();
        ManagementFactory.getGarbageCollectorMXBeans().forEach(g -> gcs.add(g.getName()));
        m.put("collectors", gcs);
        return m;
    }
}
