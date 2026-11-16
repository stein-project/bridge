/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;

/**
 * Fix for hotbar keys bound to mouse buttons doing nothing in inventories.
 *
 * <p>A key binding on a mouse button is stored as {@code button - 100}, and GuiContainer only
 * checks the hotbar keys in keyTyped, for the keyboard: a player with slots on the side buttons
 * could not move items to the hotbar from a chest or the inventory. Here the click that
 * GuiScreen.handleMouseInput hands to mouseClicked first goes through GuiContainer's own
 * checkHotbarKeys; when a hotbar key is bound to that button and a slot is under the cursor, the
 * click is that swap — the same click the keyboard sends to the server — and nothing else. {@code
 * -Dsteinbridge.opt.vanillaMouseHotbar=true} brings back 1.8.9's behaviour.
 */
public final class MouseHotbar {
    private MouseHotbar() {}

    private static final boolean VANILLA = EngineOptions.VANILLA_MOUSE_HOTBAR;

    /** In place of screen.mouseClicked(x, y, button) inside GuiScreen.handleMouseInput. */
    public static void mouseClicked(Object screen, int x, int y, int button) {
        if (!VANILLA
                && button >= 0
                && screen instanceof GuiContainer container
                && container.checkHotbarKeys(button - 100)) {
            return;
        }
        ((GuiScreen) screen).mouseClicked(x, y, button);
    }
}
