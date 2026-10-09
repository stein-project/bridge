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
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.chunk.CompiledChunk;
import net.minecraft.client.renderer.chunk.RenderChunk;

import java.util.List;

/**
 * The terrain watchdog diagnostic ({@code -Dsteinbridge.diag.terrainWatch=true}): every 5 s, how
 * many chunks the game listed per frame in the solid layer and how many chunk columns within a
 * radius of 3 around the camera the client's world has with data. Separates "the server did not
 * send the world" (empty list and empty columns) from "the drawing lost the terrain" (columns with
 * data and an empty list or invisible terrain), on both paths. It only runs when asked for.
 */
final class ChunkWatch {
    static final boolean ENABLED = EngineOptions.TERRAIN_WATCH;

    static long listed;
    static long uploads;

    /** Visibility searches (setupTerrain with the list rebuilt) since the last report. */
    static int searches;

    private static long frames;
    private static double x;
    private static double z;
    private static final long INTERVAL_NS = 5_000_000_000L;
    private static long at = System.nanoTime();

    private ChunkWatch() {}

    /** Solid layer of a frame: the number of chunks the game listed and the camera position. */
    static void layerListed(int chunks, double vx, double vz) {
        listed += chunks;
        x = vx;
        z = vz;
    }

    static void endFrame(int state) {
        frames++;
        long now = System.nanoTime();
        if (now - at < INTERVAL_NS) {
            return;
        }
        int loaded = 0;
        String center = "?";
        String debug = "?";
        try {
            Minecraft mc = Minecraft.getMinecraft();
            WorldClient world = mc.theWorld;
            RenderGlobal rg = mc.renderGlobal;
            if (rg != null) {
                debug = rg.getDebugInfoRenders() + " | queue " + rg.chunksToUpdate.size();
                int n = 0;
                int dirty = 0;
                int empty = 0;
                for (Object info : (List<?>) rg.renderInfos) {
                    RenderChunk rc = (RenderChunk) ChunkGraph.INFO_CHUNK.get(info);
                    n++;
                    if (rc.isNeedsUpdate()) {
                        dirty++;
                    }
                    if (rc.compiledChunk == CompiledChunk.DUMMY) {
                        empty++;
                    }
                }
                debug +=
                        " | visible "
                                + n
                                + ", marked "
                                + dirty
                                + ", uncompiled "
                                + empty
                                + ", searches "
                                + searches
                                + " (from arrival "
                                + ChunkSync.extraSearches
                                + ")";
                ChunkSync.extraSearches = 0;
                searches = 0;
            }
            if (world != null) {
                int cx = (int) Math.floor(x) >> 4;
                int cz = (int) Math.floor(z) >> 4;
                for (int dx = -3; dx <= 3; dx++) {
                    for (int dz = -3; dz <= 3; dz++) {
                        boolean e = world.getChunkFromChunkCoords(cx + dx, cz + dz).isEmpty();
                        if (!e) {
                            loaded++;
                        }
                        if (dx == 0 && dz == 0) {
                            center = e ? "empty" : "with data";
                        }
                    }
                }
            }
        } catch (RuntimeException | LinkageError e) {
            center = "error " + e;
        }
        Log.info(
                "watchdog",
                "state %d | solid list %.0f per frame | camera %.0f %.0f: column %s, %d"
                        + " of 49 around with data | %d uploads | %s",
                state,
                (double) listed / frames,
                x,
                z,
                center,
                loaded,
                uploads,
                debug);
        listed = 0;
        uploads = 0;
        frames = 0;
        at = now;
    }
}
