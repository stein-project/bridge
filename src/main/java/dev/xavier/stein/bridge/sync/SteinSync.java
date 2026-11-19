/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.sync;

import dev.xavier.stein.bridge.core.Log;
import dev.xavier.stein.bridge.engine.Net;
import dev.xavier.stein.bridge.hooks.GameHooks;

import io.netty.buffer.Unpooled;

import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.client.C17PacketCustomPayload;

import java.util.Collections;
import java.util.List;

/**
 * Stein Sync negotiation on the current connection, and the face of the Stein Sync client for the
 * rest of the game: a Stein Sync proxy sends a Hello on the {@code stein:sync} channel right after
 * Join Game, and this client answers it with an Accept, once per connection. From then on the
 * connection is in Stein Sync mode (the Loader's mods read the state through {@link #version},
 * {@link #features} and {@link #hits}). With the {@code hits} feature accepted, the proxy's
 * HitConfirm, Damaged and CombatSeen messages flash the hit marker and go to the game's listeners
 * ({@link GameHooks#steinSyncHit}, {@link GameHooks#steinSyncDamaged}, {@link
 * GameHooks#steinSyncCombatSeen}: the Loader turns them into mod events); with {@code move} and
 * {@code blocks}, {@link SteinSyncClient} takes over the movement, attack and block packets; with
 * {@code authority} (only over QUIC) it also takes the proxy's Corrections. On any other connection
 * the proxy's messages are ignored, whoever sent them.
 *
 * <p>All of it is the Bridge's: it runs the same with or without the Loader. A connection is the
 * game's NetHandlerPlayClient: a new one (a new join, or a reconnect) starts over, and so does
 * leaving the server. The Bridge drops packets queued for a handler whose connection already
 * closed, so a Hello seen here belongs to the current one. Everything runs on the game thread; the
 * state the mods read is volatile.
 *
 * <p>{@code -Dsteinbridge.noSteinSync=true} (or its old name {@code -Dsteinloader.noSteinSync})
 * turns this off along with the server list badge: no Accept, so the proxy keeps the connection
 * vanilla, and the game's hooks never get a listener.
 */
public final class SteinSync {

    /** Stein Sync is on for this run (not turned off with {@code -Dsteinbridge.noSteinSync}). */
    public static final boolean ENABLED = !SteinSyncOptions.NO_STEIN_SYNC;

    /** The hit marker is on (the player's switch in the video settings); off until then. */
    private static volatile boolean hitMarker;

    /** The connection the state below is for (identity only), or null outside a server. */
    private static Object connection;

    private static boolean answered;

    /** The agreed version, 0 while not in Stein Sync mode. */
    private static volatile int version;

    private static volatile List<String> features = Collections.emptyList();

    /** The {@code hits} feature is on for this connection. */
    private static volatile boolean hits;

    /**
     * The mode this connection left because a Respawn's Still was late (section 5.9), kept so that
     * the late Still brings it back; 0 when nothing is paused.
     */
    private static int pausedVersion;

    private static List<String> pausedFeatures = Collections.emptyList();

    /** A malformed message was already logged on this connection: one line, not a flood. */
    private static boolean badLogged;

    /**
     * Lines a server can repeat at will (a Hello sent again and again, a malformed Hello): at most
     * one per period each, with the count of those left out.
     */
    private static final LogLimit HELLO_LOG = new LogLimit(10_000);

    private static final LogLimit BAD_HELLO_LOG = new LogLimit(10_000);

    /** The last messages read, each reused for every message of its type. */
    static final SteinSyncCodec.HitConfirm HIT = new SteinSyncCodec.HitConfirm();

    static final SteinSyncCodec.Damaged DAMAGED = new SteinSyncCodec.Damaged();
    static final SteinSyncCodec.CombatSeen SEEN = new SteinSyncCodec.CombatSeen();
    private static final SteinSyncCodec.BlockAck ACK = new SteinSyncCodec.BlockAck();

    private static final SteinSyncCodec.Correction CORRECTION = new SteinSyncCodec.Correction();

    /** Which player entity a Still or Hello last confirmed (server switches, section 5.9). */
    private static final SteinSyncStill STILL = new SteinSyncStill();

    private SteinSync() {}

    /**
     * A plugin packet (S3F), on the game thread, before the listeners see it: only {@code
     * stein:sync} matters here. A HitConfirm, Damaged or CombatSeen of a {@code hits} connection
     * goes on to the hit marker and the game's listeners ({@link SteinSyncEvents}).
     *
     * @param channel the packet's channel
     * @param data a copy of its bytes
     */
    public static void received(String channel, byte[] data) {
        int type = read(channel, data);
        if (type != 0) {
            SteinSyncEvents.dispatch(type);
        }
    }

    /**
     * Reads a plugin packet: {@link #received} without the dispatch.
     *
     * @return the type of a message for the mods, read into {@link #HIT}, {@link #DAMAGED} or
     *     {@link #SEEN} (valid until the next call): {@link SteinSyncCodec#HIT_CONFIRM}, {@link
     *     SteinSyncCodec#DAMAGED} or {@link SteinSyncCodec#COMBAT_SEEN}; 0 for anything else
     */
    static int read(String channel, byte[] data) {
        if (!ENABLED || !SteinSyncCodec.CHANNEL.equals(channel)) {
            return 0;
        }
        Minecraft mc = Minecraft.getMinecraft();
        NetHandlerPlayClient handler = mc.getNetHandler();
        if (handler == null || mc.isSingleplayer()) {
            return 0;
        }
        follow(handler);
        int type = data == null || data.length == 0 ? -1 : data[0] & 0xFF;
        switch (type) {
            case SteinSyncCodec.HIT_CONFIRM:
                return forMods(type, hits && SteinSyncCodec.readHitConfirm(data, HIT), data);
            case SteinSyncCodec.DAMAGED:
                return forMods(type, hits && SteinSyncCodec.readDamaged(data, DAMAGED), data);
            case SteinSyncCodec.COMBAT_SEEN:
                return forMods(type, hits && SteinSyncCodec.readCombatSeen(data, SEEN), data);
            case SteinSyncCodec.STILL:
                if (version != 0) {
                    STILL.confirm(mc.thePlayer);
                } else if (pausedVersion != 0) {
                    resume(mc);
                }
                return 0;
            case SteinSyncCodec.CORRECTION:
                if (version != 0 && SteinSyncCodec.readCorrection(data, CORRECTION)) {
                    SteinSyncClient.INSTANCE.correction(CORRECTION);
                }
                return 0;
            case SteinSyncCodec.BLOCK_ACK:
                if (version != 0 && SteinSyncCodec.readBlockAck(data, ACK)) {
                    SteinSyncClient.INSTANCE.blockAck(ACK.seq);
                }
                return 0;
            default:
                hello(handler, data);
                return 0;
        }
    }

    /**
     * A message for the mods: only on a connection that accepted {@code hits}. Before that it can
     * only be a server plugin's message (the proxy sends none without the feature), so it is
     * dropped quietly; a malformed one on a {@code hits} connection is worth one line.
     */
    private static int forMods(int type, boolean read, byte[] data) {
        if (read) {
            return type;
        }
        if (hits && !badLogged) {
            badLogged = true;
            Log.info(
                    "steinsync",
                    "Stein Sync: malformed message of type "
                            + type
                            + " ignored ("
                            + data.length
                            + " bytes; further ones on this connection are not logged)");
        }
        return 0;
    }

    private static void hello(NetHandlerPlayClient handler, byte[] data) {
        SteinSyncCodec.Hello hello = SteinSyncCodec.readHello(data);
        if (hello == null) {
            // Another message type (a later version's) is ignored quietly; a malformed Hello is
            // worth a line.
            if (data != null && data.length > 0 && data[0] == SteinSyncCodec.HELLO) {
                int leftOut = BAD_HELLO_LOG.due();
                if (leftOut >= 0) {
                    Log.info(
                            "steinsync",
                            "Stein Sync: malformed Hello ignored ("
                                    + data.length
                                    + " bytes)"
                                    + LogLimit.leftOut(leftOut));
                }
            }
            return;
        }
        // The first Hello of a connection is always logged; repeats are limited.
        int leftOut = answered ? HELLO_LOG.due() : 0;
        if (answered && leftOut >= 0) {
            // Behind BungeeCord or Velocity a server switch keeps this connection (and its
            // NetHandlerPlayClient): a Hello now comes from the Stein Sync proxy of the next
            // server, which never got an Accept. Negotiating again keeps the client from sending
            // Ticks to a proxy that does not expect them; the old proxy is gone with its server.
            Log.info(
                    "steinsync",
                    "Stein Sync: new Hello on this connection (server switch?)"
                            + LogLimit.leftOut(leftOut));
        }
        // A new negotiation replaces a paused mode.
        pausedVersion = 0;
        pausedFeatures = Collections.emptyList();
        int agreed = SteinSyncCodec.agreedVersion(hello.version);
        List<String> accepted = SteinSyncCodec.supported(hello.features, SteinSyncQuic.onQuic());
        byte[] accept = SteinSyncCodec.writeAccept(agreed, accepted);
        answered = true;
        // The Accept leaves before the first Tick or BlockAction: the proxy reads them in order.
        handler.addToSendQueue(
                new C17PacketCustomPayload(
                        SteinSyncCodec.CHANNEL, new PacketBuffer(Unpooled.wrappedBuffer(accept))));
        // Right behind it, how this client reached the proxy (section 7.6): the server's count of
        // Stein players that cannot use UDP comes from these.
        handler.addToSendQueue(
                new C17PacketCustomPayload(
                        SteinSyncCodec.CHANNEL,
                        new PacketBuffer(
                                Unpooled.wrappedBuffer(SteinSyncQuic.transportMessage()))));
        features = accepted;
        hits = accepted.contains(SteinSyncCodec.HITS);
        version = agreed;
        STILL.confirm(Minecraft.getMinecraft().thePlayer);
        SteinSyncClient.INSTANCE.start(
                handler,
                accepted.contains(SteinSyncCodec.MOVE),
                hits,
                accepted.contains(SteinSyncCodec.BLOCKS),
                accepted.contains(SteinSyncCodec.AUTHORITY));
        if (leftOut >= 0) {
            Log.info(
                    "steinsync",
                    "Stein Sync on: proxy version "
                            + hello.version
                            + ", agreed "
                            + agreed
                            + ", offered "
                            + SteinSyncAddress.plain(String.valueOf(hello.features))
                            + ", accepted "
                            + accepted);
        }
    }

    /**
     * End of each tick, after the listeners: a new connection, or none, starts over. No allocation.
     */
    public static void tick() {
        if (ENABLED) {
            Minecraft mc = Minecraft.getMinecraft();
            follow(mc.getNetHandler());
            if (version != 0 && STILL.tick(mc.thePlayer)) {
                switchedAway();
            }
            SteinSyncClient.INSTANCE.tickEnd();
        }
    }

    /**
     * A new player entity got no Still in time (section 5.9): a server switch behind BungeeCord or
     * Velocity to a server without Stein Sync, or a Still held up on a bad link. Back to the
     * vanilla packets on this connection (a Stein Sync proxy forwards them for that entity), with
     * the mode kept aside: a late Still brings it back, and a later server's Hello negotiates
     * again.
     */
    private static void switchedAway() {
        Log.info(
                "steinsync",
                "Stein Sync paused: a respawn without the proxy's confirmation (a switch to a server"
                        + " without Stein Sync, or a late confirmation)");
        SteinSyncClient.INSTANCE.pause();
        STILL.clear();
        pausedVersion = version;
        pausedFeatures = features;
        version = 0;
        hits = false;
        features = Collections.emptyList();
    }

    /**
     * The Still the paused mode waited for came after all: the mode comes back for the current
     * player entity, the Tick numbering going on where it was (the proxy counts it per connection).
     */
    private static void resume(Minecraft mc) {
        version = pausedVersion;
        features = pausedFeatures;
        hits = features.contains(SteinSyncCodec.HITS);
        pausedVersion = 0;
        pausedFeatures = Collections.emptyList();
        STILL.confirm(mc.thePlayer);
        SteinSyncClient.INSTANCE.resume();
        Log.info("steinsync", "Stein Sync back on: the respawn's confirmation came late");
    }

    /**
     * May the Stein Sync packets go out now? False while the player entity built by a Respawn waits
     * for the proxy's Still (section 5.9): the vanilla packets go out meanwhile. Game thread.
     */
    static boolean confirmed() {
        return STILL.confirmed(Minecraft.getMinecraft().thePlayer);
    }

    private static void follow(Object handler) {
        if (handler == connection) {
            return;
        }
        if (version != 0) {
            Log.info("steinsync", "Stein Sync off: the connection changed");
        }
        SteinSyncClient.INSTANCE.stop();
        STILL.clear();
        connection = handler;
        answered = false;
        pausedVersion = 0;
        pausedFeatures = Collections.emptyList();
        version = 0;
        hits = false;
        badLogged = false;
        features = Collections.emptyList();
    }

    /** The agreed protocol version on this connection, or 0 when it is not in Stein Sync mode. */
    public static int version() {
        return version;
    }

    /** Is the {@code hits} feature on for this connection? */
    public static boolean hits() {
        return hits;
    }

    /** The features turned on for this connection (unmodifiable); empty when not active. */
    public static List<String> features() {
        return features;
    }

    // ---- set up by the Bridge, the player's settings and the Loader ----

    /**
     * After Minecraft.startGame, before the first server list ping: the server list and login
     * hooks, and the QUIC transport. Without it (and never with {@code -Dsteinbridge.noSteinSync})
     * the status JSON is not even searched for the Stein Sync offer.
     */
    public static void install() {
        if (ENABLED) {
            SteinSyncJoin.install();
            SteinSyncQuic.install();
            SyncView.load();
        }
    }

    /**
     * The player's Stein Sync settings (the video settings): the hit marker, and whether a join may
     * go over QUIC. Applied at once.
     */
    public static void configure(boolean hitMarker, boolean quic) {
        SteinSync.hitMarker = hitMarker;
        SteinSyncQuic.allowed = quic;
    }

    /** The newest Stein Sync protocol version this client speaks. */
    public static int clientVersion() {
        return SteinSyncCodec.CLIENT_VERSION;
    }

    /** A server's text without its formatting codes, for showing it plain. */
    public static String plainText(String text) {
        return SteinSyncAddress.plain(text);
    }

    /** Is the hit marker on? Read on every frame. */
    public static boolean hitMarker() {
        return hitMarker;
    }

    /**
     * A mod's own route for the game's connections (the Connect mod, through the Loader's API):
     * with QUIC on it is kept by the Stein Sync transport, which still gives it every connection it
     * diverts; otherwise it goes to the Bridge's {@link Net} directly.
     */
    public static void setModProxy(Net.Proxy proxy) {
        SteinSyncQuic.setModProxy(proxy);
    }
}
