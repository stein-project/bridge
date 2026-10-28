/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import dev.xavier.stein.bridge.core.Log;

import net.minecraft.client.Minecraft;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

import java.util.Arrays;

/**
 * The client world's chunk lookup (World.getChunkFromChunkCoords) answered from a small table of
 * recent columns on the client thread, instead of the chunk map every time.
 *
 * <p>Every World.getBlockState asks the world for the chunk first: the client's ChunkProviderClient
 * hashes the column and walks the LongHashMap bucket, a few dependent loads scattered over the
 * heap. The tick does it thousands of times (each entity's water, lava and suffocation checks, the
 * 1000 random blocks of doVoidFogParticles, particle collisions, the light checks): JFR put
 * getChunkFromBlockCoords at 14% of the client tick, getBlockState at 24%. The same few columns are
 * asked over and over, so a direct-mapped table of 32 x 32 columns answers almost all of them from
 * lines already in the cache. World.isChunkLoaded (behind every isAreaLoaded) asks the provider for
 * the chunk too, and goes through the same table.
 *
 * <p>Why it is exact: the answer for a column only changes when ChunkProviderClient.loadChunk or
 * unloadChunk changes the map (the chunk object itself is the same while it is loaded, and the
 * blank chunk answers for a missing column). The patcher calls {@link #changed} before and after
 * every call of either, and the table is dropped whenever that version or the world differs. A
 * Minecraft.loadWorld also drops it ({@link #forget}), so a left world is not kept alive. Only the
 * client thread uses the table: other threads (chunk building, the integrated server, any world
 * that is not remote) go to the chunk provider exactly as the game does.
 *
 * <p>{@code -Dsteinbridge.opt.vanillaClientChunks=true}: the map lookup every time. {@code
 * -Dsteinbridge.opt.checkClientChunks=true}: uses the table and compares every hit with the map.
 */
public final class ClientChunks {

    private static final boolean VANILLA = EngineOptions.VANILLA_CLIENT_CHUNKS;
    private static final boolean CHECK = !VANILLA && EngineOptions.CHECK_CLIENT_CHUNKS;

    /** 32 x 32 columns: more than the tick's working set, small enough to stay in the cache. */
    private static final int SIDE_BITS = 5;

    private static final int MASK = (1 << SIDE_BITS) - 1;
    private static final int SLOTS = 1 << (2 * SIDE_BITS);

    private static final long[] keys = new long[SLOTS];
    private static final Chunk[] chunks = new Chunk[SLOTS];

    /** Version of the client's chunk map: changes before and after every load and unload. */
    private static volatile int version;

    /** The world and map version the table holds; touched only on the owner thread. */
    private static World tableWorld;

    private static int tableVersion;

    /** The client thread, found on the first lookup made from it. */
    private static Thread owner;

    private static final CheckReport REPORT = new CheckReport(5_000_000_000L);

    static {
        if (VANILLA) {
            Log.info("clientchunks", "chunk lookup through the chunk map every time (vanilla)");
        } else if (CHECK) {
            Log.info("clientchunks", "check: every cached chunk compared with the chunk map");
        }
    }

    private ClientChunks() {}

    /** Before and after every ChunkProviderClient.loadChunk and unloadChunk. */
    public static void changed() {
        version++;
    }

    /** Before every Minecraft.loadWorld: the table lets go of the old world's chunks. */
    public static void forget() {
        if (Thread.currentThread() == owner) {
            drop(null);
        }
    }

    /**
     * The body of World.getChunkFromChunkCoords(x, z). Kept this small on purpose: it sits under
     * every getBlockState and light lookup, and the JIT has to inline it into those chains (a
     * larger body pushed World.getLightFor and its callers out of the inlining budget, -10% fps
     * with 450 tile entities). Everything else is in {@link #miss}.
     */
    public static Object chunkAt(Object worldObj, int x, int z) {
        if (!CHECK
                && worldObj == tableWorld
                && tableVersion == version
                && Thread.currentThread() == owner) {
            int i = (x & MASK) | (z & MASK) << SIDE_BITS;
            Chunk c = chunks[i];
            if (c != null && keys[i] == ((long) x << 32 | (z & 0xFFFFFFFFL))) {
                return c;
            }
        }
        return miss((World) worldObj, x, z);
    }

    /**
     * Not in the table, or the table is not this world's or this thread's (or vanilla, or check).
     */
    private static Object miss(World world, int x, int z) {
        if (VANILLA
                || world != tableWorld
                || tableVersion != version
                || Thread.currentThread() != owner) {
            if (!claim(world)) {
                return world.chunkProvider.provideChunk(x, z);
            }
        }
        int i = (x & MASK) | (z & MASK) << SIDE_BITS;
        long key = (long) x << 32 | (z & 0xFFFFFFFFL);
        Chunk c = chunks[i];
        if (c != null && keys[i] == key) {
            if (CHECK) {
                check(world, x, z, c);
            }
            return c;
        }
        c = world.chunkProvider.provideChunk(x, z);
        keys[i] = key;
        chunks[i] = c;
        return c;
    }

    /**
     * The body of World.isChunkLoaded(x, z, allowEmpty): the game's two questions to the chunk
     * provider, the second one ({@code provideChunk}) through {@link #chunkAt}.
     */
    public static boolean loaded(Object worldObj, int x, int z, boolean allowEmpty) {
        World world = (World) worldObj;
        return world.chunkProvider.chunkExists(x, z)
                && (allowEmpty || !((Chunk) chunkAt(world, x, z)).isEmpty());
    }

    /**
     * Whether the table may answer for this world on this thread; if so, it now holds this world at
     * the current map version (emptied when either changed).
     */
    private static boolean claim(World world) {
        if (VANILLA || world == null || !world.isRemote) {
            return false;
        }
        Thread t = Thread.currentThread();
        if (owner == null) {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || !mc.isCallingFromMinecraftThread()) {
                return false;
            }
            owner = t;
        } else if (t != owner) {
            return false;
        }
        int v = version;
        if (world != tableWorld || v != tableVersion) {
            drop(world);
            tableVersion = v;
        }
        return true;
    }

    private static void drop(World world) {
        Arrays.fill(chunks, null);
        tableWorld = world;
    }

    private static void check(World world, int x, int z, Chunk cached) {
        Chunk real = world.chunkProvider.provideChunk(x, z);
        if (REPORT.compared(real == cached)) {
            Log.info(
                    "clientchunks",
                    "DIVERGENCE: column %d,%d cached %s, map %s",
                    x,
                    z,
                    cached,
                    real);
        }
        REPORT.maybeReport("clientchunks");
    }
}
