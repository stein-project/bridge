/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import dev.xavier.stein.bridge.core.Log;

import net.minecraft.util.BlockPos;
import net.minecraft.world.NextTickListEntry;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.gen.ChunkProviderServer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * The scheduled block updates of each chunk, indexed once per save of the integrated server.
 *
 * <p>Writing a chunk (AnvilChunkLoader.writeChunkToNBT) asks the world for the chunk's scheduled
 * updates, and WorldServer.getPendingBlockUpdates walks every scheduled update of the world to find
 * them. A save writes every modified chunk, so the cost is chunks x updates: in a new world at view
 * distance 32 (thousands of fresh chunks, water and lava still settling) the autosave every 45 s
 * stopped the server for 17 s on the bench, and the save when the game paused for 76 s on the
 * player's machine — mobs, damage and terrain frozen while the window kept drawing.
 *
 * <p>During a save (ChunkProviderServer.saveChunks) and an unload round (unloadQueuedChunks),
 * nothing schedules or runs block updates, so the sets do not change: the first chunk walks them
 * once and files each update under every chunk whose box takes it — the game's box reaches 2 blocks
 * into the west and north neighbours, so an update can belong to up to 4 chunks —, in the order the
 * game walks them (the tree, then the current tick's list). Each chunk then gets the same list the
 * game would build, or null when it has none. Outside a save, and for the removing variant, the
 * game's method runs. {@code -Dsteinbridge.opt.vanillaPendingTicks=true} turns it off; {@code
 * -Dsteinbridge.opt.checkPendingTicks=true} answers each chunk both ways and compares.
 */
public final class PendingTicks {

    private static final boolean VANILLA = EngineOptions.VANILLA_PENDING_TICKS;
    private static final boolean CHECK = EngineOptions.CHECK_PENDING_TICKS;

    /** Server thread only: the world being saved, and its index once built. */
    private static WorldServer saving;

    private static HashMap<Long, List<NextTickListEntry>> index;

    private static final CheckReport REPORT = new CheckReport(10_000_000_000L);

    private PendingTicks() {}

    /** ChunkProviderServer.saveChunks in WorldServer.saveAllChunks. */
    public static boolean saveChunks(Object providerObj, boolean all, Object progress) {
        IChunkProvider provider = (IChunkProvider) providerObj;
        begin(provider);
        try {
            return provider.saveChunks(all, (net.minecraft.util.IProgressUpdate) progress);
        } finally {
            end();
        }
    }

    /** IChunkProvider.unloadQueuedChunks in WorldServer.tick. */
    public static boolean unloadQueuedChunks(Object providerObj) {
        IChunkProvider provider = (IChunkProvider) providerObj;
        begin(provider);
        try {
            return provider.unloadQueuedChunks();
        } finally {
            end();
        }
    }

    /** WorldServer.getPendingBlockUpdates(chunk, remove) in AnvilChunkLoader.writeChunkToNBT. */
    public static Object forChunk(Object worldObj, Object chunkObj, boolean remove) {
        WorldServer world = (WorldServer) worldObj;
        Chunk chunk = (Chunk) chunkObj;
        if (remove || world != saving) {
            return world.getPendingBlockUpdates(chunk, remove);
        }
        if (index == null) {
            index = build(world);
        }
        List<NextTickListEntry> ours = index.get(key(chunk.xPosition, chunk.zPosition));
        if (CHECK) {
            compare(ours, world.getPendingBlockUpdates(chunk, false));
        }
        return ours;
    }

    private static void begin(IChunkProvider provider) {
        if (!VANILLA && provider instanceof ChunkProviderServer p) {
            saving = p.worldObj;
            index = null;
        }
    }

    private static void end() {
        saving = null;
        index = null;
    }

    /** One walk over the world's updates, in the game's order, filed under each chunk's box. */
    private static HashMap<Long, List<NextTickListEntry>> build(WorldServer world) {
        HashMap<Long, List<NextTickListEntry>> out = new HashMap<>();
        for (NextTickListEntry e : world.pendingTickListEntriesTreeSet) {
            file(out, e);
        }
        for (NextTickListEntry e : world.pendingTickListEntriesThisTick) {
            file(out, e);
        }
        return out;
    }

    /**
     * getPendingBlockUpdates(chunk) takes x in [chunkX * 16 - 2, chunkX * 16 + 16), and z the same:
     * the chunk the block is in, and the next one east (south) when the block is in its last 2
     * columns (rows).
     */
    private static void file(HashMap<Long, List<NextTickListEntry>> out, NextTickListEntry e) {
        BlockPos p = e.position;
        int cx = p.getX() >> 4;
        int cz = p.getZ() >> 4;
        boolean east = (p.getX() & 15) >= 14;
        boolean south = (p.getZ() & 15) >= 14;
        add(out, cx, cz, e);
        if (east) {
            add(out, cx + 1, cz, e);
        }
        if (south) {
            add(out, cx, cz + 1, e);
        }
        if (east && south) {
            add(out, cx + 1, cz + 1, e);
        }
    }

    private static void add(
            HashMap<Long, List<NextTickListEntry>> out, int cx, int cz, NextTickListEntry e) {
        out.computeIfAbsent(key(cx, cz), k -> new ArrayList<>()).add(e);
    }

    private static long key(int cx, int cz) {
        return (long) cx << 32 ^ (cz & 0xFFFFFFFFL);
    }

    private static void compare(List<NextTickListEntry> ours, List<NextTickListEntry> game) {
        boolean same =
                ours == null || game == null
                        ? ours == game
                        : ours.size() == game.size() && sameEntries(ours, game);
        if (REPORT.compared(same)) {
            Log.info(
                    "ticks",
                    "DIVERGENCE: %d updates here x %d in the game",
                    ours == null ? 0 : ours.size(),
                    game == null ? 0 : game.size());
        }
        REPORT.maybeReport("ticks");
    }

    private static boolean sameEntries(List<NextTickListEntry> a, List<NextTickListEntry> b) {
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i) != b.get(i)) {
                return false;
            }
        }
        return true;
    }
}
