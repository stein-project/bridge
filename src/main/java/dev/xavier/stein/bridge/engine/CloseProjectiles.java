/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.RenderSnowball;
import net.minecraft.entity.Entity;

/**
 * Fix for thrown items covering the thrower's view: a snowball, egg, ender pearl, potion or bottle
 * o' enchanting is not drawn in its first two ticks while it is near the camera's entity.
 *
 * <p>A thrown item spawns at the thrower's eyes, so for its first frames it is drawn a few
 * centimetres in front of the camera, a big flat sprite over the screen: in PvP a pearl or a splash
 * potion hid the target at the moment of throwing. Later versions (from 1.15) skip these two ticks
 * in the same way; the entity exists, moves and collides exactly as before, only its sprite waits.
 * Checked in Visuals.doRender, where every entity's render passes (an instanceof and, for the
 * thrown items only, two reads). {@code -Dsteinbridge.opt.vanillaCloseProjectiles=true} draws them
 * from the first tick, as 1.8.9.
 */
public final class CloseProjectiles {

    private CloseProjectiles() {}

    private static final boolean VANILLA = RenderFixOptions.VANILLA_CLOSE_PROJECTILES;

    /** Squared distance to the camera's entity below which a young thrown item is not drawn. */
    private static final double NEAR_SQ = 3.5 * 3.5;

    /** Whether this render of this entity is skipped. */
    public static boolean hidden(Object render, Object entity) {
        if (VANILLA || !(render instanceof RenderSnowball)) {
            return false;
        }
        Entity e = (Entity) entity;
        if (e.ticksExisted >= 2) {
            return false;
        }
        Entity view = Minecraft.getMinecraft().getRenderViewEntity();
        return view != null && view.getDistanceSqToEntity(e) < NEAR_SQ;
    }
}
