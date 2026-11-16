/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

/**
 * Fix for the log flood of failing game tasks: Util.runTask's "Error executing task" prints the
 * full stack trace once per kind of error and then one line per interval with how many repeated.
 *
 * <p>Every packet the network thread hands to the game thread runs as a task through Util.runTask
 * (the server's tasks too), and an exception in a packet handler is logged at FATAL level with the
 * whole trace. A server that sends a packet the client cannot handle many times per second (a
 * protocol translator's mismatch, a plugin's bad entity metadata) wrote tens of trace lines per
 * packet, which slowed the game down with the console open, filled latest.log in minutes and buried
 * every other line. Here the first error of each kind (exception class and the line that threw it)
 * goes to the game's logger exactly as before, trace included; repeats of that kind are counted and
 * summed up in one line at most every {@value #EVERY_SECONDS} seconds. The task's result is the
 * same (null, as the game returns); only the log is shorter.
 */
public final class TaskErrors {

    static final int EVERY_SECONDS = 10;
    private static final long EVERY_NANOS = EVERY_SECONDS * 1_000_000_000L;

    /** At most this many kinds are remembered: past it, the map starts over. */
    private static final int MAX_KINDS = 256;

    /** Per kind: [0] repeats not yet reported, [1] when the last line was printed (nanoTime). */
    private static final Map<String, long[]> KINDS = new ConcurrentHashMap<>();

    private TaskErrors() {}

    /** Logger.fatal(message, error) in Util.runTask, for the game and the server threads. */
    public static void failed(Object logger, Object message, Object error) {
        Logger log = (Logger) logger;
        Throwable t = (Throwable) error;
        Throwable cause =
                t instanceof ExecutionException && t.getCause() != null ? t.getCause() : t;
        String kind = kind(cause);
        long now = System.nanoTime();
        long[] seen = KINDS.get(kind);
        if (seen == null) {
            if (KINDS.size() >= MAX_KINDS) {
                KINDS.clear();
            }
            KINDS.put(kind, new long[] {0L, now});
            log.fatal((String) message, t);
            return;
        }
        synchronized (seen) {
            seen[0]++;
            if (now - seen[1] < EVERY_NANOS) {
                return;
            }
            log.fatal(
                    message
                            + ": "
                            + cause
                            + " ("
                            + seen[0]
                            + " times in the last "
                            + (now - seen[1]) / 1_000_000_000L
                            + " s, trace above)");
            seen[0] = 0L;
            seen[1] = now;
        }
    }

    /** The exception's class and the first line of its trace. */
    private static String kind(Throwable cause) {
        StackTraceElement[] stack = cause.getStackTrace();
        return stack.length == 0
                ? cause.getClass().getName()
                : cause.getClass().getName() + " at " + stack[0];
    }
}
