/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.bench;

import dev.xavier.stein.bridge.core.CoreOptions;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The totals of one measured part of the route: every frame time (for the percentiles and the 1%
 * low), the sum of each zone and counter, the frames the window was out of focus or minimized, the
 * garbage collections, and the GPU frames that arrived while it was measured. Filled on the render
 * thread, one frame at a time, without allocating except when the frame list grows.
 */
final class BenchStats {

    final String name;
    final int round;

    /** The part's index in the run (its rows' "segment"). */
    final int segment;

    /** What the part is meant to show (the checks read it). */
    final BenchScene.Part part;

    final long startNanos;
    long endNanos;

    /** The dimension the world was in when the part was measured. */
    int dimension;

    /** Whether the chunks had settled when the measurement started. */
    boolean settled;

    /** Why the part was stopped early and failed (the world it films was not there), or null. */
    String aborted;

    /** A biome part's biome (-1: not one), and how often the camera was over it when sampled. */
    int biome = -1;

    int biomeSamples;
    int biomeHits;

    /** OpenGL errors the driver had queued at the end of the part (-1: not read, on Vulkan). */
    int glErrors = -1;

    /**
     * The part's picture, taken right after it (in the game's screenshots folder until the end).
     */
    java.io.File shot;

    private long[] frames = new long[8192];
    int count;

    final long[] zoneSum = new long[BenchZones.COUNT];
    final long[] zoneMax = new long[BenchZones.COUNT];
    final long[] tailSum = new long[BenchFrames.TAIL.length];
    final long[] tailMax = new long[BenchFrames.TAIL.length];
    int unfocused;
    int minimized;
    int resized;
    int lastWidth = -1;
    int lastHeight = -1;

    /** GPU frame span (first pass start to last pass end), ns, as the results arrived. */
    private long[] gpuSpans = new long[8192];

    int gpuCount;
    long gpuBusySum;
    long gpuGapSum;
    final Map<String, long[]> gpuPasses = new LinkedHashMap<>();

    BenchStats(String name, int round, int segment, BenchScene.Part part, long startNanos) {
        this.name = name;
        this.round = round;
        this.segment = segment;
        this.part = part;
        this.startNanos = startNanos;
    }

    /** One measured frame, from its row. */
    void add(long[] v, int at) {
        if (count == frames.length) {
            frames = Arrays.copyOf(frames, count * 2);
        }
        frames[count++] = v[at + BenchFrames.FRAME];
        for (int z = 0; z < BenchZones.COUNT; z++) {
            long x = v[at + BenchFrames.ZONES + z];
            zoneSum[z] += x;
            zoneMax[z] = Math.max(zoneMax[z], x);
        }
        for (int t = 0; t < BenchFrames.TAIL.length; t++) {
            long x = v[at + BenchFrames.TAIL_AT + t];
            tailSum[t] += x;
            tailMax[t] = Math.max(tailMax[t], x);
        }
        int focused = (int) v[at + BenchFrames.TAIL_AT + 17];
        int mini = (int) v[at + BenchFrames.TAIL_AT + 18];
        int w = (int) v[at + BenchFrames.TAIL_AT + 19];
        int h = (int) v[at + BenchFrames.TAIL_AT + 20];
        if (focused == 0) {
            unfocused++;
        }
        if (mini != 0) {
            minimized++;
        }
        if (lastWidth >= 0 && (w != lastWidth || h != lastHeight)) {
            resized++;
        }
        lastWidth = w;
        lastHeight = h;
    }

    /** A GPU frame whose timestamps arrived while this part was measured. */
    void gpuFrame(long span, long busy, long gaps) {
        if (gpuCount == gpuSpans.length) {
            gpuSpans = Arrays.copyOf(gpuSpans, gpuCount * 2);
        }
        gpuSpans[gpuCount++] = span;
        gpuBusySum += busy;
        gpuGapSum += gaps;
    }

    /** The GPU time of one pass label in a GPU frame. */
    void gpuPass(String label, long nanos) {
        long[] s = gpuPasses.get(label);
        if (s == null) {
            s = new long[2];
            gpuPasses.put(label, s);
        }
        s[0] += nanos;
        s[1]++;
    }

    double seconds() {
        return (endNanos - startNanos) / 1e9;
    }

    /**
     * Whether the window stayed as the run asked, the same size the whole time: focused and
     * visible, or, in a run asked to be in the background ({@code -Dsteinbridge.diag.background}),
     * minimized throughout. There a restored window is the odd one out: with it Vulkan starts
     * presenting again, and the numbers change.
     */
    boolean clean() {
        if (resized != 0) {
            return false;
        }
        return CoreOptions.DIAG_BACKGROUND ? minimized >= count : unfocused == 0 && minimized == 0;
    }

    /** The summary of the frame times, in ms (and fps for the lows). */
    Map<String, Object> frameSummary() {
        Map<String, Object> m = new LinkedHashMap<>();
        if (count == 0) {
            return m;
        }
        long[] f = Arrays.copyOf(frames, count);
        Arrays.sort(f);
        double sum = 0;
        for (long x : f) {
            sum += x;
        }
        double mean = sum / count;
        double var = 0;
        for (long x : f) {
            var += (x - mean) * (x - mean);
        }
        double secs = seconds();
        m.put("frames", count);
        m.put("seconds", round3(secs));
        m.put("fps_avg", round1(secs > 0 ? count / secs : 0));
        m.put("ms_avg", ms(mean));
        m.put("ms_p50", ms(f[pct(count, 50)]));
        m.put("ms_p90", ms(f[pct(count, 90)]));
        m.put("ms_p95", ms(f[pct(count, 95)]));
        m.put("ms_p99", ms(f[pct(count, 99)]));
        m.put("ms_p99_9", ms(f[pct(count, 99.9)]));
        m.put("ms_max", ms(f[count - 1]));
        m.put("ms_stdev", ms(Math.sqrt(var / count)));
        m.put("fps_1pct_low", round1(low(f, 100)));
        m.put("fps_0_1pct_low", round1(low(f, 1000)));
        m.put("frames_over_16ms", over(f, 16_666_667L));
        m.put("frames_over_33ms", over(f, 33_333_333L));
        m.put("frames_over_50ms", over(f, 50_000_000L));
        return m;
    }

    /** Each zone: the average per frame and the worst frame, in ms. */
    Map<String, Object> zoneSummary() {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int z = 0; z < BenchZones.COUNT; z++) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("ms_avg", count == 0 ? 0 : ms((double) zoneSum[z] / count));
            e.put("ms_max", ms(zoneMax[z]));
            e.put(
                    "share_pct",
                    round1(count == 0 || sumFrames() == 0 ? 0 : 100.0 * zoneSum[z] / sumFrames()));
            m.put(BenchZones.NAMES[z], e);
        }
        return m;
    }

    /** Each counter: the average per frame and the largest. */
    Map<String, Object> counterSummary() {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int t = 0; t < BenchFrames.TAIL.length - 4; t++) {
            Map<String, Object> e = new LinkedHashMap<>();
            // ring_wait_us and gc_pause_us are kept in ns
            double unit = t == 6 || t == 16 ? 1e3 : 1;
            e.put("avg", round3(count == 0 ? 0 : tailSum[t] / unit / count));
            e.put("max", round3(tailMax[t] / unit));
            m.put(BenchFrames.TAIL[t], e);
        }
        double secs = seconds();
        m.put("alloc_mb_per_s", round1(secs > 0 ? tailSum[14] / 1024.0 / secs : 0));
        m.put("gc_events_total", tailSum[15]);
        m.put("gc_pause_ms_total", round3(tailSum[16] / 1e6));
        return m;
    }

    /** The GPU frame times and each pass (Stein GFX only; empty otherwise). */
    Map<String, Object> gpuSummary() {
        Map<String, Object> m = new LinkedHashMap<>();
        if (gpuCount == 0) {
            return m;
        }
        long[] g = Arrays.copyOf(gpuSpans, gpuCount);
        Arrays.sort(g);
        double sum = 0;
        for (long x : g) {
            sum += x;
        }
        m.put("frames", gpuCount);
        m.put("span_ms_avg", ms(sum / gpuCount));
        m.put("span_ms_p50", ms(g[pct(gpuCount, 50)]));
        m.put("span_ms_p95", ms(g[pct(gpuCount, 95)]));
        m.put("span_ms_p99", ms(g[pct(gpuCount, 99)]));
        m.put("span_ms_max", ms(g[gpuCount - 1]));
        m.put("busy_ms_avg", ms((double) gpuBusySum / gpuCount));
        m.put("idle_between_passes_ms_avg", ms((double) gpuGapSum / gpuCount));
        Map<String, Object> passes = new LinkedHashMap<>();
        for (Map.Entry<String, long[]> e : gpuPasses.entrySet()) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("ms_avg_per_frame", ms((double) e.getValue()[0] / gpuCount));
            p.put("per_frame", round3((double) e.getValue()[1] / gpuCount));
            passes.put(e.getKey(), p);
        }
        m.put("passes", passes);
        return m;
    }

    private long sumFrames() {
        long s = 0;
        for (int i = 0; i < count; i++) {
            s += frames[i];
        }
        return s;
    }

    private static int pct(int n, double p) {
        return Math.min(n - 1, (int) Math.floor(n * p / 100.0));
    }

    /** The average fps of the slowest 1/{@code div} of the frames. */
    private static double low(long[] sorted, int div) {
        int n = Math.max(1, sorted.length / div);
        double s = 0;
        for (int i = sorted.length - n; i < sorted.length; i++) {
            s += sorted[i];
        }
        return s == 0 ? 0 : 1e9 * n / s;
    }

    private static int over(long[] sorted, long limit) {
        int n = 0;
        for (int i = sorted.length - 1; i >= 0 && sorted[i] > limit; i--) {
            n++;
        }
        return n;
    }

    static double ms(double nanos) {
        return Math.round(nanos / 1e3) / 1e3;
    }

    static double round1(double x) {
        return Math.round(x * 10) / 10.0;
    }

    static double round3(double x) {
        return Math.round(x * 1000) / 1000.0;
    }
}
