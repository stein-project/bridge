/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

package dev.xavier.stein.bridge.engine;

import dev.xavier.stein.bridge.core.Log;

/**
 * Counters and the periodic summary of a check mode ({@code -Dsteinbridge.opt.check*}): how many
 * comparisons were made, how many diverged, and one line printed every few seconds. Counters only
 * on the hot side; printing happens at most once per interval and on the first five divergences.
 */
final class CheckReport {
    /** Divergences described in the log (the rest are only counted). */
    private static final int DETAILED = 5;

    long checks;
    long divergences;
    private final long intervalNs;
    private long lastReport = System.nanoTime();

    CheckReport(long intervalNs) {
        this.intervalNs = intervalNs;
    }

    /** Counts one comparison; true if it diverged and is among the first ones worth describing. */
    boolean compared(boolean same) {
        checks++;
        return !same && divergences++ < DETAILED;
    }

    /** True once per interval; the caller then prints its own summary line. */
    boolean due() {
        long now = System.nanoTime();
        if (now - lastReport >= intervalNs) {
            lastReport = now;
            return true;
        }
        return false;
    }

    /** The standard summary line, once per interval. */
    void maybeReport(String tag) {
        if (due()) {
            Log.info(tag, "check: %d comparisons, %d divergences", checks, divergences);
        }
    }
}
