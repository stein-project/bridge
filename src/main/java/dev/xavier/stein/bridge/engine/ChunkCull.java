/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.ViewFrustum;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

/**
 * The cheaper part of the chunk visibility search: each chunk's neighbour (RenderGlobal.
 * getRenderChunkOffset) in plain arithmetic, and the visible faces of the camera's section
 * (getVisibleFacings) kept while the camera stays in the same block and no block changes. See
 * {@link ChunkGraph} for the game names and the {@code vanillaCull} / {@code checkCull} switches.
 */
final class ChunkCull {
    private static Object cachedRg;
    private static long cachedPos;
    private static int cachedEpoch;
    private static Set<?> cachedFacings;

    private ChunkCull() {}

    /**
     * RenderGlobal.getRenderChunkOffset: the neighbouring chunk in the direction, or null beyond
     * the distance and the height.
     */
    static Object neighbor(
            Object rgObj, Object playerObj, Object renderChunkObj, Object facingObj) {
        RenderGlobal rg = (RenderGlobal) rgObj;
        BlockPos player = (BlockPos) playerObj;
        RenderChunk renderChunk = (RenderChunk) renderChunkObj;
        EnumFacing facing = (EnumFacing) facingObj;
        if (ChunkGraph.VANILLA) {
            return rg.getRenderChunkOffset(player, renderChunk, facing);
        }
        if (ChunkGraph.dx == null) {
            ChunkGraph.offsets();
        }
        BlockPos p = renderChunk.position;
        int f = facing.ordinal();
        int ox = p.x + ChunkGraph.dx[f] * 16;
        int oy = p.y + ChunkGraph.dy[f] * 16;
        int oz = p.z + ChunkGraph.dz[f] * 16;
        int limit = rg.renderDistanceChunks * 16;
        RenderChunk r = null;
        if (Math.abs(player.x - ox) <= limit
                && oy >= 0
                && oy < 256
                && Math.abs(player.z - oz) <= limit) {
            // ViewFrustum.getRenderChunk: bucketInt by 16 and the grid's circular index.
            ViewFrustum vf = rg.viewFrustum;
            int cy = vf.countChunksY;
            int j = BlockMath.bucket(oy);
            if (j >= 0 && j < cy) {
                int cx = vf.countChunksX;
                int cz = vf.countChunksZ;
                int i = BlockMath.bucket(ox) % cx;
                if (i < 0) {
                    i += cx;
                }
                int k = BlockMath.bucket(oz) % cz;
                if (k < 0) {
                    k += cz;
                }
                r = vf.renderChunks[(k * cy + j) * cx + i];
            }
        }
        if (ChunkGraph.CHECK) {
            RenderChunk v = rg.getRenderChunkOffset(player, renderChunk, facing);
            ChunkGraph.check(v == r, "neighbour");
        }
        return r;
    }

    /**
     * RenderGlobal.getVisibleFacings: the faces of the camera's section through which the view
     * exits. The return goes as Object (the patcher erases the types and the caller casts to Set).
     */
    static Object visibleFacings(Object rgObj, Object posObj) {
        RenderGlobal rg = (RenderGlobal) rgObj;
        BlockPos pos = (BlockPos) posObj;
        if (ChunkGraph.VANILLA) {
            return rg.getVisibleFacings(pos);
        }
        long key = pack(pos);
        int epoch = ChunkGraph.blockEpoch;
        if (cachedFacings != null && rg == cachedRg && key == cachedPos && epoch == cachedEpoch) {
            Set<?> r = copy(cachedFacings);
            if (ChunkGraph.CHECK) {
                ChunkGraph.check(r.equals(rg.getVisibleFacings(pos)), "faces");
            }
            return r;
        }
        // The game mutates the returned set: a copy is kept.
        Set<?> fresh = rg.getVisibleFacings(pos);
        cachedFacings = copy(fresh);
        cachedRg = rg;
        cachedPos = key;
        cachedEpoch = epoch;
        return fresh;
    }

    private static long pack(BlockPos pos) {
        return BlockMath.pack(pos.x, pos.y, pos.z);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Set<?> copy(Set<?> s) {
        return s instanceof EnumSet ? ((EnumSet) s).clone() : new HashSet<>(s);
    }
}
