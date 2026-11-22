/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.inventory.Slot;

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
 *
 * <p>The other keys keyTyped checks get the same treatment: the inventory key on a mouse button
 * closes the inventory (it could open it but not close it), and the drop key drops the item under
 * the cursor (the whole stack with Ctrl), the same click the keyboard sends. Pick block already
 * works on the mouse in 1.8.9. {@code -Dsteinbridge.opt.vanillaMouseKeys=true} turns these two off;
 * the keys outside inventories are in {@link MouseKeys}.
 */
public final class MouseHotbar {
    private MouseHotbar() {}

    private static final boolean VANILLA = EngineOptions.VANILLA_MOUSE_HOTBAR;

    private static final boolean VANILLA_KEYS = GuiFixOptions.VANILLA_MOUSE_KEYS;

    /** In place of screen.mouseClicked(x, y, button) inside GuiScreen.handleMouseInput. */
    public static void mouseClicked(Object screen, int x, int y, int button) {
        if (!VANILLA
                && button >= 0
                && screen instanceof GuiContainer container
                && container.checkHotbarKeys(button - 100)) {
            return;
        }
        if (!VANILLA_KEYS
                && button >= 0
                && screen instanceof GuiContainer container
                && containerKey(container, button - 100)) {
            return;
        }
        ((GuiScreen) screen).mouseClicked(x, y, button);
    }

    /** The inventory and drop keys of GuiContainer.keyTyped, for a key on a mouse button. */
    private static boolean containerKey(GuiContainer container, int code) {
        GameSettings s = Minecraft.getMinecraft().gameSettings;
        if (code == s.keyBindInventory.getKeyCode()) {
            Minecraft.getMinecraft().thePlayer.closeScreen();
            return true;
        }
        Slot slot = container.theSlot;
        if (code == s.keyBindDrop.getKeyCode() && slot != null && slot.getHasStack()) {
            container.handleMouseClick(slot, slot.slotNumber, GuiScreen.isCtrlKeyDown() ? 1 : 0, 4);
            return true;
        }
        return false;
    }
}
