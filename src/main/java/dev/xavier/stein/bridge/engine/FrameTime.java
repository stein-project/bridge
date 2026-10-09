/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import java.util.Locale;

/**
 * Frame time for F3: the interval between two buffer swaps (Display.update), kept in a ring. Per
 * frame it costs only a nanoTime and an array write; the average and the worst of the last second
 * are computed only when F3 asks for the line. On the render thread.
 */
public final class FrameTime {

    private static final int RING = 4096;

    /** Duration of each frame (ns) and the instant it ended, to cut the 1 s window. */
    private static final long[] DURATION = new long[RING];

    private static final long[] END = new long[RING];
    private static int head;
    private static long last;

    private FrameTime() {}

    /** In Display.update, once per frame. */
    public static void tick() {
        long now = System.nanoTime();
        if (last != 0L) {
            DURATION[head] = now - last;
            END[head] = now;
            head = (head + 1) & (RING - 1);
        }
        last = now;
    }

    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final double NANOS_PER_MILLI = 1e6;

    /** The F3 line: average and worst frame of the last second. */
    public static String describe() {
        long since = System.nanoTime() - NANOS_PER_SECOND;
        long sum = 0L;
        long max = 0L;
        int n = 0;
        for (int i = 1; i <= RING; i++) {
            int k = (head - i) & (RING - 1);
            if (END[k] == 0L || END[k] < since) {
                break;
            }
            sum += DURATION[k];
            max = Math.max(max, DURATION[k]);
            n++;
        }
        if (n == 0) {
            return "Frame time: -";
        }
        return String.format(
                Locale.ROOT,
                "Frame time: %.2f ms avg, %.2f ms max",
                sum / NANOS_PER_MILLI / n,
                max / NANOS_PER_MILLI);
    }
}
