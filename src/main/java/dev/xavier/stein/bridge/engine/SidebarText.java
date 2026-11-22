/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import net.minecraft.client.gui.FontRenderer;

/**
 * Fix for the sidebar's text fading: GuiIngame.renderScoreboard draws its text opaque.
 *
 * <p>renderScoreboard draws the title, the names and the scores with the colour 0x20FFFFFF, white
 * with an alpha of 1/8 (the FontRenderer keeps any alpha above the lowest bits). It looks opaque in
 * 1.8.9 only because drawRect turns blending off just before, and the alpha test lets 1/8 through;
 * whenever blending was left on (by a mod's HUD element drawn before, a resource pack's shader) the
 * sidebar's text came out almost invisible. Here the same white is drawn with a full alpha, which
 * is what it looks like when nothing leaks. {@code -Dsteinbridge.opt.vanillaSidebarText=true}
 * brings back the 1/8.
 */
public final class SidebarText {

    private SidebarText() {}

    private static final boolean VANILLA = GuiFixOptions.VANILLA_SIDEBAR_TEXT;

    /** FontRenderer.drawString(text, x, y, color) in GuiIngame.renderScoreboard. */
    public static int drawString(Object font, Object text, int x, int y, int color) {
        int c = VANILLA ? color : color | 0xFF000000;
        return ((FontRenderer) font).drawString((String) text, x, y, c);
    }
}
