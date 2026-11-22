/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import net.minecraft.client.Minecraft;

/**
 * Fix for fullscreen not being remembered: switching it with the key saves options.txt.
 *
 * <p>Minecraft.toggleFullscreen sets GameSettings.fullScreen but never saves it, so a player who
 * pressed F11 started windowed again next time (unless some other option happened to be saved in
 * between). After the switch (the Display.setFullscreen call in toggleFullscreen, which every way
 * of switching goes through) the options are saved; the write goes through {@link OptionsWriter}, a
 * temporary file moved over the old one. {@code -Dsteinbridge.opt.vanillaFullscreenSave=true}
 * leaves it unsaved, as 1.8.9.
 */
public final class FullscreenSave {

    private FullscreenSave() {}

    private static final boolean VANILLA = GuiFixOptions.VANILLA_FULLSCREEN_SAVE;

    /** After Display.setFullscreen in Minecraft.toggleFullscreen. */
    public static void switched() {
        Minecraft mc = Minecraft.getMinecraft();
        if (!VANILLA && mc != null && mc.gameSettings != null) {
            mc.gameSettings.saveOptions();
        }
    }
}
