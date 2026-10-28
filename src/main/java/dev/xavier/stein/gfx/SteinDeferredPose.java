/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import static dev.xavier.stein.gfx.SteinDeferredFrame.*;
import static dev.xavier.stein.gfx.SteinDeferredGroups.*;
import static dev.xavier.stein.gfx.SteinDeferredResources.*;
import static dev.xavier.stein.gfx.SteinDeferredSessions.*;
import static dev.xavier.stein.gfx.SteinDeferredStats.*;
import static dev.xavier.stein.gfx.SteinInstanceWriter.*;

import org.lwjgl.system.MemoryUtil;

import java.util.Arrays;

/**
 * API of {@link SteinEntityDeferred} for the player pose ({@link SteinPlayerPose}, {@link
 * SteinPoseGpu}): reserving instances, commands written by the GPU, and the part and held-item
 * commands.
 *
 * <p>Pipeline: Stein GFX only.
 */
final class SteinDeferredPose {

    // ---- API for the pose ----
    // SteinPlayerPose records whole players at once: it reserves the instances of a batch, puts the
    // commands in the groups with the key it built itself (the state that the game's doRender
    // would set, without reading the GlStateManager for every part) and its threads write the
    // instances into mapped memory before any flush.

    /**
     * Reserves {@code n} consecutive instances: the byte offset; -2 if the region filled up; -1 if
     * it does not fit in one.
     */
    static long poseReserve(int n) {
        return instances.reserve((long) n * INSTANCE_BYTES);
    }

    /**
     * The address of offset {@code at} in mapped memory (any thread may write, before the flush).
     */
    static long poseAddress(long at) {
        return instances.address(at);
    }

    /**
     * The region filled up: draws what was recorded (the reserved instances must already be
     * written) and moves on.
     */
    static void poseNextRegion() {
        flush();
        rotate();
    }

    // On-GPU pose (SteinPoseGpu): the players' commands are reserved here, in the usual order,
    // and written by the compute. One block per group and flush (the players of a dump enter
    // consecutively in each group); the block's triple in cmds is (POSE_BLOCK, id, commands), and
    // writeCommands records where it landed in the ring.

    static final int POSE_BLOCK = Integer.MIN_VALUE;
    static int poseBlocks;
    static int[] poseBlockAt = new int[64];

    /**
     * Per block, with {@link SteinEntityBatch} on: the CPU's copy of what the compute will write in
     * each command (vertices, first vertex, instance), so the batching can regroup them by part.
     */
    static int[][] poseShadow = new int[64][];

    /**
     * The on-GPU pose can record now (indirect draw; outside strict order, where a group flushes).
     */
    static boolean poseGpuAllowed() {
        return ready && indirect && !strictOrder;
    }

    /**
     * {@code count} commands in group {@code key}, written by the GPU: returns block << 32 | offset
     * in the block. Right after what the group already had of the pose in this flush, it extends
     * the same block.
     */
    static long poseSlots(long key, int count) {
        Group g = group(key);
        int k = (g.entries - 1) * 3;
        int off;
        int block;
        if (g.entries > 0 && g.cmds[k] == POSE_BLOCK) {
            block = g.cmds[k + 1];
            off = g.cmds[k + 2];
            g.cmds[k + 2] = off + count;
        } else {
            if (g.entries * 3 + 3 > g.cmds.length) {
                g.cmds = Arrays.copyOf(g.cmds, g.cmds.length * 2);
            }
            if (poseBlocks == poseBlockAt.length) {
                poseBlockAt = Arrays.copyOf(poseBlockAt, poseBlocks * 2);
                poseShadow = Arrays.copyOf(poseShadow, poseBlocks * 2);
            }
            block = poseBlocks++;
            off = 0;
            k = g.entries * 3;
            g.cmds[k] = POSE_BLOCK;
            g.cmds[k + 1] = block;
            g.cmds[k + 2] = count;
            g.entries++;
            poseBlockAt[block] = -1;
        }
        g.n += count;
        pending += count;
        parts += count;
        if (SteinEntityBatch.ON) {
            int need = (off + count) * 3;
            int[] sh = poseShadow[block];
            if (sh == null || sh.length < need) {
                poseShadow[block] =
                        sh == null
                                ? new int[Math.max(need, 3 * 256)]
                                : Arrays.copyOf(sh, Math.max(need, sh.length * 2));
            }
        }
        return (long) block << 32 | off;
    }

    /**
     * Command {@code q} of {@code slot} (from {@link #poseSlots}), as the compute will write it:
     * for {@link SteinEntityBatch}.
     */
    static void poseShadow(long slot, int q, int verts, int first, int instance) {
        int[] sh = poseShadow[(int) (slot >>> 32)];
        int k = ((int) slot + q) * 3;
        sh[k] = verts;
        sh[k + 1] = first;
        sh[k + 2] = instance;
    }

    /** The commands of block {@code block} as {@link #poseShadow} recorded them. */
    static int[] poseShadow(int block) {
        return poseShadow[block];
    }

    static int poseBlockCount() {
        return poseBlocks;
    }

    /**
     * The index in the command ring of the block's first command (after writeCommands; -1 if it did
     * not fit).
     */
    static int poseBlockAt(int block) {
        return poseBlockAt[block];
    }

    /**
     * The on-GPU pose will not run in this flush: its blocks become empty commands (nothing
     * appears, nothing is left over).
     */
    static void clearPoseBlocks() {
        for (int i = 0; i < order.size(); i++) {
            Group g = order.get(i);
            if (g.cmdAt < 0 || g.n == 0) {
                continue;
            }
            int[] cm = g.cmds;
            for (int e = 0, k = 0; e < g.entries; e++, k += 3) {
                if (cm[k] == POSE_BLOCK && poseBlockAt[cm[k + 1]] >= 0) {
                    MemoryUtil.memSet(
                            commands.address((long) poseBlockAt[cm[k + 1]] * COMMAND_BYTES),
                            0,
                            (long) cm[k + 2] * COMMAND_BYTES);
                }
            }
        }
    }

    /**
     * The command of a part (display list {@code list}) in instance {@code instance}, in group
     * {@code key}.
     */
    static void posePart(long key, int list, int instance) {
        command(
                group(key),
                SteinModelInstancer.vertexCount(list),
                SteinModelInstancer.firstVertex(list),
                instance);
        parts++;
    }

    /**
     * The held-item command ({@link SteinHeldItem}): {@code verts} vertices of the fixed item
     * geometry.
     */
    static void poseStatic(long key, int first, int verts, int instance) {
        command(group(key), verts, first, instance);
        parts++;
    }

    /**
     * The lightmap texture that instances with FLAG_LIGHTMAP use (what beginModel would have
     * stored).
     */
    static void poseLightmap(int id) {
        lightmapId = id;
    }
}
