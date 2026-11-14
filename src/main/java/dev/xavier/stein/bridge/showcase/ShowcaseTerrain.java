/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.showcase;

import net.minecraft.block.BlockFlower;
import net.minecraft.block.BlockOldLeaf;
import net.minecraft.block.BlockOldLog;
import net.minecraft.block.BlockPlanks;
import net.minecraft.block.BlockTallGrass;
import net.minecraft.block.BlockTorch;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.EnumFacing;

/**
 * The landscape of the video settings' showcase, built by our own code (no map of anyone's): a
 * valley seen from a ridge, looking west, where the sun sets and the moon goes down. In the valley
 * a lake with a sand beach, grass hills with trees, flowers and tall grass; on the far side a range
 * whose tops hold snow; on the left slope a lava pool; on the ridge, by the camera, a small cabin
 * with torches and glowstone. Everything the Lens options change has something to show it: lights
 * at night, water, bright sand by day, outlines against the sky, distant terrain.
 *
 * <p>Deterministic: the same blocks every time (a fixed seed), so the scene is the same for every
 * player and between openings.
 */
final class ShowcaseTerrain {

    /** Half the side of the scene, in blocks: chunks from -RADIUS/16 to RADIUS/16 - 1. */
    static final int RADIUS = 128;

    static final int WATER_LEVEL = 62;

    /** Where the camera stands (the ridge, east side) and the point it looks at. */
    static final double CAMERA_X = 70.5;

    static final double CAMERA_Z = 0.5;

    /** Where blocks go: the showcase world's chunks. */
    interface Sink {
        void set(int x, int y, int z, IBlockState state);

        IBlockState get(int x, int y, int z);
    }

    private static final long SEED = 0x5737_4E1A_2026L;

    /**
     * Where the lit things stand, all inside the camera's view (it looks west, +Z on its left): the
     * cabin on the near left shore, lamp posts around the lake, the lava pool on the right slope.
     */
    private static final int CABIN_X = 30;

    private static final int CABIN_Z = 22;
    private static final int LAVA_X = 14;
    private static final int LAVA_Z = -30;
    private static final int[][] LAMPS = {{24, 10}, {22, -12}, {-4, 30}, {-14, -28}, {-30, 6}};

    private ShowcaseTerrain() {}

    /** The ground's height at (x, z). */
    static int height(int x, int z) {
        double h = 66 + 6 * noise(x / 48.0, z / 48.0) + 2.5 * noise(x / 17.0, z / 17.0, 7);
        // the lake: a bowl in the middle of the valley, its shore bent by noise
        double lake =
                Math.hypot(x - 2, z * 1.15)
                        + 7 * noise(x / 13.0, z / 13.0, 11)
                        // one lake: none on the near slope, under the camera
                        + 2 * Math.max(0, x - 22);
        h -= 12 * (1 - smooth(20, 40, lake));
        // the range on the far (west) side, and its peaks
        double west = smooth(-40, -100, x);
        h += west * (28 + 14 * noise(x / 23.0, z / 23.0, 3));
        // the ridge the camera stands on (east)
        h += 7 * smooth(45, 62, x) * (1 - smooth(85, 100, x));
        // the scene's edge rises, so the view never reaches its end
        double edge = Math.max(Math.abs(x), Math.abs(z));
        h += 30 * smooth(RADIUS - 40, RADIUS - 4, edge);
        return (int) Math.round(h);
    }

    /** Fills the whole scene. */
    static void build(Sink world) {
        for (int x = -RADIUS; x < RADIUS; x++) {
            for (int z = -RADIUS; z < RADIUS; z++) {
                column(world, x, z);
            }
        }
        decorate(world);
        cabin(world, CABIN_X, CABIN_Z);
        for (int[] lamp : LAMPS) {
            lamp(world, lamp[0], lamp[1]);
        }
        lavaPool(world, LAVA_X, LAVA_Z);
    }

    private static void column(Sink w, int x, int z) {
        int top = height(x, z);
        boolean beach = top <= WATER_LEVEL + 1 && top >= WATER_LEVEL - 2;
        boolean high = top >= 86;
        w.set(x, 0, z, Blocks.bedrock.getDefaultState());
        for (int y = 1; y <= top; y++) {
            IBlockState s;
            if (y < top - 4) {
                s = Blocks.stone.getDefaultState();
            } else if (beach) {
                s =
                        y > top - 2
                                ? Blocks.sand.getDefaultState()
                                : Blocks.sandstone.getDefaultState();
            } else if (top < WATER_LEVEL - 2) {
                s = y == top ? Blocks.gravel.getDefaultState() : Blocks.dirt.getDefaultState();
            } else if (high) {
                s = Blocks.stone.getDefaultState();
            } else {
                s = y == top ? Blocks.grass.getDefaultState() : Blocks.dirt.getDefaultState();
            }
            w.set(x, y, z, s);
        }
        if (top >= 90 || high && (hash(x, z) & 3) == 0) {
            w.set(x, top + 1, z, Blocks.snow_layer.getDefaultState());
        }
        for (int y = top + 1; y <= WATER_LEVEL; y++) {
            w.set(x, y, z, Blocks.water.getDefaultState());
        }
    }

    /** Trees, flowers and tall grass on the grass, by a hash of the position. */
    private static void decorate(Sink w) {
        for (int x = -RADIUS + 8; x < RADIUS - 8; x++) {
            for (int z = -RADIUS + 8; z < RADIUS - 8; z++) {
                int top = height(x, z);
                if (w.get(x, top, z).getBlock() != Blocks.grass || top <= WATER_LEVEL + 1) {
                    continue;
                }
                long r = hash(x, z);
                int roll = (int) (r & 1023);
                boolean nearCabin = reserved(x, z);
                // trees in groves: a few where the grove noise is high, none in the open
                double grove = noise(x / 21.0, z / 21.0, 5);
                int trees = grove > 0.25 ? 14 : grove > -0.1 ? 3 : 0;
                boolean view = Math.abs(x - CAMERA_X) < 8 && Math.abs(z - CAMERA_Z) < 8;
                if (roll < trees && !nearCabin && !view) {
                    tree(w, x, top + 1, z, (r >> 12 & 3) == 0);
                } else if (roll < 60) {
                    w.set(x, top + 1, z, flower(r >> 20));
                } else if (roll < 260) {
                    w.set(
                            x,
                            top + 1,
                            z,
                            Blocks.tallgrass
                                    .getDefaultState()
                                    .withProperty(
                                            BlockTallGrass.TYPE, BlockTallGrass.EnumType.GRASS));
                }
            }
        }
    }

    /** Kept clear of trees: around the cabin, the lamps and the lava pool. */
    private static boolean reserved(int x, int z) {
        if (Math.abs(x - CABIN_X - 3) < 9 && Math.abs(z - CABIN_Z - 3) < 9) {
            return true;
        }
        if (Math.abs(x - LAVA_X) < 7 && Math.abs(z - LAVA_Z) < 6) {
            return true;
        }
        for (int[] lamp : LAMPS) {
            if (Math.abs(x - lamp[0]) < 3 && Math.abs(z - lamp[1]) < 3) {
                return true;
            }
        }
        return false;
    }

    private static IBlockState flower(long r) {
        switch ((int) (r & 7)) {
            case 0:
            case 1:
                return Blocks.yellow_flower.getDefaultState();
            case 2:
                return red(BlockFlower.EnumFlowerType.BLUE_ORCHID);
            case 3:
                return red(BlockFlower.EnumFlowerType.ALLIUM);
            case 4:
                return red(BlockFlower.EnumFlowerType.OXEYE_DAISY);
            case 5:
                return red(BlockFlower.EnumFlowerType.ORANGE_TULIP);
            default:
                return red(BlockFlower.EnumFlowerType.POPPY);
        }
    }

    private static IBlockState red(BlockFlower.EnumFlowerType type) {
        return Blocks.red_flower
                .getDefaultState()
                .withProperty(Blocks.red_flower.getTypeProperty(), type);
    }

    private static void tree(Sink w, int x, int y, int z, boolean birch) {
        BlockPlanks.EnumType kind = birch ? BlockPlanks.EnumType.BIRCH : BlockPlanks.EnumType.OAK;
        int trunk = 4 + (int) (hash(z, x) & 2);
        IBlockState log = Blocks.log.getDefaultState().withProperty(BlockOldLog.VARIANT, kind);
        IBlockState leaf =
                Blocks.leaves
                        .getDefaultState()
                        .withProperty(BlockOldLeaf.VARIANT, kind)
                        .withProperty(BlockOldLeaf.CHECK_DECAY, false)
                        .withProperty(BlockOldLeaf.DECAYABLE, false);
        for (int dy = trunk - 3; dy <= trunk + 1; dy++) {
            int r = dy >= trunk ? 1 : 2;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    boolean corner = Math.abs(dx) == r && Math.abs(dz) == r;
                    if (corner && (dy == trunk + 1 || (hash(x + dx, z + dz + dy) & 1) == 0)) {
                        continue;
                    }
                    if (w.get(x + dx, y + dy, z + dz).getBlock() == Blocks.air) {
                        w.set(x + dx, y + dy, z + dz, leaf);
                    }
                }
            }
        }
        for (int dy = 0; dy < trunk; dy++) {
            w.set(x, y + dy, z, log);
        }
    }

    /** A small cabin on the ridge: planks, a door gap, glass, torches and glowstone. */
    private static void cabin(Sink w, int x0, int z0) {
        int y0 = height(x0, z0) + 1;
        IBlockState wall = Blocks.planks.getDefaultState();
        IBlockState corner =
                Blocks.log
                        .getDefaultState()
                        .withProperty(BlockOldLog.VARIANT, BlockPlanks.EnumType.SPRUCE);
        for (int x = x0; x < x0 + 7; x++) {
            for (int z = z0; z < z0 + 6; z++) {
                // a foundation down to the ground, wherever the slope leaves it
                for (int y = Math.min(height(x, z), y0 - 3); y < y0; y++) {
                    w.set(x, y, z, Blocks.cobblestone.getDefaultState());
                }
                for (int y = y0; y < y0 + 5; y++) {
                    boolean edgeX = x == x0 || x == x0 + 6;
                    boolean edgeZ = z == z0 || z == z0 + 5;
                    IBlockState s = Blocks.air.getDefaultState();
                    if (y == y0 + 4) {
                        s = Blocks.stonebrick.getDefaultState();
                    } else if (edgeX && edgeZ) {
                        s = corner;
                    } else if (edgeX || edgeZ) {
                        boolean window = y == y0 + 2 && (x == x0 + 3 || z == z0 + 2);
                        boolean door = x == x0 && z == z0 + 2 && y < y0 + 2;
                        s =
                                door
                                        ? Blocks.air.getDefaultState()
                                        : window ? Blocks.glass_pane.getDefaultState() : wall;
                    }
                    w.set(x, y, z, s);
                }
            }
        }
        w.set(x0 + 3, y0 + 3, z0 + 2, Blocks.glowstone.getDefaultState());
        torch(w, x0 - 1, y0 + 2, z0 + 1, EnumFacing.WEST);
        torch(w, x0 - 1, y0 + 2, z0 + 3, EnumFacing.WEST);
        // torches along the path from the door down to the shore
        for (int i = 1; i <= 3; i++) {
            int tx = x0 - 4 * i;
            int tz = z0 + 2 + (i % 2 == 0 ? 2 : -2);
            torch(w, tx, height(tx, tz) + 1, tz, EnumFacing.UP);
        }
        // a campfire of netherrack in front of the door
        int fx = x0 - 3;
        int fz = z0 - 2;
        int fy = height(fx, fz);
        w.set(fx, fy, fz, Blocks.netherrack.getDefaultState());
        w.set(fx, fy + 1, fz, Blocks.fire.getDefaultState());
    }

    /** A lamp post: three fence posts and a glowstone on top. */
    private static void lamp(Sink w, int x, int z) {
        int y = Math.max(height(x, z), WATER_LEVEL) + 1;
        for (int dy = 0; dy < 3; dy++) {
            w.set(x, y + dy, z, Blocks.oak_fence.getDefaultState());
        }
        w.set(x, y + 3, z, Blocks.glowstone.getDefaultState());
    }

    private static void torch(Sink w, int x, int y, int z, EnumFacing facing) {
        w.set(x, y, z, Blocks.torch.getDefaultState().withProperty(BlockTorch.FACING, facing));
    }

    /** A lava pool cut into the left slope, rimmed with stone. */
    private static void lavaPool(Sink w, int x0, int z0) {
        int y = height(x0, z0);
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                boolean rim = Math.abs(dx) == 3 || Math.abs(dz) == 2;
                for (int dy = 1; dy <= 4; dy++) {
                    w.set(x0 + dx, y + dy, z0 + dz, Blocks.air.getDefaultState());
                }
                w.set(
                        x0 + dx,
                        y,
                        z0 + dz,
                        rim ? Blocks.stonebrick.getDefaultState() : Blocks.lava.getDefaultState());
                w.set(x0 + dx, y - 1, z0 + dz, Blocks.stone.getDefaultState());
            }
        }
    }

    // ---- noise: smooth value noise from a hash, our own ----

    private static double smooth(double from, double to, double v) {
        double t = (v - from) / (to - from);
        t = Math.max(0, Math.min(1, t));
        return t * t * (3 - 2 * t);
    }

    private static double noise(double x, double z) {
        return noise(x, z, 0);
    }

    /** Value noise in -1..1, interpolated smoothly between lattice points. */
    private static double noise(double x, double z, int salt) {
        int x0 = (int) Math.floor(x);
        int z0 = (int) Math.floor(z);
        double fx = x - x0;
        double fz = z - z0;
        double sx = fx * fx * (3 - 2 * fx);
        double sz = fz * fz * (3 - 2 * fz);
        double a = lattice(x0, z0, salt);
        double b = lattice(x0 + 1, z0, salt);
        double c = lattice(x0, z0 + 1, salt);
        double d = lattice(x0 + 1, z0 + 1, salt);
        return (a + (b - a) * sx) + ((c + (d - c) * sx) - (a + (b - a) * sx)) * sz;
    }

    private static double lattice(int x, int z, int salt) {
        return (hash(x * 31 + salt, z) & 0xFFFF) / 32767.5 - 1;
    }

    private static long hash(int x, int z) {
        long h = SEED ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL);
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        return h;
    }
}
