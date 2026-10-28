/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import dev.xavier.stein.bridge.core.Log;

import net.minecraft.client.renderer.RegionRenderCache;
import net.minecraft.util.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.biome.BiomeGenBase;

import java.lang.ref.WeakReference;
import java.util.Arrays;

/**
 * Grass, foliage and water tints (BiomeColorHelper.getGrassColorAtPos, getFoliageColorAtPos,
 * getWaterColorAtPos) with each position's colour computed once per chunk build.
 *
 * <p>The game's tint is the average of the colour at the nine positions around the block, at the
 * block's height: for each one, the biome there (IBlockAccess.getBiomeGenForCoords) and the biome's
 * colour for that position (getGrassColorAtPos and getFoliageColorAtPos read the temperature, which
 * depends on the height, and some biomes add noise by x and z; the water colour is a field of the
 * biome). Every tinted face asks again: a leaf block with six faces sums 54 colours, and the
 * neighbouring blocks repeat most of them. On the build threads that was 16.5% of their time on the
 * benchmark (leaves 8.4%, water 4.4%, grass 3.2%).
 *
 * <p>When the block access is a chunk build's RegionRenderCache, the colour of each position of the
 * region (20 x 20 x 20 blocks) is computed the first time it is needed, with the same calls as the
 * game (the same biome lookup, the same biome method, a MutableBlockPos at that position), and kept
 * for the rest of that build; the nine are then summed and divided with the game's own arithmetic,
 * so the tint is the same int. A build sees a new RegionRenderCache, so nothing is kept from one
 * build to the next (the biome of a column can arrive with a chunk; the arrival schedules a new
 * build anyway). Positions outside the region and any other block access (items, particles, the
 * world itself) take the game's computation. One cache per thread: the build threads work at the
 * same time. {@code -Dsteinbridge.opt.vanillaBiomeColors=true} always takes the game's computation;
 * {@code -Dsteinbridge.opt.checkBiomeColors=true} computes both and compares.
 */
public final class BiomeColors {

    private static final boolean VANILLA = EngineOptions.VANILLA_BIOME_COLORS;
    private static final boolean CHECK = !VANILLA && EngineOptions.CHECK_BIOME_COLORS;

    private static final int GRASS = 0;
    private static final int FOLIAGE = 1;
    private static final int WATER = 2;

    /** The side of a RegionRenderCache's block arrays: the chunk, 1 block of margin and 1 more. */
    private static final int SIDE = 20;

    private static final int CELLS = SIDE * SIDE * SIDE;

    private static final ThreadLocal<Cache> CACHE = ThreadLocal.withInitial(Cache::new);

    private static final CheckReport REPORT = new CheckReport(5_000_000_000L);

    private BiomeColors() {}

    /** BiomeColorHelper.getGrassColorAtPos(access, pos). */
    public static int grass(Object access, Object pos) {
        return tint((IBlockAccess) access, (BlockPos) pos, GRASS);
    }

    /** BiomeColorHelper.getFoliageColorAtPos(access, pos). */
    public static int foliage(Object access, Object pos) {
        return tint((IBlockAccess) access, (BlockPos) pos, FOLIAGE);
    }

    /** BiomeColorHelper.getWaterColorAtPos(access, pos). */
    public static int water(Object access, Object pos) {
        return tint((IBlockAccess) access, (BlockPos) pos, WATER);
    }

    private static int tint(IBlockAccess access, BlockPos pos, int kind) {
        if (VANILLA || !(access instanceof RegionRenderCache region)) {
            return vanilla(access, pos, kind);
        }
        Cache c = CACHE.get();
        c.use(region);
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();
        int lx = x - 1 - c.x0;
        int ly = y - c.y0;
        int lz = z - 1 - c.z0;
        if (lx < 0 || lx + 2 >= SIDE || ly < 0 || ly >= SIDE || lz < 0 || lz + 2 >= SIDE) {
            return vanilla(access, pos, kind);
        }
        int r = 0;
        int g = 0;
        int b = 0;
        for (int dz = 0; dz < 3; dz++) {
            for (int dx = 0; dx < 3; dx++) {
                int l = c.color(access, kind, lx + dx, ly, lz + dz);
                r += (l & 0xFF0000) >> 16;
                g += (l & 0xFF00) >> 8;
                b += l & 0xFF;
            }
        }
        int tint = (r / 9 & 255) << 16 | (g / 9 & 255) << 8 | b / 9 & 255;
        if (CHECK) {
            check(tint, vanilla(access, pos, kind), pos, kind);
        }
        return tint;
    }

    /** The game's average over the nine positions, as BiomeColorHelper.getColorAtPos does it. */
    private static int vanilla(IBlockAccess access, BlockPos pos, int kind) {
        int r = 0;
        int g = 0;
        int b = 0;
        for (BlockPos.MutableBlockPos p :
                BlockPos.getAllInBoxMutable(pos.add(-1, 0, -1), pos.add(1, 0, 1))) {
            int l = colorAt(access.getBiomeGenForCoords(p), p, kind);
            r += (l & 0xFF0000) >> 16;
            g += (l & 0xFF00) >> 8;
            b += l & 0xFF;
        }
        return (r / 9 & 255) << 16 | (g / 9 & 255) << 8 | b / 9 & 255;
    }

    /** What the game's ColorResolver of that kind returns for the biome at the position. */
    private static int colorAt(BiomeGenBase biome, BlockPos pos, int kind) {
        return switch (kind) {
            case GRASS -> biome.getGrassColorAtPos(pos);
            case FOLIAGE -> biome.getFoliageColorAtPos(pos);
            default -> biome.waterColorMultiplier;
        };
    }

    private static synchronized void check(int ours, int game, BlockPos pos, int kind) {
        if (REPORT.compared(ours == game)) {
            Log.info(
                    "biomecolors",
                    "DIVERGENCE at %s kind %d: %06x here x %06x in the game",
                    pos,
                    kind,
                    ours,
                    game);
        }
        REPORT.maybeReport("biomecolors");
    }

    /** One thread's colours for the region of the build it is doing. */
    private static final class Cache {
        private WeakReference<RegionRenderCache> region = new WeakReference<>(null);
        private int x0;
        private int y0;
        private int z0;

        /** Which build each cell was computed in: a cell is valid when it equals {@link #build}. */
        private final int[] stamps = new int[3 * CELLS];

        private final int[] colors = new int[3 * CELLS];
        private int build;
        private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        void use(RegionRenderCache r) {
            if (region.get() == r) {
                return;
            }
            region = new WeakReference<>(r);
            BlockPos origin = r.position;
            x0 = origin.getX();
            y0 = origin.getY();
            z0 = origin.getZ();
            if (++build == 0) {
                // after 2^32 builds the stamps could match again: start over
                Arrays.fill(stamps, 0);
                build = 1;
            }
        }

        int color(IBlockAccess access, int kind, int lx, int ly, int lz) {
            int i = kind * CELLS + (lx * SIDE + ly) * SIDE + lz;
            if (stamps[i] == build) {
                return colors[i];
            }
            BlockPos.MutableBlockPos p = pos.set(x0 + lx, y0 + ly, z0 + lz);
            int l = colorAt(access.getBiomeGenForCoords(p), p, kind);
            colors[i] = l;
            stamps[i] = build;
            return l;
        }
    }
}
