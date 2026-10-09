/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.diag;

import dev.xavier.stein.bridge.core.Log;

import net.minecraft.client.Minecraft;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.ScreenShotHelper;

/**
 * Screenshots saved by the game itself (ScreenShotHelper) at set times, for visual checks with no
 * outside capture.
 */
final class DiagCapture {
    private DiagCapture() {}

    /**
     * {@code -Dsteinbridge.diag.screenshotAtSec=N} (with {@code screenshotName}, default "diag"): N
     * seconds after startup, the game itself (ScreenShotHelper) saves the frame to
     * screenshots/&lt;name&gt;.png — a visual check with no outside capture and no visible window.
     * With {@code screenshotCount=K} (and {@code screenshotEveryMs}, default 250), K consecutive
     * captures, &lt;name&gt;-0..K-1.png: to catch what flickers.
     */
    static final String NAME = DiagOptions.SCREENSHOT_NAME;

    private static int screenshotsTaken;

    /**
     * Saves the frame through the game's ScreenShotHelper into screenshots/&lt;fileName&gt;;
     * returns the chat message the game would show.
     */
    static IChatComponent save(String fileName) {
        Minecraft mc = DiagGame.mc();
        return ScreenShotHelper.saveScreenshot(
                mc.mcDataDir, fileName, mc.displayWidth, mc.displayHeight, mc.framebufferMc);
    }

    private static void screenshot(int index) {
        String name = DiagOptions.SCREENSHOT_COUNT > 1 ? NAME + "-" + index : NAME;
        IChatComponent msg = save(name + ".png");
        Log.info("diag", "capture " + name + ".png: " + msg);
    }

    static void frame() {
        if (DiagOptions.SCREENSHOT_AT >= 0
                && screenshotsTaken < DiagOptions.SCREENSHOT_COUNT
                && Scenes.elapsed()
                        >= DiagOptions.SCREENSHOT_AT * 1_000_000_000L
                                + screenshotsTaken * DiagOptions.SCREENSHOT_EVERY_MS * 1_000_000L) {
            try {
                screenshot(screenshotsTaken++);
            } catch (RuntimeException e) {
                Log.info("diag", "failed in the capture: " + e);
            }
        }
    }
}
