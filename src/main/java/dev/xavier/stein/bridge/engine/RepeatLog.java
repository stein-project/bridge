/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import dev.xavier.stein.bridge.core.Log;

/**
 * A log line that collapses its repeats: the first one is printed, then at most one line per
 * interval with how many came in between.
 *
 * <p>The vanilla bug fixes that drop something a server sent (a packet after the disconnect, an
 * unknown potion or item) say so in the log, but a server can repeat the same thing hundreds of
 * times per second, and one line each would bury the rest of the log. Only cold paths (the dropped
 * cases) call {@link #note}; it may be called from the network and the game threads.
 */
final class RepeatLog {

    private final String tag;
    private final long everyNanos;

    private boolean printed;
    private long lastPrint;
    private int held;

    /**
     * @param tag the {@link Log} tag
     * @param everySeconds at most one line per this many seconds after the first
     */
    RepeatLog(String tag, int everySeconds) {
        this.tag = tag;
        this.everyNanos = everySeconds * 1_000_000_000L;
    }

    /**
     * Reports one occurrence: prints {@code message} the first time and then once per interval
     * (with the count of the ones held back); otherwise only counts.
     */
    synchronized void note(String message) {
        long now = System.nanoTime();
        if (printed && now - lastPrint < everyNanos) {
            held++;
            return;
        }
        if (held > 0) {
            Log.info(tag, message + " (and " + held + " more like it since the last line)");
        } else {
            Log.info(tag, message);
        }
        printed = true;
        lastPrint = now;
        held = 0;
    }
}
