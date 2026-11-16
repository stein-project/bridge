/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import dev.xavier.stein.bridge.core.Log;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiControls;
import net.minecraft.client.gui.GuiCustomizeSkin;
import net.minecraft.client.gui.GuiLanguage;
import net.minecraft.client.gui.GuiOptions;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiScreenOptionsSounds;
import net.minecraft.client.gui.GuiSnooper;
import net.minecraft.client.gui.GuiVideoSettings;
import net.minecraft.client.gui.ScreenChatOptions;

/**
 * Saving the options when an options screen closes.
 *
 * <p>In 1.8.9 the options screens write options.txt only on their Done button: closing with Escape
 * lost the sliders moved there (field of view, sensitivity, brightness, render distance, the
 * chat's), and the Controls screen never saved at all, so a key changed there (or reset) came back
 * at the next start unless another screen saved after it. From 1.14 every options screen saves when
 * it is removed; here, in place of the GuiScreen.onGuiClosed that Minecraft.displayGuiScreen calls
 * on the screen it leaves, the game's own saveOptions runs after it for the options screens.
 * saveOptions also sends the client settings to the server, which {@link ClientSettings} sends only
 * when they changed.
 *
 * <p>Leaving the language screen (a language picked or Force Unicode switched) also re-wraps the
 * chat: its lines were cut with the old font's widths and ran over or stopped short until a new
 * message came. A mipmap level the slider left pending ({@link MipmapSlider}) is applied first, so
 * it is the one saved. {@code -Dsteinbridge.opt.vanillaOptionsSave=true} brings back 1.8.9's
 * behaviour.
 */
public final class ScreenClose {

    private ScreenClose() {}

    private static final boolean VANILLA = GuiFixOptions.VANILLA_OPTIONS_SAVE;

    /** In place of currentScreen.onGuiClosed() in Minecraft.displayGuiScreen. */
    public static void onGuiClosed(Object screenObject) {
        GuiScreen screen = (GuiScreen) screenObject;
        screen.onGuiClosed();
        MipmapSlider.flush();
        if (VANILLA || !isOptionsScreen(screen)) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        try {
            mc.gameSettings.saveOptions();
            if (screen instanceof GuiLanguage && mc.ingameGUI != null) {
                mc.ingameGUI.getChatGUI().refreshChat();
            }
        } catch (RuntimeException e) { // closing a screen must never crash the game
            Log.info("options", "could not save the options on closing a screen: " + e);
        }
    }

    private static boolean isOptionsScreen(GuiScreen screen) {
        return screen instanceof GuiOptions
                || screen instanceof GuiVideoSettings
                || screen instanceof GuiScreenOptionsSounds
                || screen instanceof GuiControls
                || screen instanceof ScreenChatOptions
                || screen instanceof GuiCustomizeSkin
                || screen instanceof GuiLanguage
                || screen instanceof GuiSnooper;
    }
}
