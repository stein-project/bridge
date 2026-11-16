/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGameOver;
import net.minecraft.client.gui.GuiScreen;

/**
 * Fix for the death screen's buttons staying off after a resize.
 *
 * <p>GuiGameOver.initGui creates its buttons disabled, and updateScreen enables them when its timer
 * reaches exactly 20 ticks; initGui did not reset the timer. initGui runs again on every resize (or
 * a switch to full screen), so resizing the window after the first second left "Respawn" and "Title
 * screen" disabled for good: the player could only leave by closing the game. Later versions reset
 * the timer in initGui; here, before initGui's first call (the button list's clear), the timer of
 * the death screen on show goes back to 0, so the buttons come back a second later. {@code
 * -Dsteinbridge.opt.vanillaGameOverButtons=true} brings back 1.8.9's behaviour.
 */
public final class GameOverButtons {

    private GameOverButtons() {}

    private static final boolean VANILLA = GuiFixOptions.VANILLA_GAME_OVER_BUTTONS;

    /**
     * Before GuiGameOver.initGui's buttonList.clear(); the screen being set up is the one on show
     * (displayGuiScreen sets it before setWorldAndResolution, and a resize goes to it).
     */
    public static void initGui() {
        if (VANILLA) {
            return;
        }
        GuiScreen screen = Minecraft.getMinecraft().currentScreen;
        if (screen instanceof GuiGameOver gameOver) {
            gameOver.enableButtonsTimer = 0;
        }
    }
}
