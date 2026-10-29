/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import static dev.xavier.stein.gfx.SteinDeferredGroups.*;
import static dev.xavier.stein.gfx.SteinDeferredResources.*;
import static dev.xavier.stein.gfx.SteinDeferredSessions.*;
import static dev.xavier.stein.gfx.SteinInstanceWriter.*;

import dev.xavier.stein.bridge.core.Log;

import org.lwjgl.system.MemoryUtil;

import java.util.Arrays;

/**
 * The opaque groups of model parts drawn with one instanced command per distinct part ({@code
 * -Dsteinbridge.gfx.entityBatch}), instead of one command per part of each entity.
 *
 * <p>Why: with indirect drawing every part of every player is its own command of one instance (a
 * crowd is ~17k of them per frame); the GPU's front end pays for each, and on an integrated GPU
 * that is most of the entity time. The GL 4.1 path ({@link SteinDeferredFrame#drawSorted}) already
 * copies each group's instances in part order on the CPU and draws one instanced command per part;
 * here the instances may come out of the pose compute shader, so the copy is a compute shader too
 * (gather.comp): at flush the CPU lists, per group, which instance goes where (it knows every
 * command: its own triples, and the pose blocks through {@link SteinDeferredPose#poseShadow}),
 * writes one command per part, and the gather runs right after the pose, before any draw.
 *
 * <p>What can change in the image: only groups without blending, with depth writes, are batched, so
 * per pixel the nearest fragment still wins; within one part the recording order is kept, so a
 * surface drawn twice keeps its order. What changes is the order between different parts, which
 * only shows where two fragments have exactly the same depth (two players standing inside each
 * other), as on the GL 4.1 path.
 *
 * <p>Outside strict order only (the entity pass); a group that would gain little (more than half of
 * its commands distinct) or does not fit stays as it was.
 *
 * <p>Pipeline: Stein GFX only.
 */
final class SteinEntityBatch {
    private SteinEntityBatch() {}

    static final boolean ON = SteinGfxOptions.ENTITY_BATCH;

    /** Instances gathered per flush, at most (a flush that has more batches the first groups). */
    private static final int CAP = RING_PARTS / 2;

    private static final int REGIONS = 3;
    private static final int STORAGE_ALIGN = 256;
    private static final int GROUP = 64;

    private static boolean initDone;
    private static boolean ready;
    private static SteinPipeline pipeline;
    private static SteinBuffer remap;
    private static SteinBuffer gathered;

    // ---- this flush ----

    /** Where this flush's list landed in {@link #remap}; -1 = nothing to gather. */
    private static long remapAt = -1;

    private static long gatherAt;
    private static int total;
    private static boolean reserved;

    // ---- scratch (grows; nothing allocated per frame) ----

    private static int[] eFirst = new int[1024];
    private static int[] eVerts = new int[1024];
    private static int[] eInst = new int[1024];
    private static int[] eBucket = new int[1024];

    /** Open addressing table (first vertex << 32 | vertices) to bucket + 1 (0 = empty). */
    private static long[] tKey = new long[4096];

    private static int[] tBucket = new int[4096];
    private static int[] bFirst = new int[256];
    private static int[] bVerts = new int[256];
    private static int[] bCount = new int[256];
    private static int[] bAt = new int[256];

    /** Turns on at the first call: compute, indirect drawing and the compiled shader. */
    static boolean ready() {
        if (initDone) {
            return ready;
        }
        initDone = true;
        SteinDevice dev = SteinDevice.get();
        SteinCaps caps = dev.caps();
        if (!ON || !caps.compute || !caps.multiDrawIndirect || !caps.persistentMapping) {
            return false;
        }
        try {
            SteinShader cs = SteinShader.load(SteinShader.Stage.COMPUTE, "gather.comp");
            pipeline =
                    dev.createPipeline(
                            SteinPipeline.builder("entities: gather")
                                    .compute(cs)
                                    .storage("BatchRemap", 0)
                                    .storage("BatchSource", 1)
                                    .storage("BatchTarget", 2)
                                    .build());
            if (!pipeline.valid()) {
                Log.info("gfx", "entity batching off: the gather shader did not compile");
                return false;
            }
            remap =
                    dev.createBuffer(
                                    new SteinBuffer.Desc(
                                            "entities: gather list",
                                            ((CAP + 1L) * 4 + STORAGE_ALIGN) * REGIONS,
                                            SteinBuffer.Memory.UPLOAD,
                                            SteinBuffer.Usage.STORAGE))
                            .ring(REGIONS);
            // The GPU writes it: a fenced ring too, so a flush never writes over instances that
            // an earlier pass may still be reading.
            gathered =
                    dev.createBuffer(
                                    new SteinBuffer.Desc(
                                            "entities: gathered instances",
                                            ((long) CAP * INSTANCE_BYTES + STORAGE_ALIGN) * REGIONS,
                                            SteinBuffer.Memory.UPLOAD,
                                            SteinBuffer.Usage.VERTEX,
                                            SteinBuffer.Usage.STORAGE))
                            .ring(REGIONS);
            if (!remap.mapped()) {
                Log.info("gfx", "entity batching off: the list buffer is not mapped");
                return false;
            }
        } catch (RuntimeException e) {
            Log.info("gfx", "entity batching off: " + e);
            return false;
        }
        ready = true;
        Log.info("gfx", "entity batching: one instanced command per part and group");
        return true;
    }

    /** The group may be batched: opaque model parts (no blend, depth writes, part geometry). */
    private static boolean eligible(Group g) {
        return g.n >= 2
                && g.cmdAt >= 0
                && !g.blend
                && !g.dynamic
                && SteinDeferredKeys.keyDepthMask((int) g.key);
    }

    /**
     * At flush, after the groups' commands and the pose input are written ({@code poseGpu}: the
     * pose compute runs in this flush, so its blocks hold instances): lists the gather and writes
     * the batched commands. Groups that are not batched draw as before.
     */
    static void plan(boolean poseGpu) {
        remapAt = -1;
        total = 0;
        reserved = false;
        if (!ON || !indirect || strictOrder || !ready()) {
            return;
        }
        int upper = 0;
        for (int i = 0; i < order.size(); i++) {
            Group g = order.get(i);
            if (eligible(g)) {
                upper += g.n;
            }
        }
        if (upper < 2) {
            return;
        }
        upper = Math.min(upper, CAP);
        long at = reserve(remap, (upper + 1L) * 4);
        long gAt = at < 0 ? -1 : reserve(gathered, (long) upper * INSTANCE_BYTES);
        reserved = at >= 0 || gAt >= 0;
        if (at < 0 || gAt < 0) {
            return;
        }
        long list = remap.address(at);
        for (int i = 0; i < order.size(); i++) {
            Group g = order.get(i);
            if (eligible(g)) {
                planGroup(g, list, upper, poseGpu);
            }
        }
        if (total == 0) {
            return;
        }
        MemoryUtil.memPutInt(list, total);
        remap.commit(at, (total + 1L) * 4);
        remapAt = at;
        gatherAt = gAt;
    }

    private static long reserve(SteinBuffer ring, long bytes) {
        long at = ring.reserve(bytes, STORAGE_ALIGN);
        if (at == -2) {
            // regions of their own, closed after every flush that used them: the open one is
            // never read by a pass still to come
            ring.nextRegion();
            at = ring.reserve(bytes, STORAGE_ALIGN);
        }
        return at;
    }

    /** One group: its commands by part, where each instance goes, and one command per part. */
    private static void planGroup(Group g, long list, int cap, boolean poseGpu) {
        int n = collect(g, poseGpu);
        if (n < 2 || total + n > cap) {
            return;
        }
        int buckets = bucket(n);
        if (buckets * 2 > n) {
            return; // mostly distinct parts: one command each either way
        }
        long bytes = (long) buckets * COMMAND_BYTES;
        long cAt = commands.reserve(bytes, COMMAND_BYTES);
        if (cAt < 0) {
            return; // the commands' region is full: this group goes out unbatched
        }
        long cp = commands.address(cAt);
        int pos = total;
        for (int b = 0; b < buckets; b++) {
            bAt[b] = pos;
            long q = cp + (long) b * COMMAND_BYTES;
            MemoryUtil.memPutInt(q, bVerts[b]);
            MemoryUtil.memPutInt(q + 4, bCount[b]);
            MemoryUtil.memPutInt(q + 8, bFirst[b]);
            MemoryUtil.memPutInt(q + 12, pos); // relative to the gathered binding's offset
            pos += bCount[b];
        }
        commands.commit(cAt, bytes);
        for (int i = 0; i < n; i++) {
            int b = eBucket[i];
            MemoryUtil.memPutInt(list + (1L + bAt[b]++) * 4, eInst[i]);
        }
        g.batchAt = cAt;
        g.batchN = buckets;
        total = pos;
        batchedGroups++;
        batchedCommands += n;
        batchedDraws += buckets;
    }

    /**
     * The group's commands in recording order into {@link #eFirst}, {@link #eVerts}, {@link
     * #eInst}; returns the count. Pose blocks without a pose run (or that did not fit) are left
     * out: their commands are empty.
     */
    private static int collect(Group g, boolean poseGpu) {
        grow(g.n);
        int[] cm = g.cmds;
        int n = 0;
        for (int e = 0, k = 0; e < g.entries; e++, k += 3) {
            if (cm[k] == SteinDeferredPose.POSE_BLOCK) {
                int block = cm[k + 1];
                int count = cm[k + 2];
                if (!poseGpu || SteinDeferredPose.poseBlockAt(block) < 0) {
                    continue;
                }
                int[] sh = SteinDeferredPose.poseShadow(block);
                for (int q = 0; q < count; q++) {
                    int v = sh[q * 3];
                    if (v > 0) {
                        eVerts[n] = v;
                        eFirst[n] = sh[q * 3 + 1];
                        eInst[n] = sh[q * 3 + 2];
                        n++;
                    }
                }
                continue;
            }
            if (cm[k] > 0) {
                eVerts[n] = cm[k];
                eFirst[n] = cm[k + 1];
                eInst[n] = cm[k + 2];
                n++;
            }
        }
        return n;
    }

    /**
     * Gives each collected command its part's bucket (buckets in order of first appearance), with
     * the buckets' geometry and counts; returns how many buckets.
     */
    private static int bucket(int n) {
        int size = tKey.length;
        while (size < n * 2) {
            size *= 2;
        }
        if (size != tKey.length) {
            tKey = new long[size];
            tBucket = new int[size];
        }
        Arrays.fill(tBucket, 0);
        int mask = size - 1;
        int shift = 64 - Integer.numberOfTrailingZeros(size);
        int buckets = 0;
        for (int i = 0; i < n; i++) {
            long key = (long) eFirst[i] << 32 | (eVerts[i] & 0xFFFFFFFFL);
            int s = (int) ((key * 0x9E3779B97F4A7C15L) >>> shift) & mask;
            while (tBucket[s] != 0 && tKey[s] != key) {
                s = (s + 1) & mask;
            }
            int b;
            if (tBucket[s] == 0) {
                if (buckets == bFirst.length) {
                    int c = buckets * 2;
                    bFirst = Arrays.copyOf(bFirst, c);
                    bVerts = Arrays.copyOf(bVerts, c);
                    bCount = Arrays.copyOf(bCount, c);
                    bAt = Arrays.copyOf(bAt, c);
                }
                b = buckets++;
                tKey[s] = key;
                tBucket[s] = b + 1;
                bFirst[b] = eFirst[i];
                bVerts[b] = eVerts[i];
                bCount[b] = 0;
            } else {
                b = tBucket[s] - 1;
            }
            bCount[b]++;
            eBucket[i] = b;
        }
        return buckets;
    }

    private static void grow(int n) {
        if (eFirst.length < n) {
            int c = Math.max(n, eFirst.length * 2);
            eFirst = new int[c];
            eVerts = new int[c];
            eInst = new int[c];
            eBucket = new int[c];
        }
    }

    /**
     * At the start of the pass, after the pose compute (whose instances it reads) and before any
     * draw: the dispatch contract puts a barrier on both sides.
     */
    static void dispatch(SteinCommands c) {
        if (remapAt < 0) {
            return;
        }
        c.bindPipeline(pipeline);
        c.bindStorage(0, remap, remapAt, (total + 1L) * 4);
        c.bindStorage(1, instances, 0, instances.size());
        c.bindStorage(2, gathered, gatherAt, (long) total * INSTANCE_BYTES);
        c.dispatch((total * 8 + GROUP - 1) / GROUP, 1, 1);
    }

    /** The whole of a batched group: its commands over the gathered instances. */
    static boolean batched(Group g, int from, int count) {
        return g.batchN > 0 && remapAt >= 0 && from == 0 && count == g.n;
    }

    /** Draws a {@link #batched} group, then puts binding 1 back on the instance ring. */
    static void draw(SteinCommands c, Group g) {
        c.bindVertexBuffer(1, gathered, gatherAt);
        c.drawIndirect(commands, g.batchAt, g.batchN);
        c.bindVertexBuffer(1, instances, 0);
    }

    /** After the pass: closes this flush's regions (with a fence) when it used them. */
    static void afterFlush() {
        if (reserved) {
            remap.nextRegion();
            gathered.nextRegion();
        }
        reserved = false;
        remapAt = -1;
        total = 0;
    }

    // ---- numbers ----

    private static long batchedGroups;
    private static long batchedCommands;
    private static long batchedDraws;

    /** "N batched groups, M commands in K" since the last time, and resets. */
    static String takeStats() {
        String s =
                batchedGroups
                        + " batched groups, "
                        + batchedCommands
                        + " commands in "
                        + batchedDraws;
        batchedGroups = 0;
        batchedCommands = 0;
        batchedDraws = 0;
        return s;
    }
}
