/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

package dev.xavier.stein.gfx;

import static dev.xavier.stein.gfx.SteinPlayerPose.*;
import static dev.xavier.stein.gfx.SteinPlayerPoseJobs.*;
import static dev.xavier.stein.gfx.SteinPlayerPoseModels.*;

import dev.xavier.stein.bridge.core.Log;

import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * {@code -Dsteinbridge.gfx.gpuPoseCheck=true}: while the pose runs on the GPU, remembers what the
 * CPU computation gives for every part (matrix rows, instance, command slot, rest of the state,
 * geometry), and after the flush reads back what the compute shader wrote and compares the two.
 * Diagnostics only: it waits for the GPU on every flush.
 *
 * <p>Pipeline: Stein GFX only.
 */
final class SteinPlayerPoseGpuCheck {
    private SteinPlayerPoseGpuCheck() {}

    // ---- check of the pose on the GPU (-Dsteinbridge.gfx.gpuPoseCheck=true) ----
    // Per part: the 3 rows the CPU computes, the instance, the block and the command's position,
    // the rest of the state and the expected geometry. After the flush, it reads back the range of
    // instances and commands and compares.

    private static int expN;
    private static float[] expRows = new float[1024 * 12];
    private static float[] expRest = new float[1024 * REST];
    private static int[] expInst = new int[1024];
    private static long[] expSlot = new long[1024];

    /** Vertices, first vertex and part (model * 16 + part). */
    private static int[] expGeo = new int[1024 * 3];

    static void checkExpect(
            float[] mats,
            int part,
            int instance,
            long slot,
            int offset,
            int restAt,
            int verts,
            int firstVertex) {
        if (expN == expInst.length) {
            int n = expN * 2;
            expRows = Arrays.copyOf(expRows, n * 12);
            expRest = Arrays.copyOf(expRest, n * REST);
            expInst = Arrays.copyOf(expInst, n);
            expSlot = Arrays.copyOf(expSlot, n);
            expGeo = Arrays.copyOf(expGeo, n * 3);
        }
        int m = part * 16;
        int o = expN * 12;
        for (int r = 0; r < 3; r++) {
            expRows[o + r * 4] = mats[m + r];
            expRows[o + r * 4 + 1] = mats[m + 4 + r];
            expRows[o + r * 4 + 2] = mats[m + 8 + r];
            expRows[o + r * 4 + 3] = mats[m + 12 + r];
        }
        System.arraycopy(jState, restAt, expRest, expN * REST, REST);
        expInst[expN] = instance;
        expSlot[expN] = slot + offset;
        expGeo[expN * 3] = verts;
        expGeo[expN * 3 + 1] = firstVertex;
        expGeo[expN * 3 + 2] = part;
        expN++;
    }

    private static long gpuParts;
    private static long gpuExact;
    private static long gpuBadCommands;
    private static long gpuBadRest;
    private static float gpuWorstRel;
    private static float gpuWorstAbs;
    private static int gpuWorstPart = -1;

    /**
     * After the flush with pose on the GPU ({@link SteinPoseGpu#afterFlush}): reads back and
     * compares.
     */
    static void gpuCheck(SteinBuffer instances, SteinBuffer commands, boolean ran) {
        int n = expN;
        expN = 0;
        if (!GPU_CHECK || n == 0 || !ran) {
            return;
        }
        int minI = Integer.MAX_VALUE;
        int maxI = -1;
        int minC = Integer.MAX_VALUE;
        int maxC = -1;
        int[] cmdAt = new int[n];
        for (int i = 0; i < n; i++) {
            minI = Math.min(minI, expInst[i]);
            maxI = Math.max(maxI, expInst[i]);
            int base = SteinEntityDeferred.poseBlockAt((int) (expSlot[i] >>> 32));
            cmdAt[i] = base < 0 ? -1 : base + (int) expSlot[i];
            if (cmdAt[i] >= 0) {
                minC = Math.min(minC, cmdAt[i]);
                maxC = Math.max(maxC, cmdAt[i]);
            }
        }
        int ib = SteinEntityDeferred.POSE_INSTANCE_BYTES;
        ByteBuffer inst = MemoryUtil.memAlloc((maxI - minI + 1) * ib);
        ByteBuffer cmd = maxC >= 0 ? MemoryUtil.memAlloc((maxC - minC + 1) * 16) : null;
        try {
            SteinDevice dev = SteinDevice.get();
            dev.readBuffer(instances, (long) minI * ib, inst);
            if (cmd != null) {
                dev.readBuffer(commands, (long) minC * 16, cmd);
            }
            inst.order(ByteOrder.nativeOrder());
            if (cmd != null) {
                cmd.order(ByteOrder.nativeOrder());
            }
            for (int i = 0; i < n; i++) {
                int base = (expInst[i] - minI) * ib;
                boolean exact = true;
                for (int r = 0; r < 3; r++) {
                    float scale = 0f;
                    float diff = 0f;
                    for (int c = 0; c < 4; c++) {
                        float want = expRows[i * 12 + r * 4 + c];
                        float got = inst.getFloat(base + (r * 4 + c) * 4);
                        if (Float.floatToRawIntBits(want) != Float.floatToRawIntBits(got)) {
                            exact = false;
                        }
                        scale = Math.max(scale, Math.max(Math.abs(want), Math.abs(got)));
                        diff = Math.max(diff, Math.abs(want - got));
                    }
                    float rel = scale > 1e-6f ? diff / scale : diff;
                    if (Float.isNaN(rel)) {
                        rel = Float.POSITIVE_INFINITY;
                    }
                    if (rel > gpuWorstRel) {
                        gpuWorstRel = rel;
                        gpuWorstPart = expGeo[i * 3 + 2];
                    }
                    gpuWorstAbs =
                            Math.max(
                                    gpuWorstAbs,
                                    Float.isNaN(diff) ? Float.POSITIVE_INFINITY : diff);
                }
                gpuParts++;
                if (exact) {
                    gpuExact++;
                }
                for (int q = 0; q < REST; q++) {
                    if (Float.floatToRawIntBits(expRest[i * REST + q])
                            != Float.floatToRawIntBits(inst.getFloat(base + 48 + q * 4))) {
                        gpuBadRest++;
                        break;
                    }
                }
                if (cmd == null || cmdAt[i] < 0) {
                    gpuBadCommands++;
                    continue;
                }
                int c = (cmdAt[i] - minC) * 16;
                if (cmd.getInt(c) != expGeo[i * 3]
                        || cmd.getInt(c + 4) != 1
                        || cmd.getInt(c + 8) != expGeo[i * 3 + 1]
                        || cmd.getInt(c + 12) != expInst[i]) {
                    gpuBadCommands++;
                }
            }
        } finally {
            MemoryUtil.memFree(inst);
            if (cmd != null) {
                MemoryUtil.memFree(cmd);
            }
        }
    }

    /** The GPU pose check line, and resets its counters. */
    static void printGpuCheck() {
        StringBuilder g = new StringBuilder("GPU pose check: ");
        g.append(gpuParts)
                .append(" parts, ")
                .append(gpuParts > 0 ? gpuExact * 100 / gpuParts : 0)
                .append("% bit-identical to the CPU, worst relative error ")
                .append(gpuWorstRel);
        if (gpuWorstPart >= 0) {
            g.append(" (")
                    .append(
                            gpuWorstPart == ITEM_PART
                                    ? "held item"
                                    : MODEL_NAMES[gpuWorstPart >>> 4]
                                            + PART_NAMES[gpuWorstPart & 15])
                    .append(')');
        }
        g.append(", largest absolute difference ")
                .append(gpuWorstAbs)
                .append(", wrong commands ")
                .append(gpuBadCommands)
                .append(", wrong rest ")
                .append(gpuBadRest);
        Log.info("gfx", g.toString());
        gpuParts = 0;
        gpuExact = 0;
        gpuBadCommands = 0;
        gpuBadRest = 0;
        gpuWorstRel = 0f;
        gpuWorstAbs = 0f;
        gpuWorstPart = -1;
    }
}
