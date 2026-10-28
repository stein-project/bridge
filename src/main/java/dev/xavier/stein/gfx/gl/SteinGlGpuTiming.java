/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx.gl;

import dev.xavier.stein.bridge.core.Log;
import dev.xavier.stein.gfx.SteinGpuPassTiming;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL44;

import java.util.HashSet;
import java.util.Set;

/**
 * GPU time per pass on OpenGL ({@code -Dsteinbridge.gfx.gpuPassTiming=true}, diagnostic): a
 * timestamp ({@code glQueryCounter}, GL 3.3) when each pass opens and another when it closes; the
 * labels, the ring and the report are {@link SteinGpuPassTiming}'s. A timestamp and not
 * GL_TIME_ELAPSED: an external frame profiler may already hold a time query open around the whole
 * frame, and those do not nest.
 *
 * <p>Reading: one availability query per frame, on the frame's last end timestamp (the GPU finishes
 * in order, so the earlier ones are there too), then one result call per timestamp. OpenGL has no
 * call that reads several query objects at once (only into a buffer on the GPU, which would add a
 * copy and a map per frame). With GL 4.4 the results are asked with {@code
 * GL_QUERY_RESULT_NO_WAIT}, which leaves the value untouched instead of waiting when a query is not
 * there yet: such a timestamp reads as 0 (an impossible result, counted) and the CPU can never
 * stall on it, even if a driver completes queries out of order.
 *
 * <p>Pipeline: Stein GFX only.
 */
public final class SteinGlGpuTiming {

    static final boolean ON = SteinGpuPassTiming.ON;

    /**
     * Ring slots ({@code -Dsteinbridge.gfx.gpuPassTiming.slots}: smaller, to test the ring
     * wrap-around).
     */
    private static final int SLOTS = SteinGlOptions.GPU_PASS_TIMING_SLOTS;

    private static int[] qBegin;
    private static int[] qEnd;
    private static SteinGpuPassTiming timing;
    private static boolean broken;

    /** GL_QUERY_RESULT_NO_WAIT (GL 4.4) where there is one, else GL_QUERY_RESULT. */
    private static int resultMode = GL15.GL_QUERY_RESULT;

    /** One result, reused for every read (no allocation per call). */
    private static final long[] VALUE = new long[1];

    /** The query results, by slot (read only for frames whose last timestamp arrived). */
    private static final SteinGpuPassTiming.Timestamps READ =
            new SteinGpuPassTiming.Timestamps() {
                @Override
                public boolean arrived(int slot) {
                    return GL15.glGetQueryObjecti(qEnd[slot], GL15.GL_QUERY_RESULT_AVAILABLE) != 0;
                }

                @Override
                public long begin(int slot) {
                    return result(qBegin[slot]);
                }

                @Override
                public long end(int slot) {
                    return result(qEnd[slot]);
                }
            };

    private SteinGlGpuTiming() {}

    /** The query's timestamp; 0 when NO_WAIT found it not there yet (the value is left alone). */
    private static long result(int query) {
        VALUE[0] = 0L;
        GL33.glGetQueryObjecti64v(query, resultMode, VALUE);
        return VALUE[0];
    }

    private static boolean ready() {
        if (broken) {
            return false;
        }
        if (qBegin == null) {
            if (!GL.getCapabilities().OpenGL33 && !GL.getCapabilities().GL_ARB_timer_query) {
                broken = true;
                Log.info("gfx", "GPU time per pass: no timer query (GL 3.3) in this context");
                return false;
            }
            qBegin = queries(SLOTS);
            qEnd = queries(SLOTS);
            if (GL.getCapabilities().OpenGL44) {
                resultMode = GL44.GL_QUERY_RESULT_NO_WAIT;
            }
            timing = new SteinGpuPassTiming(SLOTS);
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
        int slot = timing.claim(name);
        if (slot >= 0) {
            GL33.glQueryCounter(qBegin[slot], GL33.GL_TIMESTAMP);
        }
        return slot;
    }

    /** On close: the end timestamp. */
    static void end(int slot) {
        if (slot >= 0) {
            GL33.glQueryCounter(qEnd[slot], GL33.GL_TIMESTAMP);
        }
    }

    /**
     * At the end of each frame (Display.update, before the swap): reads what has already arrived
     * and closes the frame.
     */
    public static void frame() {
        if (!ON || broken || qBegin == null) {
            return;
        }
        timing.frame(READ, "OpenGL");
    }
}
