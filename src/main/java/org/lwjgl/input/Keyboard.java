/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

package org.lwjgl.input;

import dev.xavier.stein.bridge.core.Keys;
import dev.xavier.stein.bridge.core.Win;

/**
 * LWJGL 2 keyboard API on top of GLFW.
 *
 * <p>The codes returned by {@link #getEventKey()} are LWJGL 2's (DirectInput scan codes), not
 * GLFW's — the user's {@code options.txt} stores those values. The translation is in {@link Keys}.
 */
public final class Keyboard {

    public static final int KEY_NONE = 0x00;
    public static final int KEY_ESCAPE = 0x01;
    public static final int KEY_RETURN = 0x1C;
    public static final int KEY_LSHIFT = 0x2A;
    public static final int KEY_LCONTROL = 0x1D;
    public static final int KEY_SPACE = 0x39;

    private static final int KEY_COUNT = Win.keysDown.length;

    /** Names recorded by mods (e.g. joystick keys); takes precedence over the table. */
    private static final String[] keyName = new String[KEY_COUNT];

    // Key codes (LWJGL 2 / DirectInput) of the modifiers, and the Windows virtual keys they map to.
    private static final int KEY_RCONTROL = 157;
    private static final int KEY_RSHIFT = 54;
    private static final int KEY_LMENU = 56;
    private static final int KEY_RMENU = 184;

    private Keyboard() {}

    public static boolean isCreated() {
        return Win.isCreated();
    }

    public static boolean next() {
        Win.KeyEvent e = Win.keyEvents.pollFirst();
        if (e == null) {
            return false;
        }
        Win.currentKey = e;
        return true;
    }

    public static int getEventKey() {
        return Win.currentKey.key;
    }

    public static boolean getEventKeyState() {
        return Win.currentKey.state;
    }

    public static char getEventCharacter() {
        return Win.currentKey.character;
    }

    public static boolean isRepeatEvent() {
        return Win.currentKey.repeat;
    }

    public static boolean isKeyDown(int key) {
        if (key <= 0 || key >= Win.keysDown.length || !Win.keysDown[key]) {
            return false;
        }
        int vk = modifierVk(key);
        if (vk != 0 && Win.focused && !physicallyDown(vk)) {
            // This modifier's "released" was lost (AltGr, which Windows sends as left Ctrl + right
            // Alt, a system shortcut, an overlay): the game would think Ctrl is held forever, and
            // in the chat every letter would become a shortcut (A selects all). Windows says it is
            // released: so it is.
            Win.keysDown[key] = false;
            return false;
        }
        return true;
    }

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("win");

    /**
     * The Windows virtual-key code of the modifiers (Ctrl, Shift, Alt on both sides); 0 for the
     * others.
     */
    private static int modifierVk(int key) {
        if (!WINDOWS) {
            return 0;
        }
        return switch (key) {
            case KEY_LCONTROL -> 0xA2;
            case KEY_RCONTROL -> 0xA3;
            case KEY_LSHIFT -> 0xA0;
            case KEY_RSHIFT -> 0xA1;
            case KEY_LMENU -> 0xA4;
            case KEY_RMENU -> 0xA5;
            default -> 0;
        };
    }

    private static boolean physicallyDown(int vk) {
        try {
            return (org.lwjgl.system.windows.User32.GetAsyncKeyState(vk) & 0x8000) != 0;
        } catch (Throwable t) { // input polling must never crash the game
            return true; // no way to ask: keep what the game knows
        }
    }

    /** Name shown in the controls menu; null for an unknown code. */
    public static String getKeyName(int key) {
        String custom = key >= 0 && key < keyName.length ? keyName[key] : null;
        return custom != null ? custom : Keys.name(key);
    }

    /**
     * When on, held keys generate repeated events — the game enables this in text fields and turns
     * it off in the world.
     */
    public static void enableRepeatEvents(boolean enable) {
        Win.repeatEvents = enable;
    }

    public static boolean areRepeatEventsEnabled() {
        return Win.repeatEvents;
    }
}
