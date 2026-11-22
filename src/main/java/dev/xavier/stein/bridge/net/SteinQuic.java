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

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;

/**
 * The Stein Sync QUIC transport (stein-sync's docs/protocol.md section 7) from the native library
 * built from {@code native/stein-quic-ffi} (Rust, quinn), called through Java's foreign function
 * API: no JNI glue, and nothing loaded unless a server offers QUIC.
 *
 * <p>The library carries the game's own connection: {@link #connect} opens a QUIC connection to the
 * server and returns a loopback port, the game connects there with its usual Netty code, and every
 * byte it writes or reads travels on one QUIC stream. Only the Ticks leave the stream: {@link
 * #sendDatagram} sends each as a datagram with the ones before it, so a lost packet no longer holds
 * the player's movement back.
 *
 * <p>The library ships inside the Bridge jar for the system it was built for ({@link
 * SteinQuicLibrary}). When it cannot be loaded (none for this system, a security tool blocks it, a
 * wrong architecture) {@link #available} is false with {@link #unavailableReason} saying why, and
 * the client uses its Java transport instead.
 */
public final class SteinQuic {

    /** The ABI the library must have ({@code stein_quic_abi_version}). */
    private static final int ABI_VERSION = 3;

    /** Results of {@link #connect} below zero. */
    public static final long TIMEOUT = -1;

    public static final long HANDSHAKE_FAILED = -2;
    public static final long LOCAL_ERROR = -3;
    public static final long BAD_ARGUMENT = -4;
    public static final long INTERNAL_ERROR = -5;

    /** The server answered over UDP, but the handshake did not finish within the limit. */
    public static final long SLOW = -6;

    /** {@link #connect} flag: send each Tick datagram twice while the path loses packets. */
    public static final int REPEAT_DATAGRAMS = 1;

    /** The number of values {@link #stats} writes. */
    public static final int STATS = 8;

    /** The longest datagram {@link #sendDatagram} takes. */
    private static final int DATAGRAM_BUFFER = 1536;

    private SteinQuic() {}

    /** The library, bound on the first use (not when this class loads). */
    private static final class Holder {
        static final String[] WHY = new String[1];
        static final Natives NATIVES = Natives.load(WHY);

        /**
         * The native buffer of {@link #sendDatagram}, reused: Ticks are handed over one at a time
         * from the connection's network thread. Guarded by its own lock for the rare second
         * connection.
         */
        static final MemorySegment DATAGRAM =
                NATIVES == null ? null : Arena.global().allocate(DATAGRAM_BUFFER);
    }

    /**
     * Where the library is copied out of the jar (the game's folder); takes effect only before the
     * first use.
     */
    public static void cacheRoot(Path dir) {
        SteinQuicLibrary.cacheRoot(dir);
    }

    /** Whether the native library is loaded (loads it on the first call). */
    public static boolean available() {
        return Holder.NATIVES != null;
    }

    /** Why the library is not available, or null when it is. */
    public static String unavailableReason() {
        return Holder.NATIVES == null ? Holder.WHY[0] : null;
    }

    /**
     * Connects to a Stein Sync proxy over QUIC. Blocks the calling thread (the game's connection
     * thread) for at most {@code ports.length} x {@code limitMs}.
     *
     * @param ip the server's address, 4 or 16 bytes
     * @param ports the UDP ports to try, in order (1 to 4)
     * @param pin the SHA-256 of the server's certificate, from its server list entry (32 bytes)
     * @param answerMs how long each port may take to answer at all (100 to 10000): silence past
     *     this is "no UDP"
     * @param limitMs how long a port that answered may take to finish the handshake (100 to 30000)
     * @param rttHintMs the server list ping's round trip, 0 when unknown (0 to 10000)
     * @param flags {@link #REPEAT_DATAGRAMS} or 0
     * @param localPort receives, in its first element, the loopback port the game must connect to
     * @return the link's handle (above zero), or {@link #TIMEOUT}, {@link #HANDSHAKE_FAILED},
     *     {@link #LOCAL_ERROR}, {@link #BAD_ARGUMENT}, {@link #INTERNAL_ERROR} or {@link #SLOW}
     */
    public static long connect(
            byte[] ip,
            int[] ports,
            byte[] pin,
            int answerMs,
            int limitMs,
            int rttHintMs,
            int flags,
            int[] localPort) {
        Natives n = Holder.NATIVES;
        if (n == null) {
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
                            n.connect.invokeExact(
                                    ipSeg,
                                    ip.length,
                                    portSeg,
                                    ports.length,
                                    pinSeg,
                                    answerMs,
                                    limitMs,
                                    rttHintMs,
                                    flags,
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
     * Sends a datagram the Bridge built (a Tick datagram with its copies, stein-sync's
     * docs/protocol.md section 7.8). No allocation; never blocks.
     *
     * @return false when it was not sent (the caller sends the Tick on the stream instead)
     */
    public static boolean sendDatagram(long handle, byte[] datagram, int len) {
        Natives n = Holder.NATIVES;
        if (n == null || len <= 0 || len > DATAGRAM_BUFFER) {
            return false;
        }
        MemorySegment buf = Holder.DATAGRAM;
        synchronized (buf) {
            MemorySegment.copy(datagram, 0, buf, JAVA_BYTE, 0, len);
            try {
                return (int) n.sendDatagram.invokeExact(handle, buf, len) == 1;
            } catch (Throwable e) {
                return false;
            }
        }
    }

    /**
     * The link's counters into {@code out} (up to {@link #STATS} values): round trip
     * (microseconds), QUIC packets sent, QUIC packets lost, Tick datagrams sent, Tick datagrams
     * refused, 1 if closed, second copies sent on a lossy path, 1 if the join skipped the Retry
     * with a token.
     *
     * @return false for an unknown handle
     */
    public static boolean stats(long handle, long[] out) {
        Natives n = Holder.NATIVES;
        if (n == null) {
            return false;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(JAVA_LONG, STATS);
            if ((int) n.stats.invokeExact(handle, seg) != 1) {
                return false;
            }
            for (int i = 0; i < Math.min(STATS, out.length); i++) {
                out[i] = seg.getAtIndex(JAVA_LONG, i);
            }
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /** Moves the link to a new local UDP port, as an address change would (diagnostics). */
    public static boolean rebind(long handle) {
        Natives n = Holder.NATIVES;
        try {
            return n != null && (int) n.rebind.invokeExact(handle) == 1;
        } catch (Throwable e) {
            return false;
        }
    }

    /** Closes the link (safe to call twice). */
    public static void close(long handle) {
        Natives n = Holder.NATIVES;
        if (n == null) {
            return;
        }
        try {
            n.close.invokeExact(handle);
        } catch (Throwable e) {
            Log.info("net", "QUIC close failed in the native library: " + e);
        }
    }

    /** The library's functions, bound once. */
    private static final class Natives {
        final MethodHandle connect;
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
                                    JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS));
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

        /**
         * The library if it is there, loads and has the right ABI; null otherwise, with the reason
         * in {@code why[0]}. Logged once either way.
         */
        static Natives load(String[] why) {
            SteinQuicLibrary.Found found = SteinQuicLibrary.locate();
            if (found.file == null) {
                why[0] = found.why;
                Log.info("net", "QUIC: no native library (" + found.why + ")");
                return null;
            }
            try {
                SymbolLookup lib = SymbolLookup.libraryLookup(found.file, Arena.global());
                MethodHandle abi =
                        Linker.nativeLinker()
                                .downcallHandle(
                                        find(lib, "stein_quic_abi_version"),
                                        FunctionDescriptor.of(JAVA_INT));
                int version = (int) abi.invokeExact();
                if (version != ABI_VERSION) {
                    why[0] = found.file + " has ABI " + version + ", not " + ABI_VERSION;
                    Log.info("net", "QUIC: " + why[0]);
                    return null;
                }
                Log.info("net", "QUIC: native library " + found.file + " (" + found.why + ")");
                return new Natives(lib);
            } catch (Throwable e) {
                // A library a security tool blocks (Windows Smart App Control, an antivirus) or
                // built for another architecture fails here.
                why[0] = "cannot load " + found.file + ": " + e;
                Log.info("net", "QUIC: " + why[0]);
                return null;
            }
        }
    }
}
