/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;

import org.lwjgl.input.Keyboard;

/**
 * Re-reading the held keys when the game takes the focus back from a screen.
 *
 * <p>Opening a screen releases every KeyBinding (unPressAllKeys), and while it is open the keyboard
 * goes to the screen, so a key held through the closing (W while closing the inventory or the chat)
 * did nothing until it was let go and pressed again: the player stood still with W down. From 1.12
 * Minecraft.setIngameFocus reads the keyboard again (KeyBinding.updateKeyBindState). Here, after
 * the mouse is grabbed in setIngameFocus, every key binding on a keyboard key takes the key's
 * state; only the held state, so nothing counts as a new press (the inventory key that closed the
 * screen does not reopen it). The mouse buttons stay as they are, as in 1.12. {@code
 * -Dsteinbridge.opt.vanillaKeyResync=true} brings back 1.8.9's behaviour.
 */
public final class KeyResync {

    private KeyResync() {}

    private static final boolean VANILLA = GuiFixOptions.VANILLA_KEY_RESYNC;

    /** After MouseHelper.grabMouseCursor in Minecraft.setIngameFocus. */
    public static void focusBack() {
        if (VANILLA) {
            return;
        }
        for (KeyBinding binding : Minecraft.getMinecraft().gameSettings.keyBindings) {
            int code = binding.getKeyCode();
            if (code > 0 && code < 256) {
                KeyBinding.setKeyBindState(code, Keyboard.isKeyDown(code));
            }
        }
    }
}
