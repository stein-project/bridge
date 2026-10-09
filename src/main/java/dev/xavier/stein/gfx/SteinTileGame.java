/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

package dev.xavier.stein.gfx;

import dev.xavier.stein.bridge.core.Log;

import net.minecraft.block.BlockChest;
import net.minecraft.client.renderer.tileentity.TileEntityChestRenderer;
import net.minecraft.client.renderer.tileentity.TileEntityEnderChestRenderer;
import net.minecraft.client.renderer.tileentity.TileEntitySignRenderer;
import net.minecraft.client.renderer.tileentity.TileEntitySkullRenderer;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.tileentity.TileEntityEnderChest;
import net.minecraft.tileentity.TileEntitySign;
import net.minecraft.tileentity.TileEntitySkull;

/**
 * The game classes the retained tile entities tell apart (the exact tile entity classes and their
 * exact renderers); loaded only when retention acts, which is when it logs that it is on.
 *
 * <p>Pipeline: Stein GFX only.
 */
final class SteinTileGame {
    private SteinTileGame() {}

    static final Class<?> CHEST_TE = TileEntityChest.class;
    static final Class<?> ENDER_TE = TileEntityEnderChest.class;
    static final Class<?> SIGN_TE = TileEntitySign.class;
    static final Class<?> SKULL_TE = TileEntitySkull.class;
    static final Class<?> CHEST_R = TileEntityChestRenderer.class;
    static final Class<?> ENDER_R = TileEntityEnderChestRenderer.class;
    static final Class<?> SIGN_R = TileEntitySignRenderer.class;
    static final Class<?> SKULL_R = TileEntitySkullRenderer.class;
    static final Class<?> BLOCK_CHEST = BlockChest.class;

    static {
        Log.info(
                "gfx",
                "tile entities retained: idle chests, ender chests, signs and skulls"
                        + " re-emitted without the game's renderer"
                        + (SteinTileRetain.CHECK
                                ? " (check every " + SteinTileRetain.CHECK_EVERY + " passes)"
                                : ""));
    }
}
