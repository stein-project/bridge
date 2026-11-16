/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.GuiIngame;
import net.minecraft.client.gui.ScaledResolution;

/**
 * Fix for the pumpkin overlay drawn in spectator mode.
 *
 * <p>GuiIngame.renderGameOverlay draws the carved pumpkin's blur over the whole screen whenever the
 * player's helmet slot holds a pumpkin, in spectator mode too: a player put into spectator with a
 * pumpkin on (a minigame's dead players, a staff member watching) saw the world through it while
 * their own body is not even there. Later versions do not draw it for a spectator; here
 * renderPumpkinOverlay is skipped then. {@code -Dsteinbridge.opt.vanillaSpectatorPumpkin=true}
 * brings back 1.8.9's behaviour.
 */
public final class SpectatorPumpkin {

    private SpectatorPumpkin() {}

    private static final boolean VANILLA = GuiFixOptions.VANILLA_SPECTATOR_PUMPKIN;

    /** In place of renderPumpkinOverlay(resolution) in GuiIngame.renderGameOverlay. */
    public static void overlay(Object gui, Object resolution) {
        EntityPlayerSP player = Minecraft.getMinecraft().thePlayer;
        if (!VANILLA && player != null && player.isSpectator()) {
            return;
        }
        ((GuiIngame) gui).renderPumpkinOverlay((ScaledResolution) resolution);
    }
}
