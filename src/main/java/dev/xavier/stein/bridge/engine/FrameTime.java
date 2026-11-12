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

    /**
     * The frames go to a flight recording too, when the running Java has the recorder (a full JDK;
     * not the Stein Client's runtime, where FrameRecording must never load).
     */
    private static final boolean RECORDING = ModuleLayer.boot().findModule("jdk.jfr").isPresent();

    /** In Display.update, once per frame. */
    public static void tick() {
        boolean ticked = tickInFrame;
        tickInFrame = false;
        if (RECORDING) {
            FrameRecording.frame(ticked);
        }
        long now = System.nanoTime();
        if (last != 0L) {
            DURATION[head] = now - last;
            END[head] = now;
            head = (head + 1) & (RING - 1);
            if (STATS) {
                stats(now, now - last, ticked);
            }
        }
        last = now;
    }

    /** A game tick ran in the frame being drawn (GameHooks.tickEnd), for the statistics. */
    private static boolean tickInFrame;

    /** After each game tick: the frame that ran it is marked in the statistics. */
    public static void ticked() {
        tickInFrame = true;
    }

    private static void stats(long now, long frame, boolean ticked) {
        if (windowStart == 0L) {
            windowStart = now;
        }
        if (windowCount < window.length) {
            // the lowest bit says whether the frame ran a game tick (sorting by duration keeps it)
            window[windowCount++] = frame << 1 | (ticked ? 1L : 0L);
        }
        if (now - windowStart < STATS_NANOS) {
            return;
        }
        long[] f = window;
        int n = windowCount;
        Arrays.sort(f, 0, n);
        long sum = 0L;
        long tickSum = 0L;
        int ticks = 0;
        for (int i = 0; i < n; i++) {
            sum += f[i] >> 1;
            if ((f[i] & 1L) != 0) {
                tickSum += f[i] >> 1;
                ticks++;
            }
        }
        int slow = Math.max(1, n / 100);
        long slowSum = 0L;
        int slowTicks = 0;
        for (int i = n - slow; i < n; i++) {
            slowSum += f[i] >> 1;
            slowTicks += (int) (f[i] & 1L);
        }
        long otherSum = sum - tickSum;
        int others = n - ticks;
        Log.info(
                "frames",
                "%d frames in %.1f s: fps %.0f | avg %.3f ms, p50 %.3f, p99 %.3f, max %.3f | 1%% low"
                        + " %.0f fps | with a tick %d frames avg %.3f ms, without %.3f ms; %d of the"
                        + " slowest %d had a tick",
                n,
                (now - windowStart) / 1e9,
                n * 1e9 / (now - windowStart),
                (sum / NANOS_PER_MILLI) / n,
                (f[n / 2] >> 1) / NANOS_PER_MILLI,
                (f[Math.min(n - 1, n * 99 / 100)] >> 1) / NANOS_PER_MILLI,
                (f[n - 1] >> 1) / NANOS_PER_MILLI,
                1e9 * slow / slowSum,
                ticks,
                ticks > 0 ? tickSum / NANOS_PER_MILLI / ticks : 0.0,
                others > 0 ? otherSum / NANOS_PER_MILLI / others : 0.0,
                slowTicks,
                slow);
        windowCount = 0;
        windowStart = now;
    }

    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final double NANOS_PER_MILLI = 1e6;

    /** The frames of {@link #recent}, sorted there (no allocation per call). */
    private static final long[] RECENT = new long[RING];

    /**
     * The last {@code seconds}: frames per second into {@code out[0]} and the 1% low (the frame
     * rate of the slowest 1% of the frames) into {@code out[1]}; 0 for both without frames. For the
     * video settings, a few times per second.
     */
    public static void recent(double seconds, double[] out) {
        long now = System.nanoTime();
        long since = now - (long) (seconds * NANOS_PER_SECOND);
        long sum = 0L;
        int n = 0;
        for (int i = 1; i <= RING; i++) {
            int k = (head - i) & (RING - 1);
            if (END[k] == 0L || END[k] < since) {
                break;
            }
            RECENT[n++] = DURATION[k];
            sum += DURATION[k];
        }
        if (n == 0 || sum == 0L) {
            out[0] = 0.0;
            out[1] = 0.0;
            return;
        }
        Arrays.sort(RECENT, 0, n);
        int slow = Math.max(1, n / 100);
        long slowSum = 0L;
        for (int i = n - slow; i < n; i++) {
            slowSum += RECENT[i];
        }
        out[0] = n * (double) NANOS_PER_SECOND / sum;
        out[1] = slow * (double) NANOS_PER_SECOND / slowSum;
    }

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
