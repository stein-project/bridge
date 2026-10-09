/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import dev.xavier.stein.bridge.core.CoreOptions;
import dev.xavier.stein.bridge.core.Win;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiIngameMenu;
import net.minecraft.client.gui.GuiScreen;

import org.lwjgl.glfw.GLFW;

/**
 * Frame limit while nobody is playing: out of focus (another window in front, minimized) and on the
 * title screen, 30 per second; on the Esc menu, 60. Drawing 300 frames of a screen nobody is
 * looking at only heats the processor and the graphics card. The limit the player chose still
 * applies when it is lower.
 *
 * <p>Called at the end of each frame (Display.update, after the buffer swap). On the render thread.
 */
public final class IdleLimit {

    /** Out of focus and on the title screen. */
    static final int IDLE_FPS = 30;

    /** On the Esc menu: the world is still behind it, so a little smoother. */
    static final int PAUSE_FPS = 60;

    // The test bench (minimized, unfocused window) measures the game at full speed: off there,
    // unless asked for, to test the limit itself.
    private static final boolean OFF =
            EngineOptions.NO_IDLE_LIMIT
                    || (CoreOptions.DIAG_BACKGROUND && !EngineOptions.IDLE_LIMIT_IN_BENCH);

    private static long next;

    private IdleLimit() {}

    /** Waits what is left until the next frame, while the game is idle. */
    public static void frame() {
        if (OFF) {
            return;
        }
        int fps = fps();
        if (fps <= 0) {
            next = 0;
            return;
        }
        long period = 1_000_000_000L / fps;
        long now = System.nanoTime();
        if (next == 0 || now - next > period * 4) {
            next = now; // first time, or a long stall: re-anchor
        }
        next += period;
        long remaining = next - System.nanoTime();
        // Sleeps the bulk and spins the rest: no need for a perfect frame time while idle, but no
        // whole extra millisecond per frame either.
        while (remaining > 0) {
            if (remaining > 2_000_000L) {
                try {
                    Thread.sleep(1L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            } else {
                Thread.onSpinWait();
            }
            remaining = next - System.nanoTime();
        }
    }

    /** This frame's limit, or 0 for none. */
    private static int fps() {
        if (Win.handle == 0L) {
            return 0;
        }
        // GLFW's own focus: it also covers a window that never got focus (opened behind another).
        if (GLFW.glfwGetWindowAttrib(Win.handle, GLFW.GLFW_FOCUSED) != GLFW.GLFW_TRUE) {
            return IDLE_FPS;
        }
        Minecraft mc = Minecraft.getMinecraft();
        GuiScreen screen = mc == null ? null : mc.currentScreen;
        if (screen == null) {
            return 0;
        }
        if (mc.theWorld == null) {
            return IDLE_FPS; // the title screen and its screens: options, servers, worlds
        }
        return screen instanceof GuiIngameMenu ? PAUSE_FPS : 0;
    }
}
