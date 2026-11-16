/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import dev.xavier.stein.bridge.core.Log;

import net.minecraft.client.gui.GuiOptionSlider;
import net.minecraft.client.settings.GameSettings;

/**
 * The game's mipmap slider applied once, on release.
 *
 * <p>GameSettings.setOptionFloatValue reloads every texture of the game (seconds of a frozen
 * screen) each time the mipmap level changes, and GuiOptionSlider calls it at every step of a drag:
 * dragging from 0 to 4 reloaded four times. From 1.14 the slider only applies on release. Here,
 * inside GuiOptionSlider's mousePressed and mouseDragged, a mipmap value is kept pending and its
 * label shows it; the slider's mouseReleased (and, should the screen close with the button still
 * down, {@link ScreenClose}) applies it through the game's own setOptionFloatValue, which reloads
 * once if the level changed. Stein's video screen has its own mipmap control, already applied on
 * release, which does not go through here. {@code -Dsteinbridge.opt.vanillaMipmapSlider=true}
 * brings back 1.8.9's behaviour.
 */
public final class MipmapSlider {

    private MipmapSlider() {}

    private static final boolean VANILLA = GuiFixOptions.VANILLA_MIPMAP_SLIDER;

    /** The settings and level the slider left pending; null when none. */
    private static GameSettings pendingSettings;

    private static int pendingLevel;

    /** In place of GameSettings.setOptionFloatValue in GuiOptionSlider's press and drag. */
    public static void set(Object settingsObject, Object option, float value) {
        GameSettings settings = (GameSettings) settingsObject;
        if (VANILLA || option != GameSettings.Options.MIPMAP_LEVELS) {
            settings.setOptionFloatValue((GameSettings.Options) option, value);
            return;
        }
        pendingSettings = settings;
        pendingLevel = (int) value;
    }

    /**
     * In place of GameSettings.getKeyBinding in the same two methods: the label of a pending level
     * is that level's.
     */
    public static Object label(Object settingsObject, Object option) {
        GameSettings settings = (GameSettings) settingsObject;
        GameSettings.Options o = (GameSettings.Options) option;
        if (o != GameSettings.Options.MIPMAP_LEVELS || pendingSettings != settings) {
            return settings.getKeyBinding(o);
        }
        int applied = settings.mipmapLevels;
        settings.mipmapLevels = pendingLevel;
        try {
            return settings.getKeyBinding(o);
        } finally {
            settings.mipmapLevels = applied;
        }
    }

    /** The body of GuiOptionSlider.mouseReleased: the drag ends and the pending level applies. */
    public static void mouseReleased(Object slider, int mouseX, int mouseY) {
        ((GuiOptionSlider) slider).dragging = false;
        flush();
    }

    /** Applies the pending level, if any. */
    static void flush() {
        GameSettings settings = pendingSettings;
        if (settings == null) {
            return;
        }
        pendingSettings = null;
        try {
            settings.setOptionFloatValue(GameSettings.Options.MIPMAP_LEVELS, pendingLevel);
        } catch (RuntimeException e) { // a failed reload must not take the screen down
            Log.info("options", "could not apply the mipmap level: " + e);
        }
    }
}
