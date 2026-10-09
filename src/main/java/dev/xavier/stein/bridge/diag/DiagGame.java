/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

package dev.xavier.stein.bridge.diag;

import dev.xavier.stein.bridge.core.Input;
import dev.xavier.stein.bridge.core.Log;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;

/**
 * Shortcuts to the game for the diagnostic scenes: the Minecraft instance and its commonly used
 * fields, opening a screen, the GUI scale, injected mouse clicks and key presses.
 */
final class DiagGame {
    private DiagGame() {}

    /** {@code Minecraft.getMinecraft()}. */
    static Minecraft mc() {
        return Minecraft.getMinecraft();
    }

    /** {@code Minecraft.thePlayer}, null outside a world. */
    static EntityPlayerSP player() {
        return mc().thePlayer;
    }

    /** {@code Minecraft.theWorld}, null in the menu. */
    static WorldClient world() {
        return mc().theWorld;
    }

    /** {@code Minecraft.gameSettings}. */
    static GameSettings settings() {
        return mc().gameSettings;
    }

    /** {@code Minecraft.currentScreen}, null when no screen is open. */
    static GuiScreen currentScreen() {
        return mc().currentScreen;
    }

    /** {@code Minecraft.displayGuiScreen(screen)}; null closes the current screen. */
    static void open(GuiScreen screen) {
        mc().displayGuiScreen(screen);
    }

    /** The GUI scale factor: the GUI counts in points, GLFW in window pixels. */
    static int guiScale() {
        return new ScaledResolution(mc()).getScaleFactor();
    }

    /**
     * A click with {@code button} at window pixel ({@code x}, {@code y}) through the GLFW
     * callbacks: moves the cursor, presses and releases.
     */
    static void click(double x, double y, int button) {
        Input.injectMouse(x, y, -1, false);
        Input.injectMouse(x, y, button, true);
        Input.injectMouse(x, y, button, false);
    }

    /**
     * Presses or releases the key with the LWJGL 2 code (mouse: -100 left, -99 right) through
     * KeyBinding.setKeyBindState; a press also counts as a click (KeyBinding.onTick).
     */
    static void setKey(int code, boolean down) {
        KeyBinding.setKeyBindState(code, down);
        if (down) {
            KeyBinding.onTick(code);
        }
        Log.info("diag", "key " + code + (down ? " pressed" : " released"));
    }
}
