/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import net.minecraft.block.Block;
import net.minecraft.block.BlockAnvil;
import net.minecraft.block.BlockChest;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.Entity;
import net.minecraft.init.Blocks;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

import java.util.List;

/**
 * The world the player moves in while a Stein Sync proxy runs its movement ({@code move}
 * negotiated; stein-sync's docs/protocol.md 5.5 and physics-spec.md 15.6, 15.7, 18): the client
 * collides with exactly the blocks the proxy's physics uses, so the two never disagree about where
 * the player can stand.
 *
 * <ul>
 *   <li><b>Chests and anvils</b>, only with {@code -Dsteinbridge.sync.stateBoxes=true}, collide
 *       with each cell's own box, from its state and neighbours. The game takes their collision box
 *       from bounds shared by every block of the kind, which whatever last aimed at, meshed or
 *       rained on one of them left there; the proxy cannot know that value. The shared bounds are
 *       not touched: drawing, the outline and the crosshair keep using them as before. Off by
 *       default: the server (Paper) still collides with its own shared bounds and set a Stein
 *       player standing at the cell's own box back, again and again, next to anvils (rubber-band);
 *       the proxy instead treats moves near these blocks as unsure.
 *   <li><b>Columns kept by the extended distance</b> ({@link ChunkKeep}: the server dropped them,
 *       the client keeps drawing them) read as air for movement and as not loaded for the gravity
 *       rule, as they are for the server and for the proxy. They stay on screen.
 * </ul>
 *
 * <p>The Stein Loader turns it on and off with the connection's {@code move} ({@link #setMove}).
 * Off, every hook does exactly what the game does: on a vanilla server, or a server without Stein
 * Sync, nothing changes. The hooks are the patcher rules of redirects-mods.tsv ("Stein Sync move");
 * they are called for every block the movement code reads (several hundred per tick), so they do
 * not allocate. Only the client's world is changed: the integrated server's never.
 */
public final class SyncMove {

    /** The connection negotiated {@code move}. Written on the game thread. */
    private static volatile boolean move;

    /**
     * Chests and anvils collide with each cell's own box in {@code move}: only while the server
     * agrees with it (see the class comment), so off unless asked for.
     */
    private static final boolean STATE_BOXES = Boolean.getBoolean("steinbridge.sync.stateBoxes");

    /**
     * Columns {@link ChunkKeep} holds that the server dropped. Game thread only: the hooks read it
     * only for the client's world, which only the game thread moves entities in.
     */
    private static final ColumnSet KEPT = new ColumnSet();

    /** Chest neighbours are read through this one position (game thread only, see above). */
    private static final BlockPos.MutableBlockPos NEIGHBOUR = new BlockPos.MutableBlockPos();

    // The state-based boxes (physics-spec.md 15.6, 15.7), in block units. The game's own bounds are
    // these values as floats, all exact in binary.
    private static final double EDGE = 0.0625;
    private static final double FAR_EDGE = 0.9375;
    private static final double CHEST_TOP = 0.875;
    private static final double ANVIL_INSET = 0.125;
    private static final double ANVIL_FAR = 0.875;

    private SyncMove() {}

    /**
     * The Stein Loader: the current connection's {@code move} turned on or off. Game thread.
     *
     * @param on true while a Stein Sync proxy runs the player's movement
     */
    public static void setMove(boolean on) {
        move = on;
    }

    /** Whether the movement world is in force now. */
    public static boolean active() {
        return move;
    }

    // ---- kept columns (ChunkKeep, game thread) ----

    static void kept(int x, int z) {
        KEPT.add(x, z);
    }

    static void released(int x, int z) {
        KEPT.remove(x, z);
    }

    static void releasedAll() {
        KEPT.clear();
    }

    /** Whether the column (x, z) is one ChunkKeep holds after the server dropped it. */
    static boolean isKept(int x, int z) {
        return KEPT.contains(x, z);
    }

    private static boolean masked(Object world, BlockPos pos) {
        return move
                && world instanceof WorldClient
                && !KEPT.isEmpty()
                && KEPT.contains(pos.getX() >> 4, pos.getZ() >> 4);
    }

    // ---- hooks (patcher/redirects-mods.tsv, "Stein Sync move") ----

    /**
     * In place of World.getBlockState where the movement reads blocks: the collision gathering
     * (World.getCollidingBoundingBoxes), the move's landing block (Entity.moveEntity), the block
     * contacts (Entity.doBlockCollisions), the grip (EntityLivingBase.moveEntityWithHeading), the
     * ladder (EntityLivingBase.isOnLadder), the liquids (World.handleMaterialAcceleration,
     * isAABBInMaterial, isMaterialInBB, isAnyLiquid) and the push out of blocks
     * (EntityPlayerSP.isOpenBlockSpace). A kept column reads as air in {@code move}.
     */
    public static Object movementBlock(Object world, Object pos) {
        BlockPos p = (BlockPos) pos;
        if (masked(world, p)) {
            return Blocks.air.getDefaultState();
        }
        return ((World) world).getBlockState(p);
    }

    /**
     * In place of Chunk.isLoaded in EntityLivingBase.moveEntityWithHeading (the gravity of a player
     * in a column the client has not received): a kept column is not loaded in {@code move}.
     */
    public static boolean columnLoaded(Object chunkObj) {
        Chunk chunk = (Chunk) chunkObj;
        if (move
                && !KEPT.isEmpty()
                && chunk.getWorld() instanceof WorldClient
                && KEPT.contains(chunk.xPosition, chunk.zPosition)) {
            return false;
        }
        return chunk.isLoaded();
    }

    /**
     * In place of Block.addCollisionBoxesToList in World.getCollidingBoundingBoxes: in {@code move}
     * with {@link #STATE_BOXES}, a chest or anvil adds the box of its own state (and, for a chest,
     * its neighbours) instead of the box the kind's shared bounds hold.
     */
    @SuppressWarnings("unchecked")
    public static void collisionBoxes(
            Object blockObj,
            Object worldObj,
            Object posObj,
            Object stateObj,
            Object maskObj,
            Object listObj,
            Object entityObj) {
        Block block = (Block) blockObj;
        World world = (World) worldObj;
        BlockPos pos = (BlockPos) posObj;
        IBlockState state = (IBlockState) stateObj;
        AxisAlignedBB mask = (AxisAlignedBB) maskObj;
        List<AxisAlignedBB> list = (List<AxisAlignedBB>) listObj;
        if (STATE_BOXES
                && move
                && world instanceof WorldClient
                && (block instanceof BlockChest || block instanceof BlockAnvil)) {
            AxisAlignedBB box =
                    block instanceof BlockChest
                            ? chestBox(block, world, pos)
                            : anvilBox(block.getMetaFromState(state), pos);
            if (mask.intersectsWith(box)) {
                list.add(box);
            }
            return;
        }
        block.addCollisionBoxesToList(world, pos, state, mask, list, (Entity) entityObj);
    }

    /** The chest box from its neighbours of the same kind (physics-spec.md 15.6). */
    private static AxisAlignedBB chestBox(Block chest, World world, BlockPos pos) {
        int side =
                chestSide(
                        sameChest(chest, world, pos, 0, -1),
                        sameChest(chest, world, pos, 0, 1),
                        sameChest(chest, world, pos, -1, 0),
                        sameChest(chest, world, pos, 1, 0));
        double minX = side == WEST ? 0.0 : EDGE;
        double maxX = side == EAST ? 1.0 : FAR_EDGE;
        double minZ = side == NORTH ? 0.0 : EDGE;
        double maxZ = side == SOUTH ? 1.0 : FAR_EDGE;
        return box(pos, minX, 0.0, minZ, maxX, CHEST_TOP, maxZ);
    }

    static final int SINGLE = 0;
    static final int NORTH = 1;
    static final int SOUTH = 2;
    static final int WEST = 3;
    static final int EAST = 4;

    /**
     * Which neighbour the chest's box reaches into: the first of north, south, west and east that
     * is a chest of the same kind, the order the game asks in.
     */
    static int chestSide(boolean north, boolean south, boolean west, boolean east) {
        if (north) {
            return NORTH;
        }
        if (south) {
            return SOUTH;
        }
        if (west) {
            return WEST;
        }
        return east ? EAST : SINGLE;
    }

    private static boolean sameChest(Block chest, World world, BlockPos pos, int dx, int dz) {
        NEIGHBOUR.set(pos.getX() + dx, pos.getY(), pos.getZ() + dz);
        return ((IBlockState) movementBlock(world, NEIGHBOUR)).getBlock() == chest;
    }

    /**
     * The anvil box from its metadata (physics-spec.md 15.7): facing west or east (odd metadata)
     * along X, else along Z.
     */
    static AxisAlignedBB anvilBox(int metadata, BlockPos pos) {
        return anvilAlongX(metadata)
                ? box(pos, 0.0, 0.0, ANVIL_INSET, 1.0, 1.0, ANVIL_FAR)
                : box(pos, ANVIL_INSET, 0.0, 0.0, ANVIL_FAR, 1.0, 1.0);
    }

    static boolean anvilAlongX(int metadata) {
        return (metadata & 1) != 0;
    }

    /** The box at the cell, added as the game adds its bounds to the cell's corner. */
    private static AxisAlignedBB box(
            BlockPos pos,
            double minX,
            double minY,
            double minZ,
            double maxX,
            double maxY,
            double maxZ) {
        double x = pos.getX();
        double y = pos.getY();
        double z = pos.getZ();
        return new AxisAlignedBB(x + minX, y + minY, z + minZ, x + maxX, y + maxY, z + maxZ);
    }
}
