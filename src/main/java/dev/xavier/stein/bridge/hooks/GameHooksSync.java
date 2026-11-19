/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.hooks;

import dev.xavier.stein.bridge.diag.SyncReplayCheck;
import dev.xavier.stein.bridge.engine.LookFix;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.Packet;
import net.minecraft.util.BlockPos;

/**
 * The Stein Sync side of {@link GameHooks}: the call sites where the client's movement, attack and
 * block packets leave, and where the server's block changes are applied, handed to the {@link
 * SteinSyncHooks} listener (patcher/redirects-mods.tsv). The teleport confirmation has no hook: it
 * stays vanilla (stein-sync docs/protocol.md 5.5).
 *
 * <p>Every hook does what the game does when there is no listener, the listener answers false or it
 * throws: on a server without Stein Sync the packets and their order stay exactly the game's.
 */
final class GameHooksSync {

    private GameHooksSync() {}

    /** NetHandlerPlayClient.addToSendQueue in EntityPlayerSP.onUpdate / onUpdateWalkingPlayer. */
    static void playerPacket(Object handler, Object packet) {
        SteinSyncHooks l = GameHooks.steinSync;
        if (l != null) {
            try {
                if (l.playerPacket(handler, packet)) {
                    return;
                }
            } catch (Throwable e) {
                GameHooks.fail("steinSync.playerPacket", e);
            }
        }
        ((NetHandlerPlayClient) handler).addToSendQueue((Packet<?>) packet);
    }

    /** After EntityPlayerSP.onUpdateWalkingPlayer, in EntityPlayerSP.onUpdate. */
    static void playerWalked() {
        if (SyncReplayCheck.ON) {
            SyncReplayCheck.walked();
        }
        SteinSyncHooks l = GameHooks.steinSync;
        if (l != null) {
            try {
                l.playerWalked();
            } catch (Throwable e) {
                GameHooks.fail("steinSync.playerWalked", e);
            }
        }
    }

    /** NetHandlerPlayClient.addToSendQueue in PlayerControllerMP.attackEntity. */
    static void attackPacket(Object handler, Object packet) {
        SteinSyncHooks l = GameHooks.steinSync;
        if (l != null) {
            try {
                if (l.attackPacket(handler, packet)) {
                    return;
                }
            } catch (Throwable e) {
                GameHooks.fail("steinSync.attackPacket", e);
            }
        }
        ((NetHandlerPlayClient) handler).addToSendQueue((Packet<?>) packet);
    }

    /** NetHandlerPlayClient.addToSendQueue in PlayerControllerMP's block actions. */
    static void blockPacket(Object handler, Object packet) {
        SteinSyncHooks l = GameHooks.steinSync;
        if (l != null) {
            try {
                l.blockPacket(handler, packet);
            } catch (Throwable e) {
                GameHooks.fail("steinSync.blockPacket", e);
            }
        }
        ((NetHandlerPlayClient) handler).addToSendQueue((Packet<?>) packet);
    }

    /**
     * WorldClient.invalidateRegionAndSetBlock in handleBlockChange and handleMultiBlockChange. A
     * held change returns false, as the game's method does when nothing changed (the callers do not
     * read it).
     */
    static boolean blockChange(Object world, Object pos, Object state) {
        SteinSyncHooks l = GameHooks.steinSync;
        if (l != null) {
            try {
                if (l.holdBlockChange(world, pos, state)) {
                    return false;
                }
            } catch (Throwable e) {
                GameHooks.fail("steinSync.holdBlockChange", e);
            }
        }
        return ((WorldClient) world)
                .invalidateRegionAndSetBlock((BlockPos) pos, (IBlockState) state);
    }

    /**
     * The yaw of the local player's pick ray right now: Minecraft.runTick picks with partial ticks
     * 1.0 before it handles the clicks, and getLook then takes the camera's yaw (LookFix) or, with
     * 1.8.9's look, the head's.
     */
    static float pickYaw(Object player) {
        return LookFix.pickYaw(player);
    }

    /** The sneak state EntityPlayerSP last told the server (its serverSneakState). */
    static boolean serverSneaking(Object player) {
        return ((EntityPlayerSP) player).serverSneakState;
    }

    /** The sprint state EntityPlayerSP last told the server (its serverSprintState). */
    static boolean serverSprinting(Object player) {
        return ((EntityPlayerSP) player).serverSprintState;
    }
}
