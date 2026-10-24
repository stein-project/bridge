/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import dev.xavier.stein.bridge.core.CoreOptions;
import dev.xavier.stein.bridge.core.Log;

import java.util.Arrays;
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

    private static final boolean STATS = CoreOptions.FRAME_STATS;
    private static final long STATS_NANOS = 5_000_000_000L;

    /** The bench's frame statistics ({@link CoreOptions#FRAME_STATS}): this window's frames. */
    private static final long[] window = STATS ? new long[1 << 16] : null;

    private static int windowCount;
    private static long windowStart;

    /** In Display.update, once per frame. */
    public static void tick() {
        long now = System.nanoTime();
        if (last != 0L) {
            DURATION[head] = now - last;
            END[head] = now;
            head = (head + 1) & (RING - 1);
            if (STATS) {
                stats(now, now - last);
            }
        }
        last = now;
    }

    private static void stats(long now, long frame) {
        if (windowStart == 0L) {
            windowStart = now;
        }
        if (windowCount < window.length) {
            window[windowCount++] = frame;
        }
        if (now - windowStart < STATS_NANOS) {
            return;
        }
        long[] f = window;
        int n = windowCount;
        Arrays.sort(f, 0, n);
        long sum = 0L;
        for (int i = 0; i < n; i++) {
            sum += f[i];
        }
        int slow = Math.max(1, n / 100);
        long slowSum = 0L;
        for (int i = n - slow; i < n; i++) {
            slowSum += f[i];
        }
        Log.info(
                "frames",
                "%d frames in %.1f s: fps %.0f | avg %.3f ms, p50 %.3f, p99 %.3f, max %.3f | 1%% low"
                        + " %.0f fps",
                n,
                (now - windowStart) / 1e9,
                n * 1e9 / (now - windowStart),
                sum / NANOS_PER_MILLI / n,
                f[n / 2] / NANOS_PER_MILLI,
                f[Math.min(n - 1, n * 99 / 100)] / NANOS_PER_MILLI,
                f[n - 1] / NANOS_PER_MILLI,
                1e9 * slow / slowSum);
        windowCount = 0;
        windowStart = now;
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
