/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.net;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

import dev.xavier.stein.bridge.core.Log;

import java.io.File;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * The Stein Sync QUIC transport (stein-sync's docs/protocol.md section 7) from the native library
 * built from {@code native/stein-quic-ffi} (Rust, quinn), called through Java's foreign function
 * API: no JNI glue, and nothing loaded unless a server offers QUIC.
 *
 * <p>The library carries the game's own connection: {@link #connect} opens a QUIC connection to the
 * server and returns a loopback port, the game connects there with its usual Netty code, and every
 * byte it writes or reads travels on one QUIC stream. Only the Ticks leave the stream: {@link
 * #sendTick} sends each as a datagram with the ones before it, so a lost packet no longer holds the
 * player's movement back.
 *
 * <p>Where the library is: {@code -Dsteinbridge.net.quicLib=<file>}, or else the first folder of
 * {@code java.library.path} (the game's natives) that has it. Without it, {@link #available} is
 * false and every connection stays TCP, as before.
 */
public final class SteinQuic {

    /** The ABI the library must have ({@code stein_quic_abi_version}). */
    private static final int ABI_VERSION = 2;

    /** Results of {@link #connect} below zero. */
    public static final long TIMEOUT = -1;

    public static final long HANDSHAKE_FAILED = -2;
    public static final long LOCAL_ERROR = -3;
    public static final long BAD_ARGUMENT = -4;
    public static final long INTERNAL_ERROR = -5;

    /** The longest Tick the reused native buffer takes (a datagram up to three times that). */
    private static final int TICK_BUFFER = 512;

    private static final Natives NATIVES = Natives.load();

    /**
     * The native buffer of {@link #sendTick}, reused: Ticks are handed over one at a time from the
     * connection's network thread. Guarded by its own lock for the rare second connection.
     */
    private static final MemorySegment TICK =
            NATIVES == null ? null : Arena.global().allocate(TICK_BUFFER * 3);

    private SteinQuic() {}

    /** Whether the native library is loaded (QUIC can be tried at all). */
    public static boolean available() {
        return NATIVES != null;
    }

    /**
     * Connects to a Stein Sync proxy over QUIC. Blocks the calling thread (the game's connection
     * thread) for at most {@code ports.length} x {@code attemptMs}.
     *
     * @param ip the server's address, 4 or 16 bytes
     * @param ports the UDP ports to try, in order (1 to 4)
     * @param pin the SHA-256 of the server's certificate, from its server list entry (32 bytes)
     * @param attemptMs how long each port may take (100 to 10000)
     * @param localPort receives, in its first element, the loopback port the game must connect to
     * @return the link's handle (above zero), or {@link #TIMEOUT}, {@link #HANDSHAKE_FAILED},
     *     {@link #LOCAL_ERROR}, {@link #BAD_ARGUMENT} or {@link #INTERNAL_ERROR}
     */
    public static long connect(byte[] ip, int[] ports, byte[] pin, int attemptMs, int[] localPort) {
        if (NATIVES == null) {
            return INTERNAL_ERROR;
        }
        if (pin.length != 32 || ports.length == 0 || ports.length > 4) {
            return BAD_ARGUMENT;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment ipSeg = arena.allocate(ip.length);
            MemorySegment.copy(ip, 0, ipSeg, JAVA_BYTE, 0, ip.length);
            MemorySegment portSeg = arena.allocate(JAVA_SHORT, ports.length);
            for (int i = 0; i < ports.length; i++) {
                portSeg.setAtIndex(JAVA_SHORT, i, (short) ports[i]);
            }
            MemorySegment pinSeg = arena.allocate(32);
            MemorySegment.copy(pin, 0, pinSeg, JAVA_BYTE, 0, 32);
            MemorySegment out = arena.allocate(JAVA_INT);
            long handle =
                    (long)
                            NATIVES.connect.invokeExact(
                                    ipSeg,
                                    ip.length,
                                    portSeg,
                                    ports.length,
                                    pinSeg,
                                    attemptMs,
                                    out);
            if (handle > 0) {
                localPort[0] = out.get(JAVA_INT, 0);
            }
            return handle;
        } catch (Throwable e) {
            Log.info("net", "QUIC connect failed in the native library: " + e);
            return INTERNAL_ERROR;
        }
    }

    /**
     * Sends a Tick (the {@code stein:sync} message, {@code len} bytes of {@code tick}) as a
     * datagram with the Ticks before it. No allocation; never blocks.
     *
     * @param after the packets the game wrote on the stream after its Accept, before this Tick
     * @return false when it was not sent (the caller sends it on the stream instead)
     */
    public static boolean sendTick(long handle, long after, byte[] tick, int len) {
        if (NATIVES == null || len <= 0 || len > TICK_BUFFER) {
            return false;
        }
        synchronized (TICK) {
            MemorySegment.copy(tick, 0, TICK, JAVA_BYTE, 0, len);
            try {
                return (int) NATIVES.sendTick.invokeExact(handle, after, TICK, len) == 1;
            } catch (Throwable e) {
                return false;
            }
        }
    }

    /**
     * Sends a datagram the Loader built (a Tick datagram with its copies, stein-sync's
     * docs/protocol.md section 7.8). No allocation; never blocks.
     *
     * @return false when it was not sent (the caller sends the Tick on the stream instead)
     */
    public static boolean sendDatagram(long handle, byte[] datagram, int len) {
        if (NATIVES == null || len <= 0 || len > TICK_BUFFER * 3) {
            return false;
        }
        synchronized (TICK) {
            MemorySegment.copy(datagram, 0, TICK, JAVA_BYTE, 0, len);
            try {
                return (int) NATIVES.sendDatagram.invokeExact(handle, TICK, len) == 1;
            } catch (Throwable e) {
                return false;
            }
        }
    }

    /**
     * The link's counters into {@code out} (6 values): round trip (microseconds), QUIC packets
     * sent, QUIC packets lost, Tick datagrams sent, Tick datagrams refused, 1 if closed.
     *
     * @return false for an unknown handle
     */
    public static boolean stats(long handle, long[] out) {
        if (NATIVES == null || out.length < 6) {
            return false;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(JAVA_LONG, 6);
            if ((int) NATIVES.stats.invokeExact(handle, seg) != 1) {
                return false;
            }
            for (int i = 0; i < 6; i++) {
                out[i] = seg.getAtIndex(JAVA_LONG, i);
            }
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /** Moves the link to a new local UDP port, as an address change would (diagnostics). */
    public static boolean rebind(long handle) {
        try {
            return NATIVES != null && (int) NATIVES.rebind.invokeExact(handle) == 1;
        } catch (Throwable e) {
            return false;
        }
    }

    /** Closes the link (safe to call twice). */
    public static void close(long handle) {
        if (NATIVES == null) {
            return;
        }
        try {
            NATIVES.close.invokeExact(handle);
        } catch (Throwable e) {
            Log.info("net", "QUIC close failed in the native library: " + e);
        }
    }

    /** The library's functions, bound once. */
    private static final class Natives {
        final MethodHandle connect;
        final MethodHandle sendTick;
        final MethodHandle sendDatagram;
        final MethodHandle stats;
        final MethodHandle rebind;
        final MethodHandle close;

        private Natives(SymbolLookup lib) {
            Linker linker = Linker.nativeLinker();
            connect =
                    linker.downcallHandle(
                            find(lib, "stein_quic_connect"),
                            FunctionDescriptor.of(
                                    JAVA_LONG, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS,
                                    JAVA_INT, ADDRESS));
            sendTick =
                    linker.downcallHandle(
                            find(lib, "stein_quic_send_tick"),
                            FunctionDescriptor.of(
                                    JAVA_INT, JAVA_LONG, JAVA_LONG, ADDRESS, JAVA_INT));
            sendDatagram =
                    linker.downcallHandle(
                            find(lib, "stein_quic_send_datagram"),
                            FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, JAVA_INT));
            stats =
                    linker.downcallHandle(
                            find(lib, "stein_quic_stats"),
                            FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS));
            rebind =
                    linker.downcallHandle(
                            find(lib, "stein_quic_rebind"),
                            FunctionDescriptor.of(JAVA_INT, JAVA_LONG));
            close =
                    linker.downcallHandle(
                            find(lib, "stein_quic_close"), FunctionDescriptor.ofVoid(JAVA_LONG));
        }

        private static MemorySegment find(SymbolLookup lib, String name) {
            return lib.find(name).orElseThrow(() -> new IllegalStateException("no " + name));
        }

        /** The library if it is there and has the right ABI; null otherwise (logged once). */
        static Natives load() {
            Path file = locate();
            if (file == null) {
                Log.info(
                        "net",
                        "QUIC: no native library (" + fileName() + "); connections stay TCP");
                return null;
            }
            try {
                SymbolLookup lib = SymbolLookup.libraryLookup(file, Arena.global());
                MethodHandle abi =
                        Linker.nativeLinker()
                                .downcallHandle(
                                        find(lib, "stein_quic_abi_version"),
                                        FunctionDescriptor.of(JAVA_INT));
                int version = (int) abi.invokeExact();
                if (version != ABI_VERSION) {
                    Log.info(
                            "net",
                            "QUIC: " + file + " has ABI " + version + ", not " + ABI_VERSION);
                    return null;
                }
                Log.info("net", "QUIC: native library " + file);
                return new Natives(lib);
            } catch (Throwable e) {
                Log.info("net", "QUIC: cannot load " + file + ": " + e);
                return null;
            }
        }

        private static String fileName() {
            String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
            if (os.contains("win")) {
                return "stein_quic.dll";
            }
            return os.contains("mac") ? "libstein_quic.dylib" : "libstein_quic.so";
        }

        private static Path locate() {
            String explicit = System.getProperty("steinbridge.net.quicLib");
            if (explicit != null && !explicit.isEmpty()) {
                Path p = Paths.get(explicit);
                return Files.isRegularFile(p) ? p : null;
            }
            String paths = System.getProperty("java.library.path", "");
            for (String dir : paths.split(File.pathSeparator)) {
                if (dir.isEmpty()) {
                    continue;
                }
                Path p = Paths.get(dir, fileName());
                if (Files.isRegularFile(p)) {
                    return p;
                }
            }
            return null;
        }
    }
}
