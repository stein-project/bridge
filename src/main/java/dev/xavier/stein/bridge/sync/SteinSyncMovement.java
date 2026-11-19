/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.sync;

import dev.xavier.stein.bridge.core.Log;
import dev.xavier.stein.bridge.engine.Sprint;
import dev.xavier.stein.bridge.engine.SyncMove;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.ChatComponentText;

/**
 * While a Stein Sync connection negotiated {@code move}, the client moves exactly as the game does:
 * the options that change how the player moves (rather than which keys are pressed) are held off
 * for that connection only and act again after it, without touching what the player or the mods
 * chose.
 *
 * <p>Held off: sprinting in every direction, sprint through a hit and its unbraked hit (the
 * Bridge's Sprint, which mods turn on through {@code Gameplay}). Each one makes the client's
 * position differ from what the server computes from the same inputs, which the server would have
 * to correct. The compact angle table stays as chosen: it only reaches the drawing code (model
 * animations, particles, the hand), never the movement. Options that only press keys for the player
 * (toggle sprint, auto walk, the Combat mod's W-tap) produce ordinary inputs that the server
 * simulates like any other, so they stay as they are; a server that does not want them turns them
 * off as before (the Loader's {@code Server.allows}).
 *
 * <p>The client also moves against the blocks the proxy's physics uses (the Bridge's {@link
 * SyncMove}): chests and anvils collide with each cell's own box, and the columns the extended
 * distance keeps after the server dropped them stay on screen but do not collide.
 *
 * <p>Game thread only.
 */
final class SteinSyncMovement {

    private static boolean held;

    private SteinSyncMovement() {}

    /** The connection's {@code move} turned on. */
    static void enter() {
        if (held) {
            return;
        }
        held = true;
        SyncMove.setMove(true);
        boolean sprint = Sprint.anyOption();
        Sprint.setVanillaMovement(true);
        if (sprint) {
            Log.info("steinsync", "Stein Sync move: held off for this connection: sprint options");
            EntityPlayerSP player = Minecraft.getMinecraft().thePlayer;
            if (player != null) {
                player.addChatMessage(
                        new ChatComponentText(I18n.format("steinui.sync.vanillaMovement")));
            }
        }
    }

    /** The connection ended or changed: the options act again as chosen. */
    static void leave() {
        if (!held) {
            return;
        }
        held = false;
        SyncMove.setMove(false);
        Sprint.setVanillaMovement(false);
    }
}
