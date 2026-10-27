/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import dev.xavier.stein.bridge.bench.Bench;
import dev.xavier.stein.bridge.core.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * GPU time per pass ({@code -Dsteinbridge.gfx.gpuPassTiming=true}, diagnostic), the part both
 * backends share: the labels, the ring of timestamp slots read frames later, the totals and the
 * report. Each backend records a timestamp when a pass opens and another when it closes (OpenGL's
 * glQueryCounter, Vulkan's vkCmdWriteTimestamp) and answers, through {@link Timestamps}, whether a
 * slot has arrived and its two times in nanoseconds; the same report then comes out for both APIs.
 *
 * <p>The results are read from the ring frames later: only the oldest frame, and only if its last
 * timestamp has already arrived (the GPU finishes commands in order). It never stalls the CPU
 * waiting; with the ring full the measurement is discarded (and counted). Every 5 s: the per-frame
 * average of each label (us), how many passes per frame and the total.
 *
 * <p>Labels: the pass name. The recording flushes ({@code SteinEntityDeferred}) already come with
 * the name of the pass they are in (entities, sky, clouds, particles, hand, hud, screen, loose) and
 * are summed by name; the other ones repeated in the same frame get a number in order: "terrain",
 * "terrain#2"... (one per layer). An impossible result (zero, end before start, more than 1 s)
 * stays out of the average and is counted.
 *
 * <p>Pipeline: Stein GFX only.
 */
public final class SteinGpuPassTiming {

    /** What a backend answers about its timestamps, by ring slot. */
    public interface Timestamps {
        /** The slot's end timestamp has arrived (all the earlier ones too: in-order GPU). */
        boolean arrived(int slot);

        /** The slot's start, in nanoseconds on the GPU clock. */
        long begin(int slot);

        /** The slot's end, in nanoseconds on the GPU clock. */
        long end(int slot);
    }

    public static final boolean ON = SteinGfxOptions.GPU_PASS_TIMING;

    /** A pass longer than this (1 s) is an impossible result: it is left out of the average. */
    private static final long MAX_PLAUSIBLE_PASS_NANOS = 1_000_000_000L;

    /**
     * The names of the recording flushes (SteinEntityDeferred): several per frame, summed into a
     * single label (with how many per frame), without numbering.
     */
    private static final Set<String> AGGREGATED =
            Set.of(
                    "entities",
                    "particles",
                    "sky",
                    "clouds",
                    "rain/border/outline/crack",
                    "hand",
                    "hud",
                    "screen",
                    "loose");

    private final SteinTimestampRing ring;
    private long frame;
    private long invalid;

    /** Occurrences of each name in the current frame (to number the repeated ones). */
    private final Map<String, int[]> occurrences = new HashMap<>();

    private final Map<String, List<String>> numbered = new HashMap<>();
    private long windowFrames;
    private long spanSum;
    private long gapSum;
    private long spanFrames;
    private long reportAt = System.nanoTime();

    public SteinGpuPassTiming(int slots) {
        ring = new SteinTimestampRing(slots, 16);
    }

    /** On pass open: the slot for its two timestamps (-1: ring full, no measurement). */
    public int claim(String name) {
        int slot = ring.claim(frame);
        if (slot >= 0) {
            ring.setLabel(slot, ring.id(label(name)));
        }
        return slot;
    }

    private String label(String name) {
        if (AGGREGATED.contains(name)) {
            return name;
        }
        int[] n = occurrences.computeIfAbsent(name, k -> new int[1]);
        int k = n[0]++;
        if (k == 0) {
            return name;
        }
        List<String> l = numbered.computeIfAbsent(name, x -> new ArrayList<>());
        while (l.size() < k) {
            l.add(name + "#" + (l.size() + 2));
        }
        return l.get(k - 1);
    }

    /**
     * At the end of each frame: reads what has already arrived, closes the frame and reports every
     * 5 s. {@code api} names the backend in the report.
     */
    public void frame(Timestamps t, String api) {
        collect(t);
        frame++;
        windowFrames++;
        occurrences.clear();
        report(api);
    }

    /** The oldest complete frames whose last timestamp has already arrived. */
    private void collect(Timestamps t) {
        SteinTimestampRing r = ring;
        while (r.any()) {
            int n = r.oldestFrameSize(frame);
            if (n == 0) {
                return; // the current frame is still open
            }
            if (!t.arrived(r.slotAt(n - 1))) {
                return;
            }
            long first = 0;
            long prevEnd = 0;
            long gaps = 0;
            long busy = 0;
            boolean whole = true;
            for (int i = 0; i < n; i++) {
                int s = r.slotAt(i);
                long t0 = t.begin(s);
                long t1 = t.end(s);
                int id = r.label(s);
                if (t0 <= 0 || t1 < t0 || t1 - t0 > MAX_PLAUSIBLE_PASS_NANOS) {
                    invalid++; // impossible result: out of the average (counted in the report)
                    whole = false;
                    continue;
                }
                r.sum[id] += t1 - t0;
                r.hits[id]++;
                busy += t1 - t0;
                if (Bench.ON) {
                    Bench.gpuPass(r.names.get(id), t1 - t0);
                }
                // the frame's span on the GPU (from the start of the first pass to the end of the
                // last) and the gaps between passes: GPU idle waiting for commands, or work outside
                // the passes (target switch, submission)
                if (first == 0) {
                    first = t0;
                } else if (t0 > prevEnd) {
                    gaps += t0 - prevEnd;
                    r.gap[id] += t0 - prevEnd;
                }
                prevEnd = Math.max(prevEnd, t1);
            }
            if (whole && first != 0) {
                spanSum += prevEnd - first;
                gapSum += gaps;
                spanFrames++;
                if (Bench.ON) {
                    Bench.gpuFrame(prevEnd - first, busy, gaps);
                }
            }
            r.release(n);
        }
    }

    private void report(String api) {
        long now = System.nanoTime();
        if (now - reportAt < 5_000_000_000L || windowFrames == 0) {
            return;
        }
        StringBuilder b =
                new StringBuilder("GPU per pass (")
                        .append(api)
                        .append("), us per frame (")
                        .append(windowFrames)
                        .append(" frames):");
        SteinTimestampRing r = ring;
        long[] sum = r.sum;
        long[] hits = r.hits;
        long[] gapBy = r.gap;
        long total = 0;
        long passes = 0;
        for (int i = 0; i < r.names.size(); i++) {
            if (hits[i] == 0) {
                continue;
            }
            b.append(String.format(" %s %.1f", r.names.get(i), sum[i] / 1e3 / windowFrames));
            if (gapBy[i] > 0) {
                b.append(String.format(" [before %.1f]", gapBy[i] / 1e3 / windowFrames));
                gapBy[i] = 0;
            }
            if (hits[i] != windowFrames) {
                b.append(String.format(" (%.1fx)", (double) hits[i] / windowFrames));
            }
            b.append(" |");
            total += sum[i];
            passes += hits[i];
            sum[i] = 0;
            hits[i] = 0;
        }
        b.append(
                String.format(
                        " total %.1f us in %.1f passes",
                        total / 1e3 / windowFrames, (double) passes / windowFrames));
        if (spanFrames > 0) {
            b.append(
                    String.format(
                            " | frame span %.1f us, between passes %.1f us",
                            spanSum / 1e3 / spanFrames, gapSum / 1e3 / spanFrames));
            spanSum = 0;
            gapSum = 0;
            spanFrames = 0;
        }
        long dropped = r.takeDropped();
        if (dropped > 0) {
            b.append(" (").append(dropped).append(" dropped: ring full)");
        }
        if (invalid > 0) {
            b.append(" (")
                    .append(invalid)
                    .append(" invalid: impossible result, left out of the average)");
            invalid = 0;
        }
        Log.info("gfx", b.toString());
        windowFrames = 0;
        reportAt = now;
    }
}
