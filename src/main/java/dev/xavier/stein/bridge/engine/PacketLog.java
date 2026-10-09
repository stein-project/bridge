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
import dev.xavier.stein.bridge.core.SteinLog;
import dev.xavier.stein.bridge.core.SteinLog.Topic;

import net.minecraft.network.INetHandler;
import net.minecraft.network.Packet;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Packet passage: everything that arrives from the server and everything that leaves goes through
 * here, to be counted.
 *
 * <p>Two things come out of these counts: the {@link Watchdog} counters, which on their own already
 * tell whether the connection went silent, and — only with logging on — a per-second summary in the
 * {@link SteinLog}, with the packet types that moved the most. It lets you look at a freeze
 * afterwards and see what stopped: whether everything arrived except the chat, whether nothing
 * arrived, whether it was the client that stopped sending.
 *
 * <p>It sits on the path of every packet (on a full server, thousands per second), so the cost is
 * one class counter and nothing more; with logging off, not even that. The original call always
 * happens, whatever happens to the counting.
 */
public final class PacketLog {

    /** How many types appear in each direction's summary; the rest becomes "and others". */
    private static final int MAX_TYPES_PER_LINE = 6;

    /**
     * Per packet type: [0] the session total, [1] the total at the last summary — the difference is
     * the second.
     */
    private static final Map<Class<?>, long[]> IN = new ConcurrentHashMap<>();

    private static final Map<Class<?>, long[]> OUT = new ConcurrentHashMap<>();

    private static final Map<String, String> NAMES = names();

    private static Map<String, String> names() {
        Map<String, String> names = new HashMap<>();
        // At the jar root: this way the path does not change when the release build renames the
        // packages of our classes.
        try (InputStream in = PacketLog.class.getResourceAsStream("/stein-packets.txt")) {
            if (in != null) {
                BufferedReader r =
                        new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                for (String line = r.readLine(); line != null; line = r.readLine()) {
                    int tab = line.indexOf('\t');
                    if (tab > 0) {
                        names.put(line.substring(0, tab), line.substring(tab + 1));
                    }
                }
            }
        } catch (IOException e) {
            Log.info("net", "no packet name table: " + e);
        }
        return names;
    }

    private PacketLog() {}

    /**
     * Packet.processPacket, on the network thread (NetworkManager.channelRead0). Handing the packet
     * back to the game is what matters: the counting goes inside its own try so that no error here
     * gets in the game's way.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void in(Object packet, Object handler) {
        Watchdog.packetIn();
        if (SteinLog.on(Topic.NET)) {
            countQuietly(IN, packet);
        }
        ((Packet) packet).processPacket((INetHandler) handler);
    }

    /**
     * Channel.writeAndFlush of the packet send (on the network thread, through dispatchPacket or
     * the deferred Runnable).
     */
    public static Object out(Object channel, Object packet) throws Throwable {
        Watchdog.packetOut();
        if (SteinLog.on(Topic.NET)) {
            countQuietly(OUT, packet);
        }
        return P.WRITE.invokeExact(channel, packet);
    }

    /**
     * The readable name of the packet type: "S17PacketEntityLookMove" instead of "gv$b". The table
     * (stein-packets.txt, generated at build time from the MCP joined.srg) covers packets only —
     * without it, the obfuscated names remain, which can still be checked against the MCP table.
     */
    private static String name(Class<?> type) {
        String obf = type.getName();
        int dot = obf.lastIndexOf('.');
        if (dot >= 0) {
            obf = obf.substring(dot + 1);
        }
        String known = NAMES.get(obf);
        return known != null ? known : obf;
    }

    /** Counting is decoration: an error here never gets in the packet's way. */
    private static void countQuietly(Map<Class<?>, long[]> where, Object packet) {
        try {
            count(where, packet);
        } catch (Throwable ignored) {
            // the packet goes through
        }
    }

    private static void count(Map<Class<?>, long[]> where, Object packet) {
        if (packet == null) {
            return;
        }
        where.computeIfAbsent(packet.getClass(), k -> new long[2])[0]++;
    }

    /**
     * Summary of the second, called by the watchdog thread. One line per direction, only when there
     * was movement — when idle, the silence in the log is already the information (and the watchdog
     * writes the freeze).
     */
    static void report() {
        line("received", IN);
        line("sent", OUT);
    }

    /** A packet type and how many of it moved since the last summary. */
    private record Moved(Class<?> type, long count) {}

    private static void line(String what, Map<Class<?>, long[]> counts) {
        List<Moved> moved = new ArrayList<>();
        long now = 0;
        long total = 0;
        for (Map.Entry<Class<?>, long[]> e : counts.entrySet()) {
            long[] c = e.getValue();
            long since = c[0] - c[1];
            c[1] = c[0];
            total += c[0];
            if (since > 0) {
                now += since;
                moved.add(new Moved(e.getKey(), since));
            }
        }
        if (now == 0) {
            return; // idle: the missing line already says it, and the watchdog writes the freeze
        }
        moved.sort(Comparator.comparingLong(Moved::count).reversed());
        StringBuilder b =
                new StringBuilder(what)
                        .append(' ')
                        .append(now)
                        .append(" (")
                        .append(total)
                        .append(" in the session): ");
        for (int i = 0; i < moved.size() && i < MAX_TYPES_PER_LINE; i++) {
            if (i > 0) {
                b.append(", ");
            }
            b.append(name(moved.get(i).type())).append(' ').append(moved.get(i).count());
        }
        if (moved.size() > MAX_TYPES_PER_LINE) {
            b.append(" and ").append(moved.size() - MAX_TYPES_PER_LINE).append(" more type(s)");
        }
        SteinLog.debug(Topic.NET, "net", b.toString());
    }

    /** Channel.writeAndFlush: Netty is not on the compile classpath, so it stays a MethodHandle. */
    private static final class P {
        static final MethodHandle WRITE;

        static {
            try {
                Class<?> o = Object.class;
                Class<?> channel =
                        Class.forName(
                                "io.netty.channel.Channel",
                                false,
                                PacketLog.class.getClassLoader());
                Class<?> future =
                        Class.forName(
                                "io.netty.channel.ChannelFuture",
                                false,
                                PacketLog.class.getClassLoader());
                WRITE =
                        MethodHandles.lookup()
                                .findVirtual(channel, "writeAndFlush", methodType(future, o))
                                .asType(methodType(o, o, o));
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }
    }
}
