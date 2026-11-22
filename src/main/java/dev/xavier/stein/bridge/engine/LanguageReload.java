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

/**
 * Choosing a language reloads only the translations, not every resource.
 *
 * <p>The language screen's list called Minecraft.refreshResources on each click: every texture,
 * model, sound and font of every resource pack loaded again, several seconds with a large pack, for
 * a change that only touches the .lang files. Of the game's reload listeners only the
 * LanguageManager reads the language (the Stein Loader's and the mods' texts come from the same
 * .lang files, through I18n); the others would load exactly what they already have. Here the click
 * reloads the LanguageManager alone, which also hands the new table to StringTranslate (and so to
 * {@link NameCache}). The screen then sets the font's unicode and bidirectional flags from the new
 * language as before, and {@link ScreenClose} re-wraps the chat when the screen closes. {@code
 * -Dsteinbridge.opt.vanillaLanguageReload=true} brings back the full reload.
 */
public final class LanguageReload {

    private LanguageReload() {}

    private static final boolean VANILLA = GuiFixOptions.VANILLA_LANGUAGE_RELOAD;

    /** Minecraft.refreshResources() in GuiLanguage.List.elementClicked. */
    public static void languageChosen(Object minecraft) {
        Minecraft mc = (Minecraft) minecraft;
        if (VANILLA) {
            mc.refreshResources();
            return;
        }
        long start = System.nanoTime();
        mc.getLanguageManager().onResourceManagerReload(mc.getResourceManager());
        Log.info(
                "lang",
                "language "
                        + mc.gameSettings.language
                        + " loaded in "
                        + (System.nanoTime() - start) / 1_000_000L
                        + " ms (translations only)");
    }
}
