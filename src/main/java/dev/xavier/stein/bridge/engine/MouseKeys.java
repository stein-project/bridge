/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.ScreenShotHelper;

/**
 * Fix for keys that do nothing on a mouse button: perspective, smooth camera, fullscreen and
 * screenshot.
 *
 * <p>A key binding on a mouse button is stored as {@code button - 100}. Minecraft.runTick checks
 * the perspective and smooth camera keys only while handling a keyboard event, and
 * dispatchKeypresses compares the fullscreen and screenshot keys only with the keyboard's key:
 * bound to a side button, the perspective changed only at the next key typed, and fullscreen and
 * the screenshot never happened. Here each mouse press that runTick hands to KeyBinding.onTick also
 * does what the keyboard would. runTick reads the mouse this way only while playing (no screen, or
 * one that lets the game take input); in other screens the mouse goes to the screen, as in 1.8.9.
 * Keyboard keys pass through untouched. Later versions handle every key the same way. The keys
 * inside inventories are in {@link MouseHotbar}. {@code -Dsteinbridge.opt.vanillaMouseKeys=true}
 * brings back 1.8.9's behaviour.
 */
public final class MouseKeys {

    private MouseKeys() {}

    private static final boolean VANILLA = GuiFixOptions.VANILLA_MOUSE_KEYS;

    /** KeyBinding.onTick(code) in Minecraft.runTick (the mouse and keyboard loops). */
    public static void onTick(int code) {
        KeyBinding.onTick(code);
        if (VANILLA || code >= 0) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        GameSettings s = mc.gameSettings;
        if (mc.currentScreen == null) {
            if (s.keyBindTogglePerspective.getKeyCode() == code
                    && s.keyBindTogglePerspective.isPressed()) {
                togglePerspective(mc);
            }
            if (s.keyBindSmoothCamera.getKeyCode() == code && s.keyBindSmoothCamera.isPressed()) {
                s.smoothCamera = !s.smoothCamera;
            }
        }
        if (s.keyBindFullscreen.getKeyCode() == code) {
            mc.toggleFullscreen();
        } else if (s.keyBindScreenshot.getKeyCode() == code && mc.ingameGUI != null) {
            mc.ingameGUI
                    .getChatGUI()
                    .printChatMessage(
                            ScreenShotHelper.saveScreenshot(
                                    mc.mcDataDir,
                                    mc.displayWidth,
                                    mc.displayHeight,
                                    mc.getFramebuffer()));
        }
    }

    /** What runTick does for the perspective key. */
    private static void togglePerspective(Minecraft mc) {
        GameSettings s = mc.gameSettings;
        s.thirdPersonView++;
        if (s.thirdPersonView > 2) {
            s.thirdPersonView = 0;
        }
        if (s.thirdPersonView == 0) {
            mc.entityRenderer.loadEntityShader(mc.getRenderViewEntity());
        } else if (s.thirdPersonView == 1) {
            mc.entityRenderer.loadEntityShader(null);
        }
        mc.renderGlobal.setDisplayListEntitiesDirty();
    }
}
