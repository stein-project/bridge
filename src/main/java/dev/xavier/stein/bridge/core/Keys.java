/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.core;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_0;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_1;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_2;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_3;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_4;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_5;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_6;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_7;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_8;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_9;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_A;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_APOSTROPHE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_B;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSLASH;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_C;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_CAPS_LOCK;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_COMMA;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_D;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_DELETE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_E;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_END;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_EQUAL;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F1;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F10;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F11;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F12;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F13;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F14;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F15;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F16;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F17;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F18;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F2;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F3;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F4;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F5;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F6;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F7;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F8;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F9;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_G;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_GRAVE_ACCENT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_H;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_HOME;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_I;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_INSERT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_J;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_K;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_KP_0;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_KP_1;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_KP_2;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_KP_3;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_KP_4;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_KP_5;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_KP_6;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_KP_7;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_KP_8;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_KP_9;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ADD;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_KP_DECIMAL;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_KP_DIVIDE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_KP_EQUAL;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_KP_MULTIPLY;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_KP_SUBTRACT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_L;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LAST;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_ALT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_BRACKET;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_CONTROL;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SUPER;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_M;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_MENU;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_MINUS;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_N;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_NUM_LOCK;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_O;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_P;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_DOWN;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_UP;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_PAUSE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_PERIOD;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_PRINT_SCREEN;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_Q;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_R;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_ALT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_BRACKET;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_CONTROL;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_SHIFT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_SUPER;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_S;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_SCROLL_LOCK;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_SEMICOLON;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_SLASH;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_T;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_TAB;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_U;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_UP;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_V;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_W;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_X;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_Y;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_Z;

/**
 * Translation between GLFW key codes and LWJGL 2 ones.
 *
 * <p>The LWJGL 2 codes are DirectInput scan codes (DIK_*), and the user's {@code options.txt}
 * stores exactly those numbers. Getting this wrong breaks keybinds of existing installations, so
 * the table has to be complete, not approximate.
 */
public final class Keys {

    /** Indexed by GLFW code; the value is the LWJGL 2 code, or 0 if unknown. */
    private static final int[] TO_LWJGL = new int[GLFW_KEY_LAST + 1];

    /** Indexed by LWJGL 2 code; name shown in the controls menu. */
    private static final String[] NAMES = new String[256];

    private Keys() {}

    private static void map(int glfw, int lwjgl, String name) {
        if (glfw >= 0 && glfw < TO_LWJGL.length) {
            TO_LWJGL[glfw] = lwjgl;
        }
        NAMES[lwjgl] = name;
    }

    static {
        map(GLFW_KEY_ESCAPE, 0x01, "ESCAPE");
        map(GLFW_KEY_1, 0x02, "1");
        map(GLFW_KEY_2, 0x03, "2");
        map(GLFW_KEY_3, 0x04, "3");
        map(GLFW_KEY_4, 0x05, "4");
        map(GLFW_KEY_5, 0x06, "5");
        map(GLFW_KEY_6, 0x07, "6");
        map(GLFW_KEY_7, 0x08, "7");
        map(GLFW_KEY_8, 0x09, "8");
        map(GLFW_KEY_9, 0x0A, "9");
        map(GLFW_KEY_0, 0x0B, "0");
        map(GLFW_KEY_MINUS, 0x0C, "MINUS");
        map(GLFW_KEY_EQUAL, 0x0D, "EQUALS");
        map(GLFW_KEY_BACKSPACE, 0x0E, "BACK");
        map(GLFW_KEY_TAB, 0x0F, "TAB");

        map(GLFW_KEY_Q, 0x10, "Q");
        map(GLFW_KEY_W, 0x11, "W");
        map(GLFW_KEY_E, 0x12, "E");
        map(GLFW_KEY_R, 0x13, "R");
        map(GLFW_KEY_T, 0x14, "T");
        map(GLFW_KEY_Y, 0x15, "Y");
        map(GLFW_KEY_U, 0x16, "U");
        map(GLFW_KEY_I, 0x17, "I");
        map(GLFW_KEY_O, 0x18, "O");
        map(GLFW_KEY_P, 0x19, "P");
        map(GLFW_KEY_LEFT_BRACKET, 0x1A, "LBRACKET");
        map(GLFW_KEY_RIGHT_BRACKET, 0x1B, "RBRACKET");
        map(GLFW_KEY_ENTER, 0x1C, "RETURN");
        map(GLFW_KEY_LEFT_CONTROL, 0x1D, "LCONTROL");

        map(GLFW_KEY_A, 0x1E, "A");
        map(GLFW_KEY_S, 0x1F, "S");
        map(GLFW_KEY_D, 0x20, "D");
        map(GLFW_KEY_F, 0x21, "F");
        map(GLFW_KEY_G, 0x22, "G");
        map(GLFW_KEY_H, 0x23, "H");
        map(GLFW_KEY_J, 0x24, "J");
        map(GLFW_KEY_K, 0x25, "K");
        map(GLFW_KEY_L, 0x26, "L");
        map(GLFW_KEY_SEMICOLON, 0x27, "SEMICOLON");
        map(GLFW_KEY_APOSTROPHE, 0x28, "APOSTROPHE");
        map(GLFW_KEY_GRAVE_ACCENT, 0x29, "GRAVE");
        map(GLFW_KEY_LEFT_SHIFT, 0x2A, "LSHIFT");
        map(GLFW_KEY_BACKSLASH, 0x2B, "BACKSLASH");

        map(GLFW_KEY_Z, 0x2C, "Z");
        map(GLFW_KEY_X, 0x2D, "X");
        map(GLFW_KEY_C, 0x2E, "C");
        map(GLFW_KEY_V, 0x2F, "V");
        map(GLFW_KEY_B, 0x30, "B");
        map(GLFW_KEY_N, 0x31, "N");
        map(GLFW_KEY_M, 0x32, "M");
        map(GLFW_KEY_COMMA, 0x33, "COMMA");
        map(GLFW_KEY_PERIOD, 0x34, "PERIOD");
        map(GLFW_KEY_SLASH, 0x35, "SLASH");
        map(GLFW_KEY_RIGHT_SHIFT, 0x36, "RSHIFT");
        map(GLFW_KEY_KP_MULTIPLY, 0x37, "MULTIPLY");
        map(GLFW_KEY_LEFT_ALT, 0x38, "LMENU");
        map(GLFW_KEY_SPACE, 0x39, "SPACE");
        map(GLFW_KEY_CAPS_LOCK, 0x3A, "CAPITAL");

        map(GLFW_KEY_F1, 0x3B, "F1");
        map(GLFW_KEY_F2, 0x3C, "F2");
        map(GLFW_KEY_F3, 0x3D, "F3");
        map(GLFW_KEY_F4, 0x3E, "F4");
        map(GLFW_KEY_F5, 0x3F, "F5");
        map(GLFW_KEY_F6, 0x40, "F6");
        map(GLFW_KEY_F7, 0x41, "F7");
        map(GLFW_KEY_F8, 0x42, "F8");
        map(GLFW_KEY_F9, 0x43, "F9");
        map(GLFW_KEY_F10, 0x44, "F10");
        map(GLFW_KEY_NUM_LOCK, 0x45, "NUMLOCK");
        map(GLFW_KEY_SCROLL_LOCK, 0x46, "SCROLL");

        map(GLFW_KEY_KP_7, 0x47, "NUMPAD7");
        map(GLFW_KEY_KP_8, 0x48, "NUMPAD8");
        map(GLFW_KEY_KP_9, 0x49, "NUMPAD9");
        map(GLFW_KEY_KP_SUBTRACT, 0x4A, "SUBTRACT");
        map(GLFW_KEY_KP_4, 0x4B, "NUMPAD4");
        map(GLFW_KEY_KP_5, 0x4C, "NUMPAD5");
        map(GLFW_KEY_KP_6, 0x4D, "NUMPAD6");
        map(GLFW_KEY_KP_ADD, 0x4E, "ADD");
        map(GLFW_KEY_KP_1, 0x4F, "NUMPAD1");
        map(GLFW_KEY_KP_2, 0x50, "NUMPAD2");
        map(GLFW_KEY_KP_3, 0x51, "NUMPAD3");
        map(GLFW_KEY_KP_0, 0x52, "NUMPAD0");
        map(GLFW_KEY_KP_DECIMAL, 0x53, "DECIMAL");

        map(GLFW_KEY_F11, 0x57, "F11");
        map(GLFW_KEY_F12, 0x58, "F12");
        map(GLFW_KEY_F13, 0x64, "F13");
        map(GLFW_KEY_F14, 0x65, "F14");
        map(GLFW_KEY_F15, 0x66, "F15");
        map(GLFW_KEY_F16, 0x67, "F16");
        map(GLFW_KEY_F17, 0x68, "F17");
        map(GLFW_KEY_F18, 0x69, "F18");

        map(GLFW_KEY_KP_EQUAL, 0x8D, "NUMPADEQUALS");
        map(GLFW_KEY_KP_ENTER, 0x9C, "NUMPADENTER");
        map(GLFW_KEY_RIGHT_CONTROL, 0x9D, "RCONTROL");
        map(GLFW_KEY_KP_DIVIDE, 0xB5, "DIVIDE");
        map(GLFW_KEY_PRINT_SCREEN, 0xB7, "SYSRQ");
        map(GLFW_KEY_RIGHT_ALT, 0xB8, "RMENU");
        map(GLFW_KEY_PAUSE, 0xC5, "PAUSE");

        map(GLFW_KEY_HOME, 0xC7, "HOME");
        map(GLFW_KEY_UP, 0xC8, "UP");
        map(GLFW_KEY_PAGE_UP, 0xC9, "PRIOR");
        map(GLFW_KEY_LEFT, 0xCB, "LEFT");
        map(GLFW_KEY_RIGHT, 0xCD, "RIGHT");
        map(GLFW_KEY_END, 0xCF, "END");
        map(GLFW_KEY_DOWN, 0xD0, "DOWN");
        map(GLFW_KEY_PAGE_DOWN, 0xD1, "NEXT");
        map(GLFW_KEY_INSERT, 0xD2, "INSERT");
        map(GLFW_KEY_DELETE, 0xD3, "DELETE");
        map(GLFW_KEY_LEFT_SUPER, 0xDB, "LMETA");
        map(GLFW_KEY_RIGHT_SUPER, 0xDC, "RMETA");
        map(GLFW_KEY_MENU, 0xDD, "APPS");

        NAMES[0] = "NONE";
    }

    /**
     * @return LWJGL 2 code, or 0 when GLFW reports an unknown key
     */
    public static int toLwjgl(int glfwKey) {
        if (glfwKey < 0 || glfwKey >= TO_LWJGL.length) {
            return 0;
        }
        return TO_LWJGL[glfwKey];
    }

    public static String name(int lwjglKey) {
        if (lwjglKey < 0 || lwjglKey >= NAMES.length) {
            return null;
        }
        return NAMES[lwjglKey];
    }
}
