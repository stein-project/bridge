/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import net.minecraft.client.renderer.chunk.RenderChunk;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * Chunks adjacent to the player built on the threads when the reason is only data arriving.
 *
 * <p>In setupTerrain, the game builds on the render thread itself (updateChunkNow) every dirty
 * chunk adjacent to the player — so that a placed or broken block shows up in the same frame. But
 * those chunks also become dirty when their data arrives from the server (joining, teleport): then
 * it is 5 to 11 builds in the same frame, with a cold JIT on entry — the ~70-95 ms spike that was
 * left in the terrain scene.
 *
 * <p>Here each marking is separated by origin. The marking that the chunk packet handler does
 * (markBlockRangeForRenderUpdate in handleChunkData/handleMapChunkBulk) leaves the chunk "dirty
 * only by arrival"; any other (changed block, light) removes that and keeps the game's behavior. In
 * setupTerrain, a chunk dirty only by arrival goes to the build threads' queue; a changed block, or
 * a full queue, is built right away, as in the game.
 *
 * <p>{@code -Dsteinbridge.opt.vanillaNearBuild=true}: everything adjacent to the player right away.
 */
public final class ChunkSync {

    private static final boolean VANILLA = EngineOptions.VANILLA_NEAR_BUILD;
    private static final boolean VANILLA_RESEARCH = EngineOptions.VANILLA_ARRIVAL_SEARCH;

    // The field the patcher adds to RenderChunk does not exist in the readable-name jar: by handle.
    private static final VarHandle ARRIVAL_ONLY; // RenderChunk.sbArrival

    static {
        try {
            ARRIVAL_ONLY =
                    MethodHandles.lookup()
                            .findVarHandle(RenderChunk.class, "sbArrival", boolean.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** Inside the marking done by the chunk packet handler (game thread). */
    private static boolean markingArrival;

    private ChunkSync() {}

    /**
     * Searches that only happened because of this (the mark was clean): the ChunkArena monitor
     * counts them.
     */
    static int extraSearches;

    /** Before markBlockRangeForRenderUpdate in the chunk handlers. */
    public static void arrivalBegin() {
        markingArrival = true;
    }

    /**
     * After it. If the last visibility search gave only the camera's chunk, requests another (the
     * same mark the game sets when the camera moves).
     *
     * <p>The search starts at the visible faces of the camera's section; if they come up empty (the
     * section's data has not arrived yet, or arrived halfway, after a jump), the game lists only
     * the camera's chunk and does not spread. With the camera still and nothing pending, no search
     * runs again when the data arrives: the terrain stays empty until the camera moves. Measured on
     * the terrain walk at distance 16: in ~1 of every 3 rounds a jump was left without terrain
     * until the next one. Dirtying the search on every packet made ~20% more searches on the walk;
     * only in the closed case, none. {@code -Dsteinbridge.opt.vanillaArrivalSearch=true}: as in the
     * game.
     */
    public static void arrivalEnd() {
        markingArrival = false;
        ChunkPace.chunksArrived();
        if (!VANILLA_RESEARCH) {
            RenderGlobal rg = Minecraft.getMinecraft().renderGlobal;
            // Only when the last search gave the closed case (only the camera's chunk): in the
            // rest, no extra search.
            if (rg != null && rg.renderInfos.size() <= 1) {
                if (!rg.displayListEntitiesDirty) {
                    extraSearches++;
                }
                rg.displayListEntitiesDirty = true;
            }
        }
    }

    /**
     * In place of renderChunk.setNeedsUpdate(flag) in ViewFrustum.markBlocksForUpdate. A clean
     * chunk marked by arrival becomes "only by arrival"; any other marking removes that. Already
     * dirty and marked again by arrival: stays as it was.
     */
    public static void markDirty(Object chunk, boolean flag) {
        if (flag) {
            if (!markingArrival) {
                ARRIVAL_ONLY.set(chunk, false);
            } else if (!((RenderChunk) chunk).isNeedsUpdate()) {
                ARRIVAL_ONLY.set(chunk, true);
            }
        }
        ((RenderChunk) chunk).setNeedsUpdate(flag);
    }

    /** In place of renderDispatcher.updateChunkNow(chunk) for the chunks adjacent to the player. */
    public static boolean buildNear(Object dispatcherObj, Object chunk) {
        ChunkRenderDispatcher dispatcher = (ChunkRenderDispatcher) dispatcherObj;
        if (!VANILLA && (boolean) ARRIVAL_ONLY.get(chunk)) {
            if (ChunkPace.holdNear(chunk)) {
                return true; // waits for the neighbors (option) and stays "only by arrival"
            }
            ARRIVAL_ONLY.set(chunk, false);
            if (dispatcher.updateChunkLater((RenderChunk) chunk)) {
                return true;
            }
        }
        return dispatcher.updateChunkNow((RenderChunk) chunk);
    }
}
