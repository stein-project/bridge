/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx.gl;

import dev.xavier.stein.bridge.core.Log;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * GPU time per pass ({@code -Dsteinbridge.gfx.gpuPassTiming=true}, diagnostic): an OpenGL timestamp
 * ({@code glQueryCounter}, GL 3.3) when each pass opens and another when it closes, with its label.
 * A timestamp and not GL_TIME_ELAPSED: an external frame profiler may already hold a time query
 * open around the whole frame, and those do not nest.
 *
 * <p>The results are read from a ring, frames later: only the oldest frame, and only if its last
 * timestamp has already arrived ({@code GL_QUERY_RESULT_AVAILABLE}; the GPU finishes commands in
 * order). It never stalls the CPU waiting; with the ring full the measurement is discarded (and
 * counted). Every 5 s: the per-frame average of each label (us), how many passes per frame and the
 * total.
 *
 * <p>Labels: the pass name. The recording flushes ({@code SteinEntityDeferred}) already come with
 * the name of the pass they are in (entities, sky, clouds, particles, hand, hud, screen, loose) and
 * are summed by name; the other ones repeated in the same frame get a number in order: "terrain",
 * "terrain#2"... (one per layer). An impossible result (zero, end before start, more than 1 s)
 * stays out of the average and is counted.
 *
 * <p>Pipeline: Stein GFX only.
 */
public final class SteinGlGpuTiming {

    static final boolean ON = dev.xavier.stein.gfx.SteinGfxOptions.GPU_PASS_TIMING;

    /**
     * Ring slots ({@code -Dsteinbridge.gfx.gpuPassTiming.slots}: smaller, to test the ring
     * wrap-around).
     */
    /** A pass longer than this (1 s) is an impossible result: it is left out of the average. */
    private static final long MAX_PLAUSIBLE_PASS_NANOS = 1_000_000_000L;

    private static final int SLOTS = SteinGlOptions.GPU_PASS_TIMING_SLOTS;

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

    private static int[] qBegin;
    private static int[] qEnd;

    /** Slots, labels and totals of the passes (shared bookkeeping with {@link SteinGlOpTiming}). */
    private static SteinGlTimestampRing ring;

    private static long frame;
    private static boolean broken;
    private static long invalid;

    /** Occurrences of each name in the current frame (to number the repeated ones). */
    private static final Map<String, int[]> OCCURRENCES = new HashMap<>();

    private static final Map<String, List<String>> NUMBERED = new HashMap<>();
    private static long windowFrames;
    private static long spanSum;
    private static long gapSum;
    private static long spanFrames;
    private static long reportAt = System.nanoTime();

    private SteinGlGpuTiming() {}

    private static boolean ready() {
        if (broken) {
            return false;
        }
        if (qBegin == null) {
            if (!GL.getCapabilities().OpenGL33 && !GL.getCapabilities().GL_ARB_timer_query) {
                broken = true;
                Log.info("gfx", "GPU time per pass: no timer query (GL 3.3) in this" + " context");
                return false;
            }
            qBegin = queries(SLOTS);
            qEnd = queries(SLOTS);
            ring = new SteinGlTimestampRing(SLOTS, 16);
        }
        return true;
    }

    /**
     * All the query names already handed out (the start ring and the end ring cannot share any).
     */
    private static final Set<Integer> USED = new HashSet<>();

    /**
     * {@code n} distinct query objects, none of them 0. On the RX 7600 a block glGenQueries returns
     * 0..n-1 and, in a second block, names that repeat the first's (names generated and not yet
     * used count as free): the start ring and the end ring shared names, and slot 0 had name 0, for
     * which the timestamp is not recorded (reads 0) — on every lap of the ring a pass came out with
     * the GPU's absolute timestamp (~10^13 ns). With GL 4.5 glCreateQueries creates the objects
     * right away; without it, one name at a time, created by a glQueryCounter right after.
     * Verified: nothing repeated, nothing 0.
     */
    private static int[] queries(int n) {
        int[] q = new int[n];
        if (GL.getCapabilities().OpenGL45) {
            org.lwjgl.opengl.GL45.glCreateQueries(GL33.GL_TIMESTAMP, q);
            if (distinct(q)) {
                return q;
            }
        }
        for (int i = 0; i < n; ) {
            int id = GL15.glGenQueries();
            if (id != 0 && USED.add(id)) {
                GL33.glQueryCounter(
                        id, GL33.GL_TIMESTAMP); // creates the object: the name stops being free
                q[i++] = id;
            }
        }
        return q;
    }

    private static boolean distinct(int[] q) {
        Set<Integer> mine = new HashSet<>();
        for (int id : q) {
            if (id == 0 || USED.contains(id) || !mine.add(id)) {
                return false;
            }
        }
        USED.addAll(mine);
        return true;
    }

    /** On pass open: the start timestamp. Returns the slot in the ring (-1: no measurement). */
    static int begin(String name) {
        if (!ready()) {
            return -1;
        }
        int slot = ring.claim(frame);
        if (slot < 0) {
            return -1;
        }
        ring.setLabel(slot, ring.id(label(name)));
        GL33.glQueryCounter(qBegin[slot], GL33.GL_TIMESTAMP);
        return slot;
    }

    /** On close: the end timestamp. */
    static void end(int slot) {
        if (slot >= 0) {
            GL33.glQueryCounter(qEnd[slot], GL33.GL_TIMESTAMP);
        }
    }

    private static String label(String name) {
        if (AGGREGATED.contains(name)) {
            return name;
        }
        int[] n = OCCURRENCES.computeIfAbsent(name, k -> new int[1]);
        int k = n[0]++;
        if (k == 0) {
            return name;
        }
        List<String> l = NUMBERED.computeIfAbsent(name, x -> new ArrayList<>());
        while (l.size() < k) {
            l.add(name + "#" + (l.size() + 2));
        }
        return l.get(k - 1);
    }

    /**
     * At the end of each frame (Display.update, before the swap): reads what has already arrived
     * and closes the frame.
     */
    public static void frame() {
        if (!ON || broken || qBegin == null) {
            return;
        }
        collect();
        frame++;
        windowFrames++;
        OCCURRENCES.clear();
        report();
    }

    /** The oldest complete frames whose last timestamp has already arrived. */
    private static void collect() {
        SteinGlTimestampRing r = ring;
        while (r.any()) {
            int n = r.oldestFrameSize(frame);
            if (n == 0) {
                return; // the current frame is still open
            }
            int last = r.slotAt(n - 1);
            if (GL15.glGetQueryObjecti(qEnd[last], GL15.GL_QUERY_RESULT_AVAILABLE) == 0) {
                return;
            }
            long first = 0;
            long prevEnd = 0;
            long gaps = 0;
            boolean whole = true;
            for (int i = 0; i < n; i++) {
                int s = r.slotAt(i);
                long t0 = GL33.glGetQueryObjecti64(qBegin[s], GL15.GL_QUERY_RESULT);
                long t1 = GL33.glGetQueryObjecti64(qEnd[s], GL15.GL_QUERY_RESULT);
                int id = r.label(s);
                if (t0 <= 0 || t1 < t0 || t1 - t0 > MAX_PLAUSIBLE_PASS_NANOS) {
                    invalid++; // impossible result: out of the average (counted in the report)
                    whole = false;
                    continue;
                }
                r.sum[id] += t1 - t0;
                r.hits[id]++;
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
            }
            r.release(n);
        }
    }

    private static void report() {
        long now = System.nanoTime();
        if (now - reportAt < 5_000_000_000L || windowFrames == 0) {
            return;
        }
        StringBuilder b =
                new StringBuilder("GPU per pass, us per frame (")
                        .append(windowFrames)
                        .append(" frames):");
        SteinGlTimestampRing r = ring;
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
