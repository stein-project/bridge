/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import static dev.xavier.stein.gfx.SteinDeferredGroups.Group;
import static dev.xavier.stein.gfx.SteinInstanceWriter.INSTANCE_BYTES;

import dev.xavier.stein.gfx.vk.SteinVkOptions;

import org.lwjgl.system.MemoryUtil;

import java.util.Arrays;
import java.util.List;

/**
 * The bulk writes of the deferred entities split across the pose threads ({@link SteinPoseWorkers})
 * on Vulkan ({@code -Dsteinbridge.gfx.vkParallelRecord}): the retained tile blocks whose matrices
 * are taken to the current camera, and the indirect commands of the groups at the flush.
 *
 * <p>Only the bytes move between threads: every ring reservation, group, segment and draw is still
 * made by the render thread in the same order, and the workers write exactly the bytes the render
 * thread would have written into the slices it reserved. The tile blocks are queued while the pass
 * records and written at the start of the next flush ({@link #drain}), before anything is committed
 * or drawn; the ring memory on Vulkan is host-coherent and the GPU reads it only in a later
 * submission, after the join (whose atomic counter orders the writes before the submission).
 *
 * <p>The queued blocks read the heap copy of the recorded instances ({@code SteinTileEntry#data}),
 * never its native blob, which a re-recording of the same tile may free before the flush.
 *
 * <p>Pipeline: Stein GFX only (Vulkan).
 */
final class SteinParallelWrites {

    /** On: Vulkan with the option, outside the per-instance replay check (which runs inline). */
    static final boolean ON =
            SteinGfxOptions.VULKAN
                    && SteinVkOptions.PARALLEL_RECORD
                    && !SteinTileReplayCheck.ENABLED;

    /** Below this many queued instances the render thread writes them itself (waking costs). */
    private static final int MIN_PARALLEL_INSTANCES = 512;

    /** Instances per worker task: enough to cover the claim of an index. */
    private static final int TASK_INSTANCES = 128;

    /** Below this many commands in the flush, the render thread writes them itself. */
    private static final int MIN_PARALLEL_COMMANDS = 8192;

    private static final int FLOATS = INSTANCE_BYTES / 4;

    private SteinParallelWrites() {}

    // ---- worker threads ----

    /** Worker threads besides the render thread (-1 = not started yet). */
    private static int workers = -1;

    private static int workers() {
        if (workers < 0) {
            workers = SteinPoseWorkers.start(Math.max(0, SteinGfxOptions.POSE_THREADS));
        }
        return workers;
    }

    // ---- retained tile blocks taken to the current camera ----

    private static int blocks;
    private static int queuedInstances;
    private static long[] blockDst = new long[256];
    private static float[][] blockData = new float[256][];
    private static int[] blockCount = new int[256];

    /** The 3x4 correction of each block (copied: the caller's array is reused). */
    private static double[] blockFix = new double[256 * 12];

    /** Task t writes the blocks {@code [taskFrom[t], taskFrom[t + 1])}. */
    private static int[] taskFrom = new int[64];

    private static int tasks;

    private static final SteinPoseWorkers.Task WRITE_BLOCKS = SteinParallelWrites::writeTask;

    /**
     * Queues the {@code n} instances of a retained block (floats in {@code data}, recorded with
     * another camera) to be written at {@code dst} with the correction {@code fix} at the next
     * {@link #drain}.
     */
    static void deferCorrected(long dst, float[] data, int n, double[] fix) {
        if (blocks == blockDst.length) {
            int m = blocks * 2;
            blockDst = Arrays.copyOf(blockDst, m);
            blockData = Arrays.copyOf(blockData, m);
            blockCount = Arrays.copyOf(blockCount, m);
            blockFix = Arrays.copyOf(blockFix, m * 12);
        }
        blockDst[blocks] = dst;
        blockData[blocks] = data;
        blockCount[blocks] = n;
        System.arraycopy(fix, 0, blockFix, blocks * 12, 12);
        blocks++;
        queuedInstances += n;
    }

    /** Writes every queued block (split across the threads when worth it) and empties the queue. */
    static void drain() {
        if (blocks == 0) {
            return;
        }
        try {
            if (queuedInstances < MIN_PARALLEL_INSTANCES || workers() == 0) {
                writeBlocks(0, blocks);
            } else {
                planTasks();
                rethrow(SteinPoseWorkers.run(WRITE_BLOCKS, 0, tasks));
            }
        } finally {
            Arrays.fill(blockData, 0, blocks, null);
            blocks = 0;
            queuedInstances = 0;
        }
    }

    /**
     * Cuts the queue into tasks of about {@link #TASK_INSTANCES} instances, keeping blocks whole.
     */
    private static void planTasks() {
        tasks = 0;
        int acc = TASK_INSTANCES;
        for (int b = 0; b < blocks; b++) {
            if (acc >= TASK_INSTANCES) {
                if (tasks + 1 >= taskFrom.length) {
                    taskFrom = Arrays.copyOf(taskFrom, taskFrom.length * 2);
                }
                taskFrom[tasks++] = b;
                acc = 0;
            }
            acc += blockCount[b];
        }
        taskFrom[tasks] = blocks;
    }

    private static void writeTask(int task, int worker) {
        writeBlocks(taskFrom[task], taskFrom[task + 1]);
    }

    private static void writeBlocks(int from, int to) {
        for (int b = from; b < to; b++) {
            writeCorrected(blockDst[b], blockData[b], blockCount[b], blockFix, b * 12);
        }
    }

    /**
     * The same bytes as {@code SteinDeferredRetained.writeCorrected}: the 3 matrix rows taken to
     * the current camera in double, then the rest of the record as recorded (raw bits), each ring
     * byte written once and in order. No scratch: safe on any thread.
     */
    private static void writeCorrected(long p, float[] data, int n, double[] fix, int f) {
        for (int i = 0; i < n; i++) {
            int s = i * FLOATS;
            long dst = p + (long) i * INSTANCE_BYTES;
            for (int r = 0; r < 3; r++) {
                double c0 = fix[f + r * 4];
                double c1 = fix[f + r * 4 + 1];
                double c2 = fix[f + r * 4 + 2];
                for (int c = 0; c < 4; c++) {
                    double v = c0 * data[s + c] + c1 * data[s + 4 + c] + c2 * data[s + 8 + c];
                    if (c == 3) {
                        v += fix[f + r * 4 + 3];
                    }
                    MemoryUtil.memPutFloat(dst + (r * 4 + c) * 4L, (float) v);
                }
            }
            for (int k = 12; k < FLOATS; k++) {
                MemoryUtil.memPutInt(dst + k * 4L, Float.floatToRawIntBits(data[s + k]));
            }
        }
    }

    // ---- the groups' indirect commands at the flush ----

    private static List<Group> commandGroups;

    private static final SteinPoseWorkers.Task WRITE_COMMANDS = SteinParallelWrites::commandTask;

    /**
     * Whether the flush should reserve the commands first and fill them with {@link #fillCommands}:
     * on, and enough commands for the threads to pay off.
     */
    static boolean parallelCommands(int pending, int groups) {
        return ON && pending >= MIN_PARALLEL_COMMANDS && groups > 1 && workers() > 0;
    }

    /**
     * Fills the commands of every group of {@code order} with a reservation ({@code cmdAt} > 0 or a
     * valid offset; see {@code SteinDeferredFrame#reserveCommands}), one group per task.
     */
    static void fillCommands(List<Group> order) {
        commandGroups = order;
        try {
            rethrow(SteinPoseWorkers.run(WRITE_COMMANDS, 0, order.size()));
        } finally {
            commandGroups = null;
        }
    }

    private static void commandTask(int index, int worker) {
        Group g = commandGroups.get(index);
        if (g.n > 0 && g.cmdAt >= 0) {
            SteinDeferredFrame.fillCommands(g, g.cmdAt);
        }
    }

    private static void rethrow(Throwable t) {
        if (t instanceof RuntimeException e) {
            throw e;
        }
        if (t instanceof Error e) {
            throw e;
        }
        if (t != null) {
            throw new IllegalStateException("parallel entity writes failed", t);
        }
    }
}
