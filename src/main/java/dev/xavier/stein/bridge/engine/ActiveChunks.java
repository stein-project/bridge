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
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
 * <p>The patcher redirects the call in World.updateBlocks to here. {@code
 * -Dsteinbridge.opt.vanillaActiveChunks=true} turns it off, for A/B. {@code
 * -Dsteinbridge.opt.checkActiveChunks=true} runs both paths every tick and compares the sets, to
 * prove the equivalence in the real game.
 */
public final class ActiveChunks {

    private static final boolean VANILLA = EngineOptions.VANILLA_ACTIVE_CHUNKS;
    private static final boolean CHECK = EngineOptions.CHECK_ACTIVE_CHUNKS;

    private static long checks;
    private static long mismatches;

    private ActiveChunks() {}

    @SuppressWarnings("unchecked")
    public static void setActivePlayerChunksAndCheckLight(Object worldObj) {
        World world = (World) worldObj;
        List<Object> players = (List<Object>) (List<?>) world.playerEntities;
        if (VANILLA || players.size() < 2) {
            world.setActivePlayerChunksAndCheckLight();
            return;
        }
        List<Object> representatives = representatives(players);
        if (representatives.size() == players.size()) {
            world.setActivePlayerChunksAndCheckLight();
            return;
        }
        if (CHECK) {
            check(world, players, representatives);
            return;
        }
        runWith(world, players, representatives);
    }

    /**
     * One player per distinct chunk. Allocated per call: the client and the integrated server use
     * different threads.
     */
    private static List<Object> representatives(List<Object> players) {
        List<Object> out = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < players.size(); i++) {
            Entity p = (Entity) players.get(i);
            // Same calculation as the original: MathHelper.floor_double(pos / 16.0).
            long cx = (long) Math.floor(p.posX / 16.0);
            long cz = (long) Math.floor(p.posZ / 16.0);
            if (seen.add(cx << 32 ^ (cz & 0xFFFFFFFFL))) {
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
     * Runs the original with the full list, saves the set, runs the deduplicated one and compares.
     * Diagnostics only: doubles the cost of the method.
     */
    @SuppressWarnings("unchecked")
    private static void check(World world, List<Object> players, List<Object> representatives) {
        world.setActivePlayerChunksAndCheckLight();
        Set<Object> expected = new HashSet<>((Set<Object>) (Set<?>) world.activeChunkSet);
        runWith(world, players, representatives);
        Set<Object> actual = (Set<Object>) (Set<?>) world.activeChunkSet;
        checks++;
        if (!expected.equals(actual)) {
            mismatches++;
            Log.info(
                    "activechunks",
                    "DIVERGENCE: original %d chunks, deduplicated %d",
                    expected.size(),
                    actual.size());
        }
        if (checks % 200 == 0) {
            Log.info(
                    "activechunks",
                    "%d ticks checked, %d divergences | %d players -> %d"
                            + " representatives, %d active chunks",
                    checks,
                    mismatches,
                    players.size(),
                    representatives.size(),
                    actual.size());
        }
    }
}
