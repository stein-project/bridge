/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;

/**
 * BlockPos.offset(facing) as a reused MutableBlockPos, at the call sites where the neighbour's
 * position is only read and dropped before the next one.
 *
 * <p>Every offset allocates a BlockPos. On the benchmark three call sites were most of the
 * allocation of their threads: World.getRawLight's six neighbours (about 25% of the integrated
 * server, light updates), the side test of BlockModelRenderer's ambient-occlusion and flat paths
 * (17% of the chunk build threads) and the corners of AmbientOcclusionFace.updateVertexBrightness
 * (about 8% more). The patcher sends only those call sites here (redirects-opt.tsv), and each was
 * read in the bytecode:
 *
 * <ul>
 *   <li>getRawLight passes the neighbour only to World.getLightFor, which reads its coordinates
 *       (isValid, isBlockLoaded, getChunkFromBlockCoords, Chunk.getLightFor) and copies it into a
 *       new BlockPos when it is below the world; no World subclass overrides it;
 *   <li>the side test passes it only to Block.shouldSideBeRendered and, on the flat path,
 *       Block.getMixedBrightnessForBlock; every override of both in the game reads the block access
 *       (RegionRenderCache or World getBlockState / getCombinedLight, which only index arrays or
 *       look the chunk up by coordinates) and offsets the position again into a new BlockPos; none
 *       writes a field, an array or a collection. The chunk build already hands those methods the
 *       MutableBlockPos of its own block loop;
 *   <li>updateVertexBrightness has no loop and 15 offset calls; its positions go only to
 *       getBlockState, getMixedBrightnessForBlock and further offsets (which read the receiver
 *       before taking the next slot).
 * </ul>
 *
 * So the callee sees the same coordinates and nothing keeps the object. One set of positions per
 * thread: the server, the render thread and every build thread use these at the same time. The
 * corners take a ring of 16, so the positions of one updateVertexBrightness call never share a
 * slot. {@code -Dsteinbridge.opt.vanillaRawLight=true} and {@code
 * -Dsteinbridge.opt.vanillaBlockNeighbours=true} allocate a BlockPos again, as the game does.
 */
public final class NeighbourPos {

    private static final boolean VANILLA_LIGHT = EngineOptions.VANILLA_RAW_LIGHT;
    private static final boolean VANILLA_BLOCK = EngineOptions.VANILLA_BLOCK_NEIGHBOURS;

    /** More slots than offset calls in one updateVertexBrightness (15); a power of two. */
    private static final int CORNERS = 16;

    private static final ThreadLocal<Slots> SLOTS = ThreadLocal.withInitial(Slots::new);

    private NeighbourPos() {}

    /** pos.offset(facing) in World.getRawLight. */
    public static Object light(Object pos, Object facing) {
        if (VANILLA_LIGHT) {
            return ((BlockPos) pos).offset((EnumFacing) facing);
        }
        return at(SLOTS.get().light, (BlockPos) pos, (EnumFacing) facing);
    }

    /**
     * pos.offset(facing) of the side test in BlockModelRenderer's ambient-occlusion and flat paths.
     */
    public static Object side(Object pos, Object facing) {
        if (VANILLA_BLOCK) {
            return ((BlockPos) pos).offset((EnumFacing) facing);
        }
        return at(SLOTS.get().side, (BlockPos) pos, (EnumFacing) facing);
    }

    /** pos.offset(facing) in AmbientOcclusionFace.updateVertexBrightness. */
    public static Object corner(Object pos, Object facing) {
        if (VANILLA_BLOCK) {
            return ((BlockPos) pos).offset((EnumFacing) facing);
        }
        Slots s = SLOTS.get();
        BlockPos.MutableBlockPos m = s.corners[s.next];
        s.next = (s.next + 1) & (CORNERS - 1);
        return at(m, (BlockPos) pos, (EnumFacing) facing);
    }

    /**
     * BlockPos.offset(facing, 1): the receiver's coordinates plus the facing's unit vector. The
     * receiver is read before the slot is written, so it may be another slot.
     */
    private static BlockPos at(BlockPos.MutableBlockPos m, BlockPos pos, EnumFacing facing) {
        int x = pos.getX() + facing.getFrontOffsetX();
        int y = pos.getY() + facing.getFrontOffsetY();
        int z = pos.getZ() + facing.getFrontOffsetZ();
        return m.set(x, y, z);
    }

    /** One thread's positions. */
    private static final class Slots {
        final BlockPos.MutableBlockPos light = new BlockPos.MutableBlockPos();
        final BlockPos.MutableBlockPos side = new BlockPos.MutableBlockPos();
        final BlockPos.MutableBlockPos[] corners = new BlockPos.MutableBlockPos[CORNERS];
        int next;

        Slots() {
            for (int i = 0; i < CORNERS; i++) {
                corners[i] = new BlockPos.MutableBlockPos();
            }
        }
    }
}
