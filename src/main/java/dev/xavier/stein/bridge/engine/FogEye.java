/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.util.BlockPos;
import net.minecraft.world.World;

/**
 * Fix for the fog colour darkening with the block at the feet: its brightness is read at the eyes.
 *
 * <p>EntityRenderer.updateRenderer eases fogColor1 (the brightness the fog colour is multiplied by)
 * towards World.getLightBrightness at {@code new BlockPos(viewEntity)}, the block at the feet.
 * Standing in a dark block (half slabs, stairs, a door, a carpet under a roof...) the fog and sky
 * darkened although the eyes, a block and a half higher, were in the light. Here the same call
 * reads the block at the eyes, the camera's own block. Once a tick, on the client thread, with one
 * kept mutable position (the call only reads it). Both pipelines: the fog colour is the game's,
 * which Stein GFX reads from its state. {@code -Dsteinbridge.opt.vanillaFogLight=true} brings back
 * 1.8.9's feet.
 */
public final class FogEye {
    private FogEye() {}

    private static final boolean VANILLA = RenderFixOptions.VANILLA_FOG_LIGHT;

    private static final BlockPos.MutableBlockPos EYE = new BlockPos.MutableBlockPos();

    /** WorldClient.getLightBrightness(pos) in EntityRenderer.updateRenderer. */
    public static float brightness(Object world, Object pos) {
        World w = (World) world;
        Entity e = Minecraft.getMinecraft().getRenderViewEntity();
        if (VANILLA || e == null) {
            return w.getLightBrightness((BlockPos) pos);
        }
        EYE.set(
                BlockMath.floor(e.posX),
                BlockMath.floor(e.posY + (double) e.getEyeHeight()),
                BlockMath.floor(e.posZ));
        return w.getLightBrightness(EYE);
    }
}
