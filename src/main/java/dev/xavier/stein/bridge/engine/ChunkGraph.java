/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import static java.lang.invoke.MethodType.methodType;

import dev.xavier.stein.bridge.core.Log;

import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.client.renderer.chunk.VisGraph;
import net.minecraft.util.EnumFacing;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Set;

/**
 * Entry points, game handles and versions of the cheaper chunk visibility search
 * (RenderGlobal.setupTerrain), with the same result. The work itself lives in {@link ChunkCull},
 * {@link ChunkAtCache}, {@link ChunkInfoFilter} and {@link ChunkSearch}; this class keeps the
 * reflection handles and the mutable state they share (the direction offsets and the versions,
 * always read as {@code ChunkGraph.x}, so every write to shared state is visible at a glance) and
 * the frozen patcher targets, which delegate.
 *
 * <p>1.8.9 redoes the whole search — over the chunks from the camera, face by face — whenever the
 * player moves, the camera turns or there is still a chunk waiting to be built: walking or looking
 * around, that is nearly every frame. In the walk through the city, 83% of the frames above 1.5 ms
 * had 0.8 ms or more in it alone. Two hot spots, replaced here:
 *
 * <ul>
 *   <li>each chunk's neighbour (getRenderChunkOffset): the game reads the neighbouring position
 *       from an EnumMap kept in the chunk and divides a BlockPos's coordinates; here, the same
 *       position (the chunk's + 16 in the direction), the same distance and height tests and the
 *       same circular ViewFrustum index, in plain arithmetic;
 *   <li>the visible faces of the camera's section (getVisibleFacings): the game sweeps the 4096
 *       blocks of the section on every search; here the result is kept while the camera stays in
 *       the same block and no block changes — a version goes up before every block write in a
 *       section, every chunk data swap and every load or unload.
 * </ul>
 *
 * <p>{@code -Dsteinbridge.opt.vanillaCull=true}: both as in the game. {@code
 * -Dsteinbridge.opt.checkCull=true}: computes both things both ways and compares.
 */
public final class ChunkGraph {
    /** Interval between check summaries, in nanoseconds. */
    private static final long REPORT_INTERVAL_NS = 5_000_000_000L;

    static final boolean VANILLA = EngineOptions.VANILLA_CULL;
    static final boolean CHECK = !VANILLA && EngineOptions.CHECK_CULL;

    // The container of the search (RenderGlobal.ContainerLocalRenderInformation: renderChunk a,
    // facing b, setFacing c) is a package-private class with a private constructor: it cannot be
    // named in the readable-name build, so it stays reached by handle. So does the field the
    // patcher adds to RenderChunk (sbInfo).
    static final MethodHandle INFO_NEW;
    static final VarHandle INFO_CHUNK;
    static final VarHandle INFO_FACING;
    static final VarHandle INFO_SET;
    static final VarHandle RC_INFO;

    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            Class<?> info = Obf.cls("bfr$a");
            RC_INFO =
                    MethodHandles.privateLookupIn(RenderChunk.class, l)
                            .findVarHandle(RenderChunk.class, "sbInfo", Object.class);
            MethodHandles.Lookup il = MethodHandles.privateLookupIn(info, l);
            INFO_NEW =
                    il.findConstructor(
                                    info,
                                    methodType(
                                            void.class,
                                            RenderGlobal.class,
                                            RenderChunk.class,
                                            EnumFacing.class,
                                            int.class))
                            .asType(
                                    methodType(
                                            Object.class,
                                            Object.class,
                                            Object.class,
                                            Object.class,
                                            int.class));
            INFO_CHUNK = il.findVarHandle(info, "a", RenderChunk.class);
            INFO_FACING = il.findVarHandle(info, "b", EnumFacing.class);
            INFO_SET = il.findVarHandle(info, "c", Set.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
        if (CHECK) {
            Log.info(
                    "cull",
                    "check: neighbour and visible faces computed both ways and" + " compared");
        } else if (VANILLA) {
            Log.info("cull", "visibility search as in 1.8.9 (vanilla)");
        }
    }

    /**
     * Offset of each direction (by ordinal), read from the EnumFacing itself on the first search.
     */
    static int[] dx;

    static int[] dy;
    static int[] dz;

    static EnumFacing[] facings;
    static int[] opposite;

    /**
     * Version of the client world's blocks: goes up before every change that the section sweep
     * would see.
     */
    static volatile int blockEpoch;

    /** Version of the client's chunk map: goes up before every load and unload. */
    static volatile int chunkEpoch;

    static volatile int entityVersion;

    /**
     * Compilations version: goes up after every setCompiledChunk and stopCompileTask. An empty
     * chunk receives its CompiledChunk on the build thread, during the search: the check discards
     * the comparison when that happens between the game's search and this one.
     */
    static volatile int compiledVersion;

    private static final CheckReport REPORT = new CheckReport(REPORT_INTERVAL_NS);

    private ChunkGraph() {}

    /** Before every block write in a section, chunk data swap and chunk load or unload. */
    public static void bump() {
        blockEpoch++;
    }

    /** The blocks version, for the stored light ({@link LightCache}). */
    static int blockEpoch() {
        return blockEpoch;
    }

    /**
     * Before every ChunkProviderClient.loadChunk and unloadChunk: the chunk map and the blocks
     * change.
     */
    public static void chunkMap() {
        chunkEpoch++;
        blockEpoch++;
    }

    /** World.getChunkFromBlockCoords inside renderEntities. */
    public static Object chunkAt(Object world, Object pos) {
        return ChunkAtCache.chunkAt(world, pos);
    }

    /** Before every Chunk.addEntity, removeEntity and removeEntityAtIndex. */
    public static void entities() {
        entityVersion++;
    }

    /**
     * List.size() in renderBlockLayer's layer loop: the only size() in the method, which gives the
     * loop's bound. The copy is checked (and rebuilt, if needed) only here.
     */
    public static int layerSize(Object list) {
        return ChunkInfoFilter.layerSize(list);
    }

    /**
     * List.get(i) in renderBlockLayer's layer loop: reads the copy pinned at the size() that opened
     * the loop.
     */
    public static Object layerGet(Object list, int i) {
        return ChunkInfoFilter.layerGet(list, i);
    }

    /** List.iterator() in renderEntities: in both renderInfos loops, the filtered copy. */
    public static Object entityIterator(Object list) {
        return ChunkInfoFilter.entityIterator(list);
    }

    /** After every RenderChunk.setCompiledChunk and stopCompileTask. */
    public static void compiled() {
        compiledVersion++;
    }

    /**
     * RenderGlobal.getRenderChunkOffset: the neighbouring chunk in the direction, or null beyond
     * the distance and the height.
     */
    public static Object neighbor(Object rg, Object player, Object renderChunk, Object facing) {
        return ChunkCull.neighbor(rg, player, renderChunk, facing);
    }

    /**
     * RenderGlobal.getVisibleFacings: the faces of the camera's section through which the view
     * exits. The return goes as Object (the patcher erases the types and the caller casts to Set).
     */
    public static Object visibleFacings(Object rg, Object pos) {
        return ChunkCull.visibleFacings(rg, pos);
    }

    /**
     * EnumFacing.values() in the visibility search's loop (the game's own loop: vanillaBfs and
     * checkBfs): the one cached array instead of a copy per visited chunk. The loop only reads it.
     */
    public static Object facingValues() {
        return ChunkFlood.facings();
    }

    /** Body of VisGraph.floodFill(int) (bhw.a(I)): see {@link ChunkFlood}. */
    public static Object floodFill(Object graph, int start) {
        return ChunkFlood.fill((VisGraph) graph, start);
    }

    static void offsets() {
        EnumFacing[] values = EnumFacing.values();
        int[] x = new int[values.length];
        int[] y = new int[values.length];
        int[] z = new int[values.length];
        int[] o2 = new int[values.length];
        for (EnumFacing v : values) {
            int o = v.ordinal();
            x[o] = v.getFrontOffsetX();
            y[o] = v.getFrontOffsetY();
            z[o] = v.getFrontOffsetZ();
            o2[o] = v.getOpposite().ordinal();
        }
        facings = values;
        opposite = o2;
        dy = y;
        dz = z;
        dx = x;
    }

    /**
     * setupTerrain call in renderWorldPass: stores the arguments for the search and calls the
     * original.
     */
    public static void setupTerrain(
            Object rg,
            Object entity,
            double partialTicks,
            Object camera,
            int frame,
            boolean spectator) {
        ChunkSearch.setupTerrain(rg, entity, partialTicks, camera, frame, spectator);
    }

    /**
     * The queue.isEmpty() of the search loop: on the first turn, the whole search; the queue comes
     * back empty.
     */
    public static boolean bfsLoop(Object queue) {
        return ChunkSearch.bfsLoop(queue);
    }

    static void check(boolean ok, String what) {
        if (REPORT.compared(ok)) {
            Log.info("cull", "DIVERGENCE in " + what);
        }
        REPORT.maybeReport("cull");
    }
}
