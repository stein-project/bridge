/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

package dev.xavier.stein.gfx.gl;

import dev.xavier.stein.bridge.core.Log;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL45;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * GPU time per replayed command ({@code -Dsteinbridge.gfx.gpuOpTiming=true}, diagnostics, only with
 * the recorded frame): a timestamp after each command in the list ({@link SteinGlFrame}), and the
 * interval between two consecutive ones is what the GPU spent (or waited) on that command. Label:
 * the command and where it is — inside a pass ("indexed draw in terrain#4"; the opening is "pass",
 * the closing with the restore is "pass end") or outside ("upload outside, after terrain#3"). The
 * timestamps are from the end of the queue (the command finished), so a command that waits for
 * earlier work accumulates the wait.
 *
 * <p>Reading as in {@link SteinGlGpuTiming}: ring, complete frames, never waits. Every 5 s, the 30
 * labels that cost the most (us per frame and how many per frame), and the total measured. Made to
 * find the ~130 us hole before the terrain's translucent pass.
 *
 * <p>Pipeline: Stein GFX only.
 */
final class SteinGlOpTiming {

    static final boolean ON = SteinGlOptions.GPU_OP_TIMING;

    private static final String INITIAL_CONTEXT = "start";

    private static final int SLOTS = 16384;
    private static int[] queries;

    /**
     * Slots, labels and totals of the commands (shared bookkeeping with {@link SteinGlGpuTiming}).
     */
    private static SteinGlTimestampRing ring;

    /**
     * The first timestamp of a submission: it only serves as a starting point (there is no command
     * before it in the same submission).
     */
    private static boolean[] slotStart;

    private static long frame;
    private static boolean broken;

    private static long windowFrames;
    private static long reportAt = System.nanoTime();

    /** The current context: the open pass, or the last closed one (for the outside commands). */
    private static String context = INITIAL_CONTEXT;

    private static boolean inPass;
    private static final Map<String, int[]> OCCURRENCES = new HashMap<>();

    private SteinGlOpTiming() {}

    private static boolean ready() {
        if (broken) {
            return false;
        }
        if (queries == null) {
            if (!GL.getCapabilities().OpenGL45) {
                broken = true;
                Log.info("gfx", "GPU time per command: needs GL 4.5");
                return false;
            }
            queries = new int[SLOTS];
            // one at a time: AMD's block glGenQueries returns 0 and repeats names (see
            // SteinGlGpuTiming)
            for (int i = 0; i < SLOTS; i++) {
                queries[i] = GL45.glCreateQueries(GL33.GL_TIMESTAMP);
            }
            ring = new SteinGlTimestampRing(SLOTS, 64);
            slotStart = new boolean[SLOTS];
        }
        return true;
    }

    private static void stamp(String label, boolean start) {
        if (!ready()) {
            return;
        }
        int id = ring.id(label);
        int s = ring.claim(frame);
        if (s < 0) {
            return;
        }
        ring.setLabel(s, id);
        slotStart[s] = start;
        GL33.glQueryCounter(queries[s], GL33.GL_TIMESTAMP);
    }

    /** Start of a submission (the interval up to here belongs to no command). */
    static void replayStart() {
        stamp("(start of submission)", true);
    }

    /**
     * Pass {@code name} opened: the following commands belong to it (repeats in the frame get a
     * number).
     */
    static void passBegin(String name) {
        int[] n = OCCURRENCES.computeIfAbsent(name, k -> new int[1]);
        int k = ++n[0];
        context = k == 1 ? name : name + "#" + k;
        inPass = true;
    }

    static void passEnd() {
        inPass = false;
    }

    /** After a replayed command. */
    static void after(String op) {
        stamp(op + (inPass ? " in " : " outside, after ") + context, false);
    }

    /** End of the frame (after the end replay): reads whatever has already arrived. */
    static void frame() {
        if (!ON || queries == null) {
            return;
        }
        collect();
        frame++;
        windowFrames++;
        OCCURRENCES.clear();
        context = INITIAL_CONTEXT;
        inPass = false;
        report();
    }

    private static void collect() {
        SteinGlTimestampRing r = ring;
        long[] sum = r.sum;
        long[] hits = r.hits;
        while (r.any()) {
            int n = r.oldestFrameSize(frame);
            if (n == 0) {
                return;
            }
            int last = r.slotAt(n - 1);
            if (GL15.glGetQueryObjecti(queries[last], GL15.GL_QUERY_RESULT_AVAILABLE) == 0) {
                return;
            }
            long prev = 0;
            for (int i = 0; i < n; i++) {
                int s = r.slotAt(i);
                long t = GL33.glGetQueryObjecti64(queries[s], GL15.GL_QUERY_RESULT);
                if (!slotStart[s] && prev > 0 && t >= prev && t - prev < 1_000_000_000L) {
                    sum[r.label(s)] += t - prev;
                    hits[r.label(s)]++;
                }
                prev = t > 0 ? t : 0;
            }
            r.release(n);
        }
    }

    private static void report() {
        long now = System.nanoTime();
        if (now - reportAt < 5_000_000_000L || windowFrames == 0) {
            return;
        }
        SteinGlTimestampRing r = ring;
        long[] sum = r.sum;
        long[] hits = r.hits;
        Integer[] order = new Integer[r.names.size()];
        long total = 0;
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
            total += sum[i];
        }
        Arrays.sort(order, (x, y) -> Long.compare(sum[y], sum[x]));
        StringBuilder b =
                new StringBuilder(
                        String.format(
                                "GPU per command, us per frame (%d frames, total measured"
                                        + " %.1f us):",
                                windowFrames, total / 1e3 / windowFrames));
        for (int k = 0; k < Math.min(30, order.length); k++) {
            int i = order[k];
            if (sum[i] == 0) {
                break;
            }
            b.append(
                    String.format(
                            "\n[gfx]   %7.1f  (%.1fx)  %s",
                            sum[i] / 1e3 / windowFrames,
                            (double) hits[i] / windowFrames,
                            r.names.get(i)));
        }
        long dropped = r.takeDropped();
        if (dropped > 0) {
            b.append("\n[gfx]   (").append(dropped).append(" dropped: ring full)");
        }
        Arrays.fill(sum, 0);
        Arrays.fill(hits, 0);
        Log.info("gfx", b.toString());
        windowFrames = 0;
        reportAt = now;
    }
}
