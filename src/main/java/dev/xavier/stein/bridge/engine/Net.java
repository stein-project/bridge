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

    /** How long {@link #lazy} waits for a value another thread is creating: 500 x 10 ms. */
    private static final int MAX_WAIT_POLLS = 500;

    private static final long POLL_MILLIS = 10;

    private Net() {}

    /** Registers (or removes, with null) whoever diverts the connection. */
    public static void set(Proxy p) {
        proxy = p;
        Log.info("net", "connection " + (p == null ? "direct" : "via " + p.getClass().getName()));
    }

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
        if (via == null) {
            return NettyHandles.CONNECT.invokeExact(bootstrap, address, port);
        }
        Log.info("net", "connecting via " + via);
        return NettyHandles.CONNECT_VIA.invokeExact(bootstrap, via);
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
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }
    }
}
