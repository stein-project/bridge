/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.util.BlockPos;
import net.minecraft.world.World;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Set;

/**
 * Pace of chunk building: two options (Visuals, in the Bridge's visual options), both trading a
 * little image quality for more regular frames.
 *
 * <ul>
 *   <li><b>chunks per frame</b>: how many finished chunks (built on the threads) go to the GPU per
 *       frame in updateChunks. The game uploads while there is frame time left; with the limit, an
 *       arrival of many chunks (joining, teleporting) is spread over more frames — the terrain
 *       appears more slowly, without the long frame;
 *   <li><b>wait for neighbours</b>: a chunk is only built when the 8 columns around it have also
 *       arrived. On arrival, each neighbouring column that arrives dirties the previous one's edge
 *       again (faces that were outside become inside): 3 to 5 builds per chunk. Waiting, one. The
 *       last ring of loaded columns does not show (it never has all its neighbours).
 * </ul>
 *
 * <p>The chunk that waits leaves the game's set marked as clean (the updateChunks loop and
 * setupTerrain do this after the call); it stays in a list here and, on every arrival of chunk
 * data, those that already have neighbours are marked again — the game picks them up again in the
 * next setupTerrain, through the normal path. Option off: all the ones that were waiting return.
 */
public final class ChunkPace {

    private static int uploadsPerFrame = EngineOptions.CHUNK_UPLOADS;
    private static boolean waitNeighbors = EngineOptions.WAIT_NEIGHBORS;

    /** Chunk data has arrived since the last review of the waiting list (game thread). */
    private static boolean arrived;

    private static final Set<Object> WAITING = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final int WAITING_MAX = 8192;

    private ChunkPace() {}

    /** 0 = like the game (while frame time remains). */
    public static void setUploadsPerFrame(int n) {
        uploadsPerFrame = Math.max(0, n);
    }

    public static void setWaitNeighbors(boolean on) {
        waitNeighbors = on;
    }

    /** ChunkSync: end of marking a chunk packet (new data: review the waiting list). */
    static void chunksArrived() {
        arrived = true;
    }

    // ---- hooks (patcher/redirects-mods.tsv) ----

    /** In place of runChunkUploads(time), at the start of updateChunks. */
    public static boolean uploads(Object dispatcherObj, long finishTime) {
        ChunkRenderDispatcher dispatcher = (ChunkRenderDispatcher) dispatcherObj;
        // Extended distance: columns read from disk come in, and the search around.
        ChunkKeep.frame();
        ChunkKeep.debug();
        if (!WAITING.isEmpty() && (arrived || !waitNeighbors)) {
            retry();
        }
        arrived = false;
        if (uploadsPerFrame <= 0) {
            return dispatcher.runChunkUploads(finishTime);
        }
        boolean any = false;
        for (int i = 0; i < uploadsPerFrame; i++) {
            // With time 0 the game uploads one (or none, empty queue) and stops.
            if (!dispatcher.runChunkUploads(0L)) {
                break;
            }
            any = true;
            if (finishTime != 0L && System.nanoTime() - finishTime > 0L) {
                break; // and the game's frame time still applies
            }
        }
        return any;
    }

    /** In place of updateChunkLater, in the updateChunks loop. */
    public static boolean buildLater(Object dispatcher, Object chunk) {
        if (waitNeighbors && hold(chunk)) {
            // The game's loop removes it from the set and marks it clean; it comes back through
            // the waiting list.
            return true;
        }
        return ((ChunkRenderDispatcher) dispatcher).updateChunkLater((RenderChunk) chunk);
    }

    /** ChunkSync.buildNear, a chunk next to the player dirtied only by the arrival of data. */
    static boolean holdNear(Object chunk) {
        return waitNeighbors && hold(chunk);
    }

    /** Without the neighbours (and with room in the list): waits. */
    private static boolean hold(Object chunk) {
        if (ready(chunk) || WAITING.size() >= WAITING_MAX) {
            return false;
        }
        WAITING.add(chunk);
        return true;
    }

    /** The 9 columns (the chunk's and the 8 around it) with data. */
    private static boolean ready(Object chunk) {
        RenderChunk renderChunk = (RenderChunk) chunk;
        World world = renderChunk.world;
        if (world == null) {
            return true;
        }
        BlockPos pos = renderChunk.getPosition();
        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (world.getChunkFromChunkCoords(cx + dx, cz + dz).isEmpty()) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Those that wait and already have their neighbours (or all of them, option off) are marked
     * again.
     */
    private static void retry() {
        for (Iterator<Object> it = WAITING.iterator(); it.hasNext(); ) {
            Object c = it.next();
            if (!waitNeighbors || ready(c)) {
                it.remove();
                ((RenderChunk) c).setNeedsUpdate(true);
            }
        }
    }
}
