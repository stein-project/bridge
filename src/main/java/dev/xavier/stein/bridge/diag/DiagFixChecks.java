/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.diag;

import dev.xavier.stein.bridge.core.Input;
import dev.xavier.stein.bridge.core.Log;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.ChatComponentText;

/**
 * Scene that reproduces, in a running single player world, the 1.8.9 bugs fixed by the Bridge's
 * later fixes, and reports each fix: one {@code [fixcheck] <name> ok|FAIL: ...} log line per check.
 * Run once as it is and once with the fix's {@code -Dsteinbridge.opt.vanilla*} option: the vanilla
 * run shows the bug (FAIL), the default one its fix (ok).
 *
 * <p>{@code -Dsteinbridge.diag.fixChecksAtSec=N} starts the checks N seconds after startup, once
 * the world is open; {@code -Dsteinbridge.diag.fixTitleAtSec=M} puts a long title on screen at
 * second M, before the world opens, for the join check. The checks that need no timing are in
 * {@link DiagFixUnits}; this class runs those and the ones spread over several frames (mouse input,
 * the banner cache's second, the download time limit).
 */
final class DiagFixChecks {

    private DiagFixChecks() {}

    private static int phase;
    private static long phaseAt;
    private static boolean titleDone;

    /** A check that reloads resources draws loading frames, which come back here: not re-run. */
    private static boolean running;

    private static int perspectiveBefore;
    private static int perspectiveKey;
    private static int inventoryKey;

    /** Mouse button 3 as a key code. */
    private static final int MOUSE_3 = 3 - 100;

    static void frame() {
        title();
        if (DiagOptions.FIX_CHECKS_AT < 0 || phase > 5) {
            return;
        }
        long now = Scenes.elapsed();
        if (now < (phase == 0 ? DiagOptions.FIX_CHECKS_AT * 1_000_000_000L : phaseAt)) {
            return;
        }
        Minecraft mc = DiagGame.mc();
        if (mc.theWorld == null || mc.thePlayer == null) {
            return;
        }
        if (running) {
            return;
        }
        running = true;
        try {
            step(mc);
        } catch (Throwable t) {
            Log.error("fixcheck", "phase " + phase + " failed", t);
            phase = 99;
        } finally {
            running = false;
        }
    }

    private static void step(Minecraft mc) {
        GameSettings s = mc.gameSettings;
        switch (phase) {
            case 0 -> {
                DiagFixUnits.runAll(mc);
                textLines(mc);
                DiagFixUnits.bannersFill();
                DiagFixUnits.skinTimeoutStart();
                mc.displayGuiScreen(new GuiChat("typed message"));
                next(2500);
            }
            case 1 -> {
                // The open chat shows the text lines: a picture of the two fonts fixes.
                DiagCapture.save(DiagCapture.NAME + "-text.png");
                DiagFixUnits.bannersAfterSecond();
                DiagFixUnits.chatClose(mc);
                mc.displayGuiScreen(null);
                perspectiveBefore = s.thirdPersonView;
                perspectiveKey = s.keyBindTogglePerspective.getKeyCode();
                s.keyBindTogglePerspective.setKeyCode(MOUSE_3);
                KeyBinding.resetKeyBindingArrayAndHash();
                Input.injectMouse(-1, -1, 3, true);
                Input.injectMouse(-1, -1, 3, false);
                next(1000);
            }
            case 2 -> {
                boolean changed = s.thirdPersonView != perspectiveBefore;
                DiagFixUnits.result(
                        "mouseKeys.perspective",
                        changed,
                        "view " + perspectiveBefore + " -> " + s.thirdPersonView);
                s.thirdPersonView = perspectiveBefore;
                s.keyBindTogglePerspective.setKeyCode(perspectiveKey);
                inventoryKey = s.keyBindInventory.getKeyCode();
                s.keyBindInventory.setKeyCode(MOUSE_3);
                KeyBinding.resetKeyBindingArrayAndHash();
                mc.displayGuiScreen(new GuiInventory(mc.thePlayer));
                next(500);
            }
            case 3 -> {
                Input.injectMouse(-1, -1, 3, true);
                Input.injectMouse(-1, -1, 3, false);
                next(1000);
            }
            case 4 -> {
                boolean closed = mc.currentScreen == null;
                DiagFixUnits.result(
                        "mouseKeys.inventory",
                        closed,
                        "screen after the click: " + name(mc.currentScreen));
                mc.displayGuiScreen(null);
                s.keyBindInventory.setKeyCode(inventoryKey);
                KeyBinding.resetKeyBindingArrayAndHash();
                next(9000);
            }
            case 5 -> {
                DiagFixUnits.skinTimeoutCheck();
                Log.info("fixcheck", "done");
                phase++;
            }
            default -> phase++;
        }
    }

    private static void next(long delayMs) {
        phase++;
        phaseAt = Scenes.elapsed() + delayMs * 1_000_000L;
    }

    private static String name(Object o) {
        return o == null ? "none" : o.getClass().getSimpleName();
    }

    /** Lines for a screenshot: a style that would leak from the shadow, and right-half glyphs. */
    private static void textLines(Minecraft mc) {
        mc.ingameGUI
                .getChatGUI()
                .printChatMessage(new ChatComponentText("plain words then §lbold words"));
        mc.ingameGUI
                .getChatGUI()
                .printChatMessage(new ChatComponentText("glyphs: aㅣb ▐ cㅏd ▕ end of the line"));
    }

    /** A title that lasts minutes, before the world opens: the join should clear it. */
    private static void title() {
        if (titleDone
                || DiagOptions.FIX_TITLE_AT < 0
                || Scenes.elapsed() < DiagOptions.FIX_TITLE_AT * 1_000_000_000L) {
            return;
        }
        titleDone = true;
        Minecraft mc = DiagGame.mc();
        if (mc.ingameGUI != null) {
            mc.ingameGUI.displayTitle(null, null, 0, 20 * 600, 0);
            mc.ingameGUI.displayTitle("lobby title", null, -1, -1, -1);
            Log.info("fixcheck", "title set before the join");
        }
    }
}
