/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

package dev.xavier.stein.bridge.diag;

import dev.xavier.stein.bridge.core.Input;
import dev.xavier.stein.bridge.core.Log;
import dev.xavier.stein.bridge.core.Win;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MovingObjectPosition;

/**
 * Diagnostic scenes that drive the game's keyboard, wheel and mouse movement: drags, typing, held
 * keys, scripted key presses, the wheel, the forced hit and the input probe, all through the same
 * GLFW-side queues a real device feeds.
 *
 * <p>Each {@code ...Frame()} method is called by {@link Scenes#onFrame()} and does nothing unless
 * its {@code -Dsteinbridge.diag.*} option is set; each scene owns its option reads and state.
 */
final class DiagInputScenes {
    private DiagInputScenes() {}

    /**
     * {@code -Dsteinbridge.diag.drag=S:x1:y1:x2:y2|S:x1:y1:x2:y2...}: at S seconds, drags the mouse
     * from (x1,y1) to (x2,y2) — GUI coordinates, as the game's screens see them — through the GLFW
     * callbacks: presses the button, moves in ten steps and releases. The path of a real drag, to
     * check scroll bars, text selection and anything you grab and pull. With the same point at both
     * ends, it is a click. An optional sixth field is the button (0 left, the default; 1 right):
     * S:x:y:x:y:1 is a right click.
     */
    private static final int DRAG_STEPS = 10;

    private static int dragIndex;
    private static int dragStep;
    private static long dragLast;

    private static void drag(long elapsed) {
        String[] d = DiagOptions.DRAGS[dragIndex].split(":");
        if (d.length != 5 && d.length != 6
                || elapsed < (long) (Double.parseDouble(d[0]) * 1e9)
                || System.nanoTime() - dragLast < 40_000_000L) {
            return;
        }
        dragLast = System.nanoTime();
        // The GUI counts in points, GLFW in window pixels.
        int scale = DiagGame.guiScale();
        double x1 = Double.parseDouble(d[1]) * scale;
        double y1 = Double.parseDouble(d[2]) * scale;
        double x2 = Double.parseDouble(d[3]) * scale;
        double y2 = Double.parseDouble(d[4]) * scale;
        int button = d.length == 6 ? Integer.parseInt(d[5]) : 0;
        int step = dragStep++;
        if (step == 0) {
            Input.injectMouse(x1, y1, -1, false);
            Input.injectMouse(x1, y1, button, true);
            return;
        }
        if (step <= DRAG_STEPS) {
            double f = (double) step / DRAG_STEPS;
            Input.injectMouse(x1 + (x2 - x1) * f, y1 + (y2 - y1) * f, -1, false);
            return;
        }
        Input.injectMouse(x2, y2, button, false);
        Log.info("diag", "drag: " + d[1] + "," + d[2] + " -> " + d[3] + "," + d[4]);
        dragStep = 0;
        dragIndex++;
    }

    /**
     * {@code -Dsteinbridge.diag.type=S:text}: at S seconds, types the text into the GLFW keyboard
     * queue, one letter per frame, like a real keyboard — and so it reaches any screen of the game.
     * {@code ^a}, {@code ^c}, {@code ^v} and {@code ^x} send the Ctrl shortcut (select all, copy,
     * paste, cut).
     */
    private static int typeAt;

    /**
     * While greater than zero, Ctrl stays pressed (the event has already gone to the queue, the
     * screen has not read it yet).
     */
    private static long ctrlUntil;

    private static long typeLast;

    private static void type(long elapsed) {
        if (ctrlUntil > 0 && System.nanoTime() > ctrlUntil) {
            Win.keysDown[29] = false;
            ctrlUntil = 0L;
        }
        if (elapsed < (long) (Double.parseDouble(DiagOptions.TYPE[0]) * 1e9)
                || System.nanoTime() - typeLast < TYPE_GAP_NS
                || ctrlUntil > 0) {
            return;
        }
        typeLast = System.nanoTime();
        String text = DiagOptions.TYPE[1];
        char c = text.charAt(typeAt++);
        if (c == '^' && typeAt < text.length()) {
            char combo = text.charAt(typeAt++);
            int code =
                    switch (combo) {
                        case 'a' -> 30;
                        case 'c' -> 46;
                        case 'v' -> 47;
                        case 'x' -> 45;
                        default -> 0;
                    };
            // Ctrl stays pressed until the screen reads the event: whoever checks the shortcut
            // asks the keyboard whether it is down at the moment it reads the queue, not when the
            // event entered it.
            Win.keysDown[29] = true;
            press(code, '\0');
            ctrlUntil = System.nanoTime() + 300_000_000L;
            if (typeAt >= text.length()) {
                Log.info("diag", "typed: " + text);
            }
            return;
        }
        if (c == '~' && typeAt < text.length()) {
            // ~N: key with code N (LWJGL 2) directly, for those with no letter — ~1 is Esc.
            int code = 0;
            while (typeAt < text.length() && Character.isDigit(text.charAt(typeAt))) {
                code = code * 10 + (text.charAt(typeAt++) - '0');
            }
            press(code, '\0');
            if (typeAt >= text.length()) {
                Log.info("diag", "typed: " + text);
            }
            return;
        }
        press(letterCode(c), c);
        if (typeAt >= text.length()) {
            Log.info("diag", "typed: " + text);
        }
    }

    /**
     * {@code -Dsteinbridge.diag.typeGapMs=N}: interval between the letters of diag.type (default
     * 60). Below the tick (50), it is the player who types fast right after opening the chat — the
     * window in which a mod could see the letters with "no screen".
     */
    private static final long TYPE_GAP_NS = DiagOptions.TYPE_GAP_MS * 1_000_000L;

    /**
     * Key code (LWJGL 2, QWERTY keyboard) of a letter or of the space; 0 for the rest (only the
     * character).
     */
    private static int letterCode(char c) {
        int[] codes = {
            30, 48, 46, 32, 18, 33, 34, 35, 23, 36, 37, 38, 50, 49, 24, 25, 16, 19, 31, 20, 22, 47,
            17, 45, 21, 44
        };
        char l = Character.toLowerCase(c);
        return l >= 'a' && l <= 'z' ? codes[l - 'a'] : c == ' ' ? 57 : 0;
    }

    /** A tap on the keyboard queue: goes down with the letter and comes up. */
    private static void press(int code, char c) {
        Win.KeyEvent down = new Win.KeyEvent();
        down.key = code;
        down.state = true;
        down.character = c;
        Win.keyEvents.addLast(down);
        Win.KeyEvent up = new Win.KeyEvent();
        up.key = code;
        up.state = false;
        up.character = '\0';
        Win.keyEvents.addLast(up);
    }

    /**
     * {@code -Dsteinbridge.diag.holdKeyAtSec=N} (with {@code holdKey}, the LWJGL 2 key code —
     * default 46, C — and {@code holdKeyMs}, default 5000): N seconds after startup, the key goes
     * down in the keyboard event queue, as the GLFW callback would put it, and goes up holdKeyMs
     * later. From there on the path is that of a real key (the game's keyboard loop, KeyBinding,
     * mods). A key posted from outside does not reach a background window; this tests a held key
     * (the visual options' zoom) without bringing the window to the front. A negative code is a
     * mouse button, as in the game's KeyBinding (-100 left, -99 right): the physical button goes
     * down and the event enters the mouse queue (Combat's Autoclick follows the held button).
     */
    private static int holdKeyStage;

    private static void holdKey(long elapsed) {
        long down = DiagOptions.HOLD_KEY_AT * 1_000_000_000L;
        if (holdKeyStage == 0 && elapsed >= down) {
            holdKeyStage = 1;
            keyEvent(true);
            Log.info("diag", "key " + DiagOptions.HOLD_KEY + " down");
        } else if (holdKeyStage == 1 && elapsed >= down + DiagOptions.HOLD_KEY_MS * 1_000_000L) {
            holdKeyStage = 2;
            keyEvent(false);
            Log.info("diag", "key " + DiagOptions.HOLD_KEY + " up");
        }
    }

    private static void keyEvent(boolean down) {
        if (DiagOptions.HOLD_KEY < 0) {
            int b = DiagOptions.HOLD_KEY + 100;
            if (b >= 0 && b < Win.mouseButtons.length) {
                Win.mouseButtons[b] = down;
                Win.MouseEvent m = new Win.MouseEvent();
                m.button = b;
                m.state = down;
                m.x = Win.mouseX;
                m.y = Win.mouseY;
                Win.mouseEvents.addLast(m);
            }
            return;
        }
        if (DiagOptions.HOLD_KEY > 0 && DiagOptions.HOLD_KEY < Win.keysDown.length) {
            Win.keysDown[DiagOptions.HOLD_KEY] = down;
        }
        Win.KeyEvent e = new Win.KeyEvent();
        e.key = DiagOptions.HOLD_KEY;
        e.state = down;
        e.character = '\0';
        Win.keyEvents.addLast(e);
    }

    /**
     * {@code -Dsteinbridge.diag.wheel=S:x:y:steps}: at S seconds, the cursor goes to (x, y) of the
     * GUI and the wheel turns "steps" (positive is up), through the GLFW callbacks — the path of a
     * real wheel, to check what reacts to it on an open screen (the inventory gestures of a mod).
     */
    private static int wheelNext;

    private static void wheel(long elapsed) {
        String[] w = DiagOptions.WHEELS[wheelNext].split(":");
        if (elapsed < (long) (Double.parseDouble(w[0]) * 1e9)) {
            return;
        }
        wheelNext++;
        int scale = DiagGame.guiScale();
        Input.injectMouse(
                Double.parseDouble(w[1]) * scale, Double.parseDouble(w[2]) * scale, -1, false);
        Input.injectWheel(Integer.parseInt(w[3]));
        Log.info("diag", "wheel " + w[3] + " at " + w[1] + "," + w[2]);
    }

    /**
     * {@code -Dsteinbridge.diag.inputProbe=true}: every second, says what the game thinks is
     * pressed (use item, attack, walk) and which screen is open. It is the check for the stuck
     * button: released while the window is unfocused, GLFW does not send the "released", and
     * without the release events the game is left with the right button pressed forever — just
     * aiming at a chest was enough to open it.
     */
    private static long probeNext;

    private static void inputProbe() {
        long now = System.nanoTime();
        if (now < probeNext) {
            return;
        }
        probeNext = now + 1_000_000_000L;
        Minecraft mc = DiagGame.mc();
        GameSettings settings = DiagGame.settings();
        GuiScreen screen = DiagGame.currentScreen();
        // isKeyDown; isPressed would consume the press
        Log.info(
                "diag",
                "input: use="
                        + settings.keyBindUseItem.isKeyDown()
                        + " attack="
                        + settings.keyBindAttack.isKeyDown()
                        + " forward="
                        + settings.keyBindForward.isKeyDown()
                        + " screen="
                        + (screen == null ? "-" : screen.getClass().getSimpleName())
                        + " focus="
                        + Win.focused
                        + " inGame="
                        + mc.inGameHasFocus);
    }

    /**
     * {@code -Dsteinbridge.diag.keys=S:code:1|S:code:0...}: at S seconds from startup (fractions
     * work), presses (1) or releases (0) the key with the LWJGL 2 code (mouse: -100 left, -99
     * right) through KeyBinding.setKeyBindState, and the press counts as a click
     * (KeyBinding.onTick) — walking, sprinting, holding Tab, hitting, without touching the
     * keyboard.
     */
    private static int keyNext;

    /**
     * {@code -Dsteinbridge.diag.attackAtSec=S1,S2...}: at S seconds, aims at the nearest player
     * (Minecraft.objectMouseOver) and calls Minecraft.clickMouse — the path of a real click, with
     * the hit rule (Stein Combat), without needing to be within reach (the server ignores a hit
     * from afar).
     */
    private static int attackNext;

    private static void attack() {
        Minecraft mc = DiagGame.mc();
        EntityPlayerSP player = DiagGame.player();
        WorldClient world = DiagGame.world();
        if (player == null || world == null) {
            Log.info("diag", "hit: no player");
            return;
        }
        EntityPlayer best = null;
        double bestD = Double.MAX_VALUE;
        for (EntityPlayer o : world.playerEntities) {
            if (o == player) {
                continue;
            }
            double dx = o.posX - player.posX;
            double dy = o.posY - player.posY;
            double dz = o.posZ - player.posZ;
            double d = dx * dx + dy * dy + dz * dz;
            if (d < bestD) {
                best = o;
                bestD = d;
            }
        }
        if (best == null) {
            Log.info("diag", "hit: no other player in the world");
            return;
        }
        mc.objectMouseOver = new MovingObjectPosition(best);
        Log.info(
                "diag",
                "hit on the nearest player: %s at %.1f blocks",
                best.getName(),
                Math.sqrt(bestD));
        // leftClickCounter: without window focus the game leaves it at 10000 and clickMouse does
        // nothing.
        mc.leftClickCounter = 0;
        mc.clickMouse();
    }

    private static void keys(long elapsed) {
        while (keyNext < DiagOptions.KEYS.length) {
            String[] p = DiagOptions.KEYS[keyNext].split(":");
            if (elapsed < (long) (Double.parseDouble(p[0]) * 1_000_000_000L)) {
                return;
            }
            keyNext++;
            int code = Integer.parseInt(p[1]);
            boolean down = !p[2].equals("0");
            DiagGame.setKey(code, down);
        }
    }

    // ---- per-frame entry points, called by Scenes.onFrame() in that order

    static void wheelFrame() {
        if (wheelNext < DiagOptions.WHEELS.length) {
            try {
                wheel(Scenes.elapsed());
            } catch (RuntimeException e) {
                Log.info("diag", "wheel: " + e);
            }
        }
    }

    static void inputProbeFrame() {
        if (DiagOptions.INPUT_PROBE) {
            try {
                inputProbe();
            } catch (RuntimeException e) {
                Log.info("diag", "input probe: " + e);
            }
        }
    }

    static void attackFrame() {
        if (attackNext < DiagOptions.ATTACKS.length
                && Scenes.elapsed()
                        >= (long)
                                (Double.parseDouble(DiagOptions.ATTACKS[attackNext])
                                        * 1_000_000_000L)) {
            attackNext++;
            try {
                attack();
            } catch (RuntimeException e) {
                Log.info("diag", "failed in the hit: " + e);
            }
        }
    }

    static void keysFrame() {
        if (keyNext < DiagOptions.KEYS.length) {
            try {
                keys(Scenes.elapsed());
            } catch (RuntimeException e) {
                keyNext++;
                Log.info("diag", "failed in the key: " + e);
            }
        }
    }

    static void holdKeyFrame() {
        if (DiagOptions.HOLD_KEY_AT >= 0 && holdKeyStage < 2) {
            holdKey(Scenes.elapsed());
        }
    }

    static void dragFrame() {
        if (dragIndex < DiagOptions.DRAGS.length) {
            try {
                drag(Scenes.elapsed());
            } catch (RuntimeException e) {
                dragIndex = DiagOptions.DRAGS.length;
                Log.info("diag", "drag: " + e);
            }
        }
    }

    static void typeFrame() {
        if (DiagOptions.TYPE.length == 2 && typeAt < DiagOptions.TYPE[1].length()) {
            try {
                type(Scenes.elapsed());
            } catch (RuntimeException e) {
                typeAt = DiagOptions.TYPE[1].length();
                Log.info("diag", "typing: " + e);
            }
        }
    }
}
