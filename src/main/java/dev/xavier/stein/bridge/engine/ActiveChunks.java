/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import dev.xavier.stein.bridge.core.Log;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.profiler.Profiler;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Deduplica World.setActivePlayerChunksAndCheckLight.
 *
 * <p>The original walks ALL the players in the world and, for each one, inserts into a set the
 * (2r+1)^2 chunks within view distance — on the client this includes every remote player. With 400
 * players and distance 10 that is ~176 thousand allocations and insertions per tick, and JFR showed
 * the method at 6% of the main thread's time and 22% of a 360ms spike when the crowd arrived.
 *
 * <p>The square depends only on the chunk the player is in; players in the same chunk produce the
 * same square. Here the original method runs with ONE representative per distinct chunk and the
 * list is restored afterwards: the final set is identical. The only difference is the ambient light
 * check, which picks a random player from the list — it now picks among the representatives, which
 * does not change the outcome of any check.
 *
 * <p>The representatives' squares still overlap: 400 players fighting 5 blocks apart sit in about
 * 30 chunks, and at distance 12 that is ~19 thousand insertions (each a new ChunkCoordIntPair, a
 * hash and an equals) for ~900 distinct chunks. So the method's body is done here too ({@link
 * #fill}): the squares are marked on a bitmap of the area they cover, and only a chunk seen for the
 * first time is inserted. The set receives the same new elements in the same order as in the
 * original (an insertion of an element already present changes nothing in a HashSet), so it ends
 * with the same elements in the same iteration order. One caveat, the game's own: when 8 or more
 * chunks fall in one hash bin (ChunkCoordIntPair's hash collides easily) the HashMap turns the bin
 * into a tree ordered by identity hash codes, and then the iteration order differs between two runs
 * of the original itself (about 3% of random layouts in a test, and the same share between the
 * original and this fill); without such a bin the order is equal. The rest of the method (the
 * ambient countdown and the light check, with the same random draws) is copied as it is. When the
 * area is too large for the bitmap (players spread over thousands of blocks) the original runs.
 *
 * <p>On the integrated server the set then leaves out the chunks {@link LazyChunks} has not loaded
 * yet: the game's updateBlocks would load (and generate) each one on the spot.
 *
 * <p>The patcher redirects the call in World.updateBlocks to here. {@code
 * -Dsteinbridge.opt.vanillaActiveChunks=true} turns it all off, for A/B; {@code
 * -Dsteinbridge.opt.vanillaActiveChunkFill=true} keeps the representatives but lets the original
 * insert their squares. {@code -Dsteinbridge.opt.checkActiveChunks=true} runs both paths every tick
 * and compares the sets and the ambient countdown, to prove the equivalence in the real game; it
 * also counts the ticks whose iteration order differed (expected only with a tree bin, see above).
 */
public final class ActiveChunks {

    private static final boolean VANILLA = EngineOptions.VANILLA_ACTIVE_CHUNKS;
    private static final boolean VANILLA_FILL = EngineOptions.VANILLA_ACTIVE_CHUNK_FILL;
    private static final boolean CHECK = EngineOptions.CHECK_ACTIVE_CHUNKS;

    /**
     * The largest area, in chunks, marked on the bitmap (64 KB of bits); beyond it the original
     * runs. A crowd in one place needs about (spread + 2r + 1)^2, ~1 thousand at distance 12.
     */
    private static final long MAX_CELLS = 1L << 19;

    private static long checks;
    private static long mismatches;
    private static long orderDiffs;

    private ActiveChunks() {}

    public static void setActivePlayerChunksAndCheckLight(Object worldObj) {
        World world = (World) worldObj;
        build(world);
        // integrated server: chunks still waiting for the spread loading are not ticked yet
        LazyChunks.trimActive(world);
    }

    @SuppressWarnings("unchecked")
    private static void build(World world) {
        List<Object> players = (List<Object>) (List<?>) world.playerEntities;
        // the client's set may be kept between ticks (ActiveReuse), so a lone player takes the
        // fill too: with one square it inserts exactly what the original does
        boolean reuse = ActiveReuse.ENABLED && world.isRemote;
        if (reuse) {
            ActiveReuse.begin();
        }
        if (VANILLA || players.isEmpty() || (players.size() < 2 && !reuse)) {
            world.setActivePlayerChunksAndCheckLight();
            return;
        }
        List<Object> representatives = representatives(players);
        if (CHECK) {
            check(world, players, representatives);
            return;
        }
        optimized(world, players, representatives, reuse);
    }

    /** The optimized path: the fill when the area fits, otherwise the original on the list. */
    private static void optimized(
            World world, List<Object> players, List<Object> representatives, boolean reuse) {
        if (!VANILLA_FILL && fill(world, representatives, reuse)) {
            return;
        }
        if (representatives.size() == players.size()) {
            world.setActivePlayerChunksAndCheckLight();
        } else {
            runWith(world, players, representatives);
        }
    }

    /**
     * One player per distinct chunk. Allocated per call: the client and the integrated server use
     * different threads.
     */
    private static List<Object> representatives(List<Object> players) {
        int n = players.size();
        List<Object> out = new ArrayList<>();
        // an open-addressing set of the chunk keys: no box and no node per player
        int slots = Integer.highestOneBit(Math.max(1, n) * 2 - 1) << 1;
        long[] keys = new long[slots];
        boolean[] used = new boolean[slots];
        int mask = slots - 1;
        for (int i = 0; i < n; i++) {
            Entity p = (Entity) players.get(i);
            // Same calculation as the original: MathHelper.floor_double(pos / 16.0).
            long cx = (long) Math.floor(p.posX / 16.0);
            long cz = (long) Math.floor(p.posZ / 16.0);
            long key = cx << 32 ^ (cz & 0xFFFFFFFFL);
            int s = (int) ((key * 0x9E3779B97F4A7C15L) >>> 40) & mask;
            while (used[s] && keys[s] != key) {
                s = (s + 1) & mask;
            }
            if (!used[s]) {
                used[s] = true;
                keys[s] = key;
                out.add(p);
            }
        }
        return out;
    }

    /** Runs the original seeing only the representatives, and restores the list. */
    private static void runWith(World world, List<Object> players, List<Object> representatives) {
        List<Object> all = new ArrayList<>(players);
        players.clear();
        players.addAll(representatives);
        try {
            world.setActivePlayerChunksAndCheckLight();
        } finally {
            players.clear();
            players.addAll(all);
        }
    }

    /**
     * World.setActivePlayerChunksAndCheckLight over {@code list}, inserting each chunk once.
     * Returns false, having changed nothing, when the area does not fit the bitmap.
     */
    @SuppressWarnings("unchecked")
    private static boolean fill(World world, List<Object> list, boolean reuse) {
        int n = list.size();
        // the original asks once per player; it is a plain getter (the client's option, the
        // server's view distance), changed only on this same thread, so once is the same value
        int r = world.getRenderDistanceChunks();
        if (n == 0 || r < 0) {
            return false;
        }
        int[] cx = new int[n];
        int[] cz = new int[n];
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (int i = 0; i < n; i++) {
            Entity p = (Entity) list.get(i);
            // the original's calculation, exactly
            int x = MathHelper.floor_double(p.posX / 16.0);
            int z = MathHelper.floor_double(p.posZ / 16.0);
            cx[i] = x;
            cz[i] = z;
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minZ = Math.min(minZ, z);
            maxZ = Math.max(maxZ, z);
        }
        long width = (long) maxX - minX + 2L * r + 1;
        long depth = (long) maxZ - minZ + 2L * r + 1;
        if (width * depth > MAX_CELLS) {
            return false;
        }
        int x0 = minX - r;
        int z0 = minZ - r;
        int d = (int) depth;
        Set<ChunkCoordIntPair> set = (Set<ChunkCoordIntPair>) (Set<?>) world.activeChunkSet;
        Profiler profiler = world.theProfiler;
        if (reuse) {
            ActiveReuse.fill(set, profiler, cx, cz, n, r, x0, z0, d, (int) (width * depth));
            finish(world, profiler, list, n);
            return true;
        }
        long[] seen = new long[(int) ((width * depth + 63) >>> 6)];

        set.clear();
        profiler.startSection("buildList");
        for (int i = 0; i < n; i++) {
            int px = cx[i];
            int pz = cz[i];
            // the original's order: x outside, z inside
            for (int dx = -r; dx <= r; dx++) {
                int x = dx + px;
                int column = (x - x0) * d;
                for (int dz = -r; dz <= r; dz++) {
                    int z = dz + pz;
                    int cell = column + (z - z0);
                    long bit = 1L << cell;
                    if ((seen[cell >>> 6] & bit) == 0) {
                        seen[cell >>> 6] |= bit;
                        set.add(new ChunkCoordIntPair(x, z));
                    }
                }
            }
        }
        profiler.endSection();
        finish(world, profiler, list, n);
        return true;
    }

    /** The rest of the original after the set, as it is: the ambient countdown and light check. */
    private static void finish(World world, Profiler profiler, List<Object> list, int n) {
        if (world.ambientTickCountdown > 0) {
            world.ambientTickCountdown--;
        }
        profiler.startSection("playerCheckLight");
        Random rand = world.rand;
        EntityPlayer p = (EntityPlayer) list.get(rand.nextInt(n));
        int x = MathHelper.floor_double(p.posX) + rand.nextInt(11) - 5;
        int y = MathHelper.floor_double(p.posY) + rand.nextInt(11) - 5;
        int z = MathHelper.floor_double(p.posZ) + rand.nextInt(11) - 5;
        world.checkLight(new BlockPos(x, y, z));
        profiler.endSection();
    }

    /**
     * Runs the original with the full list, saves the set in iteration order and the ambient
     * countdown, runs the optimized path from the same countdown and compares. Diagnostics only:
     * doubles the cost of the method (and its light checks).
     */
    @SuppressWarnings("unchecked")
    private static void check(World world, List<Object> players, List<Object> representatives) {
        int countdown = world.ambientTickCountdown;
        world.setActivePlayerChunksAndCheckLight();
        List<Object> expected = new ArrayList<>((Set<Object>) (Set<?>) world.activeChunkSet);
        int expectedCountdown = world.ambientTickCountdown;
        world.ambientTickCountdown = countdown;
        optimized(world, players, representatives, false);
        List<Object> actual = new ArrayList<>((Set<Object>) (Set<?>) world.activeChunkSet);
        checks++;
        boolean sameSet = new HashSet<>(expected).equals(new HashSet<>(actual));
        if (!sameSet || expectedCountdown != world.ambientTickCountdown) {
            mismatches++;
            Log.info(
                    "activechunks",
                    "DIVERGENCE: original %d chunks, optimized %d (same set: %s), countdown %d"
                            + " vs %d",
                    expected.size(),
                    actual.size(),
                    sameSet,
                    expectedCountdown,
                    world.ambientTickCountdown);
        } else if (!expected.equals(actual)) {
            // the game's own variation with a tree bin (see the class comment), not a divergence
            orderDiffs++;
        }
        if (checks % 200 == 0) {
            Log.info(
                    "activechunks",
                    "%d ticks checked, %d divergences, %d with another iteration order | %d"
                            + " players -> %d representatives, %d active chunks",
                    checks,
                    mismatches,
                    orderDiffs,
                    players.size(),
                    representatives.size(),
                    actual.size());
        }
    }
}
