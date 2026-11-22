/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.sync;

import dev.xavier.stein.bridge.core.Log;
import dev.xavier.stein.bridge.net.SteinQuic;

import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Opens a QUIC link with the best transport this system has (stein-sync's docs/protocol.md section
 * 7): the Bridge's native library (quinn, in Rust: less CPU, no garbage, and its datagrams leave in
 * their own packets) by default, Kwik (pure Java) when the library cannot be used, TCP (the
 * caller's fallback) when neither connects.
 *
 * <p>Kwik is tried only when the native library is missing or broken on this system (not loaded,
 * blocked by a security tool, failed to start), never after a network failure: a server that does
 * not answer over UDP would not answer Kwik either, and the player would wait twice.
 *
 * <p>How long to wait comes from the server list ping ({@link Timing}): a server far away gets more
 * time, and a server that answers over UDP, only slowly, is given the time to finish instead of
 * being taken for a network without UDP.
 */
final class SteinSyncConnect {

    /** Logged once per run: the native library could not be used, Kwik is used instead. */
    private static final AtomicBoolean NATIVE_FALLBACK_LOGGED = new AtomicBoolean();

    /** Set when the native library failed to start a connection: Kwik from then on. */
    private static volatile boolean nativeBroken;

    private SteinSyncConnect() {}

    /** How long a QUIC attempt may take, from the round trip the server list ping measured. */
    static final class Timing {
        /** Without a ping: today's 1.2 s to answer. */
        static final int DEFAULT_ANSWER_MS = 1200;

        /** The shortest wait for an answer (a lost first packet is resent within it). */
        static final int MIN_ANSWER_MS = 1200;

        /** The longest wait for an answer: past this, silence really is "no UDP". */
        static final int MAX_ANSWER_MS = 5000;

        /** The shortest and longest time a server that answered has to finish. */
        static final int MIN_LIMIT_MS = 4000;

        static final int MAX_LIMIT_MS = 12000;

        /**
         * Each port's wait for any answer over UDP: the Retry or the handshake comes back one round
         * trip after the first packet, or four after it if that first packet is lost (quinn resends
         * it after about three round trips).
         */
        final int answerMs;

        /**
         * Each port's whole limit once it answered: the Retry and the handshake are two round
         * trips, and a lossy path needs a few more.
         */
        final int limitMs;

        /**
         * Kwik's limit (it cannot tell silence from slowness): the answer wait and the handshake.
         */
        final int kwikMs;

        /** The round trip in ms for quinn's first estimate, 0 when unknown. */
        final int rttHintMs;

        private Timing(int answerMs, int limitMs, int kwikMs, int rttHintMs) {
            this.answerMs = answerMs;
            this.limitMs = limitMs;
            this.kwikMs = kwikMs;
            this.rttHintMs = rttHintMs;
        }

        /**
         * The timing for a server whose list ping took {@code pingMs} (the game's ServerData
         * pingToServer: one round trip on the status connection; 0 or less when unknown).
         */
        static Timing fromPing(long pingMs) {
            if (pingMs <= 0 || pingMs > 10_000) {
                return new Timing(DEFAULT_ANSWER_MS, 6000, DEFAULT_ANSWER_MS, 0);
            }
            int rtt = (int) pingMs;
            int answer = clamp(500 + 4 * rtt, MIN_ANSWER_MS, MAX_ANSWER_MS);
            int limit = clamp(2000 + 8 * rtt, MIN_LIMIT_MS, MAX_LIMIT_MS);
            int kwik = clamp(answer + 2 * rtt, MIN_ANSWER_MS, 10_000);
            return new Timing(answer, limit, kwik, rtt);
        }

        private static int clamp(int v, int lo, int hi) {
            return Math.max(lo, Math.min(hi, v));
        }
    }

    /**
     * Connects to the proxy at {@code ip} on the first of {@code ports} that completes the
     * handshake with the pinned certificate.
     *
     * @param transport {@code native} or {@code kwik} to force one; anything else picks
     * @param outcome receives, on failure, a {@code SteinSyncCodec.QUIC_*} value
     * @return the link, or null (the join goes over TCP)
     */
    static SteinSyncLink connect(
            InetAddress ip,
            int[] ports,
            byte[] pin,
            Timing timing,
            String transport,
            boolean kwikPresent,
            int[] outcome) {
        boolean onlyKwik = "kwik".equals(transport);
        boolean onlyNative = "native".equals(transport);
        if (!onlyKwik && !(nativeBroken && !onlyNative)) {
            if (SteinQuic.available()) {
                long[] result = new long[1];
                SteinSyncLink link = connectNative(ip, ports, pin, timing, outcome, result);
                if (link != null || onlyNative || outcome[0] != SteinSyncCodec.QUIC_UNSUPPORTED) {
                    return link;
                }
                if (result[0] == SteinQuic.LOCAL_ERROR) {
                    // A local socket failed this time (no IPv6 for an IPv6 address, the adapter
                    // down for a moment): the library is fine, the next join tries it again. This
                    // one goes on with Kwik when present.
                    Log.info(
                            "steinsync",
                            "Stein Sync QUIC: the native library could not open a"
                                    + " local socket for this join");
                } else {
                    // The library loaded but could not start a connection (bad arguments, an
                    // internal error): its own failure, not the network's. Kwik from now on.
                    nativeBroken = true;
                    fellBack("the native QUIC library failed to start a connection");
                }
            } else if (onlyNative) {
                outcome[0] = SteinSyncCodec.QUIC_UNSUPPORTED;
                return null;
            } else {
                fellBack("no native QUIC library: " + SteinQuic.unavailableReason());
            }
        }
        if (!kwikPresent) {
            outcome[0] = SteinSyncCodec.QUIC_UNSUPPORTED;
            return null;
        }
        return SteinSyncKwik.connect(ip, ports, pin, timing.kwikMs, outcome);
    }

    private static void fellBack(String why) {
        if (NATIVE_FALLBACK_LOGGED.compareAndSet(false, true)) {
            Log.info("steinsync", "Stein Sync QUIC: " + why + "; using Kwik (Java) instead");
        }
    }

    /**
     * The native library's connection; null with {@code outcome} on failure, {@code
     * QUIC_UNSUPPORTED} meaning a local failure, and the library's own result in {@code result}
     * ({@link SteinQuic#LOCAL_ERROR} for a local socket, the others for the library itself).
     */
    private static SteinSyncLink connectNative(
            InetAddress ip, int[] ports, byte[] pin, Timing timing, int[] outcome, long[] result) {
        int[] localPort = new int[1];
        long handle =
                SteinQuic.connect(
                        ip.getAddress(),
                        ports,
                        pin,
                        timing.answerMs,
                        timing.limitMs,
                        timing.rttHintMs,
                        SteinSyncOptions.QUIC_REPEAT ? SteinQuic.REPEAT_DATAGRAMS : 0,
                        localPort);
        if (handle > 0) {
            return new SteinSyncNativeLink(handle, localPort[0]);
        }
        result[0] = handle;
        outcome[0] = outcomeOf(handle);
        return null;
    }

    /** The Transport outcome of a failed native connect. */
    static int outcomeOf(long handle) {
        if (handle == SteinQuic.TIMEOUT) {
            return SteinSyncCodec.QUIC_TIMEOUT;
        }
        if (handle == SteinQuic.SLOW) {
            return SteinSyncCodec.QUIC_SLOW;
        }
        if (handle == SteinQuic.HANDSHAKE_FAILED) {
            return SteinSyncCodec.QUIC_HANDSHAKE_FAILED;
        }
        return SteinSyncCodec.QUIC_UNSUPPORTED;
    }
}
