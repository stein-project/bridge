/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.entity.Entity;

import java.util.List;

/**
 * When the chunk visibility search can keep the previous list.
 *
 * <p>The game redoes the whole search on every frame in which a chunk waits to be built (and on
 * every one in which the camera moved or turned). What the search finds depends on the camera, the
 * view frustum's chunk positions and each chunk's compiled visibility; a chunk waiting to be built
 * changes none of them. In singleplayer at distance 32 chunks wait for minutes, and the search over
 * 10 to 13 thousand sections ran a thousand times every 5 s, taking half of the render thread on
 * the bench while the camera stood still.
 *
 * <p>The previous list is kept when the camera (position and rotation, compared as the game does),
 * the RenderGlobal, its view frustum, the distance, the starting chunk and renderChunksMany are the
 * ones of the last search, the starting chunk is not waiting for a build, and no chunk finished
 * compiling since — or one did, less than {@link #THROTTLE_NS} ago: a newly built chunk can open
 * the way to chunks behind it, and those join the list up to 50 ms later instead of on the next
 * frame. A chunk already in the list is drawn as soon as it is built, search or not. A list of one
 * chunk (the camera's, closed) is never kept: ChunkSync asks for that search. {@code
 * -Dsteinbridge.opt.vanillaSearchRate=true} turns it off.
 */
final class SearchRate {

    private static final boolean OFF = EngineOptions.VANILLA_SEARCH_RATE;

    /** A finished chunk waits at most this long for the search that goes past it. */
    static final long THROTTLE_NS = 50_000_000L;

    // The last search (render thread only).
    private static RenderGlobal rg;
    private static Object frustum;
    private static int distance;
    private static boolean many;
    private static double x;
    private static double y;
    private static double z;
    private static float pitch;
    private static float yaw;
    private static Object startChunk;
    private static int startSize;
    private static int compiled;
    private static long at;

    /** Searches answered with the previous list (the terrain watchdog). */
    static int kept;

    private SearchRate() {}

    /**
     * True if the previous list still holds; {@code start} is the search's starting queue, {@code
     * searchMany} the search's renderChunksMany (off for a spectator inside a block).
     */
    static boolean keep(
            RenderGlobal g, Entity e, List<Object> start, List<?> previous, boolean searchMany) {
        if (OFF || g != rg || previous == null || previous.size() <= 1 || start.isEmpty()) {
            return false;
        }
        if (e.posX != x
                || e.posY != y
                || e.posZ != z
                || e.rotationPitch != pitch
                || e.rotationYaw != yaw) {
            return false;
        }
        if (g.viewFrustum != frustum || g.renderDistanceChunks != distance || searchMany != many) {
            return false;
        }
        RenderChunk first = (RenderChunk) ChunkGraph.INFO_CHUNK.get(start.get(0));
        if (start.size() != startSize || first != startChunk || first.isNeedsUpdate()) {
            return false;
        }
        if (ChunkGraph.compiledVersion != compiled) {
            if (System.nanoTime() - at >= THROTTLE_NS) {
                return false;
            }
            // Put off, not dropped: the game cleared its flag for this search and, with the camera
            // still and nothing left to build, would not ask again (after a resource reload the
            // terrain stayed at the few chunks around the camera). Asking again on the next frame
            // runs it once the 50 ms are up.
            g.displayListEntitiesDirty = true;
        }
        kept++;
        return true;
    }

    /**
     * A search is starting with these: {@code compiledBefore} was read before it, so a chunk that
     * finishes during it asks for the next one.
     */
    static void searched(
            RenderGlobal g, Entity e, List<Object> start, int compiledBefore, boolean searchMany) {
        rg = g;
        frustum = g.viewFrustum;
        distance = g.renderDistanceChunks;
        many = searchMany;
        x = e.posX;
        y = e.posY;
        z = e.posZ;
        pitch = e.rotationPitch;
        yaw = e.rotationYaw;
        startChunk = start.isEmpty() ? null : ChunkGraph.INFO_CHUNK.get(start.get(0));
        startSize = start.size();
        compiled = compiledBefore;
        at = System.nanoTime();
    }

    /** A search made by the game's own loop (vanillaBfs, check mode): the next one runs. */
    static void forget() {
        rg = null;
    }
}
