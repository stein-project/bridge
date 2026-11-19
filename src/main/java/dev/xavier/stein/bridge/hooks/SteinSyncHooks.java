/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.hooks;

/**
 * The listener of the Stein Sync client messages (stein-sync's docs/protocol.md, section 5): the
 * one object (the Bridge's Stein Sync client, bridge.sync.SteinSyncClient) that may take over the
 * player's movement, attack and block packets on a connection where a Stein Sync proxy negotiated
 * it. Set in {@link GameHooks#steinSync}; while it is null, or it answers false, every hook does
 * exactly what the game does.
 *
 * <p>Everything is called on the game thread. Each method runs once per packet the game sends (a
 * few per tick), so an implementation answers quickly and without allocating when its connection is
 * not in Stein Sync mode.
 */
public interface SteinSyncHooks {

    /**
     * A packet EntityPlayerSP's update sends through NetHandlerPlayClient.addToSendQueue: the
     * movement packets (C03 to C06), the sneak and sprint Entity Actions (C0B) of
     * onUpdateWalkingPlayer, and the look and steer input (C05, C0C) of a rider.
     *
     * @param handler the NetHandlerPlayClient
     * @param packet the packet the game is about to send
     * @return true when the listener took the packet over (the game does not send it)
     */
    boolean playerPacket(Object handler, Object packet);

    /**
     * EntityPlayerSP.onUpdateWalkingPlayer finished: the point where a walking player's tick has
     * sent everything it sends (also when it sent no movement packet, for a player whose camera is
     * on another entity).
     */
    void playerWalked();

    /**
     * The attack (C02, action ATTACK) PlayerControllerMP.attackEntity is about to send, after its
     * held-item sync and before the client applies the hit to its own world.
     *
     * @return true when the listener took the attack over (the game does not send it)
     */
    boolean attackPacket(Object handler, Object packet);

    /**
     * A Player Digging (C07) or Block Placement (C08) packet PlayerControllerMP is about to send
     * for a block action (clickBlock, onPlayerDamageBlock, resetBlockRemoving, onPlayerRightClick).
     * The game sends it right after this returns.
     */
    void blockPacket(Object handler, Object packet);

    // The teleport confirmation (the C06 NetHandlerPlayClient.handlePlayerPosLook sends) has no
    // hook on purpose: it stays vanilla also in Stein Sync mode (protocol 5.5), because the server
    // accepts movement again only from a packet with the teleport's exact position.

    /**
     * A Block Change or Multi Block Change entry about to be applied
     * (WorldClient.invalidateRegionAndSetBlock in handleBlockChange and handleMultiBlockChange).
     *
     * @param world the WorldClient
     * @param pos the BlockPos (the packet's own object)
     * @param state the IBlockState the server sent
     * @return true when the listener holds the change (the game does not apply it now)
     */
    boolean holdBlockChange(Object world, Object pos, Object state);
}
