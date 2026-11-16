/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.RenderManager;

/**
 * Fix for name tags tilting the wrong way in the front view (F5 twice): the camera-facing pitch is
 * negated there, as from 1.9.
 *
 * <p>RenderManager.cacheActiveRenderInfo turns playerViewY around by 180 degrees in the front view,
 * where the camera looks back at the player, but leaves playerViewX as the player's pitch, while
 * that camera's pitch is the opposite one. Everything that faces the camera with these two angles
 * (name tags, the snowball/egg/potion/fireball/XP sprites, the fishing bobber, item frame names)
 * tilted away from it when looking up or down. 1.9 sets playerViewX to minus the pitch in that
 * view; this does the same right after the call in RenderGlobal.renderEntities, so every reader of
 * the field agrees, including Stein's own name tags ({@code SteinLabelEmit}, {@code
 * SteinLabelTail}), which read the same field. {@code -Dsteinbridge.opt.vanillaTagPitch=true}
 * brings back 1.8.9's tilt.
 */
public final class TagPitch {
    private TagPitch() {}

    private static final boolean VANILLA = RenderFixOptions.VANILLA_TAG_PITCH;

    /** The front view's value of GameSettings.thirdPersonView. */
    private static final int FRONT_VIEW = 2;

    /** After RenderManager.cacheActiveRenderInfo in RenderGlobal.renderEntities. */
    public static void cached() {
        if (VANILLA) {
            return;
        }
        RenderManager rm = Minecraft.getMinecraft().getRenderManager();
        if (rm.options != null && rm.options.thirdPersonView == FRONT_VIEW) {
            rm.playerViewX = -rm.playerViewX;
        }
    }
}
