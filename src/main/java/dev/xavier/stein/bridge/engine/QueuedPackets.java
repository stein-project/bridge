/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.INetHandler;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.server.S0CPacketSpawnPlayer;
import net.minecraft.network.play.server.S3FPacketCustomPayload;

/**
 * Fixes on the game-thread pass of a received packet (the task PacketThreadUtil queues): packets of
 * a closed connection, a player spawned before their tab list entry, and the buffer of a plugin
 * message.
 *
 * <p>The network thread hands each play packet to the game thread through
 * PacketThreadUtil.checkThreadAndEnqueue, and the queued task ({@code PacketThreadUtil$1.run})
 * calls {@code packet.processPacket(handler)}. The patcher sends that call here:
 *
 * <ul>
 *   <li>Packets after the disconnect: the tasks still queued when the connection closes ran against
 *       a client that was already leaving the world, and threw NullPointerExceptions into the log
 *       (the world or the player already gone). For the client's play handler whose connection had
 *       a channel that is now closed, the packet is skipped, as Mojang does from 1.14 ("Ignoring
 *       packet due to disconnection"). A handler with no channel at all (a replay's handler) is
 *       never skipped. The disconnect packet itself is not queued (handleDisconnect runs on the
 *       network thread), so the kick message still arrives.
 *   <li>A player spawned before their tab list entry (S0CPacketSpawnPlayer): handleSpawnPlayer
 *       reads {@code getPlayerInfo(uuid).getGameProfile()} and threw a NullPointerException for a
 *       uuid the list does not have (NPC plugins do this all the time), after having changed
 *       nothing; the packet is left out with one log line. Later versions return early in the same
 *       way.
 *   <li>The plugin message buffer (S3FPacketCustomPayload): the packet copies its bytes into a
 *       buffer of its own when read, and handleCustomPayload releases it only for MC|TrList; every
 *       other channel left one buffer for the garbage collector to find (Netty's leak detector
 *       reports it, and a pooled buffer goes back to the pool only on release). The listeners of
 *       the Bridge and the Loader get a copy of the bytes (GameHooks.customPayload), never the
 *       buffer, and nothing in the game keeps the packet, so it is released after the handler
 *       returns.
 * </ul>
 *
 * Everything else goes to processPacket exactly as before; the server's handlers (the integrated
 * server uses the same task) only pass through.
 */
public final class QueuedPackets {

    private static final RepeatLog CLOSED = new RepeatLog("net", 10);
    private static final RepeatLog SPAWN = new RepeatLog("net", 10);

    private QueuedPackets() {}

    /** Packet.processPacket(handler) in PacketThreadUtil$1.run, on the game thread. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void process(Object packet, Object handler) {
        Packet p = (Packet) packet;
        if (!(handler instanceof NetHandlerPlayClient)) {
            p.processPacket((INetHandler) handler);
            return;
        }
        NetHandlerPlayClient client = (NetHandlerPlayClient) handler;
        try {
            if (skip(p, client)) {
                return;
            }
            p.processPacket(client);
        } finally {
            if (p instanceof S3FPacketCustomPayload) {
                release((S3FPacketCustomPayload) p);
            }
        }
    }

    private static boolean skip(Packet<?> p, NetHandlerPlayClient client) {
        NetworkManager net = client.getNetworkManager();
        if (net != null && !net.hasNoChannel() && !net.isChannelOpen()) {
            CLOSED.note(
                    "ignoring "
                            + p.getClass().getSimpleName()
                            + ": it arrived before the disconnect");
            return true;
        }
        if (p instanceof S0CPacketSpawnPlayer) {
            S0CPacketSpawnPlayer spawn = (S0CPacketSpawnPlayer) p;
            if (client.getPlayerInfo(spawn.getPlayer()) == null) {
                SPAWN.note(
                        "player "
                                + spawn.getPlayer()
                                + " spawned before their tab list entry: left out");
                return true;
            }
        }
        return false;
    }

    /** Releases the payload's buffer, unless the handler already did (MC|TrList). */
    private static void release(S3FPacketCustomPayload payload) {
        PacketBuffer data = payload.getBufferData();
        if (data != null && data.refCnt() > 0) {
            data.release();
        }
    }
}
