/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import java.util.Arrays;

/**
 * The frame's player list for {@link SteinPlayerPose}: one entry per player taken by the pose,
 * stored as parallel primitive arrays (no allocation per frame), plus the plan of instances and the
 * constants that describe how a job is laid out (states, held-item mode, corpse kind, state bits,
 * layer bits).
 *
 * <p>Pipeline: Stein GFX only.
 */
final class SteinPlayerPoseJobs {
    private SteinPlayerPoseJobs() {}

    // ---- each player's states (the instance's "rest": 20 floats) ----

    static final int ST_BODY = 0;
    static final int ST_ARMOR = 1; // 1 + 2 * piece: base; + 1: leather overlay
    static final int ST_GLINT = 9; // + pass

    /** The held item ({@link SteinHeldItem}): + draw (the model and the two glint passes). */
    static final int ST_ITEM = 11;

    static final int STATES = 14;

    /**
     * The held item's "part" in the plan: model 3 (pose.comp's kind 3), part 0; the matrix is the
     * arm's.
     */
    static final int ITEM_PART = 3 * 16;

    /**
     * This player's held item ({@link #jHeld}): by the game's layer; by the pose; by the game,
     * recording the template.
     */
    static final int H_GAME = 0;

    static final int H_OURS = 1;
    static final int H_RECORD = 2;
    static final int REST = 20;
    static final int MAX_PLAN = 64;

    // ---- the frame's player list (array structure, no allocation per frame) ----

    static int jobs;
    static Object[] jEntity = new Object[256];
    static int[] jInfo = new int[256];
    static float[] jBase = new float[256 * 16];
    static float[] jPos = new float[256 * 3];
    static float[] jPt = new float[256];

    /**
     * Sneaking, item in left/right hand (bbj.l/m), bow (bbj.o): what setModelVisibilities puts on
     * the model.
     */
    static int[] jModel = new int[256 * 4];

    /** Bits: which models the pose needs (M_PLAYER, M_ARMOR, M_LEGS). */
    static int[] jModels = new int[256];

    /**
     * Bits of the game layers this player draws (1 << index in the RenderPlayer list); 0 = none.
     */
    static int[] jLayers = new int[256];

    /**
     * How rotateCorpse turns (C_*), with up to two numbers in jCorpseF; and the living mount (null
     * = none).
     */
    static int[] jCorpse = new int[256];

    static float[] jCorpseF = new float[256 * 2];
    static Object[] jMount = new Object[256];

    /** S_* bits: the player's states (the render's sneak, riding, and for the per-state check). */
    static int[] jStates = new int[256];

    static int[] jPlanAt = new int[256];
    static int[] jPlanLen = new int[256];
    static long[] jAddr = new long[256];
    static float[] jState = new float[256 * STATES * REST];

    /**
     * The key of each state's group (long: the held item's carries the game's texture in the upper
     * bits).
     */
    static long[] jKey = new long[256 * STATES];

    /**
     * The held item: H_GAME, H_OURS or H_RECORD; its template; the IF_* bits of {@link
     * SteinHeldItem}.
     */
    static int[] jHeld = new int[256];

    static SteinHeldItem.Entry[] jHeldEntry = new SteinHeldItem.Entry[256];
    static int[] jItemFlags = new int[256];

    /** Per instance: part (model * 16 + part) and state. */
    static int[] plan = new int[256 * MAX_PLAN * 2];

    static int planN;

    static void grow() {
        int n = jEntity.length * 2;
        jEntity = Arrays.copyOf(jEntity, n);
        jInfo = Arrays.copyOf(jInfo, n);
        jBase = Arrays.copyOf(jBase, n * 16);
        jPos = Arrays.copyOf(jPos, n * 3);
        jPt = Arrays.copyOf(jPt, n);
        jModel = Arrays.copyOf(jModel, n * 4);
        jModels = Arrays.copyOf(jModels, n);
        jLayers = Arrays.copyOf(jLayers, n);
        jCorpse = Arrays.copyOf(jCorpse, n);
        jCorpseF = Arrays.copyOf(jCorpseF, n * 2);
        jMount = Arrays.copyOf(jMount, n);
        jStates = Arrays.copyOf(jStates, n);
        jPlanAt = Arrays.copyOf(jPlanAt, n);
        jPlanLen = Arrays.copyOf(jPlanLen, n);
        jAddr = Arrays.copyOf(jAddr, n);
        jState = Arrays.copyOf(jState, n * STATES * REST);
        jKey = Arrays.copyOf(jKey, n * STATES);
        jHeld = Arrays.copyOf(jHeld, n);
        jHeldEntry = Arrays.copyOf(jHeldEntry, n);
        jItemFlags = Arrays.copyOf(jItemFlags, n);
        plan = Arrays.copyOf(plan, n * MAX_PLAN * 2);
    }

    static void clearJobs() {
        Arrays.fill(jEntity, 0, jobs, null);
        Arrays.fill(jMount, 0, jobs, null);
        Arrays.fill(jHeldEntry, 0, jobs, null);
        jobs = 0;
        planN = 0;
    }

    /** rotateCorpse: standing; dying (Z spin); upside down (Dinnerbone/Grumm); sleeping (bed). */
    static final int C_NORMAL = 0;

    static final int C_DEATH = 1;
    static final int C_FLIP = 2;
    static final int C_SLEEP = 3;

    /** Bits of {@link #jStates}. */
    static final int S_SNEAK = 1;

    static final int S_RIDING = 1 << 1;
    static final int S_SPECTATOR = 1 << 2;
    static final int S_TRANSLUCENT = 1 << 3;
    static final int S_HIDDEN = 1 << 4;
    static final int S_SLEEP = 1 << 5;
    static final int S_DEATH = 1 << 6;
    static final int S_FLIP = 1 << 7;

    /**
     * Bits of {@link #jLayers}: 1 << index of the layer in the RenderPlayer list (0 is the armor,
     * which is ours).
     */
    static final int L_HELD = 1 << 1;

    static final int L_ARROW = 1 << 2;
    static final int L_EARS = 1 << 3;
    static final int L_CAPE = 1 << 4;
    static final int L_HEAD = 1 << 5;
}
