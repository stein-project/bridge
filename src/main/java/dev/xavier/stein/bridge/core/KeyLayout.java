/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.core;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_A;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_APOSTROPHE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSLASH;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_COMMA;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_EQUAL;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_GRAVE_ACCENT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_BRACKET;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_MINUS;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_PERIOD;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_BRACKET;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_SEMICOLON;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_SLASH;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_WORLD_1;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_WORLD_2;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_Z;
import static org.lwjgl.glfw.GLFW.GLFW_RELEASE;
import static org.lwjgl.glfw.GLFW.GLFW_REPEAT;

import dev.xavier.stein.bridge.engine.GuiFixOptions;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Platform;

/**
 * Translation of a key's physical position into the key its label names, under the player's
 * keyboard layout.
 *
 * <p>GLFW reports the key by its position on a US keyboard: on AZERTY the key labelled A comes as
 * GLFW_KEY_Q. LWJGL 2, which 1.8.9 shipped with, took the code from the label on Windows (the
 * virtual key) and on Linux (the X keysym): an AZERTY or QWERTZ player bound "Z" to the key that
 * says Z, saw that name in the controls menu, and Ctrl+A selected all with the key that says A.
 * Stein's GLFW shim had lost that, so binds landed on the wrong keys and their names were wrong.
 * Here letters and the US punctuation keys take the code of the character GLFW says they type
 * (glfwGetKeyName, unshifted); a key whose character is none LWJGL 2 has a code for (é, ù, ², the
 * dead keys) keeps its position. The digit row keeps its digits, as Windows' virtual keys do on
 * every layout (AZERTY types &amp; on 1, but the hotbar keys are still 1 to 9). macOS stays by
 * position: LWJGL 2 used the Mac's positional key codes there too.
 *
 * <p>A key's release gives the code its press gave, even if the layout changed while it was held:
 * otherwise the game would think the key is held forever. {@code
 * -Dsteinbridge.opt.vanillaPhysicalKeys=true} keeps every key by position.
 */
public final class KeyLayout {

    private KeyLayout() {}

    private static final boolean ACTIVE =
            !GuiFixOptions.VANILLA_PHYSICAL_KEYS && Platform.get() != Platform.MACOSX;

    /**
     * The key each held scancode was given at its press, plus one (0: none). Windows scancodes go
     * up to 0x1FF (extended keys), X11 and Wayland keycodes up to 255.
     */
    private static final int[] HELD = new int[512];

    /**
     * The GLFW key the label of {@code glfwKey} names, for the key callback; the key itself when it
     * is not a letter or punctuation key, when the label is not one LWJGL 2 knows, or for a
     * diagnostic key (scancode 0, layout-independent on purpose). On the main thread (the GLFW
     * callbacks run in glfwPollEvents), and with no allocation.
     */
    public static int labelKey(int glfwKey, int scancode, int action) {
        if (!ACTIVE || scancode <= 0 || !translatable(glfwKey)) {
            return glfwKey;
        }
        if (scancode >= HELD.length) {
            return translate(glfwKey, scancode);
        }
        int held = HELD[scancode];
        if (action == GLFW_RELEASE) {
            HELD[scancode] = 0;
            return held != 0 ? held - 1 : translate(glfwKey, scancode);
        }
        if (action == GLFW_REPEAT && held != 0) {
            return held - 1;
        }
        int key = translate(glfwKey, scancode);
        HELD[scancode] = key + 1;
        return key;
    }

    /** The keys whose label changes with the layout and that LWJGL 2 named by it. */
    private static boolean translatable(int glfwKey) {
        if (glfwKey >= GLFW_KEY_A && glfwKey <= GLFW_KEY_Z) {
            return true;
        }
        return switch (glfwKey) {
            case GLFW_KEY_APOSTROPHE,
                    GLFW_KEY_COMMA,
                    GLFW_KEY_MINUS,
                    GLFW_KEY_PERIOD,
                    GLFW_KEY_SLASH,
                    GLFW_KEY_SEMICOLON,
                    GLFW_KEY_EQUAL,
                    GLFW_KEY_LEFT_BRACKET,
                    GLFW_KEY_BACKSLASH,
                    GLFW_KEY_RIGHT_BRACKET,
                    GLFW_KEY_GRAVE_ACCENT,
                    GLFW_KEY_WORLD_1,
                    GLFW_KEY_WORLD_2 ->
                    true;
            default -> false;
        };
    }

    /**
     * The key named by the single ASCII character GLFW gives for this key, or the key itself. The
     * name is read straight from GLFW's UTF-8 string: a non-ASCII first byte is negative and falls
     * back.
     */
    private static int translate(int glfwKey, int scancode) {
        long name;
        try {
            name = GLFW.nglfwGetKeyName(glfwKey, scancode);
        } catch (Throwable t) { // input must never crash the game
            return glfwKey;
        }
        if (name == 0L || MemoryUtil.memGetByte(name + 1) != 0) {
            return glfwKey;
        }
        int key = keyOf(MemoryUtil.memGetByte(name));
        return key != 0 ? key : glfwKey;
    }

    /** The GLFW key typing this ASCII character unshifted on a US keyboard; 0 when none. */
    static int keyOf(int c) {
        if (c >= 'a' && c <= 'z') {
            return GLFW_KEY_A + (c - 'a');
        }
        if (c >= 'A' && c <= 'Z') {
            return GLFW_KEY_A + (c - 'A');
        }
        return switch (c) {
            case '\'' -> GLFW_KEY_APOSTROPHE;
            case ',' -> GLFW_KEY_COMMA;
            case '-' -> GLFW_KEY_MINUS;
            case '.' -> GLFW_KEY_PERIOD;
            case '/' -> GLFW_KEY_SLASH;
            case ';' -> GLFW_KEY_SEMICOLON;
            case '=' -> GLFW_KEY_EQUAL;
            case '[' -> GLFW_KEY_LEFT_BRACKET;
            case '\\' -> GLFW_KEY_BACKSLASH;
            case ']' -> GLFW_KEY_RIGHT_BRACKET;
            case '`' -> GLFW_KEY_GRAVE_ACCENT;
            default -> 0;
        };
    }
}
