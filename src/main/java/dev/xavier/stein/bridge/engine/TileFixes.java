/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import net.minecraft.client.renderer.chunk.CompiledChunk;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;
import net.minecraft.tileentity.TileEntity;

/**
 * Fixes to which tile entities 1.8.9 draws:
 *
 * <ul>
 *   <li><b>Removed tile entities still drawn</b>: a chunk's compilation keeps its tile entities
 *       until the chunk is rebuilt, so a chest or sign broken a moment ago (already invalidated by
 *       the world) was still drawn for those frames.
 *       TileEntityRendererDispatcher.getSpecialRenderer gives no renderer to an invalid tile
 *       entity, so it is not drawn from the moment it is removed.
 *   <li><b>Beacon beam drawn twice</b>: RenderChunk.rebuildChunk puts a tile entity whose renderer
 *       draws outside the block (forceTileEntityRender: the beacon) both in the compiled chunk and
 *       in the world-wide set drawn every frame, so while its chunk was in view the blended beam
 *       went out twice, brighter. Here it goes only in the world-wide set (as Forge does from
 *       1.11).
 * </ul>
 *
 * Both run on the chunk build threads too: they read the tile entity's flag and the dispatcher's
 * renderer map, as the game's own code there does. Both pipelines: Stein GFX draws tile entities
 * through the game's dispatcher (a retained one is only kept for a renderer this returns, {@code
 * SteinTileKey.kindOf}), and its chunk lists read the compiled chunks' tile entities ({@link
 * TileChunks}). {@code -Dsteinbridge.opt.vanillaInvalidTiles=true} and {@code
 * -Dsteinbridge.opt.vanillaBeaconBeam=true} bring each bug back.
 */
public final class TileFixes {
    private TileFixes() {}

    private static final boolean VANILLA_INVALID = RenderFixOptions.VANILLA_INVALID_TILES;
    private static final boolean VANILLA_BEAM = RenderFixOptions.VANILLA_BEACON_BEAM;

    /** The body of TileEntityRendererDispatcher.getSpecialRenderer(tileEntity). */
    public static Object specialRenderer(Object self, Object tileEntity) {
        if (tileEntity == null) {
            return null;
        }
        TileEntity te = (TileEntity) tileEntity;
        if (!VANILLA_INVALID && te.isInvalid()) {
            return null;
        }
        return ((TileEntityRendererDispatcher) self).getSpecialRendererByClass(te.getClass());
    }

    /** CompiledChunk.addTileEntity(tileEntity) in RenderChunk.rebuildChunk. */
    public static void addTileEntity(Object compiled, Object tileEntity) {
        TileEntity te = (TileEntity) tileEntity;
        if (!VANILLA_BEAM) {
            // rebuildChunk only gets here with a renderer, and adds the tile entity to the
            // world-wide set right after when this is true.
            TileEntitySpecialRenderer<TileEntity> r =
                    TileEntityRendererDispatcher.instance.getSpecialRenderer(te);
            if (r != null && r.forceTileEntityRender()) {
                return;
            }
        }
        ((CompiledChunk) compiled).addTileEntity(te);
    }
}
