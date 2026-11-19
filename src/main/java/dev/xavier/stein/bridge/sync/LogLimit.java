/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.sync;

/**
 * One log line at most once per period for one call site, counting the ones left out. For lines a
 * server can trigger at will (a Stein Sync Hello sent again and again, a malformed message): one
 * per event would let a server fill the player's log. No allocation.
 */
final class LogLimit {

    private final long periodMs;
    private long lastMs = Long.MIN_VALUE;
    private int leftOut;

    /** A limit of one line per {@code periodMs} milliseconds. */
    LogLimit(long periodMs) {
        this.periodMs = periodMs;
    }

    /**
     * Counts one event at {@code nowMs}.
     *
     * @return -1 when no line is due; otherwise the number of events left out since the last line
     */
    synchronized int due(long nowMs) {
        if (lastMs != Long.MIN_VALUE && nowMs - lastMs < periodMs) {
            leftOut++;
            return -1;
        }
        lastMs = nowMs;
        int n = leftOut;
        leftOut = 0;
        return n;
    }

    /** {@link #due(long)} now. */
    int due() {
        return due(System.currentTimeMillis());
    }

    /** The suffix of a line that follows left-out ones: empty when none were. */
    static String leftOut(int n) {
        return n > 0 ? " (" + n + " more since the last line)" : "";
    }
}
