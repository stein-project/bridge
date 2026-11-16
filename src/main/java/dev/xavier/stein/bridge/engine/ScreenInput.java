/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import net.minecraft.client.Minecraft;

import org.lwjgl.input.Keyboard;

/**
 * Fixes for where a screen's keyboard events go.
 *
 * <ul>
 *   <li>GuiScreen.handleInput reads the mouse queue and then the keyboard queue. When a click in
 *       the same tick replaced the screen (a button that opens another one, or closes it), the keys
 *       still went to the old screen, which was no longer shown: a key typed right after clicking
 *       "Chat Settings..." or a server in the list did its thing on the screen that was left. From
 *       1.13 the events go to the screen on show; here the old screen stops reading the keyboard,
 *       and the keys stay in the queue for the new screen (or the world) on the next tick. {@code
 *       -Dsteinbridge.opt.vanillaScreenInput=true} brings back 1.8.9's behaviour.
 *   <li>Minecraft.dispatchKeypresses takes the character as the key code when an event has no key.
 *       The shim gives every typed character its key, as LWJGL 2 did (core/Input), but a character
 *       from an IME or a virtual keyboard has none, so typing '&lt;' (60, F2) in the chat took a
 *       screenshot and 'W' (87, F11) toggled full screen. From 1.13 only key codes count; here an
 *       event without a key gives no code to dispatchKeypresses. {@code
 *       -Dsteinbridge.opt.vanillaCharKeys=true} brings back 1.8.9's behaviour.
 * </ul>
 */
public final class ScreenInput {

    private ScreenInput() {}

    private static final boolean VANILLA_SCREEN = GuiFixOptions.VANILLA_SCREEN_INPUT;
    private static final boolean VANILLA_CHAR = GuiFixOptions.VANILLA_CHAR_KEYS;

    /** The screen on show when its handleInput began. */
    private static Object reading;

    /** Before GuiScreen.handleInput's first Mouse.isCreated: notes the screen on show. */
    public static void inputBegin() {
        reading = Minecraft.getMinecraft().currentScreen;
    }

    /**
     * In place of Keyboard.next in GuiScreen.handleInput: no more events once a click of this same
     * call put another screen on show.
     */
    public static boolean keyboardNext() {
        if (!VANILLA_SCREEN && Minecraft.getMinecraft().currentScreen != reading) {
            return false;
        }
        return Keyboard.next();
    }

    /**
     * In place of Keyboard.getEventCharacter in Minecraft.dispatchKeypresses, read only for an
     * event with no key: no code, so the method returns at once.
     */
    public static char dispatchCharacter() {
        return VANILLA_CHAR ? Keyboard.getEventCharacter() : '\0';
    }
}
