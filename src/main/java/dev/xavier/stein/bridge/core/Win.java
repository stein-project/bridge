/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.core;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

/**
 * Shared state of the window and the event queues.
 *
 * <p>In LWJGL 2, {@code Display}, {@code Mouse} and {@code Keyboard} are static and implicitly
 * coupled to the same window. They live in different packages ({@code org.lwjgl.opengl} and {@code
 * org.lwjgl.input}), so the common state needs to live in a third public place.
 *
 * <p>The central translation: LWJGL 2 exposes an <b>event queue</b> consumed by {@code next()},
 * while GLFW delivers <b>callbacks</b>. We buffer here.
 */
public final class Win {

    /** Size of the window before the game asks for another. */
    private static final int DEFAULT_WIDTH = 854;

    private static final int DEFAULT_HEIGHT = 480;

    /** GLFW handle of the window, or 0 if not created. */
    public static long handle;

    /**
     * The window has an OpenGL core context (4.6 or 4.1), so Stein GFX draws; false: the
     * compatibility context and the game's fixed-function pipeline. Set by Display before anything
     * reads {@code SteinGfxOptions}.
     */
    public static boolean coreContext;

    /** Framebuffer size: what OpenGL sees. Governs Display.getWidth. */
    public static int width = DEFAULT_WIDTH;

    public static int height = DEFAULT_HEIGHT;

    /**
     * Window size in screen coordinates. It differs from the framebuffer on scaled screens; the
     * ratio between the two corrects the mouse coordinates, which GLFW reports in window
     * coordinates.
     */
    public static int windowWidth = DEFAULT_WIDTH;

    public static int windowHeight = DEFAULT_HEIGHT;

    public static boolean resized;

    /** Set by the callbacks; consumed in update to remeasure outside the callback. */
    public static boolean sizeDirty;

    public static boolean focused = true;
    public static boolean fullscreen;

    /** Window position before entering fullscreen, to restore afterwards. */
    public static int windowedX;

    public static int windowedY;
    public static int windowedWidth = DEFAULT_WIDTH;
    public static int windowedHeight = DEFAULT_HEIGHT;

    // ---- mouse ----

    /** Mouse event: button (-1 if motion), state, x, y, wheel delta. */
    public static final class MouseEvent {
        public int button = -1;
        public boolean state;
        public int x;
        public int y;
        public int dwheel;

        /** An event at the given position. */
        static MouseEvent at(int button, boolean state, int x, int y) {
            MouseEvent e = new MouseEvent();
            e.button = button;
            e.state = state;
            e.x = x;
            e.y = y;
            return e;
        }

        /** An event at the cursor's current position. */
        static MouseEvent atCursor(int button, boolean state) {
            return at(button, state, mouseX, mouseY);
        }
    }

    public static final Deque<MouseEvent> mouseEvents = new ArrayDeque<>();
    public static MouseEvent currentMouse = new MouseEvent();

    public static int mouseX;
    public static int mouseY;

    /** Accumulators reset on read, as in LWJGL 2. */
    public static int mouseDX;

    public static int mouseDY;
    public static int mouseDWheel;
    public static boolean grabbed;
    public static boolean mouseInside = true;
    public static final boolean[] mouseButtons = new boolean[16];

    // ---- keyboard ----

    /** Keyboard event: LWJGL 2 code, state, character, repeat. */
    public static final class KeyEvent {
        public int key;
        public boolean state;
        public char character;
        public boolean repeat;

        /** A (non-repeat) event. */
        static KeyEvent of(int key, boolean state, char character) {
            KeyEvent e = new KeyEvent();
            e.key = key;
            e.state = state;
            e.character = character;
            return e;
        }
    }

    public static final Deque<KeyEvent> keyEvents = new ArrayDeque<>();
    public static KeyEvent currentKey = new KeyEvent();

    public static boolean repeatEvents;

    /** Indexed by LWJGL 2 code (DirectInput scan codes, up to 0xFF). */
    public static final boolean[] keysDown = new boolean[256];

    private Win() {}

    public static boolean isCreated() {
        return handle != 0L;
    }

    /** Discards queues and pressed keys — used when recreating the context. */
    public static void resetInput() {
        mouseEvents.clear();
        keyEvents.clear();
        mouseDX = 0;
        mouseDY = 0;
        mouseDWheel = 0;
        Arrays.fill(keysDown, false);
        Arrays.fill(mouseButtons, false);
    }
}
