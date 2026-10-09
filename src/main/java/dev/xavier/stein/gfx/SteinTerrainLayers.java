/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import static dev.xavier.stein.gfx.SteinTerrain.COMMAND_BYTES;
import static dev.xavier.stein.gfx.SteinTerrain.ONE_BUFFER;
import static dev.xavier.stein.gfx.SteinTerrain.ORIGIN_BYTES;

import dev.xavier.stein.bridge.core.Log;

import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The terrain's layer assembly and its cache: for each game layer, the draw order, the runs per
 * region and the indirect commands and origins, kept between frames and redone only when the game's
 * chunk list or the chunks' ranges change.
 *
 * <p>Pipeline: Stein GFX only.
 */
final class SteinTerrainLayers {
    private SteinTerrainLayers() {}

    /**
     * Each layer's draw kept between frames ({@code -Dsteinbridge.gfx.terrainCache=false} turns it
     * off): the order, the commands and the origins are only reassembled when the game's list
     * changes. See {@link #begin}.
     */
    static final boolean CACHE = SteinGfxOptions.TERRAIN_CACHE;

    /**
     * Cache check ({@code -Dsteinbridge.gfx.terrainCacheCheck=true}): every {@code
     * steinbridge.gfx.terrainCacheCheckEvery} uses of the cache (default 30) the layer is
     * reassembled from scratch and compared with the kept one, entry by entry; a difference counts
     * as a divergence (and the draw uses the reassembled one).
     */
    static final boolean CACHE_CHECK = CACHE && SteinGfxOptions.TERRAIN_CACHE_CHECK;

    private static final int CHECK_EVERY = Math.max(1, SteinGfxOptions.TERRAIN_CACHE_CHECK_EVERY);

    // The assembly of each layer and its cache.
    //
    // The game rebuilds each layer's list every frame (renderBlockLayer -> addRenderChunk), but it
    // is almost always the same as the previous frame's: it only changes when the visibility
    // search gives another answer (camera turned or changed chunk, chunk arrived) or a chunk was
    // built again. Each layer keeps what it assembled (Layer) and only reassembles when it
    // changes, at two levels:
    //  1. the game's RenderChunk list is the same, in the same order (comparison of references,
    //     over a contiguous array: it does not touch the objects), and no upload or release has
    //     happened since the assembly (epoch): nothing to read from the chunk;
    //  2. otherwise, each chunk's ranges (SteinTerrainSlice) are read as before; if they are the
    //     same as the assembly's (references: the SteinTerrainSlice is immutable), the assembly
    //     holds.
    // Reassembling is what used to be done every frame: the order (by region on the layers
    // without blending, the game's on the translucent one and with the single buffer), and from
    // it the commands and the origins in CPU memory. Per frame, two consecutive copies remain for
    // the rings (the origins are only rewritten when the camera changes block) — without walking
    // the objects of each chunk, which was the bulk of the cost (each one a cache miss).

    static final class Layer {
        /** Level 1: the game's RenderChunk list from the assembly and the ranges' version. */
        Object[] chunks = new Object[256];

        int chunkCount = -1;
        long epoch = -1;

        /** Level 2: the list's ranges (null: layer empty in the chunk) and the layer's blending. */
        Object[] handles = new Object[256];

        int handleCount = -1;
        boolean blend;

        /**
         * The assembly: entries in draw order, and the runs of consecutive chunks of the same
         * region.
         */
        int count;

        int missing;
        SteinTerrainSlice[] order = new SteinTerrainSlice[256];
        SteinTerrainRegion[] runRegion = new SteinTerrainRegion[16];
        int[] runEnd = new int[16];
        int runs;

        /**
         * Commands (20 bytes, base instance relative to the start of the layer's origins) and
         * origins (16 bytes).
         */
        ByteBuffer commandBytes = MemoryUtil.memAlloc(256 * COMMAND_BYTES);

        ByteBuffer originBytes = MemoryUtil.memAlloc(256 * ORIGIN_BYTES);

        /** The camera block of the kept origins. */
        boolean originValid;

        int originX;
        int originY;
        int originZ;

        /** The layer's last pipeline and its state key: no lookup per frame with the same state. */
        int pipeKey;

        SteinPipeline pipe;

        void ensure(int entries) {
            if (order.length < entries) {
                int c = Math.max(entries, order.length * 2);
                order = new SteinTerrainSlice[c];
                commandBytes = MemoryUtil.memRealloc(commandBytes, c * COMMAND_BYTES);
                originBytes = MemoryUtil.memRealloc(originBytes, c * ORIGIN_BYTES);
            }
            if (handles.length < entries) {
                handles = Arrays.copyOf(handles, Math.max(entries, handles.length * 2));
            }
        }

        void addRun(SteinTerrainRegion r, int end) {
            if (runs == runRegion.length) {
                runRegion = Arrays.copyOf(runRegion, runs * 2);
                runEnd = Arrays.copyOf(runEnd, runs * 2);
            }
            runRegion[runs] = r;
            runEnd[runs] = end;
            runs++;
        }
    }

    private static Layer[] layerCache = new Layer[4];

    /** The current layer (between {@link #begin} and {@link #draw}). */
    static Layer layer;

    /**
     * Version of the ranges: goes up on every upload and release (a chunk changed range or was left
     * without one).
     */
    static long epoch;

    /** {@link #begin} found level 1: the list was not read. */
    private static boolean listSame;

    /** This layer reassembles from scratch to compare with the kept one (check). */
    private static boolean checking;

    private static int checkTick;
    private static final Layer CHECK_LAYER = new Layer();
    private static long cacheChecks;
    private static long cacheDivergences;
    private static long checkReportAt = System.nanoTime();

    /** The ranges of the game's list, in its order (null: chunk without geometry in the layer). */
    private static Object[] list = new Object[4096];

    private static int n;
    private static SteinTerrainSlice[] gathered = new SteinTerrainSlice[4096];
    private static final List<SteinTerrainRegion> layerRegions = new ArrayList<>();
    private static int[] regionCount = new int[64];
    private static int layerMark;

    /** Layers via cache level 1, via level 2, and reassembled. */
    static long levelOneHits;

    static long levelTwoHits;
    static long builds;

    /**
     * Start of layer {@code ordinal} with the game's RenderChunk list. Returns whether the list
     * needs to be read (each chunk's ranges through {@link #add}); false: the kept assembly holds
     * (level 1) and {@link #draw} comes straight away.
     */
    static boolean begin(int ordinal, List<?> chunks) {
        if (ordinal >= layerCache.length) {
            layerCache = Arrays.copyOf(layerCache, ordinal + 1);
        }
        Layer d = layerCache[ordinal];
        if (d == null) {
            d = new Layer();
            layerCache[ordinal] = d;
        }
        layer = d;
        n = 0;
        checking = false;
        listSame = false;
        if (!CACHE) {
            return true;
        }
        int size = chunks.size();
        if (d.chunks.length < size) {
            d.chunks = new Object[Math.max(size, d.chunks.length * 2)];
            d.chunkCount = -1;
        }
        // compares and copies in the same pass (the kept array becomes the current list anyway)
        Object[] saved = d.chunks;
        boolean same = d.chunkCount == size;
        for (int i = 0; i < size; i++) {
            Object c = chunks.get(i);
            if (saved[i] != c) {
                saved[i] = c;
                same = false;
            }
        }
        d.chunkCount = size;
        if (same && d.epoch == epoch) {
            if (CACHE_CHECK && ++checkTick % CHECK_EVERY == 0) {
                checking = true;
                return true;
            }
            listSame = true;
            levelOneHits++;
            return false;
        }
        return true;
    }

    static void add(Object handle) {
        if (n == list.length) {
            list = Arrays.copyOf(list, n * 2);
        }
        list[n++] = handle; // no cast: the cast reads the object header (one cache miss per chunk)
    }

    /**
     * Brings the layer up to date after {@link #begin}: the kept assembly, the one that matches the
     * ranges just read, or a reassembly.
     */
    static void assemble(Layer d, boolean blend) {
        if (checking) {
            check(d, blend);
        } else if (!listSame) {
            if (CACHE && sameHandles(d, blend)) {
                levelTwoHits++;
            } else {
                build(d, list, n, blend);
                builds++;
            }
            d.epoch = epoch;
        } else if (d.blend != blend) {
            // blending changed on the same layer (no game layer does this; a mod could): the
            // order changes, from the kept ranges (still valid: the version is the same)
            build(d, d.handles, d.handleCount, blend);
            builds++;
        }
    }

    /** Level 2: the ranges read now are the assembly's (and the layer's blending the same). */
    private static boolean sameHandles(Layer d, boolean blend) {
        if (d.handleCount != n || d.blend != blend) {
            return false;
        }
        Object[] h = d.handles;
        Object[] l = list;
        for (int i = 0; i < n; i++) {
            if (h[i] != l[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Assembles the layer from {@code n} ranges of the game's list: the draw order, the runs per
     * region and the commands. The origins are left for drawing (they depend on the camera block).
     */
    private static void build(Layer d, Object[] handles, int n, boolean blend) {
        d.ensure(n);
        if (gathered.length < n) {
            gathered = new SteinTerrainSlice[Math.max(n, gathered.length * 2)];
        }
        int count = 0;
        for (int i = 0; i < n; i++) {
            Object h = handles[i];
            if (h != null) {
                gathered[count++] = (SteinTerrainSlice) h;
            }
        }
        SteinTerrainSlice[] order = d.order;
        if (blend || ONE_BUFFER) {
            // translucent: the game's order (far to near). Single buffer: the game's order on the
            // others too (near to far), with no need to group
            System.arraycopy(gathered, 0, order, 0, count);
        } else {
            byRegion(gathered, count, order);
        }
        Arrays.fill(gathered, 0, count, null);
        d.runs = 0;
        long c = MemoryUtil.memAddress(d.commandBytes);
        for (int i = 0; i < count; i++) {
            SteinTerrainSlice s = order[i];
            if (i > 0 && s.region != order[i - 1].region) {
                d.addRun(order[i - 1].region, i);
            }
            long q = c + (long) i * COMMAND_BYTES;
            MemoryUtil.memPutInt(q, s.count / 4 * 6);
            MemoryUtil.memPutInt(q + 4, 1);
            MemoryUtil.memPutInt(q + 8, 0);
            MemoryUtil.memPutInt(q + 12, s.first);
            MemoryUtil.memPutInt(q + 16, i);
        }
        if (count > 0) {
            d.addRun(order[count - 1].region, count);
        }
        if (count < d.count) {
            Arrays.fill(order, count, d.count, null); // does not hold old ranges
        }
        System.arraycopy(handles, 0, d.handles, 0, n);
        if (n < d.handleCount) {
            Arrays.fill(d.handles, n, d.handleCount, null);
        }
        d.handleCount = n;
        d.blend = blend;
        d.count = count;
        d.missing = n - count;
        d.originValid = false;
    }

    /** Each chunk's origin minus the camera block (integers: exact in float). */
    static void writeOrigins(Layer d, int camX, int camY, int camZ) {
        long o = MemoryUtil.memAddress(d.originBytes);
        SteinTerrainSlice[] order = d.order;
        for (int i = 0, count = d.count; i < count; i++) {
            SteinTerrainSlice s = order[i];
            long p = o + (long) i * ORIGIN_BYTES;
            MemoryUtil.memPutFloat(p, s.x - camX);
            MemoryUtil.memPutFloat(p + 4, s.y - camY);
            MemoryUtil.memPutFloat(p + 8, s.z - camZ);
            float[] born = s.born;
            if (born != null && born[0] == 0f) {
                born[0] = SteinChunkFade.now(); // the first time on screen
            }
            MemoryUtil.memPutFloat(p + 12, born == null ? 0f : born[0]);
        }
        d.originValid = true;
        d.originX = camX;
        d.originY = camY;
        d.originZ = camZ;
    }

    /**
     * Check: the layer the cache would give (level 1, without reading the list) against the
     * reassembly from scratch of the ranges read now. Divergence: the draw uses the reassembly.
     */
    private static void check(Layer d, boolean blend) {
        Layer f = CHECK_LAYER;
        build(f, list, n, blend);
        boolean same =
                d.handleCount == f.handleCount
                        && d.count == f.count
                        && d.runs == f.runs
                        && d.blend == blend
                        && d.missing == f.missing;
        for (int i = 0; same && i < f.handleCount; i++) {
            same = d.handles[i] == f.handles[i];
        }
        for (int i = 0; same && i < f.count; i++) {
            same = d.order[i] == f.order[i];
        }
        for (int i = 0; same && i < f.runs; i++) {
            same = d.runRegion[i] == f.runRegion[i] && d.runEnd[i] == f.runEnd[i];
        }
        if (same) {
            long a = MemoryUtil.memAddress(d.commandBytes);
            long b = MemoryUtil.memAddress(f.commandBytes);
            for (long i = 0, bytes = (long) f.count * COMMAND_BYTES; same && i < bytes; i += 4) {
                same = MemoryUtil.memGetInt(a + i) == MemoryUtil.memGetInt(b + i);
            }
        }
        cacheChecks++;
        if (!same) {
            if (cacheDivergences++ < 5) {
                Log.info(
                        "gfx",
                        "terrain: DIVERGENCE in the layer cache ("
                                + d.count
                                + " kept x "
                                + f.count
                                + " reassembled)");
            }
            build(d, list, n, blend);
            d.epoch = epoch;
        }
        long now = System.nanoTime();
        if (now - checkReportAt >= 5_000_000_000L) {
            checkReportAt = now;
            Log.info(
                    "gfx",
                    "terrain: cache check: %d comparisons, %d divergences",
                    cacheChecks,
                    cacheDivergences);
        }
    }

    /**
     * {@code count} ranges grouped by region in {@code out}, keeping the game's order within each
     * one (near to far: the GPU discards early what ends up behind). Only on the layers without
     * blending, with regions.
     */
    private static void byRegion(SteinTerrainSlice[] in, int count, SteinTerrainSlice[] out) {
        layerMark++;
        layerRegions.clear();
        for (int i = 0; i < count; i++) {
            SteinTerrainRegion r = in[i].region;
            if (r.mark != layerMark) {
                r.mark = layerMark;
                r.slot = layerRegions.size();
                layerRegions.add(r);
                if (r.slot == regionCount.length) {
                    regionCount = Arrays.copyOf(regionCount, r.slot * 2);
                }
                regionCount[r.slot] = 0;
            }
            regionCount[r.slot]++;
        }
        int at = 0;
        for (int k = 0; k < layerRegions.size(); k++) {
            int c = regionCount[k];
            regionCount[k] = at;
            at += c;
        }
        for (int i = 0; i < count; i++) {
            SteinTerrainSlice s = in[i];
            out[regionCount[s.region.slot]++] = s;
        }
    }
}
