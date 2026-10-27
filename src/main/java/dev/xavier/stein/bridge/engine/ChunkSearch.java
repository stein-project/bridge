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
import net.minecraft.client.renderer.ViewFrustum;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.client.renderer.culling.ICamera;
import net.minecraft.entity.Entity;
import net.minecraft.util.EnumFacing;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Set;

/**
 * The whole chunk visibility search (the setupTerrain loop) over arrays, with one reusable
 * ContainerLocalRenderInformation per chunk. See {@link ChunkGraph} for the switches.
 */
final class ChunkSearch {
    // ---- whole search ----
    //
    // The setupTerrain search loop (while (!queue.isEmpty()) {...}) creates, per visited chunk, a
    // ContainerLocalRenderInformation, an EnumSet with the origin faces (the parent's copy plus
    // one), a LinkedList node and the EnumFacing.values() array. Here the loop is the same —
    // queue in the same order, the same tests in the same order and with the same short-circuits,
    // the same visited marking —, with the queue in an array, the origin faces in a byte and one
    // ContainerLocalRenderInformation per chunk, reused between searches (whoever reads the
    // renderInfos list only uses the chunk; the other fields were only read by the search).

    private static boolean armed;
    private static RenderGlobal bfsRg;
    private static Entity bfsEntity;
    private static ICamera bfsCamera;
    private static double bfsPt;
    private static int bfsFrame;
    private static List<Object> checkStart;
    private static List<?> previous;

    /** setupTerrain calls: one per rendered frame (the layer copy's rebuilds, ChunkInfoFilter). */
    static int frames;

    private static Object[] qInfo = new Object[0];
    private static RenderChunk[] qChunk = new RenderChunk[0];
    private static byte[] qFacing = new byte[0];
    private static byte[] qMask = new byte[0];
    private static final boolean VANILLA_BFS = ChunkGraph.VANILLA || EngineOptions.VANILLA_BFS;
    private static final boolean CHECK_BFS = !VANILLA_BFS && EngineOptions.CHECK_BFS;

    private static int checkCompiled;
    private static final CheckReport REPORT = new CheckReport(5_000_000_000L);
    private static long bfsChunks;
    private static long bfsInconclusive;

    private ChunkSearch() {}

    /** The RenderGlobal whose setupTerrain is running, or null outside it. */
    static RenderGlobal currentRg() {
        return bfsRg;
    }

    /**
     * setupTerrain call in renderWorldPass: stores the arguments for the search and calls the
     * original.
     */
    static void setupTerrain(
            Object rgObj,
            Object entityObj,
            double partialTicks,
            Object cameraObj,
            int frame,
            boolean spectator) {
        RenderGlobal rg = (RenderGlobal) rgObj;
        Entity entity = (Entity) entityObj;
        ICamera camera = (ICamera) cameraObj;
        ChunkInfoFilter.lastRg = rg;
        // A spectator inside a block changes a local variable of the search: the game's loop stays
        // there.
        armed = !VANILLA_BFS && !spectator;
        bfsRg = rg;
        bfsEntity = entity;
        bfsCamera = camera;
        bfsPt = partialTicks;
        bfsFrame = frame;
        frames++;
        // the list of the last search: the game replaces it before its loop (SearchRate)
        previous = rg.renderInfos;
        if (!armed || CHECK_BFS) {
            SearchRate.forget();
        }
        try {
            rg.setupTerrain(entity, partialTicks, camera, frame, spectator);
        } finally {
            armed = false;
            checkStart = null;
            bfsRg = null;
            bfsEntity = null;
            bfsCamera = null;
            previous = null;
        }
    }

    /**
     * The queue.isEmpty() of the search loop: on the first turn, the whole search; the queue comes
     * back empty.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static boolean bfsLoop(Object queue) {
        Queue<Object> q = (Queue<Object>) queue;
        if (!armed) {
            boolean empty = q.isEmpty();
            if (empty && checkStart != null) {
                List<Object> start = checkStart;
                checkStart = null;
                compare(start);
            }
            return empty;
        }
        armed = false;
        if (CHECK_BFS) {
            ChunkWatch.searches++;
            // The game does the search; at the end it is redone here and the two lists are
            // compared.
            checkStart = new ArrayList<>(q);
            checkCompiled = ChunkGraph.compiledVersion;
            return q.isEmpty();
        }
        List<Object> start = new ArrayList<>(q);
        q.clear();
        if (SearchRate.keep(bfsRg, bfsEntity, start, previous)) {
            bfsRg.renderInfos = (List) previous;
            return true;
        }
        ChunkWatch.searches++;
        SearchRate.searched(bfsRg, bfsEntity, start, ChunkGraph.compiledVersion);
        List<Object> out = (List<Object>) (List<?>) bfsRg.renderInfos;
        if (out instanceof ArrayList<Object> list && previous != null) {
            // the game's new list grew one copy at a time: 2.2 GB in 50 s at distance 32
            list.ensureCapacity(previous.size() + previous.size() / 8 + 16);
        }
        bfs(start, out, bfsFrame);
        return true;
    }

    @SuppressWarnings("unchecked")
    private static void bfs(List<Object> start, List<Object> out, int frame) {
        if (ChunkGraph.dx == null) {
            ChunkGraph.offsets();
        }
        RenderGlobal rg = bfsRg;
        Entity e = bfsEntity;
        FrustumBox.begin(bfsCamera);
        double partialTicks = bfsPt;
        // The BlockPos of the player's chunk, as setupTerrain computes it (interpolated position).
        double lx = e.lastTickPosX;
        double lz = e.lastTickPosZ;
        int px = BlockMath.floor((lx + (e.posX - lx) * partialTicks) / 16.0) * 16;
        int pz = BlockMath.floor((lz + (e.posZ - lz) * partialTicks) / 16.0) * 16;
        int limit = rg.renderDistanceChunks * 16;
        ViewFrustum vf = rg.viewFrustum;
        int cy = vf.countChunksY;
        int cx = vf.countChunksX;
        int cz = vf.countChunksZ;
        RenderChunk[] chunks = vf.renderChunks;
        boolean many = rg.mc.renderChunksMany;
        int cap = chunks.length + start.size() + 1;
        if (qInfo.length < cap) {
            qInfo = new Object[cap];
            qChunk = new RenderChunk[cap];
            qFacing = new byte[cap];
            qMask = new byte[cap];
        }
        int tail = 0;
        for (Object info : start) {
            EnumFacing f = (EnumFacing) ChunkGraph.INFO_FACING.get(info);
            int mask = 0;
            for (Object s : (Set<Object>) ChunkGraph.INFO_SET.get(info)) {
                mask |= 1 << ((Enum<?>) s).ordinal();
            }
            qInfo[tail] = info;
            qChunk[tail] = (RenderChunk) ChunkGraph.INFO_CHUNK.get(info);
            qFacing[tail] = (byte) (f == null ? -1 : f.ordinal());
            qMask[tail] = (byte) mask;
            tail++;
        }
        int head = 0;
        while (head < tail) {
            Object info = qInfo[head];
            RenderChunk rc = qChunk[head];
            int facing = qFacing[head];
            int mask = qMask[head];
            qInfo[head] = null;
            qChunk[head] = null;
            head++;
            out.add(info);
            ChunkCorner.sync(rc);
            int rx = ChunkCorner.x(rc);
            int ry = ChunkCorner.y(rc);
            int rz = ChunkCorner.z(rc);
            long visibility = -1L; // ChunkVisibility bits, read on first use
            for (int f = 0; f < 6; f++) {
                // getRenderChunkOffset: the neighbouring position, the distance and height tests
                // and the ViewFrustum's circular index.
                int ox = rx + ChunkGraph.dx[f] * 16;
                int oy = ry + ChunkGraph.dy[f] * 16;
                int oz = rz + ChunkGraph.dz[f] * 16;
                RenderChunk n = null;
                if (Math.abs(px - ox) <= limit
                        && oy >= 0
                        && oy < 256
                        && Math.abs(pz - oz) <= limit) {
                    int j = BlockMath.bucket(oy);
                    if (j >= 0 && j < cy) {
                        int i = BlockMath.bucket(ox) % cx;
                        if (i < 0) {
                            i += cx;
                        }
                        int k = BlockMath.bucket(oz) % cz;
                        if (k < 0) {
                            k += cz;
                        }
                        n = chunks[(k * cy + j) * cx + i];
                    }
                }
                // The game's tests, in the same order.
                if (many && (mask & (1 << ChunkGraph.opposite[f])) != 0) {
                    continue;
                }
                if (many && facing >= 0) {
                    if (visibility == -1L) {
                        visibility = ChunkVisibility.of(rc);
                    }
                    if (!ChunkVisibility.visible(visibility, ChunkGraph.opposite[facing], f)) {
                        continue;
                    }
                }
                if (n == null || !n.setFrameIndex(frame) || !FrustumBox.inside(n)) {
                    continue;
                }
                Object ni = ChunkGraph.RC_INFO.get(n);
                if (ni == null) {
                    ni = newInfo(rg, n, ChunkGraph.facings[f]);
                    ChunkGraph.RC_INFO.set(n, ni);
                }
                qInfo[tail] = ni;
                qChunk[tail] = n;
                qFacing[tail] = (byte) f;
                qMask[tail] = (byte) (mask | 1 << f);
                tail++;
            }
        }
    }

    /** A ContainerLocalRenderInformation (a class that cannot be named here), counter 0. */
    private static Object newInfo(RenderGlobal rg, RenderChunk chunk, EnumFacing facing) {
        try {
            return (Object)
                    ChunkGraph.INFO_NEW.invokeExact(
                            (Object) rg, (Object) chunk, (Object) facing, 0);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    /** Check: the search done here, with another frame index, against the list the game built. */
    @SuppressWarnings("unchecked")
    private static void compare(List<Object> start) {
        if (start.isEmpty()) {
            return;
        }
        int fake = bfsFrame ^ 0x40000000;
        for (Object info : start) {
            boolean ignored = ((RenderChunk) ChunkGraph.INFO_CHUNK.get(info)).setFrameIndex(fake);
        }
        List<Object> ours = new ArrayList<>();
        bfs(start, ours, fake);
        List<Object> vanilla = (List<Object>) (List<?>) bfsRg.renderInfos;
        boolean same = ours.size() == vanilla.size();
        for (int i = 0; same && i < ours.size(); i++) {
            same =
                    ChunkGraph.INFO_CHUNK.get(ours.get(i))
                            == ChunkGraph.INFO_CHUNK.get(vanilla.get(i));
        }
        if (ChunkGraph.compiledVersion != checkCompiled) {
            // A chunk changed compilation between the two searches: the comparison is worthless.
            bfsInconclusive++;
        } else {
            bfsChunks += vanilla.size();
            if (REPORT.compared(same)) {
                Log.info(
                        "bfs",
                        "DIVERGENCE: "
                                + ours.size()
                                + " chunks here x "
                                + vanilla.size()
                                + " in the game");
            }
        }
        if (REPORT.due()) {
            Log.info(
                    "bfs",
                    "check: %d searches, %d chunks, %d divergences, %d inconclusive",
                    REPORT.checks,
                    bfsChunks,
                    REPORT.divergences,
                    bfsInconclusive);
        }
    }
}
