/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import dev.xavier.stein.bridge.core.Log;
import dev.xavier.stein.bridge.engine.AngleTable;

import org.lwjgl.system.MemoryUtil;

/**
 * The players' pose on the GPU ({@code src/main/shaders/pose.comp}): the default path of {@link
 * SteinPlayerPose} when the device has compute, indirect drawing and a persistent buffer ({@code
 * -Dsteinbridge.gfx.gpuPose=false} goes back to the CPU, which remains the level 4.1 fallback and
 * the reference).
 *
 * <p>The CPU only reads the entity and writes short records: per player, the 3 rows of the root
 * (doRender up to the sneak, computed on the CPU with the game's math, including the cosines in
 * double) and the setRotationAngles numbers; per state (body, each armor piece, leather overlay,
 * each glint pass), the "rest" of the instance and where its commands live; per drawn part, one
 * word (state, model, part). The compute shader expands this into the instances (in the instance
 * ring of {@link SteinEntityDeferred}, at the positions the CPU reserved) and the indirect commands
 * (at the positions the groups reserved: {@link SteinEntityDeferred#poseSlots}). The order of the
 * commands in each group is the same as on the CPU path, so the image is too.
 *
 * <p>Synchronization, Vulkan-style: the input is written before the pass is opened; the dispatch
 * runs at the start of the pass, before any draw; the contract of {@link SteinCommands#dispatch}
 * inserts the barrier (what the compute shader wrote becomes visible to the following draws:
 * vertex, indirect). The input lives in a ring of its own, closed with a fence on every flush that
 * had pose.
 *
 * <p>Everything on the game thread. Nothing here runs with the renderer off.
 *
 * <p>Pipeline: Stein GFX only.
 */
final class SteinPoseGpu {

    // ---- input format (the same constants as pose.comp) ----

    static final int H_COUNT = 0;
    static final int H_PLAYERS = 1;
    static final int H_STATES = 2;
    static final int H_ENTRIES = 3;
    static final int H_BLOCKS = 4;
    static final int H_MODELS = 5;
    static final int H_MASK = 6;
    static final int H_SCALE = 7;
    static final int H_SHIFT = 8;

    /**
     * Always 0: the shader XORs with it so the driver does not fold computations across a point
     * (pose.comp, opaque).
     */
    static final int H_ZERO = 9;

    static final int HEADER_WORDS = 16;

    /**
     * Player: root (3 rows, 12), netHeadYaw, pitch, age, swing, step, hit, model bits, models base;
     * for the held item ({@link SteinHeldItem}): the root without the sneak (12), translate, rotate
     * and scale of the third-person transform (9) and the IF_* bits; 2 unused.
     */
    static final int PLAYER_WORDS = 44;

    /**
     * State: player, block, offset in the block, first entry, first instance, and for the held item
     * the first vertex and the vertices in the fixed geometry and 1 (otherwise 0, 0, 0); the rest
     * (20).
     */
    static final int STATE_WORDS = 28;

    /**
     * Part of a model: offset (3), first vertex, rotation point (3), vertices, angles (3), unused.
     */
    static final int PART_WORDS = 12;

    static final int MODEL_PARTS = 16;
    private static final int GROUP = 64;

    /** Entries of the sine table (the game's MathHelper table). */
    private static final long SIN_TABLE_ENTRIES = 65536;

    /** Words of slack kept in the room estimate for the block bases and the other small tables. */
    private static final int ROOM_PAD_WORDS = 256;

    /** Model slots whose parts the input has room for. */
    private static final long MAX_MODEL_SLOTS = 64;

    /**
     * One region of the input ring: it fits the pose of a whole region of the instance ring with
     * room to spare.
     */
    private static final long INPUT_REGION = 4L << 20;

    private static final int REGIONS = 3;

    /** Alignment of the storage offset (the largest that OpenGL and Vulkan may require). */
    private static final int STORAGE_ALIGN = 256;

    private static boolean initDone;
    private static boolean ready;
    private static SteinPipeline pipeline;
    private static SteinBuffer input;
    private static SteinBuffer table;
    private static float[] tableLoaded;

    // CPU scratch (native memory, grows): players, states and entries until the next flush.
    private static long players;
    private static int playerCap;
    private static int playerN;
    private static long states;
    private static int stateCap;
    private static int stateN;
    private static long entries;
    private static int entryCap;
    private static int entryN;

    /**
     * Where this flush's input ended up in the ring ({@link #prepare}); -1 = nothing to dispatch.
     */
    private static long inputAt = -1;

    private static long inputBytes;

    private SteinPoseGpu() {}

    /**
     * Turns on at the first call: requires compute, indirect drawing, a persistent buffer and the
     * compiled shader.
     */
    static boolean ready() {
        if (initDone) {
            return ready;
        }
        initDone = true;
        SteinDevice dev = SteinDevice.get();
        SteinCaps caps = dev.caps();
        if (!caps.compute || !caps.multiDrawIndirect || !caps.persistentMapping) {
            return disable("the device lacks compute/indirect/persistent mapping");
        }
        try {
            SteinShader cs = SteinShader.load(SteinShader.Stage.COMPUTE, "pose.comp");
            pipeline =
                    dev.createPipeline(
                            SteinPipeline.builder("player pose")
                                    .compute(cs)
                                    .storage("PoseIn", 0)
                                    .storage("PoseSin", 1)
                                    .storage("PoseInstances", 2)
                                    .storage("PoseCommands", 3)
                                    .build());
            if (!pipeline.valid()) {
                return disable("the shader did not compile");
            }
            input =
                    dev.createBuffer(
                                    new SteinBuffer.Desc(
                                            "pose: input",
                                            INPUT_REGION * REGIONS,
                                            SteinBuffer.Memory.UPLOAD,
                                            SteinBuffer.Usage.STORAGE))
                            .ring(REGIONS);
            if (!input.mapped()) {
                return disable("the input buffer is not mapped");
            }
            table =
                    dev.createBuffer(
                            new SteinBuffer.Desc(
                                    "pose: sine table",
                                    SIN_TABLE_ENTRIES * Float.BYTES,
                                    SteinBuffer.Memory.GPU,
                                    SteinBuffer.Usage.STORAGE));
        } catch (RuntimeException e) {
            return disable(String.valueOf(e));
        }
        ready = true;
        Log.info("gfx", "player pose on the GPU: matrices, instances and commands in compute");
        return true;
    }

    /** Logs why the GPU pose stays off (the pose remains on the CPU) and returns false. */
    private static boolean disable(String reason) {
        Log.info("gfx", "GPU pose off: " + reason + " (the pose stays on the CPU)");
        return false;
    }

    // ---- records (game thread, in SteinPlayerPose's dump) ----

    /** Is there pose waiting for the next flush. */
    static boolean pending() {
        return entryN > 0;
    }

    /**
     * Does one more player with {@code stateCount} states and {@code entryCount} parts fit without
     * going past half a region of the input? (otherwise the dump flushes first, as when the
     * instance region fills up)
     */
    static boolean room(int stateCount, int entryCount) {
        long words =
                HEADER_WORDS
                        + ROOM_PAD_WORDS
                        + MAX_MODEL_SLOTS * MODEL_PARTS * PART_WORDS
                        + (long) (playerN + 1) * PLAYER_WORDS
                        + (long) (stateN + stateCount) * STATE_WORDS
                        + entryN
                        + entryCount;
        return words * 4 <= INPUT_REGION / 2;
    }

    /**
     * A player; returns its index. {@code root}: the game's matrix (16, column-major); only rows
     * 0..2 are uploaded.
     */
    static int player(
            float[] root,
            float netHeadYaw,
            float pitch,
            float age,
            float amount,
            float limbSwing,
            float swing,
            int flags,
            int modelBase,
            float[] root0,
            float[] xform,
            int itemFlags) {
        if (playerN == playerCap) {
            playerCap = Math.max(256, playerCap * 2);
            players = SteinNativeBuffer.grow(players, (long) playerCap * PLAYER_WORDS * 4);
        }
        long p = players + (long) playerN * PLAYER_WORDS * 4;
        for (int r = 0; r < 3; r++) {
            MemoryUtil.memPutFloat(p, root[r]);
            MemoryUtil.memPutFloat(p + 4, root[4 + r]);
            MemoryUtil.memPutFloat(p + 8, root[8 + r]);
            MemoryUtil.memPutFloat(p + 12, root[12 + r]);
            p += 16;
        }
        MemoryUtil.memPutFloat(p, netHeadYaw);
        MemoryUtil.memPutFloat(p + 4, pitch);
        MemoryUtil.memPutFloat(p + 8, age);
        MemoryUtil.memPutFloat(p + 12, amount);
        MemoryUtil.memPutFloat(p + 16, limbSwing);
        MemoryUtil.memPutFloat(p + 20, swing);
        MemoryUtil.memPutInt(p + 24, flags);
        MemoryUtil.memPutInt(p + 28, modelBase);
        p += 32;
        if (root0 == null) {
            MemoryUtil.memSet(p, 0, (PLAYER_WORDS - 20) * 4L);
            return playerN++;
        }
        for (int r = 0; r < 3; r++) {
            MemoryUtil.memPutFloat(p, root0[r]);
            MemoryUtil.memPutFloat(p + 4, root0[4 + r]);
            MemoryUtil.memPutFloat(p + 8, root0[8 + r]);
            MemoryUtil.memPutFloat(p + 12, root0[12 + r]);
            p += 16;
        }
        for (int i = 0; i < 9; i++) {
            MemoryUtil.memPutFloat(p + i * 4L, xform[i]);
        }
        MemoryUtil.memPutInt(p + 36, itemFlags);
        MemoryUtil.memPutInt(p + 40, 0);
        MemoryUtil.memPutInt(p + 44, 0);
        return playerN++;
    }

    /**
     * A state of player {@code player}: the commands at {@code slot} (block << 32 | offset, from
     * {@link SteinEntityDeferred#poseSlots}), the instances starting at {@code instStart}, the rest
     * at {@code rest[restAt..restAt+19]}. Its entries come right after ({@link #entry}). Returns
     * the index.
     */
    static int state(
            int player,
            long slot,
            int instStart,
            float[] rest,
            int restAt,
            int itemFirst,
            int itemVerts) {
        if (stateN == stateCap) {
            stateCap = Math.max(1024, stateCap * 2);
            states = SteinNativeBuffer.grow(states, (long) stateCap * STATE_WORDS * 4);
        }
        long p = states + (long) stateN * STATE_WORDS * 4;
        MemoryUtil.memPutInt(p, player);
        MemoryUtil.memPutInt(p + 4, (int) (slot >>> 32));
        MemoryUtil.memPutInt(p + 8, (int) slot);
        MemoryUtil.memPutInt(p + 12, entryN);
        MemoryUtil.memPutInt(p + 16, instStart);
        MemoryUtil.memPutInt(p + 20, itemFirst < 0 ? 0 : itemFirst);
        MemoryUtil.memPutInt(p + 24, itemFirst < 0 ? 0 : itemVerts);
        MemoryUtil.memPutInt(p + 28, itemFirst < 0 ? 0 : 1);
        p += 32;
        for (int i = 0; i < 20; i++) {
            MemoryUtil.memPutFloat(p + i * 4L, rest[restAt + i]);
        }
        return stateN++;
    }

    /** A part of state {@code state}: {@code modelPart} = model * 16 + part. */
    static void entry(int state, int modelPart) {
        if (entryN == entryCap) {
            entryCap = Math.max(4096, entryCap * 2);
            entries = SteinNativeBuffer.grow(entries, (long) entryCap * 4);
        }
        MemoryUtil.memPutInt(entries + (long) entryN * 4, state << 6 | modelPart);
        entryN++;
    }

    // ---- flush (SteinEntityDeferred.flush) ----

    /**
     * Before opening the pass, with the groups' commands already written (the block bases known):
     * uploads the sine table if it changed and writes the whole input to the ring. false = it did
     * not fit (the caller empties the blocks).
     */
    static boolean prepare() {
        inputAt = -1;
        if (entryN == 0) {
            return false;
        }
        float[] t = AngleTable.table();
        if (t != tableLoaded) {
            table.upload(0, t, t.length);
            tableLoaded = t;
        }
        int blocks = SteinEntityDeferred.poseBlockCount();
        int models = SteinPlayerPose.gpuModelSlots();
        long blockAt = HEADER_WORDS;
        long modelAt = blockAt + ((blocks + 3) & ~3);
        long playerAt = modelAt + (long) models * MODEL_PARTS * PART_WORDS;
        long stateAt = playerAt + (long) playerN * PLAYER_WORDS;
        long entryAt = stateAt + (long) stateN * STATE_WORDS;
        long bytes = (entryAt + entryN) * 4;
        long at = input.reserve(bytes, STORAGE_ALIGN);
        if (at == -2) {
            input.nextRegion();
            at = input.reserve(bytes, STORAGE_ALIGN);
        }
        if (at < 0) {
            // Does not fit (does not happen with the room limit): this batch's players do not
            // come out in this frame.
            Log.info("gfx", "GPU pose: input of " + bytes + " bytes did not fit in the ring");
            return false;
        }
        long p = input.address(at);
        MemoryUtil.memPutInt(p + H_COUNT * 4L, entryN);
        MemoryUtil.memPutInt(p + H_PLAYERS * 4L, (int) playerAt);
        MemoryUtil.memPutInt(p + H_STATES * 4L, (int) stateAt);
        MemoryUtil.memPutInt(p + H_ENTRIES * 4L, (int) entryAt);
        MemoryUtil.memPutInt(p + H_BLOCKS * 4L, (int) blockAt);
        MemoryUtil.memPutInt(p + H_MODELS * 4L, (int) modelAt);
        boolean compact = t.length != SIN_TABLE_ENTRIES;
        MemoryUtil.memPutInt(p + H_MASK * 4L, t.length - 1);
        // the AngleTable/MathHelper constants: 65536 / 2pi and a quarter turn (or those of the
        // compact table)
        MemoryUtil.memPutFloat(p + H_SCALE * 4L, compact ? 651.8986F : 10430.378F);
        MemoryUtil.memPutFloat(p + H_SHIFT * 4L, compact ? 1024.0F : 16384.0F);
        MemoryUtil.memPutInt(p + H_ZERO * 4L, 0);
        for (int i = 0; i < blocks; i++) {
            MemoryUtil.memPutInt(p + (blockAt + i) * 4, SteinEntityDeferred.poseBlockAt(i));
        }
        SteinPlayerPose.gpuWriteModels(p + modelAt * 4);
        MemoryUtil.memCopy(players, p + playerAt * 4, (long) playerN * PLAYER_WORDS * 4);
        MemoryUtil.memCopy(states, p + stateAt * 4, (long) stateN * STATE_WORDS * 4);
        MemoryUtil.memCopy(entries, p + entryAt * 4, (long) entryN * 4);
        input.commit(at, bytes);
        inputAt = at;
        inputBytes = bytes;
        return true;
    }

    /**
     * At the start of the pass, before any draw: the compute shader writes the instances and the
     * commands. The dispatch contract inserts the barrier for the draws that read both.
     */
    static void dispatch(SteinCommands c, SteinBuffer instances, SteinBuffer commands) {
        if (inputAt < 0) {
            return;
        }
        c.bindPipeline(pipeline);
        c.bindStorage(0, input, inputAt, inputBytes);
        c.bindStorage(1, table, 0, table.size());
        c.bindStorage(2, instances, 0, instances.size());
        c.bindStorage(3, commands, 0, commands.size());
        c.dispatch((entryN + GROUP - 1) / GROUP, 1, 1);
        dispatches++;
        parts += entryN;
    }

    /**
     * After the pass: the check (if on), and the scratch zeroed; the input region is closed with a
     * fence.
     */
    static void afterFlush(SteinBuffer instances, SteinBuffer commands) {
        if (entryN == 0) {
            return;
        }
        try {
            SteinPlayerPose.gpuCheck(instances, commands, inputAt >= 0);
        } finally {
            playerN = 0;
            stateN = 0;
            entryN = 0;
            inputAt = -1;
            input.nextRegion();
        }
    }

    // ---- numbers (SteinPlayerPose.stats) ----

    private static long dispatches;
    private static long parts;

    /** "N dispatches, M parts" since the last time, and resets. */
    static String takeStats() {
        String s = dispatches + " dispatches, " + parts + " parts";
        dispatches = 0;
        parts = 0;
        return s;
    }
}
