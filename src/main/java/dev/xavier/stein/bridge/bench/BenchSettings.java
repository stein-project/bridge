/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.bench;

import dev.xavier.stein.bridge.core.Log;
import dev.xavier.stein.bridge.core.Win;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.GameSettings;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.Display;

/**
 * The video settings for the run and the player's own, given back at the end. Only the fields in
 * memory change: options.txt is never written, so a run that dies halfway leaves the player's file
 * as it was. Settings that would reload the textures (mipmaps) stay as the player has them and are
 * recorded instead.
 */
final class BenchSettings {

    private final int renderDistance;
    private final boolean fancy;
    private final int ao;
    private final int particles;
    private final int clouds;
    private final int maxFps;
    private final boolean vsync;
    private final float fov;
    private final boolean bobbing;
    private final boolean shadows;
    private final boolean debug;
    private final boolean hideGui;
    private final int thirdPerson;
    private final boolean pauseOnLostFocus;

    private BenchSettings(GameSettings s) {
        renderDistance = s.renderDistanceChunks;
        fancy = s.fancyGraphics;
        ao = s.ambientOcclusion;
        particles = s.particleSetting;
        clouds = s.clouds;
        maxFps = s.limitFramerate;
        vsync = s.enableVsync;
        fov = s.fovSetting;
        bobbing = s.viewBobbing;
        shadows = s.entityShadows;
        debug = s.showDebugInfo;
        hideGui = s.hideGUI;
        thirdPerson = s.thirdPersonView;
        pauseOnLostFocus = s.pauseOnLostFocus;
    }

    /** Keeps the player's settings and applies the run's; returns what to restore. */
    static BenchSettings apply(Minecraft mc) {
        GameSettings s = mc.gameSettings;
        BenchSettings kept = new BenchSettings(s);
        // the route sees what a player sees: no F3, the HUD on, first person, and no pause menu
        // when the window loses focus
        s.showDebugInfo = false;
        s.hideGUI = false;
        s.thirdPersonView = 0;
        s.pauseOnLostFocus = false;
        if (!"player".equalsIgnoreCase(BenchOptions.SETTINGS)) {
            s.renderDistanceChunks = 12;
            s.fancyGraphics = true;
            s.ambientOcclusion = 2;
            s.particleSetting = 0;
            s.clouds = 2;
            s.limitFramerate = (int) GameSettings.Options.FRAMERATE_LIMIT.getValueMax();
            s.enableVsync = false;
            s.fovSetting = 70.0F;
            s.viewBobbing = true;
            s.entityShadows = true;
            Display.setVSyncEnabled(false);
            mc.renderGlobal.loadRenderers();
        }
        Bench.event(
                "settings: "
                        + BenchOptions.SETTINGS
                        + " (render distance "
                        + s.renderDistanceChunks
                        + ", max fps "
                        + s.limitFramerate
                        + ", vsync "
                        + s.enableVsync
                        + ")");
        return kept;
    }

    void restore(Minecraft mc) {
        GameSettings s = mc.gameSettings;
        boolean reload =
                s.renderDistanceChunks != renderDistance
                        || s.fancyGraphics != fancy
                        || s.ambientOcclusion != ao;
        s.renderDistanceChunks = renderDistance;
        s.fancyGraphics = fancy;
        s.ambientOcclusion = ao;
        s.particleSetting = particles;
        s.clouds = clouds;
        s.limitFramerate = maxFps;
        s.enableVsync = vsync;
        s.fovSetting = fov;
        s.viewBobbing = bobbing;
        s.entityShadows = shadows;
        s.showDebugInfo = debug;
        s.hideGUI = hideGui;
        s.thirdPersonView = thirdPerson;
        s.pauseOnLostFocus = pauseOnLostFocus;
        Display.setVSyncEnabled(vsync);
        if (reload && mc.renderGlobal != null && mc.theWorld != null) {
            mc.renderGlobal.loadRenderers();
        }
    }

    /** {@code bench.size=WxH}: the window at that size (out of maximized first). */
    static void resize(String size) {
        if (size == null || size.isBlank() || Win.handle == 0L) {
            return;
        }
        String[] wh = size.toLowerCase(java.util.Locale.ROOT).split("x");
        try {
            int w = Integer.parseInt(wh[0].trim());
            int h = Integer.parseInt(wh[1].trim());
            GLFW.glfwRestoreWindow(Win.handle);
            GLFW.glfwSetWindowSize(Win.handle, w, h);
            Bench.event("window set to " + w + "x" + h);
        } catch (RuntimeException e) {
            Log.warn("bench", "bench.size: not WxH: " + size);
        }
    }
}
