/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.hooks;

import com.google.gson.Gson;

import dev.xavier.stein.bridge.core.Log;

import io.netty.buffer.Unpooled;

import net.minecraft.client.gui.GuiListExtended;
import net.minecraft.client.gui.ServerListEntryNormal;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.EnumConnectionState;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.ServerStatusResponse;
import net.minecraft.network.handshake.client.C00Handshake;
import net.minecraft.network.status.INetHandlerStatusClient;
import net.minecraft.network.status.server.S00PacketServerInfo;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;

/**
 * The server list side of {@link GameHooks}: the Stein Sync offer read from a server's status
 * response and kept with its list entry, and the event after each entry draws itself.
 *
 * <p>The offer travels in two fields the patcher adds ({@code sbSteinSync}): on the
 * ServerStatusResponse, filled where S00PacketServerInfo reads its JSON (the only place that still
 * sees the whole text), and on the ServerData, copied when the pinger's handler receives that
 * packet.
 */
final class GameHooksServers {

    // The fields the patcher adds do not exist in the readable-name jar the Bridge compiles
    // against, so they are reached by VarHandle; null when the game was not patched with them.
    private static final VarHandle RESPONSE_OFFER =
            added(ServerStatusResponse.class, "ServerStatusResponse");
    private static final VarHandle SERVER_OFFER = added(ServerData.class, "ServerData");

    /** The ServerData field of each status handler class, or null for a handler with none. */
    private static final ClassValue<VarHandle> PING_TARGET =
            new ClassValue<>() {
                @Override
                protected VarHandle computeValue(Class<?> type) {
                    return serverField(type);
                }
            };

    private GameHooksServers() {}

    private static VarHandle added(Class<?> owner, String name) {
        try {
            return MethodHandles.privateLookupIn(owner, MethodHandles.lookup())
                    .findVarHandle(owner, "sbSteinSync", Object.class);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.info("servers", name + " has no Stein Sync field, the offer is not read: " + e);
            return null;
        }
    }

    /**
     * Gson.fromJson in S00PacketServerInfo.readPacketData: the game's own parse, then the Stein
     * Sync offer from the same text. The text is parsed again only when it names the field, so a
     * server without Stein Sync costs one substring search.
     */
    static Object statusJson(Object gson, Object json, Object type) {
        Object response = ((Gson) gson).fromJson((String) json, (Class<?>) type);
        if (response != null && RESPONSE_OFFER != null && GameHooks.steinSyncLogin != null) {
            try {
                RESPONSE_OFFER.setRelease(response, SteinSyncOffer.fromStatus((String) json));
            } catch (RuntimeException e) {
                Log.info("servers", "Stein Sync status field unreadable: " + e);
            }
        }
        return response;
    }

    /**
     * The body of S00PacketServerInfo.processPacket(INetHandlerStatusClient): the response's offer
     * goes to the ServerData the handler is pinging (cleared when the server no longer sends it),
     * then the handler receives the packet as before.
     */
    static void serverInfo(Object packet, Object handler) {
        S00PacketServerInfo info = (S00PacketServerInfo) packet;
        if (RESPONSE_OFFER != null && SERVER_OFFER != null && handler != null) {
            VarHandle target = PING_TARGET.get(handler.getClass());
            ServerStatusResponse response = info.getResponse();
            if (target != null && response != null) {
                Object server = target.get(handler);
                if (server != null) {
                    Object offer = RESPONSE_OFFER.getAcquire(response);
                    SERVER_OFFER.setRelease(server, offer);
                    pinged(server, offer);
                }
            }
        }
        ((INetHandlerStatusClient) handler).handleServerInfo(info);
    }

    private static void pinged(Object server, Object offer) {
        SteinSyncLoginHooks l = GameHooks.steinSyncLogin;
        if (l != null) {
            try {
                l.pinged(server, offer instanceof SteinSyncOffer ? (SteinSyncOffer) offer : null);
            } catch (Throwable e) {
                GameHooks.fail("steinSyncLogin.pinged", e);
            }
        }
    }

    /** The longest handshake address a server reads (the game's own limit). */
    private static final int HANDSHAKE_HOST_CHARS = 255;

    /**
     * NetworkManager.sendPacket in GuiConnecting's connection thread: the handshake (and then the
     * Login Start) of a join. A login handshake goes out with the address the listener gives back
     * (the Stein Sync marker appended for a server that offered Stein Sync); everything else, and
     * every packet without a listener, goes out as the game made it.
     */
    static void connectPacket(Object networkManager, Object packet) {
        Packet<?> out = (Packet<?>) packet;
        SteinSyncLoginHooks l = GameHooks.steinSyncLogin;
        if (l != null && packet instanceof C00Handshake) {
            try {
                out = marked((C00Handshake) packet, l);
            } catch (Throwable e) {
                GameHooks.fail("steinSyncLogin.loginAddress", e);
            }
        }
        ((NetworkManager) networkManager).sendPacket(out);
    }

    /** The handshake with the listener's address, or the same packet when nothing changes. */
    private static Packet<?> marked(C00Handshake handshake, SteinSyncLoginHooks l)
            throws IOException {
        if (handshake.getRequestedState() != EnumConnectionState.LOGIN) {
            return handshake;
        }
        // The packet keeps its fields private: they are read back from its own encoding.
        PacketBuffer buf = new PacketBuffer(Unpooled.buffer(64));
        handshake.writePacketData(buf);
        int protocol = buf.readVarIntFromBuffer();
        String address = buf.readStringFromBuffer(HANDSHAKE_HOST_CHARS);
        int port = buf.readUnsignedShort();
        l.loginTarget(address, port);
        String sent = l.loginAddress(address);
        if (sent == null || sent.equals(address) || sent.length() > HANDSHAKE_HOST_CHARS) {
            return handshake;
        }
        return new C00Handshake(protocol, sent, port, EnumConnectionState.LOGIN);
    }

    /**
     * The server pinged by a status handler. OldServerPinger's handler is an anonymous class (it
     * cannot be named, even in the readable jar), so its ServerData is found by type: the one
     * declared field of that type, or none for another mod's handler.
     */
    private static VarHandle serverField(Class<?> type) {
        Field found = null;
        for (Field f : type.getDeclaredFields()) {
            if (f.getType() == ServerData.class) {
                if (found != null) {
                    return null;
                }
                found = f;
            }
        }
        if (found == null) {
            return null;
        }
        try {
            return MethodHandles.privateLookupIn(type, MethodHandles.lookup())
                    .unreflectVarHandle(found);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.info("servers", "status handler " + type.getName() + " unreadable: " + e);
            return null;
        }
    }

    /** The Stein Sync offer a server list ping found for this ServerData, or null. */
    static SteinSyncOffer offer(Object server) {
        if (SERVER_OFFER == null || !(server instanceof ServerData)) {
            return null;
        }
        Object o = SERVER_OFFER.getAcquire(server);
        return o instanceof SteinSyncOffer ? (SteinSyncOffer) o : null;
    }

    /**
     * In place of IGuiListEntry.drawEntry in GuiListExtended.drawSlot: the entry draws itself, then
     * the listeners draw over a multiplayer server entry. Every list goes through here; the others
     * only pay an instanceof.
     */
    static void drawEntry(
            Object entry,
            int slot,
            int x,
            int y,
            int listWidth,
            int slotHeight,
            int mouseX,
            int mouseY,
            boolean selected) {
        ((GuiListExtended.IGuiListEntry) entry)
                .drawEntry(slot, x, y, listWidth, slotHeight, mouseX, mouseY, selected);
        if (GameHooks.SINK != null && entry instanceof ServerListEntryNormal) {
            try {
                GameHooks.SINK.serverListEntry(
                        ((ServerListEntryNormal) entry).getServerData(),
                        x,
                        y,
                        listWidth,
                        mouseX,
                        mouseY);
            } catch (Throwable e) {
                GameHooks.fail("serverListEntry", e);
            }
        }
    }
}
