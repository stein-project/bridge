/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import static dev.xavier.stein.gfx.SteinDeferredKeys.*;
import static dev.xavier.stein.gfx.SteinPlayerPoseJobs.*;

import java.util.Arrays;

/**
 * The order in which the dump of {@link SteinPlayerPose} hands the players to the groups when the
 * pose runs on the GPU: nearest first for the opaque states, the game's order for the blended ones.
 *
 * <p>Why: every model part is its own indirect draw, in the order the players entered the list (the
 * world's entity list, unrelated to the camera). In a crowd the far players' pixels are shaded and
 * then covered by the near ones; nearest first, the GPU's early depth test (and its coarse Hi-Z)
 * rejects them before the fragment shader runs. With {@link #ARMOR_FIRST} the armor groups also go
 * before the body group, so the skin under a chestplate is rejected the same way.
 *
 * <p>Why the pixels stay the same: a state without blending, with depth test LESS or LEQUAL and
 * depth writes, keeps per pixel the nearest fragment whatever the order; the order only decides
 * between fragments at exactly the same depth. Those come from geometry drawn twice on the same
 * surface (the leather overlay over the leather, with its own texture): when that geometry ends up
 * in two different groups, their order would decide which one shows, so such a dump keeps the
 * game's order ({@link #nearFirst} refuses it). Each player's commands keep their plan order inside
 * each group, and the blended states (glint, held item, translucent body), where order does change
 * the result, are handed over afterwards in the game's order. What remains is two different players
 * at exactly the same depth on the same pixel (players standing inside each other), where the
 * nearer-by-position one now wins the tie.
 *
 * <p>Only with the pose on the GPU and without its check (which keeps one reference pose at a
 * time). Off by default (no gain measured on AMD): {@code -Dsteinbridge.gfx.entityNearFirst=true}
 * turns it on, {@code entityArmorFirst=false} keeps the armor in the game's order.
 *
 * <p>Pipeline: Stein GFX only.
 */
final class SteinPlayerPoseOrder {
    private SteinPlayerPoseOrder() {}

    static final boolean NEAR_FIRST = SteinGfxOptions.ENTITY_NEAR_FIRST;
    static final boolean ARMOR_FIRST = NEAR_FIRST && SteinGfxOptions.ENTITY_ARMOR_FIRST;

    /** Which states {@link SteinPlayerPose}'s GPU record hands over in one call. */
    static final int ALL = 0;

    static final int OPAQUE = 1;
    static final int BLENDED = 2;

    /** The dump's jobs, nearest first (valid after {@link #nearFirst} returned true). */
    static int[] order = new int[256];

    /** Per job: its player record on the GPU (-1: reading it failed) and its first instance. */
    static int[] player = new int[256];

    static int[] first = new int[256];

    /** Per job: its blended state records and their parts (the room they will take later). */
    static int[] blendedStates = new int[256];

    static int[] blendedEntries = new int[256];

    private static long[] sortKeys = new long[256];
    private static int[] chunk = new int[256];

    /**
     * Per plan part (model * 16 + part, or the held item): the opaque key it had, in this player.
     */
    private static final long[] PART_KEY = new long[ITEM_PART + 16];

    private static final boolean[] PART_SEEN = new boolean[ITEM_PART + 16];

    /**
     * The group's key goes in the blended pass: blending, or a logic operation, which reads the
     * destination too (as {@link SteinDeferredGroups.Group}).
     */
    static boolean blended(long key) {
        return keyBlend((int) key) || (key & KEY_LOGIC) != 0;
    }

    /**
     * An opaque state whose result does not depend on order: depth test LESS or LEQUAL with depth
     * writes (the nearest fragment stays).
     */
    private static boolean depthResolved(long key) {
        int k = (int) key;
        if (!keyDepthTest(k) || !keyDepthMask(k)) {
            return false;
        }
        SteinPipeline.Depth f = keyDepthFunc(k);
        return f == SteinPipeline.Depth.LESS || f == SteinPipeline.Depth.LEQUAL;
    }

    /**
     * Decides the order of the {@code n} jobs: true with {@link #order} filled (nearest first;
     * equal distances keep the game's order); false keeps the game's order (a state that depends on
     * it, or a surface drawn twice in two groups).
     */
    static boolean nearFirst(int n) {
        if (!NEAR_FIRST || n < 2) {
            return false;
        }
        if (order.length < n) {
            int c = Math.max(n, order.length * 2);
            order = new int[c];
            player = new int[c];
            first = new int[c];
            blendedStates = new int[c];
            blendedEntries = new int[c];
            sortKeys = new long[c];
            chunk = new int[c];
        }
        for (int j = 0; j < n; j++) {
            if (!independent(j)) {
                return false;
            }
        }
        long[] s = sortKeys;
        for (int j = 0; j < n; j++) {
            float x = jPos[j * 3];
            float y = jPos[j * 3 + 1];
            float z = jPos[j * 3 + 2];
            float d = x * x + y * y + z * z;
            // a non-negative float's bits sort like the float; NaN ends up last
            int bits = d >= 0f ? Float.floatToIntBits(d) : Integer.MAX_VALUE;
            s[j] = (long) bits << 32 | j;
        }
        Arrays.sort(s, 0, n);
        for (int j = 0; j < n; j++) {
            order[j] = (int) s[j];
            player[j] = -1;
        }
        return true;
    }

    /** Job {@code j}'s opaque states may be reordered (see the class comment). */
    private static boolean independent(int j) {
        int at = jPlanAt[j];
        int len = jPlanLen[j];
        int s0 = j * STATES;
        Arrays.fill(PART_SEEN, false);
        int states = 0;
        int entries = 0;
        for (int k = 0; k < len; k++) {
            int part = plan[(at + k) * 2];
            int st = plan[(at + k) * 2 + 1];
            long key = jKey[s0 + st];
            if (blended(key)) {
                // a new state record at each sequence of the same state (as gpuPlayer cuts them)
                if (k == 0 || plan[(at + k - 1) * 2 + 1] != st) {
                    states++;
                }
                entries++;
                continue;
            }
            if (!depthResolved(key) || part < 0 || part >= PART_KEY.length) {
                return false;
            }
            if (PART_SEEN[part] && PART_KEY[part] != key) {
                return false; // the same surface twice, in two groups: their order decides
            }
            PART_SEEN[part] = true;
            PART_KEY[part] = key;
        }
        blendedStates[j] = states;
        blendedEntries[j] = entries;
        return true;
    }

    /**
     * The first player of a flush: its opaque groups enter the order from the last to appear to the
     * first (armor and overlay before the body), before any of its commands.
     */
    static void enlistReversed(int j) {
        if (!ARMOR_FIRST) {
            return;
        }
        int at = jPlanAt[j];
        int len = jPlanLen[j];
        int s0 = j * STATES;
        for (int k = len - 1; k >= 0; k--) {
            long key = jKey[s0 + plan[(at + k) * 2 + 1]];
            if (!blended(key)) {
                SteinDeferredGroups.enlist(key);
            }
        }
    }

    /**
     * The jobs at positions {@code [from, to)} of {@link #order}, back in the game's order (for
     * their blended states), in the first {@code to - from} entries of the returned array.
     */
    static int[] gameOrder(int from, int to) {
        int c = to - from;
        System.arraycopy(order, from, chunk, 0, c);
        Arrays.sort(chunk, 0, c);
        return chunk;
    }
}
