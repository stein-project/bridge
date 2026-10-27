/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.core;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_A;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_TAB;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_Z;
import static org.lwjgl.glfw.GLFW.GLFW_MOD_ALT;
import static org.lwjgl.glfw.GLFW.GLFW_MOD_CONTROL;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.GLFW_RELEASE;
import static org.lwjgl.glfw.GLFW.GLFW_REPEAT;
import static org.lwjgl.glfw.GLFW.glfwSetCharCallback;
import static org.lwjgl.glfw.GLFW.glfwSetCursorEnterCallback;
import static org.lwjgl.glfw.GLFW.glfwSetCursorPosCallback;
import static org.lwjgl.glfw.GLFW.glfwSetFramebufferSizeCallback;
import static org.lwjgl.glfw.GLFW.glfwSetKeyCallback;
import static org.lwjgl.glfw.GLFW.glfwSetMouseButtonCallback;
import static org.lwjgl.glfw.GLFW.glfwSetScrollCallback;
import static org.lwjgl.glfw.GLFW.glfwSetWindowFocusCallback;

import dev.xavier.stein.bridge.bench.Bench;
import dev.xavier.stein.bridge.engine.KeyBindings;

import org.lwjgl.glfw.GLFWCharCallback;
import org.lwjgl.glfw.GLFWCursorEnterCallback;
import org.lwjgl.glfw.GLFWCursorPosCallback;
import org.lwjgl.glfw.GLFWFramebufferSizeCallback;
import org.lwjgl.glfw.GLFWKeyCallback;
import org.lwjgl.glfw.GLFWMouseButtonCallback;
import org.lwjgl.glfw.GLFWScrollCallback;
import org.lwjgl.glfw.GLFWWindowFocusCallback;
import org.lwjgl.system.Callback;
import org.lwjgl.system.Platform;
import org.lwjgl.system.windows.User32;

import java.util.Deque;

/**
 * Installs the GLFW callbacks and converts them into the event queues that LWJGL 2 exposes via
 * {@code next()}.
 *
 * <p>Two non-obvious conversions happen here:
 *
 * <ul>
 *   <li><b>Y axis.</b> LWJGL 2 uses an origin at the bottom-left corner; GLFW uses the top-left.
 *       Every mouse Y is mirrored.
 *   <li><b>Character.</b> LWJGL 2 delivers the character together with the key event; GLFW uses
 *       separate callbacks. GLFW guarantees that the character callback comes right after the key
 *       one, so we fill in the pending event.
 * </ul>
 */
public final class Input {

    private static GLFWKeyCallback keyCallback;
    private static GLFWCharCallback charCallback;
    private static GLFWCursorPosCallback cursorPos;
    private static GLFWMouseButtonCallback mouseButtonCallback;
    private static GLFWScrollCallback scroll;
    private static GLFWCursorEnterCallback cursorEnter;
    private static GLFWWindowFocusCallback focusCallback;

    /** How long the cursor is hidden again every frame after the window gets focus back. */
    private static final long HIDE_CURSOR_NANOS = 2_000_000_000L;

    private static final boolean WINDOWS = Platform.get() == Platform.WINDOWS;

    /**
     * Until when (nanoTime) {@link #afterEvents} hides the cursor; 0 when it does not. After an
     * Alt+Tab back to the game with the mouse captured, Windows may keep showing the cursor frozen
     * at the center of the window for seconds: GLFW hides it only on WM_SETCURSOR, which can go to
     * the task switcher still on top when the focus arrives.
     */
    private static long hideCursorUntil;

    private static GLFWFramebufferSizeCallback framebufferSizeCallback;

    /** Last key-press event still without an associated character. */
    private static Win.KeyEvent pendingChar;

    /**
     * Marker in {@link #pendingChar}: the next character belongs to a discarded repeat and is
     * dropped with it.
     */
    private static final Win.KeyEvent SWALLOW = new Win.KeyEvent();

    /** Limit of queued events: avoids unbounded growth if the game freezes. */
    private static final int MAX_QUEUE = 1024;

    /** LWJGL 2 reports the wheel in steps of 120 (Windows convention). */
    private static final int WHEEL_DELTA = 120;

    private Input() {}

    /**
     * Character that LWJGL 2 (Windows' WM_CHAR) delivered together with the key and that GLFW
     * discards: Backspace 8, Tab 9, Enter 13, Esc 27 and Ctrl+letter 1 to 26 (Ctrl+A 1, Ctrl+C 3,
     * Ctrl+V 22, Ctrl+X 24). Screens that decide by the character depend on it — a mod's text
     * editor could neither erase nor paste. The game's text boxes ignore control characters.
     * Ctrl+Alt is left out: it is AltGr, which types a real character (GLFW delivers it through the
     * character callback).
     */
    static char legacyChar(int glfwKey, int mods) {
        switch (glfwKey) {
            case GLFW_KEY_BACKSPACE:
                return '\b';
            case GLFW_KEY_TAB:
                return '\t';
            case GLFW_KEY_ENTER:
            case GLFW_KEY_KP_ENTER:
                return '\r';
            case GLFW_KEY_ESCAPE:
                return '\u001b';
            default:
                break;
        }
        if ((mods & GLFW_MOD_CONTROL) != 0
                && (mods & GLFW_MOD_ALT) == 0
                && glfwKey >= GLFW_KEY_A
                && glfwKey <= GLFW_KEY_Z) {
            return (char) (glfwKey - GLFW_KEY_A + 1);
        }
        return '\0';
    }

    /**
     * Diagnostics: the key (GLFW code) goes through the same callbacks that glfwPollEvents would
     * call — event, control character and, with a codepoint, the typed character. On the game
     * thread.
     */
    public static void inject(int glfwKey, int mods, boolean press, int codepoint) {
        if (keyCallback == null) {
            return;
        }
        keyCallback.invoke(Win.handle, glfwKey, 0, press ? GLFW_PRESS : GLFW_RELEASE, mods);
        if (press && codepoint > 0 && charCallback != null) {
            charCallback.invoke(Win.handle, codepoint);
        }
    }

    /**
     * Diagnostics: the wheel turns {@code amount} steps ({@link #WHEEL_DELTA} per step, the Windows
     * convention), through the same callback that glfwPollEvents would call. On the game thread.
     */
    public static void injectWheel(int amount) {
        if (scroll != null) {
            scroll.invoke(Win.handle, 0.0, amount);
        }
    }

    /**
     * Diagnostics: the cursor moves to (x, y) in the window (GLFW pixels, origin at the top) and,
     * with button >= 0, the button goes down or up — through the same callbacks as glfwPollEvents.
     * A negative x leaves the cursor where it is (in the world, moving the cursor turns the
     * camera). On the game thread.
     */
    public static void injectMouse(double x, double y, int button, boolean press) {
        if (cursorPos == null) {
            return;
        }
        if (x >= 0) {
            cursorPos.invoke(Win.handle, x, y);
        }
        if (button >= 0 && mouseButtonCallback != null) {
            mouseButtonCallback.invoke(Win.handle, button, press ? GLFW_PRESS : GLFW_RELEASE, 0);
        }
    }

    public static void install(long win) {
        Win.resetInput();

        keyCallback = GLFWKeyCallback.create(Input::onKey);
        charCallback = GLFWCharCallback.create(Input::onChar);
        cursorPos = GLFWCursorPosCallback.create(Input::onCursorPos);
        mouseButtonCallback = GLFWMouseButtonCallback.create(Input::onMouseButton);
        scroll = GLFWScrollCallback.create(Input::onScroll);
        cursorEnter = GLFWCursorEnterCallback.create((w, entered) -> Win.mouseInside = entered);
        focusCallback = GLFWWindowFocusCallback.create(Input::onFocus);
        framebufferSizeCallback =
                GLFWFramebufferSizeCallback.create((w, width, height) -> Win.sizeDirty = true);

        glfwSetKeyCallback(win, keyCallback);
        glfwSetCharCallback(win, charCallback);
        glfwSetCursorPosCallback(win, cursorPos);
        glfwSetMouseButtonCallback(win, mouseButtonCallback);
        glfwSetScrollCallback(win, scroll);
        glfwSetCursorEnterCallback(win, cursorEnter);
        glfwSetWindowFocusCallback(win, focusCallback);
        glfwSetFramebufferSizeCallback(win, framebufferSizeCallback);
    }

    private static void onKey(long w, int k, int scancode, int action, int mods) {
        if (Bench.inputLocked()) {
            return; // the benchmark is playing: nothing the player presses reaches the game
        }
        if (action == GLFW_REPEAT && !Win.repeatEvents) {
            // The repeat is dropped, and so is its character (in LWJGL 2 it
            // came in the same event). Without this, the character of each
            // repeat became an event with no key, and the game
            // (Minecraft.dispatchKeypresses) uses the character in place of
            // the key: holding Shift+W gave 'W' = 87 = F11, and fullscreen
            // toggled on and off while the player walked sneaking.
            pendingChar = SWALLOW;
            return;
        }
        int lwjglKey = Keys.toLwjgl(k);
        boolean down = action != GLFW_RELEASE;
        if (lwjglKey > 0 && lwjglKey < Win.keysDown.length) {
            Win.keysDown[lwjglKey] = down;
        }
        // The control character that LWJGL 2 brought along with the key (GLFW
        // does not send it: Backspace, Enter, Ctrl+V...).
        Win.KeyEvent e = Win.KeyEvent.of(lwjglKey, down, down ? legacyChar(k, mods) : ' ');
        e.repeat = action == GLFW_REPEAT;
        push(Win.keyEvents, e);
        // Only pressed keys produce a character; the control one has already come.
        pendingChar = down && e.character == ' ' ? e : null;
    }

    private static void onChar(long w, int codepoint) {
        if (Bench.inputLocked()) {
            return;
        }
        char c = codepoint < Character.MAX_VALUE ? (char) codepoint : ' ';
        if (pendingChar == SWALLOW) { // character of a discarded repeat
            pendingChar = null;
            return;
        }
        if (pendingChar != null && pendingChar.character == ' ') {
            pendingChar.character = c;
            pendingChar = null;
            return;
        }
        if (!Win.repeatEvents) {
            // With repeat off (in the world), a character without a key is
            // that of a discarded repeat that arrived out of order (several
            // keys in the queue before it): it is dropped too. Typing screens
            // (chat, sign, anvil, creative search) turn repeat on, and there
            // the loose character (IME) goes through.
            return;
        }
        // Character without a matching key (IME, virtual keyboard).
        push(Win.keyEvents, Win.KeyEvent.of(0, true, c));
    }

    private static void onCursorPos(long w, double xpos, double ypos) {
        if (Bench.inputLocked()) {
            return;
        }
        int x = (int) (xpos * scaleX());
        int y = flipY((int) (ypos * scaleY()));
        Win.mouseDX += x - Win.mouseX;
        Win.mouseDY += y - Win.mouseY;
        Win.mouseX = x;
        Win.mouseY = y;
        push(Win.mouseEvents, Win.MouseEvent.at(-1, false, x, y));
    }

    private static void onMouseButton(long w, int button, int action, int mods) {
        if (Bench.inputLocked()) {
            return;
        }
        boolean down = action == GLFW_PRESS;
        if (button >= 0 && button < Win.mouseButtons.length) {
            Win.mouseButtons[button] = down;
        }
        push(Win.mouseEvents, Win.MouseEvent.atCursor(button, down));
    }

    private static void onScroll(long w, double xoff, double yoff) {
        if (Bench.inputLocked()) {
            return;
        }
        int d = (int) (yoff * WHEEL_DELTA);
        if (d == 0) {
            return;
        }
        Win.mouseDWheel += d;
        Win.MouseEvent e = Win.MouseEvent.atCursor(-1, false);
        e.dwheel = d;
        push(Win.mouseEvents, e);
    }

    private static void onFocus(long w, boolean focused) {
        Win.focused = focused;
        if (focused && Win.grabbed && WINDOWS) {
            hideCursorUntil = System.nanoTime() + HIDE_CURSOR_NANOS;
        }
        if (!focused) {
            releaseAll();
        }
    }

    /**
     * After the window's events of the frame: for a moment after the focus comes back with the
     * mouse captured, the cursor is hidden again (see {@link #hideCursorUntil}).
     */
    public static void afterEvents() {
        if (hideCursorUntil == 0L) {
            return;
        }
        if (!Win.focused || !Win.grabbed || System.nanoTime() - hideCursorUntil > 0L) {
            hideCursorUntil = 0L;
            return;
        }
        User32.SetCursor(0L);
    }

    public static void free() {
        for (Callback c :
                new Callback[] {
                    keyCallback,
                    charCallback,
                    cursorPos,
                    mouseButtonCallback,
                    scroll,
                    cursorEnter,
                    focusCallback,
                    framebufferSizeCallback
                }) {
            if (c != null) {
                c.free();
            }
        }
        keyCallback = null;
        charCallback = null;
        cursorPos = null;
        mouseButtonCallback = null;
        scroll = null;
        cursorEnter = null;
        focusCallback = null;
        framebufferSizeCallback = null;
        pendingChar = null;
    }

    /** Converts Y from GLFW coordinates (top) to LWJGL 2 ones (bottom). */
    public static int flipY(int y) {
        return Win.height - 1 - y;
    }

    private static double scaleX() {
        return Win.windowWidth == 0 ? 1.0 : (double) Win.width / Win.windowWidth;
    }

    private static double scaleY() {
        return Win.windowHeight == 0 ? 1.0 : (double) Win.height / Win.windowHeight;
    }

    /**
     * Releases everything that was pressed when the window loses focus.
     *
     * <p>GLFW does not send the "released" event for a key or button released while the window is
     * already unfocused (alt-tab, clicking on another program): as far as it is concerned, that
     * never happened. Clearing only our own arrays is not enough — the game does not read the
     * array, it keeps the state of each KeyBinding from the <b>events</b>
     * (KeyBinding.setKeyBindState in runTick). Without the release event, the right button stayed
     * pressed forever: just aiming at a chest or a crafting table was enough for it to open by
     * itself, which is the game's rightClickMouse repeating every four ticks.
     *
     * <p>So both are done here: the release events, for whoever reads events, and the game's own
     * KeyBinding.unPressAllKeys — the same one it calls when opening a screen — for what was
     * already counted.
     */
    private static void releaseAll() {
        for (int k = 0; k < Win.keysDown.length; k++) {
            if (Win.keysDown[k]) {
                Win.keysDown[k] = false;
                Win.KeyEvent e = new Win.KeyEvent();
                e.key = k;
                e.state = false;
                e.character = '\0';
                push(Win.keyEvents, e);
            }
        }
        for (int b = 0; b < Win.mouseButtons.length; b++) {
            if (Win.mouseButtons[b]) {
                Win.mouseButtons[b] = false;
                push(Win.mouseEvents, Win.MouseEvent.atCursor(b, false));
            }
        }
        KeyBindings.unPressAll();
    }

    /**
     * A synthetic click of the button (press and release) at the end of the mouse queue, as the
     * GLFW callback puts the real ones: the game reads both on the next tick, in order with the
     * other events. The state of the physical button ({@link Win#mouseButtons}) does not change: an
     * autoclick that follows the held button keeps seeing the finger on it. Only on the game thread
     * (the queue is the callbacks' one, which run in its glfwPollEvents).
     */
    public static void syntheticClick(int button) {
        if (button < 0 || button >= Win.mouseButtons.length) {
            return;
        }
        for (int i = 0; i < 2; i++) {
            push(Win.mouseEvents, Win.MouseEvent.atCursor(button, i == 0));
        }
    }

    private static <T> void push(Deque<T> q, T e) {
        if (q.size() >= MAX_QUEUE) {
            q.pollFirst();
        }
        q.addLast(e);
    }
}
