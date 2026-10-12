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
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.GuiMultiplayer;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.gui.inventory.GuiContainerCreative;
import net.minecraft.client.multiplayer.PlayerControllerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.world.WorldSettings;

/**
 * Diagnostic scenes that click: the CPS and miss-penalty tests, middle-click picking and the
 * server-list clicks, through the GLFW callbacks (the path of a real click).
 *
 * <p>Each {@code ...Frame()} method is called by {@link Scenes#onFrame()} and does nothing unless
 * its {@code -Dsteinbridge.diag.*} option is set; each scene owns its option reads and state.
 */
final class DiagClickScenes {
    private DiagClickScenes() {}

    /**
     * {@code -Dsteinbridge.diag.cpsTestAtSec=N}: N seconds after startup, 10 left-button clicks in
     * the world, one every 80 ms, through the GLFW callbacks — the path of a real click; the HUD's
     * CPS must count 10.
     */

    /** How many clicks and how often (default 10, one every 80 ms). */
    private static int cpsClicks;

    private static void cpsTest(long elapsed) {
        long t = elapsed - DiagOptions.CPS_TEST_AT * 1_000_000_000L;
        if (t >= 200_000_000L + cpsClicks * DiagOptions.CPS_STEP_MS * 1_000_000L) {
            cpsClicks++;
            Input.injectMouse(-1, -1, 0, true);
            Input.injectMouse(-1, -1, 0, false);
            if (cpsClicks == DiagOptions.CPS_CLICKS) {
                Log.info("diag", "CPS: " + DiagOptions.CPS_CLICKS + " left-button clicks");
            }
        }
    }

    /**
     * {@code -Dsteinbridge.diag.clickTestAtSec=N}: measures the penalty for missing a 1.8 swing
     * (leftClickCounter). Looking at the sky (every click misses), the game sets 10 ticks — half a
     * second — in which {@code Minecraft.clickMouse} returns on its first line: in that half
     * second, aiming at a player does not help, and the arm does not even swing. The scene runs two
     * phases of {@code clickForSec} seconds (default 4): first with the button held, then clicking
     * at {@code clickHoldMs}/{@code clickGapMs} (default 5 and 5, ~100 clicks per second), and at
     * the end prints how long the counter was above zero and the average and largest wait.
     *
     * <p>The penalty only applies outside creative ({@code PlayerControllerMP.isNotCreative}), so
     * the scene sets the client's game type to survival — it is the same field the game consults.
     */

    /**
     * {@code -Dsteinbridge.diag.clickInject=false}: the scene does not click, it only watches — to
     * measure someone else's click (a mod's auto clicker, for example) with the same accounting:
     * how long the penalty blocked and how many clicks the game accepted.
     */
    private static int clickPhase; // 0 before, 1 holding, 2 clicking, 3 end

    private static boolean clickDown;
    private static long clickPhaseStart;
    private static long clickNextEvent;
    private static long clickLastFrame;

    /**
     * Clicks that missed up to the previous frame (GameHooks.missedClicks): the difference is what
     * the game accepted.
     */
    private static int clickLastSwing;

    /**
     * Per phase: total time, time with the counter above zero, sum of the counter over time,
     * largest counter, accepted hits.
     */
    private static final long[] CLICK_TIME = new long[3];

    private static final long[] CLICK_BLOCKED = new long[3];
    private static final double[] CLICK_SUM = new double[3];
    private static final int[] CLICK_MAX = new int[3];
    private static final int[] CLICK_LANDED = new int[3];
    private static final int[] CLICK_SENT = new int[3];

    /** Frames of the phase: with its time, gives the click cost in ms per frame. */
    private static final int[] CLICK_FRAMES = new int[3];

    private static void clickTest(long elapsed) {
        if (elapsed < DiagOptions.CLICK_TEST_AT * 1_000_000_000L) {
            return;
        }
        Minecraft mc = DiagGame.mc();
        EntityPlayerSP player = DiagGame.player();
        if (player == null) {
            return;
        }
        long now = System.nanoTime();
        if (clickPhase == 0) {
            survival(mc.playerController);
            clickPhase = 1;
            clickPhaseStart = now;
            clickLastFrame = now;
            clickNextEvent = now;
            Log.info(
                    "diag",
                    DiagOptions.CLICK_INJECT
                            ? "miss penalty: phase 1, button held ("
                                    + DiagOptions.CLICK_FOR_SEC
                                    + "s)"
                            : "miss penalty: phase 1, looking ("
                                    + DiagOptions.CLICK_FOR_SEC
                                    + "s) — another click comes in between the phases and both"
                                    + " measurements come from the same session");
        }
        player.rotationPitch = -90.0F; // looking at the sky: every click misses
        int slot = clickPhase - 1;
        int counter = mc.leftClickCounter;
        long frame = now - clickLastFrame;
        clickLastFrame = now;
        if (slot >= 0 && slot < 2) {
            sampleFrame(slot, counter, frame);
        }

        if (clickPhase == 1) {
            holdPhase(now);
        } else if (clickPhase == 2) {
            clickingPhase(now);
        }
    }

    /** Adds one frame to the accounting of the phase {@code slot}. */
    private static void sampleFrame(int slot, int counter, long frame) {
        CLICK_TIME[slot] += frame;
        CLICK_FRAMES[slot]++;
        CLICK_SUM[slot] += (double) counter * frame;
        if (counter > 0) {
            CLICK_BLOCKED[slot] += frame;
        }
        CLICK_MAX[slot] = Math.max(CLICK_MAX[slot], counter);
        int missed = dev.xavier.stein.bridge.hooks.GameHooks.missedClicks();
        if (clickLastSwing > 0) {
            CLICK_LANDED[slot] += missed - clickLastSwing;
        }
        clickLastSwing = missed;
    }

    /** Phase 1: the button held down for {@code clickForSec} seconds. */
    private static void holdPhase(long now) {
        if (DiagOptions.CLICK_INJECT && !clickDown) {
            clickDown = true;
            CLICK_SENT[0]++;
            Input.injectMouse(-1, -1, 0, true);
        }
        if (now - clickPhaseStart >= DiagOptions.CLICK_FOR_SEC * 1_000_000_000L) {
            if (clickDown) {
                Input.injectMouse(-1, -1, 0, false);
                clickDown = false;
            }
            clickPhase = 2;
            clickPhaseStart = now;
            clickNextEvent = now;
            Log.info(
                    "diag",
                    DiagOptions.CLICK_INJECT
                            ? "miss penalty: phase 2, "
                                    + DiagOptions.CLICK_HOLD_MS
                                    + " ms pressed / "
                                    + DiagOptions.CLICK_GAP_MS
                                    + " ms released ("
                                    + DiagOptions.CLICK_FOR_SEC
                                    + "s)"
                            : "miss penalty: phase 2, looking ("
                                    + DiagOptions.CLICK_FOR_SEC
                                    + "s)");
        }
    }

    /**
     * Phase 2: clicks at {@code clickHoldMs}/{@code clickGapMs} for {@code clickForSec} seconds.
     */
    private static void clickingPhase(long now) {
        // All the transitions that fit since the last frame, in order: a 5 ms clicker sends
        // more than one event per frame, and the last one decides the button state when the
        // game tick looks.
        while (DiagOptions.CLICK_INJECT && now >= clickNextEvent) {
            clickDown = !clickDown;
            if (clickDown) {
                CLICK_SENT[1]++;
            }
            Input.injectMouse(-1, -1, 0, clickDown);
            clickNextEvent +=
                    (clickDown ? DiagOptions.CLICK_HOLD_MS : DiagOptions.CLICK_GAP_MS) * 1_000_000L;
        }
        if (now - clickPhaseStart >= DiagOptions.CLICK_FOR_SEC * 1_000_000_000L) {
            if (clickDown) {
                Input.injectMouse(-1, -1, 0, false);
                clickDown = false;
            }
            clickPhase = 3;
            if (DiagOptions.CLICK_INJECT) {
                report(0, "button held");
                report(1, DiagOptions.CLICK_HOLD_MS + "/" + DiagOptions.CLICK_GAP_MS + " ms");
            } else {
                report(0, "phase 1");
                report(1, "phase 2");
            }
        }
    }

    /** The client's game type: the penalty for missing does not exist in creative. */
    private static void survival(PlayerControllerMP controller) {
        controller.currentGameType = WorldSettings.GameType.SURVIVAL;
    }

    private static void report(int slot, String how) {
        double seconds = CLICK_TIME[slot] / 1e9;
        double blocked = CLICK_TIME[slot] == 0 ? 0 : 100.0 * CLICK_BLOCKED[slot] / CLICK_TIME[slot];
        double avgMs = CLICK_TIME[slot] == 0 ? 0 : CLICK_SUM[slot] / CLICK_TIME[slot] * 50.0;
        double frameMs = CLICK_FRAMES[slot] == 0 ? 0 : CLICK_TIME[slot] / 1e6 / CLICK_FRAMES[slot];
        Log.info(
                "diag",
                "miss penalty (%s): %.1fs, blocked %.1f%% of the time, average wait %.0f"
                        + " ms, max %d ms, %d clicks accepted of %d, %d frames (%.2f ms per frame,"
                        + " %.0f fps)",
                how,
                seconds,
                blocked,
                avgMs,
                CLICK_MAX[slot] * 50,
                CLICK_LANDED[slot],
                CLICK_SENT[slot],
                CLICK_FRAMES[slot],
                frameMs,
                frameMs == 0 ? 0 : 1000.0 / frameMs);
    }

    /**
     * {@code -Dsteinbridge.diag.pickTestAtSec=N} (in the world, creative, block in the crosshair):
     * 10 middle-button clicks in the world — empty hand before each one; the crosshair block must
     * come to it — and then 10 on the first item of the creative inventory — empty cursor before
     * each one; the stack must come to it —, through the GLFW callbacks; counts the hits. {@code
     * -Dsteinbridge.diag.serverClicksAtSec=N} (title screen, one server in the list): 12 clicks
     * only, spaced 600 ms apart, in the middle of the first server (must only select) and one on
     * the right half of the icon (the game connects on purpose).
     */
    private static int pickStep;

    private static int pickHits;
    private static int creativeHits;
    private static long pickLastStep;
    private static int clickStep;

    /**
     * One step every 400 ms: alternating clear and click; the hit is checked in the clearing step.
     */
    private static void pickTest(long elapsed) throws Exception {
        long t = elapsed - DiagOptions.PICK_TEST_AT * 1_000_000_000L;
        if (t < pickStep * 400_000_000L || System.nanoTime() - pickLastStep < 300_000_000L) {
            return;
        }
        pickLastStep = System.nanoTime();
        int s = pickStep++;
        Minecraft mc = DiagGame.mc();
        EntityPlayerSP player = DiagGame.player();
        if (player == null) {
            pickStep = 999;
            Log.info("diag", "pick block: no player");
            return;
        }
        InventoryPlayer inv = player.inventory;
        ItemStack[] slots = inv.mainInventory;
        int c = inv.currentItem;
        if (s < 20) {
            if (s % 2 == 0) {
                if (s > 0 && slots[c] != null) {
                    pickHits++;
                }
                slots[c] = null;
            } else {
                if (s == 1 || s == 19) {
                    GuiScreen screenNow = DiagGame.currentScreen();
                    Log.info(
                            "diag",
                            "pick block, click "
                                    + (s + 1) / 2
                                    + ": screen "
                                    + (screenNow == null ? "none" : screenNow.getClass().getName())
                                    + ", crosshair "
                                    + mc.objectMouseOver);
                }
                Input.injectMouse(-1, -1, 2, true);
                Input.injectMouse(-1, -1, 2, false);
            }
        } else if (s == 20) {
            if (slots[c] != null) {
                pickHits++;
            }
            Log.info("diag", "pick block in the world (middle click): " + pickHits + " of 10");
            DiagGame.open(new GuiContainerCreative(player));
        } else if (s < 42) {
            int k = s - 21;
            if (k % 2 == 0) {
                if (k > 0 && inv.getItemStack() != null) {
                    creativeHits++;
                }
                inv.setItemStack(null);
            } else {
                GuiContainer gui = (GuiContainer) DiagGame.currentScreen();
                int scale = DiagGame.guiScale();
                // First item of the tab: an 18 px slot at (guiLeft + 9, guiTop + 18); its middle.
                double x = (gui.guiLeft + 9 + 8) * scale;
                double y = (gui.guiTop + 18 + 8) * scale;
                DiagGame.click(x, y, 2);
            }
        } else if (s == 42) {
            if (inv.getItemStack() != null) {
                creativeHits++;
            }
            inv.setItemStack(null);
            Log.info("diag", "middle click in the creative inventory: " + creativeHits + " of 10");
            DiagGame.open(null);
        }
    }

    private static void serverClicks(long elapsed) throws Exception {
        long t = elapsed - DiagOptions.SERVER_CLICKS_AT * 1_000_000_000L;
        if (t < clickStep * 600_000_000L) {
            return;
        }
        int s = clickStep++;
        if (s == 0) {
            DiagGame.open(new GuiMultiplayer(DiagGame.currentScreen()));
            Log.info("diag", "list clicks: list open");
            return;
        }
        GuiScreen cur = DiagGame.currentScreen();
        String name = cur == null ? "none" : cur.getClass().getName();
        if (s < 18 && !(cur instanceof GuiMultiplayer)) {
            Log.info(
                    "diag",
                    "list clicks: left the list before step " + s + " (screen " + name + ")");
            clickStep = 99;
            return;
        }
        int scale = DiagGame.guiScale();
        int guiWidth = DiagGame.mc().displayWidth / scale;
        double y = 50.0 * scale;
        if (s >= 3 && s <= 14) {
            double x = guiWidth / 2.0 * scale;
            DiagGame.click(x, y, 0);
        } else if (s == 15) {
            Log.info(
                    "diag",
                    "list clicks: 12 clicks only in the middle of the server, screen after: "
                            + name);
        } else if (s == 16) {
            // The entry starts at x = middle - 150 (305 px list); the icon is 32 px: the right
            // half, at +24.
            double x = (guiWidth / 2.0 - 150 + 24) * scale;
            DiagGame.click(x, y, 0);
        } else if (s == 18) {
            Log.info(
                    "diag",
                    "list clicks: one click on the right half of the icon, screen after: " + name);
            clickStep = 99;
        }
    }

    // ---- per-frame entry points, called by Scenes.onFrame() in that order

    static void pickTestFrame() {
        if (DiagOptions.PICK_TEST_AT >= 0 && pickStep <= 42) {
            try {
                pickTest(Scenes.elapsed());
            } catch (Exception e) {
                pickStep = 999;
                Log.info("diag", "failed in the pick-block test: " + e);
            }
        }
    }

    static void serverClicksFrame() {
        if (DiagOptions.SERVER_CLICKS_AT >= 0 && clickStep <= 18) {
            try {
                serverClicks(Scenes.elapsed());
            } catch (Exception e) {
                clickStep = 99;
                Log.info("diag", "failed in the list clicks: " + e);
            }
        }
    }

    static void clickTestFrame() {
        if (DiagOptions.CLICK_TEST_AT >= 0 && clickPhase < 3) {
            try {
                clickTest(Scenes.elapsed());
            } catch (RuntimeException e) {
                clickPhase = 3;
                Log.info("diag", "miss penalty: " + e);
            }
        }
    }

    static void cpsTestFrame() {
        if (DiagOptions.CPS_TEST_AT >= 0 && cpsClicks < DiagOptions.CPS_CLICKS) {
            cpsTest(Scenes.elapsed());
        }
    }
}
