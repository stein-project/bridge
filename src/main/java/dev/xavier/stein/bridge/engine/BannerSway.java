/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import net.minecraft.tileentity.TileEntityBanner;
import net.minecraft.util.BlockPos;
import net.minecraft.world.World;

/**
 * Fix for banners that stutter and then freeze on old worlds: the sway is fed its phase instead of
 * the whole world time.
 *
 * <p>TileEntityBannerRenderer computes {@code t = (float) (7x + 9y + 13z) + (float) worldTime +
 * partialTicks} and sways by {@code cos(t * pi * 0.02)}, a period of 100 ticks. A float has 24
 * bits: past a few days of world time the partial ticks no longer change {@code t} (the banner
 * moves once a tick), and later {@code t} itself only changes every few ticks. Mojang (about 1.14)
 * takes {@code floorMod(hash + time, 100) + partialTicks}. Here World.getTotalWorldTime inside that
 * renderer returns {@code floorMod(hash + time, 100) - hash}, so the renderer's own sum comes out
 * as that phase, exactly while both terms fit a float (|hash| below 2^23, coordinates up to about
 * 300 000); further out only the time is reduced. The cosine sees the same angle modulo its period:
 * the same sway, with the precision back. The world time itself is read as always.
 *
 * <p>The banner is taken from the renderer's first call, TileEntityBanner.getWorld (render thread
 * only). Both pipelines: Stein GFX records the game's banner renderer (banners are not retained).
 * {@code -Dsteinbridge.opt.vanillaBannerSway=true} brings back 1.8.9's sum.
 */
public final class BannerSway {
    private BannerSway() {}

    private static final boolean VANILLA = RenderFixOptions.VANILLA_BANNER_SWAY;

    /** The sway's period in ticks. */
    private static final long PERIOD = 100L;

    /** Below this |hash|, both float terms of the renderer's sum are exact integers. */
    private static final int EXACT = 1 << 23;

    /** The banner being drawn. */
    private static TileEntityBanner banner;

    /** TileEntityBanner.getWorld() in TileEntityBannerRenderer.renderTileEntityAt. */
    public static Object world(Object te) {
        TileEntityBanner b = (TileEntityBanner) te;
        banner = b;
        return b.getWorld();
    }

    /** World.getTotalWorldTime() in TileEntityBannerRenderer.renderTileEntityAt. */
    public static long time(Object world) {
        long time = ((World) world).getTotalWorldTime();
        TileEntityBanner b = banner;
        banner = null;
        if (VANILLA || b == null) {
            return time;
        }
        BlockPos pos = b.getPos();
        // The renderer's int sum, overflow included.
        int hash = pos.getX() * 7 + pos.getY() * 9 + pos.getZ() * 13;
        if (hash <= -EXACT || hash >= EXACT) {
            return Math.floorMod(time, PERIOD);
        }
        return Math.floorMod((long) hash + time, PERIOD) - hash;
    }
}
