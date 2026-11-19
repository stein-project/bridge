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
import dev.xavier.stein.bridge.hooks.SteinSyncOffer;
import dev.xavier.stein.bridge.net.SteinQuic;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;

import java.net.InetAddress;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Joins over QUIC when the server offers it (stein-sync's docs/protocol.md section 7): the Ticks
 * then travel as datagrams, so a lost packet no longer holds back the player's movement, and the
 * session survives a change of the player's address.
 *
 * <p>Only a join (GuiConnecting) to a server whose server list entry announced QUIC, with the
 * native library present and the player's setting on, tries it; a server list ping, a vanilla
 * server, or a server without QUIC goes out exactly as before. A failed attempt falls back to TCP
 * at once, which is today's mode, and is remembered for a while so the next join does not wait
 * again. What happened is reported to the proxy after the Accept (the Transport message), which is
 * how a server learns how many of its Stein players cannot use UDP.
 *
 * <p>It takes the Bridge's connection hooks (engine/Net). A mod's own route (the Connect mod, a
 * SOCKS proxy) still applies: a connection its route diverts (it gives an address for it) takes the
 * route and QUIC is not tried; a mod that only registered its route (the Connect mod with its SOCKS
 * off) leaves joins free to use QUIC ({@link SteinSyncRoute}).
 */
final class SteinSyncQuic implements Net.Proxy {

    /** The player's setting (the video settings, {@code steinSyncQuic}); on by default. */
    static volatile boolean allowed = true;

    /** How long each announced port may take to answer. */
    private static final int ATTEMPT_MS = 1200;

    /** How long a failed attempt keeps the next joins to that server on TCP without trying. */
    private static final long FAILURE_MEMORY_MS = 10 * 60 * 1000L;

    /** Servers remembered at most (a list never has this many; a flood cannot grow it). */
    private static final int MAX_REMEMBERED = 256;

    private static final SteinSyncQuic INSTANCE = new SteinSyncQuic();

    /** A mod's route, which takes precedence (QUIC cannot go through it). */
    private volatile Net.Proxy mod;

    /** The initializer {@link #wrap} made for the join being set up on this thread. */
    private final ThreadLocal<Setup> pending = new ThreadLocal<>();

    /** Servers whose last attempt failed, until when to skip them. */
    private final ConcurrentHashMap<String, Long> failedUntil = new ConcurrentHashMap<>();

    /** The latest join's attempt, reported after its Accept. */
    private volatile Attempt last;

    /** What one join's QUIC attempt came to (the Transport message's fields). */
    static final class Attempt {
        final int outcome;
        final int millis;

        /** The QUIC link, null when the join goes over TCP. */
        final SteinSyncLink link;

        Attempt(int outcome, int millis, SteinSyncLink link) {
            this.outcome = outcome;
            this.millis = millis;
            this.link = link;
        }

        static Attempt tcp(int outcome) {
            return new Attempt(outcome, 0, null);
        }
    }

    /**
     * The transport of a QUIC join ({@code -Dsteinbridge.quicTransport}): {@code kwik} (default,
     * Java) or {@code native} (the Bridge's library, kept for comparison).
     */
    static boolean nativeTransport() {
        return "native".equals(SteinSyncOptions.QUIC_TRANSPORT);
    }

    private SteinSyncQuic() {}

    /** Takes the Bridge's connection hooks when Stein Sync and QUIC are on. */
    static void install() {
        if (SteinSync.ENABLED && !SteinSyncOptions.NO_QUIC) {
            Net.set(INSTANCE);
        }
    }

    /**
     * A mod's route (the Loader's LoaderHost.setProxy, through SteinSync.setModProxy): with QUIC
     * installed it is kept here and every connection takes it; otherwise it goes to the Bridge
     * directly, as before.
     */
    static void setModProxy(Net.Proxy proxy) {
        if (SteinSync.ENABLED && !SteinSyncOptions.NO_QUIC) {
            INSTANCE.mod = proxy;
            Log.info(
                    "net",
                    "connection "
                            + (proxy == null
                                    ? "direct"
                                    : "via " + ((Object) proxy).getClass().getName()));
        } else {
            Net.set(proxy);
        }
    }

    /**
     * The Transport message for the connection that just accepted (type 12): its transport and its
     * QUIC attempt. Called once per Accept, on the game thread.
     */
    static byte[] transportMessage() {
        Attempt a = INSTANCE.last;
        if (a == null) {
            // Never tried on this run: QUIC turned off with -Dsteinbridge.noQuic (the hooks are
            // not even installed), or a join that did not go through them.
            SteinSyncOffer offer = SteinSyncJoin.currentOffer();
            boolean offered = offer != null && offer.quicPorts != null;
            return SteinSyncCodec.writeTransport(
                    0,
                    offered && SteinSyncOptions.NO_QUIC
                            ? SteinSyncCodec.QUIC_DISABLED
                            : SteinSyncCodec.QUIC_NOT_OFFERED,
                    0);
        }
        return SteinSyncCodec.writeTransport(a.link != null ? 1 : 0, a.outcome, a.millis);
    }

    /**
     * Whether the latest join reached its server over QUIC: the connection a Hello is read on (the
     * proxy's movement authority is accepted only then, section 5.10).
     */
    static boolean onQuic() {
        Attempt a = INSTANCE.last;
        return a != null && a.link != null;
    }

    @Override
    public Object wrap(Object initializer) {
        // A mod's route (Connect's SOCKS) wraps first; whether it diverts this join is only known
        // from its address, so a join keeps the QUIC setup around the mod's initializer.
        Net.Proxy route = mod;
        Object init = route != null ? route.wrap(initializer) : initializer;
        if (!Net.forLogin()) {
            return init;
        }
        Setup setup = new Setup((ChannelHandler) init);
        pending.set(setup);
        return setup;
    }

    @Override
    public Object address(Object destination, int port) {
        Setup setup = pending.get();
        pending.remove();
        Object diverted = modAddress(mod, destination, port);
        boolean join = setup != null && Net.forLogin() && destination instanceof InetAddress;
        int choice = SteinSyncRoute.choose(diverted, join);
        if (choice == SteinSyncRoute.MOD) {
            // The mod really diverts this join (its SOCKS is on): QUIC cannot go through it.
            if (join) {
                last = Attempt.tcp(SteinSyncCodec.QUIC_DISABLED);
            }
            return diverted;
        }
        if (choice == SteinSyncRoute.DIRECT) {
            return null;
        }
        Attempt attempt = attempt((InetAddress) destination);
        last = attempt;
        if (attempt.link == null) {
            return null;
        }
        setup.link = attempt.link;
        return attempt.link.gameAddress();
    }

    /**
     * The address a mod's route sends this connection to, or null when it has none or lets it go
     * direct (Connect registers its route even with its SOCKS off). A route that fails counts as
     * going direct, as the Bridge treats it.
     */
    private static Object modAddress(Net.Proxy route, Object destination, int port) {
        if (route == null) {
            return null;
        }
        try {
            return route.address(destination, port);
        } catch (Throwable e) {
            Log.info("net", "mod route failed, connecting without it: " + e);
            return null;
        }
    }

    private Attempt attempt(InetAddress destination) {
        SteinSyncOffer offer = SteinSyncJoin.currentOffer();
        if (offer == null || offer.quicPorts == null) {
            return Attempt.tcp(SteinSyncCodec.QUIC_NOT_OFFERED);
        }
        if (!allowed) {
            return Attempt.tcp(SteinSyncCodec.QUIC_DISABLED);
        }
        boolean nativeLib = nativeTransport();
        if (nativeLib ? !SteinQuic.available() : !kwikAvailable()) {
            return Attempt.tcp(SteinSyncCodec.QUIC_UNSUPPORTED);
        }
        String key = destination.getHostAddress();
        Long until = failedUntil.get(key);
        long now = System.currentTimeMillis();
        if (until != null && until > now) {
            return Attempt.tcp(SteinSyncCodec.QUIC_SKIPPED);
        }
        long started = System.nanoTime();
        int[] outcome = new int[1];
        SteinSyncLink link =
                nativeLib
                        ? connectNative(destination, offer, outcome)
                        : SteinSyncKwik.connect(
                                destination, offer.quicPorts, offer.quicPin, ATTEMPT_MS, outcome);
        int millis = (int) ((System.nanoTime() - started) / 1_000_000);
        if (link != null) {
            failedUntil.remove(key);
            Log.info(
                    "steinsync",
                    "Stein Sync: joining over QUIC ("
                            + link.kind()
                            + ", "
                            + millis
                            + " ms to connect)");
            return new Attempt(SteinSyncCodec.QUIC_CONNECTED, millis, link);
        }
        if (failedUntil.size() >= MAX_REMEMBERED) {
            failedUntil.clear();
        }
        failedUntil.put(key, now + FAILURE_MEMORY_MS);
        Log.info(
                "steinsync",
                "Stein Sync: QUIC failed (outcome "
                        + outcome[0]
                        + " after "
                        + millis
                        + " ms); joining over TCP");
        return new Attempt(outcome[0], millis, null);
    }

    /**
     * Whether Kwik is on the classpath (its jars ship next to the Bridge's). Checked here, not in
     * {@link SteinSyncKwik}: loading that class needs Kwik's.
     */
    private static boolean kwikAvailable() {
        try {
            Class.forName(
                    "tech.kwik.core.QuicClientConnection",
                    false,
                    SteinSyncQuic.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    /** The native library's connection (the comparison transport). */
    private static SteinSyncLink connectNative(
            InetAddress destination, SteinSyncOffer offer, int[] outcome) {
        int[] localPort = new int[1];
        long handle =
                SteinQuic.connect(
                        destination.getAddress(),
                        offer.quicPorts,
                        offer.quicPin,
                        ATTEMPT_MS,
                        localPort);
        if (handle > 0) {
            return new SteinSyncNativeLink(handle, localPort[0]);
        }
        outcome[0] =
                handle == SteinQuic.TIMEOUT
                        ? SteinSyncCodec.QUIC_TIMEOUT
                        : handle == SteinQuic.HANDSHAKE_FAILED
                                ? SteinSyncCodec.QUIC_HANDSHAKE_FAILED
                                : SteinSyncCodec.QUIC_UNSUPPORTED;
        return null;
    }

    /**
     * Builds the game's pipeline, then, on a QUIC connection, puts {@link SteinSyncQuicHandler}
     * after the game's packet handler. A ChannelInitializer cannot call another's initChannel, so
     * the game's initializer is added behind this one and a one-shot handler behind it inserts ours
     * once the game's handlers exist (the registration event reaches them in that order).
     */
    static final class Setup extends ChannelInitializer<Channel> {
        private final ChannelHandler game;

        /** The link, set by {@link SteinSyncQuic#address} before the connection registers. */
        volatile SteinSyncLink link;

        Setup(ChannelHandler game) {
            this.game = game;
        }

        @Override
        protected void initChannel(Channel ch) {
            ch.pipeline().addLast(game);
            SteinSyncLink l = link;
            if (l != null) {
                ch.pipeline().addLast("stein_quic_setup", new Insert(l));
            }
        }
    }

    /** Inserts the QUIC handler once the game's pipeline is built, then leaves. */
    private static final class Insert extends ChannelInboundHandlerAdapter {
        private final SteinSyncLink link;

        Insert(SteinSyncLink link) {
            this.link = link;
        }

        @Override
        public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
            ChannelPipeline p = ctx.pipeline();
            if (p.get("packet_handler") != null) {
                p.addBefore("packet_handler", "stein_quic", new SteinSyncQuicHandler(link));
            } else {
                // Another pipeline than the game's: the bytes still go over QUIC, but Ticks stay
                // on the stream (nothing diverts them), which the proxy reads as before.
                Log.info("steinsync", "Stein Sync QUIC: no packet_handler in the pipeline");
            }
            p.remove(this);
            super.channelRegistered(ctx);
        }
    }
}
