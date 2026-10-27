/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import net.minecraft.entity.Entity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.PlayerManager;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.gen.ChunkProviderServer;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Chunk loading of the integrated server spread over the ticks, nearest first.
 *
 * <p>The game loads (and generates, in a new world) every chunk a player starts watching in the
 * tick that asks for it: PlayerInstance's constructor calls loadChunk. Raising the view distance
 * from 12 to 32 asks for 3,600 chunks at once and the server stopped for 11 to 15 s on the bench
 * (mobs, damage and terrain frozen while the window kept drawing); walking at 32, every chunk
 * border asks for a row of 65. A dedicated server is usually set to a smaller distance, so this
 * only showed in singleplayer.
 *
 * <p>Here the constructor loads at once only what is near a player (within {@link #NEAR} chunks:
 * where mobs spawn and where the player walks) or already in memory; the rest waits in a set per
 * world, and every tick, before the game's PlayerManager update, loads the nearest ones within the
 * time the tick has left (the previous tick's time without this, up to {@link #TARGET_NS}). A chunk
 * whose watcher is gone by then is dropped. While chunks wait, two places that would load them on
 * the spot leave them alone: the list of active chunks (random ticks: {@link ActiveChunks} trims
 * it) and the PlayerManager reading a watched chunk only to update its inhabited time or to tell
 * the player it is gone (they get the empty chunk, which was never sent). The server sends a chunk
 * only once it is loaded and populated, as before, so the player sees the world fill from the
 * centre instead of a frozen server. {@code -Dsteinbridge.opt.vanillaChunkLoading=true} turns it
 * off.
 */
public final class LazyChunks {

    private static final boolean VANILLA = EngineOptions.VANILLA_CHUNK_LOADING;

    /** Chunks within this distance (in chunks) of a player load at once, as in the game. */
    static final int NEAR = 8;

    /** The whole tick, the game's part and this one, aims at this (the tick lasts 50 ms). */
    private static final long TARGET_NS = 45_000_000L;

    private static final long MIN_NS = 2_000_000L;
    private static final long MAX_NS = 40_000_000L;

    /** Server thread only. Weak: a closed world's set goes with it. */
    private static final Map<WorldServer, Set<Long>> PENDING = new WeakHashMap<>();

    private static int tickSeen = -1;
    private static long spentThisTick;
    private static long spentLastTick;
    private static long loadedTotal;

    private LazyChunks() {}

    /** ChunkProviderServer.loadChunk in PlayerInstance's constructor. */
    public static Object load(Object providerObj, int x, int z) {
        ChunkProviderServer provider = (ChunkProviderServer) providerObj;
        WorldServer world = provider.worldObj;
        if (VANILLA || provider.chunkExists(x, z) || nearPlayer(world, x, z, NEAR)) {
            // in memory, the game's call is cheap (and takes the chunk off the unload list)
            return provider.loadChunk(x, z);
        }
        PENDING.computeIfAbsent(world, w -> new HashSet<>()).add(key(x, z));
        return null;
    }

    /** PlayerManager.updatePlayerInstances in WorldServer.tick: the waiting chunks first. */
    public static void updatePlayerInstances(Object managerObj) {
        PlayerManager manager = (PlayerManager) managerObj;
        if (!VANILLA) {
            drain(manager);
        }
        manager.updatePlayerInstances();
    }

    /**
     * World.getChunkFromChunkCoords in PlayerInstance.removePlayer and processChunk: a waiting
     * chunk stays unloaded (the empty chunk is not populated, so no unload packet goes out).
     */
    public static Object chunkIfLoaded(Object worldObj, int x, int z) {
        WorldServer world = (WorldServer) worldObj;
        ChunkProviderServer provider = world.theChunkProviderServer;
        if (VANILLA || provider.chunkExists(x, z)) {
            return world.getChunkFromChunkCoords(x, z);
        }
        return provider.dummyChunk;
    }

    /**
     * Active chunks (ActiveChunks): without the ones still waiting, which random ticks would load.
     */
    static void trimActive(World world) {
        if (!(world instanceof WorldServer ws)) {
            return;
        }
        Set<Long> pending = PENDING.get(ws);
        if (pending == null || pending.isEmpty()) {
            return;
        }
        ChunkProviderServer provider = ws.theChunkProviderServer;
        Iterator<ChunkCoordIntPair> it = ws.activeChunkSet.iterator();
        while (it.hasNext()) {
            ChunkCoordIntPair p = it.next();
            if (!provider.chunkExists(p.chunkXPos, p.chunkZPos)) {
                it.remove();
            }
        }
    }

    /** Chunks waiting in every world (the performance log). */
    static int pending() {
        int n = 0;
        for (Set<Long> s : PENDING.values()) {
            n += s.size();
        }
        return n;
    }

    /** Chunks loaded by the spread loading since the game started (the performance log). */
    static long loaded() {
        return loadedTotal;
    }

    private static void drain(PlayerManager manager) {
        WorldServer world = manager.getWorldServer();
        Set<Long> pending = PENDING.get(world);
        if (pending == null || pending.isEmpty()) {
            return;
        }
        long start = System.nanoTime();
        long budget = budget(world);
        ChunkProviderServer provider = world.theChunkProviderServer;
        long[] order = nearestFirst(world, pending);
        int loaded = 0;
        for (long o : order) {
            long k = snapshot[(int) (o & INDEX_MASK)];
            int x = (int) (k >> 32);
            int z = (int) k;
            int distance = (int) (o >>> 40);
            if (!manager.hasPlayerInstance(x, z) || provider.chunkExists(x, z)) {
                pending.remove(k);
                continue;
            }
            if (distance > NEAR && loaded > 0 && System.nanoTime() - start >= budget) {
                break;
            }
            provider.loadChunk(x, z);
            pending.remove(k);
            loaded++;
        }
        loadedTotal += loaded;
        spentThisTick += System.nanoTime() - start;
    }

    /** What the tick has left: the target minus the game's part of the previous tick. */
    private static long budget(WorldServer world) {
        MinecraftServer server = world.getMinecraftServer();
        int tick = server.getTickCounter();
        if (tick != tickSeen) {
            tickSeen = tick;
            spentLastTick = spentThisTick;
            spentThisTick = 0;
        }
        long[] times = server.tickTimeArray;
        long last = times[Math.floorMod(tick - 1, times.length)];
        long game = Math.max(0, last - spentLastTick);
        return Math.min(MAX_NS, Math.max(MIN_NS, TARGET_NS - game));
    }

    // The keys of the set, ordered by distance to the nearest player: one long per key, the
    // distance in the top bits and the key's position in a snapshot array below it.
    private static long[] snapshot = new long[0];

    private static final long INDEX_MASK = (1L << 40) - 1;

    private static long[] nearestFirst(WorldServer world, Set<Long> pending) {
        int n = pending.size();
        if (snapshot.length < n) {
            snapshot = new long[Math.max(n, snapshot.length * 2)];
        }
        long[] order = new long[n];
        int i = 0;
        for (Long k : pending) {
            snapshot[i] = k;
            int x = (int) (k >> 32);
            int z = (int) (long) k;
            order[i] = (long) distanceToPlayers(world, x, z) << 40 | i;
            i++;
        }
        Arrays.sort(order);
        return order;
    }

    /** Chebyshev distance, in chunks, to the nearest player of the world (0 without players). */
    private static int distanceToPlayers(WorldServer world, int x, int z) {
        List<? extends Entity> players = world.playerEntities;
        int best = Integer.MAX_VALUE;
        for (int i = 0; i < players.size(); i++) {
            Entity p = players.get(i);
            int px = (int) Math.floor(p.posX / 16.0);
            int pz = (int) Math.floor(p.posZ / 16.0);
            best = Math.min(best, Math.max(Math.abs(px - x), Math.abs(pz - z)));
        }
        return best == Integer.MAX_VALUE ? 0 : Math.min(best, 0xFFFFFF);
    }

    private static boolean nearPlayer(WorldServer world, int x, int z, int radius) {
        return !world.playerEntities.isEmpty() && distanceToPlayers(world, x, z) <= radius;
    }

    private static long key(int x, int z) {
        return (long) x << 32 | (z & 0xFFFFFFFFL);
    }
}
