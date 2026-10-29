/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import dev.xavier.stein.bridge.core.Log;

import net.minecraft.profiler.Profiler;
import net.minecraft.world.ChunkCoordIntPair;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Keeps the client world's active-chunk set from one tick to the next when it would be rebuilt with
 * the same chunks in the same order, and then skips WorldClient.updateBlocks' retainAll, which
 * would remove nothing. See {@link ActiveChunks} for the fill itself.
 *
 * <p>Every tick the game empties World.activeChunkSet and inserts the squares around the players
 * again: on the client about a thousand new ChunkCoordIntPair and HashMap nodes per tick (a quarter
 * of everything the tick allocated, in JFR) and a thousand hashes; then updateBlocks runs
 * previousActiveChunkSet.retainAll(activeChunkSet), another thousand lookups. Standing still, or
 * with the crowd in the same chunks, all of it produces the set it already had.
 *
 * <p>Why it is exact: the fill computes, without allocating, the sequence of chunks it would insert
 * (each chunk at its first appearance, the order of the inserts). When that sequence is the one the
 * set was last built from, and the set is the same object with the same size (nothing else touched
 * it), clearing and inserting the same keys in the same order into the same table would rebuild the
 * very same buckets: the set already is the result, iteration order included (except for a tree
 * bin, whose order the game itself does not keep between two rebuilds; see {@link ActiveChunks}).
 * Then previousActiveChunkSet, which only ever holds chunks of the set as it was on the previous
 * tick (updateBlocks adds only from it, after its own retainAll), is already inside it: retainAll
 * would remove nothing and return false. The skip needs the previous tick's retainAll to have run
 * on this same pair of sets, right after the previous build.
 *
 * <p>Only the client world, on the client thread (the only one that ticks it), uses this; the
 * buffers are reused. {@code -Dsteinbridge.opt.vanillaActiveReuse=true}: rebuilds every tick.
 * {@code -Dsteinbridge.opt.checkActiveReuse=true}: rebuilds a kept set anyway and compares the two
 * iteration orders, and runs every skipped retainAll to check that it removed nothing.
 */
public final class ActiveReuse {

    static final boolean ENABLED =
            !EngineOptions.VANILLA_ACTIVE_REUSE
                    && !EngineOptions.VANILLA_ACTIVE_CHUNKS
                    && !EngineOptions.VANILLA_ACTIVE_CHUNK_FILL
                    && !EngineOptions.CHECK_ACTIVE_CHUNKS;

    private static final boolean CHECK = ENABLED && EngineOptions.CHECK_ACTIVE_REUSE;

    /** The insert sequence being computed, and the one the set was last built from. */
    private static int[] seqX = new int[1024];

    private static int[] seqZ = new int[1024];
    private static int[] lastX = new int[1024];
    private static int[] lastZ = new int[1024];
    private static int lastCount = -1;

    private static long[] seen = new long[64];

    /** The set built last and its size then; valid only while {@link #valid}. */
    private static Set<?> lastSet;

    private static int lastSize;
    private static boolean valid;

    /** Builds of the client set so far; the set kept at which build; the last retainAll. */
    private static int builds;

    private static Set<?> keptSet;
    private static int keptBuild;
    private static Object retainPrevious;
    private static int retainBuild = Integer.MIN_VALUE;

    private static final CheckReport REPORT = new CheckReport(5_000_000_000L);

    static {
        if (!ENABLED && !EngineOptions.VANILLA_ACTIVE_CHUNKS) {
            Log.info("activechunks", "client active-chunk set rebuilt every tick");
        } else if (CHECK) {
            Log.info("activechunks", "check: kept active-chunk sets rebuilt and compared");
        }
    }

    private ActiveReuse() {}

    /**
     * At the start of every build of the client world's set: whether the last build left a set this
     * one may keep. Whatever runs instead of {@link #fill} leaves it invalid.
     */
    static void begin() {
        builds++;
        keptSet = null;
        // consumed by fill; if another path runs instead, it rebuilds the set and the next build
        // finds valid false
        pending = valid;
        valid = false;
    }

    /** {@link #begin}'s answer, for this build only. */
    private static boolean pending;

    /**
     * The insert loop of {@link ActiveChunks#fill} for the client world: computes the sequence,
     * keeps the set when it is the last one, otherwise clears and inserts as the game does.
     */
    @SuppressWarnings("unchecked")
    static void fill(
            Set<ChunkCoordIntPair> set,
            Profiler profiler,
            int[] cx,
            int[] cz,
            int n,
            int r,
            int x0,
            int z0,
            int d,
            int cells) {
        boolean mayKeep = pending;
        pending = false;
        int words = (cells + 63) >>> 6;
        if (seen.length < words) {
            seen = new long[Math.max(words, seen.length * 2)];
        } else {
            Arrays.fill(seen, 0, words, 0L);
        }
        // the clear comes before the section, as in the game; it is decided after the walk
        int m = walk(cx, cz, n, r, x0, z0, d);
        boolean keep =
                mayKeep
                        && set == lastSet
                        && set.size() == lastSize
                        && m == lastCount
                        && Arrays.equals(seqX, 0, m, lastX, 0, m)
                        && Arrays.equals(seqZ, 0, m, lastZ, 0, m);
        List<Object> before = keep && CHECK ? new ArrayList<>(set) : null;
        if (!keep || CHECK) {
            set.clear();
        }
        profiler.startSection("buildList");
        if (!keep || CHECK) {
            for (int k = 0; k < m; k++) {
                set.add(new ChunkCoordIntPair(seqX[k], seqZ[k]));
            }
        }
        profiler.endSection();
        if (before != null) {
            boolean same = before.equals(new ArrayList<>(set));
            if (REPORT.compared(same)) {
                Log.info(
                        "activechunks",
                        "DIVERGENCE: kept set of %d chunks differs from the rebuilt one (%d)",
                        before.size(),
                        set.size());
            }
            REPORT.maybeReport("activechunks-reuse");
        }
        if (keep) {
            keptSet = set;
            keptBuild = builds;
        } else {
            int[] t = lastX;
            lastX = seqX;
            seqX = t;
            t = lastZ;
            lastZ = seqZ;
            seqZ = t;
            lastCount = m;
        }
        lastSet = set;
        lastSize = set.size();
        valid = true;
    }

    /**
     * The squares of the players, x outside and z inside as in the game, each chunk recorded at its
     * first appearance. Returns how many.
     */
    private static int walk(int[] cx, int[] cz, int n, int r, int x0, int z0, int d) {
        long[] bits = seen;
        int m = 0;
        for (int i = 0; i < n; i++) {
            int px = cx[i];
            int pz = cz[i];
            for (int dx = -r; dx <= r; dx++) {
                int x = dx + px;
                int column = (x - x0) * d;
                for (int dz = -r; dz <= r; dz++) {
                    int z = dz + pz;
                    int cell = column + (z - z0);
                    long bit = 1L << cell;
                    if ((bits[cell >>> 6] & bit) == 0) {
                        bits[cell >>> 6] |= bit;
                        if (m == seqX.length) {
                            grow();
                        }
                        seqX[m] = x;
                        seqZ[m] = z;
                        m++;
                    }
                }
            }
        }
        return m;
    }

    private static void grow() {
        int size = seqX.length * 2;
        seqX = Arrays.copyOf(seqX, size);
        seqZ = Arrays.copyOf(seqZ, size);
        lastX = Arrays.copyOf(lastX, Math.max(size, lastX.length));
        lastZ = Arrays.copyOf(lastZ, Math.max(size, lastZ.length));
    }

    /**
     * In place of previousActiveChunkSet.retainAll(activeChunkSet) in WorldClient.updateBlocks:
     * nothing to remove when the active set was kept and the last retainAll ran on the same sets
     * right after the previous build.
     */
    @SuppressWarnings("unchecked")
    public static boolean retainAll(Object previous, Object active) {
        Set<Object> prev = (Set<Object>) previous;
        boolean skip =
                ENABLED
                        && active == keptSet
                        && keptBuild == builds
                        && previous == retainPrevious
                        && retainBuild == builds - 1;
        retainPrevious = previous;
        retainBuild = builds;
        if (!skip) {
            return prev.retainAll((Collection<Object>) active);
        }
        if (CHECK) {
            boolean removed = prev.retainAll((Collection<Object>) active);
            if (REPORT.compared(!removed)) {
                Log.info("activechunks", "DIVERGENCE: a skipped retainAll removed chunks");
            }
        }
        return false;
    }
}
