/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

package dev.xavier.stein.bridge.engine;

import net.minecraft.util.BlockPos;
import net.minecraft.world.World;

/**
 * Stores, per column, the chunk that World.getChunkFromBlockCoords answers inside renderEntities.
 * See {@link ChunkGraph} for the switches.
 */
final class ChunkAtCache {
    // ---- each section's chunk in renderEntities ----
    //
    // renderEntities walks, every frame, all the chunks of the search and asks the world for each
    // one's chunk (getChunkFromBlockCoords: the client's chunk map) to see whether the section has
    // an entity. Looking at the sky, with no entity at all, this was 24% of the render thread. The
    // chunk for a coordinate only changes when a chunk is loaded or unloaded, and each
    // RenderChunk's position is a fixed BlockPos: the answer is stored per column (x >> 4, z >> 4)
    // — the 16 sections of a column give the same chunk —, world and chunk-map version.
    // -Dsteinbridge.opt.vanillaChunkAt=true: the lookup every frame.

    private static final boolean VANILLA_CHUNK =
            ChunkGraph.VANILLA || EngineOptions.VANILLA_CHUNK_AT;

    /** 128 x 128 columns: covers the maximum distance (32 chunks, 65 columns) with no collision. */
    private static final int CHUNK_SLOTS = 128 * 128;

    private static final int[] slotCx = new int[CHUNK_SLOTS];
    private static final int[] slotCz = new int[CHUNK_SLOTS];
    private static final Object[] slotWorld = new Object[CHUNK_SLOTS];
    private static final Object[] slotChunk = new Object[CHUNK_SLOTS];
    private static final int[] slotEpoch = new int[CHUNK_SLOTS];

    private ChunkAtCache() {}

    /** World.getChunkFromBlockCoords inside renderEntities. */
    static Object chunkAt(Object worldObj, Object posObj) {
        World world = (World) worldObj;
        BlockPos pos = (BlockPos) posObj;
        if (VANILLA_CHUNK) {
            return world.getChunkFromBlockCoords(pos);
        }
        // getChunkFromBlockCoords(pos) = getChunkFromChunkCoords(x >> 4, z >> 4).
        int cx = pos.x >> 4;
        int cz = pos.z >> 4;
        int i = (cx & 127) | (cz & 127) << 7;
        int epoch = ChunkGraph.chunkEpoch;
        Object c = slotChunk[i];
        if (c != null
                && slotCx[i] == cx
                && slotCz[i] == cz
                && slotWorld[i] == world
                && slotEpoch[i] == epoch) {
            if (ChunkGraph.CHECK) {
                ChunkGraph.check(c == world.getChunkFromBlockCoords(pos), "chunk");
            }
            return c;
        }
        c = world.getChunkFromBlockCoords(pos);
        slotChunk[i] = c;
        slotCx[i] = cx;
        slotCz[i] = cz;
        slotWorld[i] = world;
        slotEpoch[i] = epoch;
        return c;
    }
}
