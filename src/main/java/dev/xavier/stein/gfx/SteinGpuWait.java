/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import dev.xavier.stein.bridge.core.Log;

import java.util.function.LongPredicate;

/**
 * The CPU waiting for the GPU, made visible: every wait that can block goes in slices of {@link
 * #SLICE_NANOS}, and a long one leaves a line in the log with what it was waiting for.
 *
 * <p>Before, the ring buffers waited one second and went on as if the GPU were done (writing over
 * what it was still reading), and the swapchain waited forever; a player whose game stopped for
 * seconds in a crowded fight left no trace of it in the log.
 */
public final class SteinGpuWait {

    /** Each slice of a wait: also the step at which a stuck wait is reported. */
    public static final long SLICE_NANOS = 1_000_000_000L;

    /** A wait shorter than this is ordinary and is only counted. */
    private static final long SLOW_NANOS = 250_000_000L;

    /** Lines about slow waits before only every power of two is reported. */
    private static final int FREE_LINES = 20;

    private static int slow;
    private static long slowNanos;
    private static long longest;

    private SteinGpuWait() {}

    /**
     * Waits in slices until {@code slice} returns true (each call waits at most the nanoseconds it
     * receives), reporting every slice that runs out. {@code giveUpNanos} &gt; 0: after that long,
     * returns false (the caller decides what is safe); 0: waits as long as it takes. The caller
     * reports the total with {@link #done}.
     */
    public static boolean await(String what, long giveUpNanos, LongPredicate slice) {
        if (slice.test(SLICE_NANOS)) {
            return true;
        }
        long t0 = System.nanoTime() - SLICE_NANOS;
        while (true) {
            long waited = System.nanoTime() - t0;
            if (giveUpNanos > 0 && waited >= giveUpNanos) {
                Log.warn(
                        "gfx",
                        "gave up waiting for the GPU ("
                                + what
                                + ") after "
                                + waited / 1_000_000L
                                + " ms");
                return false;
            }
            Log.warn(
                    "gfx",
                    "still waiting for the GPU ("
                            + what
                            + "): "
                            + waited / 1_000_000L
                            + " ms so far");
            if (slice.test(SLICE_NANOS)) {
                return true;
            }
        }
    }

    /** A wait that ended after {@code nanos}: a slow one goes to the log (rate-limited). */
    public static void done(String what, long nanos) {
        if (nanos < SLOW_NANOS) {
            return;
        }
        slow++;
        slowNanos += nanos;
        longest = Math.max(longest, nanos);
        if (slow <= FREE_LINES || Integer.bitCount(slow) == 1) {
            Log.warn(
                    "gfx",
                    "slow GPU wait ("
                            + what
                            + "): "
                            + nanos / 1_000_000L
                            + " ms | "
                            + slow
                            + " slow wait(s) in the session, "
                            + slowNanos / 1_000_000L
                            + " ms in all, longest "
                            + longest / 1_000_000L
                            + " ms");
        }
    }
}
