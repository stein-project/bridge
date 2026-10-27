/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import dev.xavier.stein.bridge.core.Log;

import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ClassInheritanceMultiMap;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Copies of the renderInfos list holding only the chunks in which renderBlockLayer's and
 * renderEntities' loop bodies can do something. See {@link ChunkGraph} for the switches.
 */
final class ChunkInfoFilter {
    // ---- renderInfos loops over filtered copies ----
    //
    // Looking at the sky, almost all of renderInfos is empty air sections, and two loops walk all
    // of it every frame: renderBlockLayer (on each layer, isLayerEmpty of each chunk: 13% of the
    // render thread) and renderEntities (the section's chunk, entity list, tile entities: 30%).
    // Both now walk a copy of renderInfos, in the same order, holding only the chunks in which the
    // loop body can do something:
    //  - renderBlockLayer (the loop uses size/get): chunks with some layer used;
    //  - renderEntities (both loops use iterator): chunks with an entity in the section or with a
    //    tile entity.
    // Each loop body keeps testing what it tested: only the iterations that did nothing are gone.
    // The copy is rebuilt when the list changes (setupTerrain creates another on every search), the
    // size, the compilations version, the world or — for entities, instead of the compilations
    // version — which chunks have tile entities ({@link TileChunks}) and the set of sections
    // holding
    // an entity ({@link EntitySections}, read from the client's entities on every use: the entities
    // version went up on every move of the integrated server's entities too, and the chunk map
    // version on every chunk arriving, so the copy was rebuilt on almost every frame at distance
    // 32). The reordering of the translucent layer (iterator in renderBlockLayer) keeps using the
    // whole list.
    // -Dsteinbridge.opt.vanillaInfoFilter=true: the loops over the whole list.
    // -Dsteinbridge.opt.checkInfoFilter=true: rebuilds the copy on every use and compares it with
    // the stored one.

    private static final boolean VANILLA_FILTER =
            ChunkGraph.VANILLA || EngineOptions.VANILLA_INFO_FILTER;
    private static final boolean CHECK_FILTER = !VANILLA_FILTER && EngineOptions.CHECK_INFO_FILTER;

    /** The RenderGlobal of the last search: the current renderInfos is its own. */
    static Object lastRg;

    // Copies are swapped whole on every rebuild, never modified: a loop in progress keeps going
    // on the copy it started with (the build thread raises the compilations version at any
    // moment, including in the middle of a loop).
    private static ArrayList<Object> layerInfos = new ArrayList<>();
    private static Object layerSrc;
    private static int layerSize = -1;
    private static int layerCompiled;
    private static int layerFrame;

    private static ArrayList<Object> entityInfos = new ArrayList<>();
    private static Object entitySrc;
    private static int entitySize = -1;
    private static int entityTiles;
    private static long[] entitySections = new long[0];
    private static Object entityWorld;

    private static final CheckReport REPORT = new CheckReport(5_000_000_000L);
    private static long filterInconclusive;

    private ChunkInfoFilter() {}

    /**
     * List.size() in renderBlockLayer's layer loop: the only size() in the method, which gives the
     * loop's bound. The copy is checked (and rebuilt, if needed) only here.
     */
    static int layerSize(Object list) {
        return VANILLA_FILTER ? ((List<?>) list).size() : layers(list).size();
    }

    /**
     * List.get(i) in renderBlockLayer's layer loop: reads the copy pinned at the size() that opened
     * the loop. Rebuilding here would change the list in the middle of the loop, with the bound
     * already computed.
     */
    static Object layerGet(Object list, int i) {
        if (VANILLA_FILTER || list != layerSrc) {
            return ((List<?>) list).get(i);
        }
        return layerInfos.get(i);
    }

    private static List<Object> layers(Object list) {
        List<?> src = (List<?>) list;
        int compiled = ChunkGraph.compiledVersion;
        int frame = ChunkSearch.frames;
        // a chunk compiled after the frame's first layer joins on the next frame: each layer asked
        // again, with chunks finishing on the build threads all the time, and the copy was rebuilt
        // up to 4 times a frame at distance 32
        if (list != layerSrc
                || src.size() != layerSize
                || (compiled != layerCompiled && frame != layerFrame)) {
            layerInfos = fillLayers(src);
            layerSrc = list;
            layerSize = src.size();
            layerCompiled = compiled;
            layerFrame = frame;
        } else if (CHECK_FILTER && compiled == layerCompiled) {
            ArrayList<Object> fresh = fillLayers(src);
            checkFilter(fresh.equals(layerInfos), ChunkGraph.compiledVersion != compiled, "layers");
        }
        return layerInfos;
    }

    /** The chunks with some layer used, in list order, in a new list. */
    private static ArrayList<Object> fillLayers(List<?> src) {
        ArrayList<Object> out = new ArrayList<>(sizeLike(layerInfos));
        for (int i = 0, n = src.size(); i < n; i++) {
            Object info = src.get(i);
            if (!((RenderChunk) ChunkGraph.INFO_CHUNK.get(info)).getCompiledChunk().isEmpty()) {
                out.add(info);
            }
        }
        return out;
    }

    /** List.iterator() in renderEntities: in both renderInfos loops, the filtered copy. */
    static Object entityIterator(Object list) {
        Object rg = lastRg;
        if (VANILLA_FILTER || rg == null || list != ((RenderGlobal) rg).renderInfos) {
            return ((List<?>) list).iterator();
        }
        return entityList(rg, list).iterator();
    }

    private static List<Object> entityList(Object rg, Object list) {
        List<?> src = (List<?>) list;
        World world = ((RenderGlobal) rg).theWorld;
        int compiled = ChunkGraph.compiledVersion;
        TileChunks.update();
        int tiles = TileChunks.version;
        EntitySections.collect(world);
        long[] sections = EntitySections.keys();
        int n = EntitySections.count();
        if (list != entitySrc
                || src.size() != entitySize
                || tiles != entityTiles
                || world != entityWorld
                || !Arrays.equals(sections, 0, n, entitySections, 0, entitySections.length)) {
            entityInfos = fillEntities(src, sections, n);
            entitySrc = list;
            entitySize = src.size();
            entityTiles = tiles;
            entitySections = Arrays.copyOf(sections, n);
            entityWorld = world;
        } else if (CHECK_FILTER) {
            ArrayList<Object> fresh = fillEntities(src, sections, n);
            checkFilter(
                    fresh.equals(entityInfos), ChunkGraph.compiledVersion != compiled, "entities");
        }
        if (CHECK_FILTER) {
            // the sections from the entities against each chunk's own lists (the game's loop)
            checkFilter(
                    entityInfos.equals(fillEntitiesByChunk(world, src)),
                    ChunkGraph.compiledVersion != compiled,
                    "entities by chunk");
        }
        return entityInfos;
    }

    /**
     * The chunks with an entity in the section (from {@link EntitySections}) or with a tile entity
     * in the compilation (from {@link TileChunks}), in list order.
     */
    private static ArrayList<Object> fillEntities(List<?> src, long[] sections, int n) {
        EntitySections.index(sections, n);
        ArrayList<Object> out = new ArrayList<>(sizeLike(entityInfos));
        for (int i = 0, size = src.size(); i < size; i++) {
            Object info = src.get(i);
            RenderChunk rc = (RenderChunk) ChunkGraph.INFO_CHUNK.get(info);
            BlockPos p = rc.position;
            boolean has = EntitySections.has(p.x >> 4, p.y / 16, p.z >> 4);
            if (!has) {
                has = TileChunks.has(rc);
            }
            if (has) {
                out.add(info);
            }
        }
        return out;
    }

    /**
     * The check's reference: the section's own list (chunk.getEntityLists()[y / 16], like the
     * game's loop) or a tile entity in the compilation, in list order.
     */
    private static ArrayList<Object> fillEntitiesByChunk(World world, List<?> src) {
        ArrayList<Object> out = new ArrayList<>();
        for (int i = 0, n = src.size(); i < n; i++) {
            Object info = src.get(i);
            RenderChunk rc = (RenderChunk) ChunkGraph.INFO_CHUNK.get(info);
            BlockPos p = rc.position;
            ClassInheritanceMultiMap<?>[] sections =
                    ((Chunk) ChunkGraph.chunkAt(world, p)).getEntityLists();
            boolean has = !sections[p.y / 16].isEmpty();
            if (!has) {
                has = !rc.getCompiledChunk().getTileEntities().isEmpty();
            }
            if (has) {
                out.add(info);
            }
        }
        return out;
    }

    /**
     * A new copy starts with room for the previous one and a margin: it is rebuilt after every
     * search, and growing one copy at a time allocated almost 1 GB in 50 s at distance 32.
     */
    private static int sizeLike(List<?> previous) {
        return previous.size() + previous.size() / 8 + 16;
    }

    private static void checkFilter(boolean same, boolean compiledChanged, String what) {
        if (compiledChanged) {
            // A chunk changed compilation on the build thread during the comparison.
            filterInconclusive++;
        } else if (REPORT.compared(same)) {
            Log.info("filter", "DIVERGENCE in copy of " + what);
        }
        if (REPORT.due()) {
            Log.info(
                    "filter",
                    "check: %d comparisons, %d divergences, %d inconclusive",
                    REPORT.checks,
                    REPORT.divergences,
                    filterInconclusive);
        }
    }
}
