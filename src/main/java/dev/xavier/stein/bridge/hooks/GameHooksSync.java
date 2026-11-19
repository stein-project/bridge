/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.hooks;

import dev.xavier.stein.bridge.diag.SyncReplayCheck;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.Packet;
import net.minecraft.util.BlockPos;

/**
 * The Stein Sync side of {@link GameHooks}: the call sites where the client's movement, attack and
 * block packets leave, and where the server's block changes are applied, handed to the {@link
 * SteinSyncHooks} listener (patcher/redirects-mods.tsv), and the Stein Sync events the Bridge's
 * client hands to the {@link HookSink} listeners. The teleport confirmation has no hook: it stays
 * vanilla (stein-sync docs/protocol.md 5.5).
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
     * A HitConfirm the Stein Sync client read (bridge.sync.SteinSync): to every listener, with the
     * target as this client's world has it.
     */
    static void hit(int targetId, float damage, boolean critical, boolean killed, int tick) {
        if (GameHooks.SINK != null) {
            try {
                GameHooks.SINK.steinSyncHit(
                        targetId, entity(targetId), damage, critical, killed, tick);
            } catch (Throwable e) {
                GameHooks.fail("steinSyncHit", e);
            }
        }
    }

    /** A Damaged the Stein Sync client read: to every listener. */
    static void damaged(int attackerId, float damage, int cause, boolean critical, boolean killed) {
        if (GameHooks.SINK != null) {
            try {
                GameHooks.SINK.steinSyncDamaged(
                        attackerId,
                        attackerId == 0 ? null : entity(attackerId),
                        damage,
                        cause,
                        critical,
                        killed);
            } catch (Throwable e) {
                GameHooks.fail("steinSyncDamaged", e);
            }
        }
    }

    /** A CombatSeen the Stein Sync client read: to every listener. */
    static void combatSeen(int attackerId, int targetId, boolean critical, boolean killed) {
        if (GameHooks.SINK != null) {
            try {
                GameHooks.SINK.steinSyncCombatSeen(
                        attackerId,
                        entity(attackerId),
                        targetId,
                        entity(targetId),
                        critical,
                        killed);
            } catch (Throwable e) {
                GameHooks.fail("steinSyncCombatSeen", e);
            }
        }
    }

    /** The entity with this id in the client's world, or null. */
    private static Object entity(int id) {
        WorldClient world = Minecraft.getMinecraft().theWorld;
        return world == null ? null : world.getEntityByID(id);
    }
}
