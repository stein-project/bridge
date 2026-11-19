/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import static java.lang.invoke.MethodType.methodType;

import dev.xavier.stein.bridge.core.Log;

import net.minecraft.network.NetworkManager;
import net.minecraft.util.LazyLoadBase;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.net.InetAddress;
import java.net.SocketAddress;

/**
 * How the connection to the server goes out. The Bridge does not know how to talk to any proxy: it
 * only opens the two points that whoever does need, in the game's
 * NetworkManager.createNetworkManagerAndConnect (patcher rules, scoped to that single function, so
 * the rest of the game does not change):
 *
 * <ul>
 *   <li>{@link #handler}: the channel initializer (the game's, which builds the packet stack) first
 *       passes through the listener, which may return another one — and so put a handler of its own
 *       in front of the game's;
 *   <li>{@link #connect}: the destination address first passes through the listener, which may
 *       order a connection elsewhere (the proxy) and keep the real destination for its handler to
 *       request the tunnel.
 * </ul>
 *
 * <p>The Netty types come and go as Object: the Bridge compiles without it (LWJGL only), and
 * whoever implements the {@link Proxy} is a mod, which compiles with the game and Netty at hand.
 * With nobody registered, both hooks go straight to what the game would do.
 */
public final class Net {

    /**
     * Something that knows how to connect by another route (the Connect mod, via the Loader API).
     */
    public interface Proxy {
        /**
         * Where to actually connect.
         *
         * @param destination the server's InetAddress
         * @param port its port
         * @return the SocketAddress to go through (the proxy), or null to go direct
         */
        Object address(Object destination, int port);

        /**
         * The channel initializer to use.
         *
         * @param initializer the game's (ChannelHandler)
         * @return the same one, or another that puts handlers ahead of the game's
         */
        Object wrap(Object initializer);
    }

    private static volatile Proxy proxy;

    /**
     * Set on GuiConnecting's connection thread while it creates the connection of a join: the
     * listener can tell a login (which may go over another transport) from a server list ping
     * (always the plain connection), since both go through createNetworkManagerAndConnect.
     */
    private static final ThreadLocal<Boolean> LOGIN = new ThreadLocal<>();

    /** How long {@link #lazy} waits for a value another thread is creating: 500 x 10 ms. */
    private static final int MAX_WAIT_POLLS = 500;

    private static final long POLL_MILLIS = 10;

    private Net() {}

    /** Registers (or removes, with null) whoever diverts the connection. */
    public static void set(Proxy p) {
        proxy = p;
        Log.info("net", "connection " + (p == null ? "direct" : "via " + p.getClass().getName()));
    }

    /**
     * NetworkManager.createNetworkManagerAndConnect in GuiConnecting's connection thread (patcher
     * rule, redirects-mods.tsv): the same call, marked as a join for {@link #forLogin}.
     */
    public static Object loginConnect(Object address, int port, boolean nativeTransport) {
        LOGIN.set(Boolean.TRUE);
        try {
            return NetworkManager.createNetworkManagerAndConnect(
                    (InetAddress) address, port, nativeTransport);
        } finally {
            LOGIN.remove();
        }
    }

    /**
     * Whether the connection being created on this thread is a join (from GuiConnecting), not a
     * server list ping.
     */
    public static boolean forLogin() {
        return LOGIN.get() != null;
    }

    /**
     * The initializer {@link #handler} gave the connection being created on this thread, for a
     * connection {@link #connect} moves to a local channel (a new bootstrap needs it again).
     */
    private static final ThreadLocal<Object> INITIALIZER = new ThreadLocal<>();

    /** Bootstrap.handler(ChannelHandler) in createNetworkManagerAndConnect. */
    public static Object handler(Object bootstrap, Object handler) throws Throwable {
        Proxy p = proxy;
        Object use = handler;
        if (p != null) {
            try {
                use = p.wrap(handler);
            } catch (Throwable e) {
                Log.info("net", "mod initializer failed, carrying on with the game's: " + e);
            }
        }
        INITIALIZER.set(use);
        return NettyHandles.HANDLER.invokeExact(bootstrap, use);
    }

    /** Bootstrap.connect(InetAddress, int) in createNetworkManagerAndConnect. */
    public static Object connect(Object bootstrap, Object address, int port) throws Throwable {
        Proxy p = proxy;
        Object via = null;
        if (p != null) {
            try {
                via = p.address(address, port);
            } catch (Throwable e) {
                Log.info("net", "mod detour failed, connecting directly: " + e);
            }
        }
        Object initializer = INITIALIZER.get();
        INITIALIZER.remove();
        if (via == null) {
            return NettyHandles.CONNECT.invokeExact(bootstrap, address, port);
        }
        Log.info("net", "connecting via " + via);
        if (NettyHandles.LOCAL_ADDRESS.isInstance(via) && initializer != null) {
            // An address inside this JVM (a transport the listener runs itself, such as Stein
            // Sync's QUIC): a local channel on the game's own event loop, with the same
            // initializer, so the game's pipeline is built as for a socket. Netty sets a
            // bootstrap's channel type once, so it is a new bootstrap.
            Object local = NettyHandles.NEW_BOOTSTRAP.invoke();
            Object unused =
                    NettyHandles.GROUP_SET.invoke(local, NettyHandles.GROUP.invoke(bootstrap));
            unused = NettyHandles.CHANNEL.invokeExact(local, NettyHandles.LOCAL_CHANNEL);
            unused = NettyHandles.HANDLER.invokeExact(local, initializer);
            return NettyHandles.CONNECT_VIA.invokeExact(local, via);
        }
        return NettyHandles.CONNECT_VIA.invokeExact(bootstrap, via);
    }

    /**
     * Prefix of the Netty LocalAddress ids of the transports that run inside this JVM (Stein Sync's
     * QUIC, {@code local:stein-quic-1}): such a connection is a server's, not the integrated
     * server's, whatever its channel class.
     */
    public static final String TRANSPORT_PREFIX = "stein-";

    /**
     * NetworkManager.isLocalChannel where the client decides to turn compression on
     * (NetHandlerLoginClient.handleEnableCompression,
     * NetHandlerPlayClient.handleSetCompressionLevel): the game skips compression on a local
     * channel because only the integrated server uses one; a transport inside this JVM carries a
     * real server's compressed stream, so it is not local there.
     */
    public static boolean isLocalChannel(Object networkManager) {
        NetworkManager nm = (NetworkManager) networkManager;
        if (!nm.isLocalChannel()) {
            return false;
        }
        Object remote = nm.getRemoteAddress();
        return remote == null || !String.valueOf(remote).startsWith("local:" + TRANSPORT_PREFIX);
    }

    /**
     * LazyLoadBase.getValue (no.c) in NetworkManager: the game's has no lock — it marks "loaded"
     * before creating the value, so two threads at the same time (the --server connection leaving
     * during startGame while the Loader creates the network group, or the server list pings) got
     * null and Netty refused it ("NullPointerException: group"). Here, one thread at a time; and if
     * another one, outside this code, is still creating it, wait for the value to appear.
     */
    public static Object lazy(Object lazy) throws InterruptedException {
        Object v;
        synchronized (lazy) {
            v = ((LazyLoadBase<?>) lazy).getValue();
        }
        for (int i = 0; v == null && i < MAX_WAIT_POLLS; i++) {
            Thread.sleep(POLL_MILLIS);
            v = ((LazyLoadBase<?>) lazy).getValue();
        }
        return v;
    }

    /**
     * The Netty methods, found on the first connection (the Bridge does not compile against it).
     */
    private static final class NettyHandles {
        static final MethodHandle HANDLER;
        static final MethodHandle CONNECT;
        static final MethodHandle CONNECT_VIA;
        static final MethodHandle CHANNEL;
        static final MethodHandle NEW_BOOTSTRAP;
        static final MethodHandle GROUP;
        static final MethodHandle GROUP_SET;
        static final Class<?> LOCAL_ADDRESS;
        static final Class<?> LOCAL_CHANNEL;

        static {
            try {
                ClassLoader cl = Net.class.getClassLoader();
                Class<?> boot = Class.forName("io.netty.bootstrap.Bootstrap", false, cl);
                Class<?> abs = Class.forName("io.netty.bootstrap.AbstractBootstrap", false, cl);
                Class<?> handler = Class.forName("io.netty.channel.ChannelHandler", false, cl);
                Class<?> future = Class.forName("io.netty.channel.ChannelFuture", false, cl);
                MethodHandles.Lookup l = MethodHandles.lookup();
                Class<?> o = Object.class;
                HANDLER =
                        l.findVirtual(boot, "handler", methodType(abs, handler))
                                .asType(methodType(o, o, o));
                CONNECT =
                        l.findVirtual(
                                        boot,
                                        "connect",
                                        methodType(future, InetAddress.class, int.class))
                                .asType(methodType(o, o, o, int.class));
                CONNECT_VIA =
                        l.findVirtual(boot, "connect", methodType(future, SocketAddress.class))
                                .asType(methodType(o, o, o));
                CHANNEL =
                        l.findVirtual(abs, "channel", methodType(abs, Class.class))
                                .asType(methodType(o, o, Class.class));
                Class<?> group = Class.forName("io.netty.channel.EventLoopGroup", false, cl);
                NEW_BOOTSTRAP = l.findConstructor(boot, methodType(void.class));
                GROUP = l.findVirtual(abs, "group", methodType(group));
                GROUP_SET = l.findVirtual(abs, "group", methodType(abs, group));
                LOCAL_ADDRESS = Class.forName("io.netty.channel.local.LocalAddress", false, cl);
                LOCAL_CHANNEL = Class.forName("io.netty.channel.local.LocalChannel", false, cl);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }
    }
}
