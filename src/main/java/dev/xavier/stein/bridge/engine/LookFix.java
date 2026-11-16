/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.EntityLivingBase;

/**
 * Fix for MC-67665, the "mouse delay": the local player's look vector follows the camera again.
 *
 * <p>1.8 made EntityLivingBase.getLook interpolate the head's yaw ({@code rotationYawHead}), which
 * for the local player trails the camera's yaw by a tick. EntityRenderer.getMouseOver takes the
 * target from getLook, so the block or entity under the crosshair lagged behind where the player
 * was looking, while the server already got the camera's yaw in the movement packets. Mojang fixed
 * it in 1.11 by giving the local player the plain Entity.getLook; this is that, as the body of
 * EntityLivingBase.getLook (rule in redirects-mc.tsv). Every other entity keeps the head's yaw, as
 * in 1.8.9. {@code -Dsteinbridge.opt.vanillaLook=true} brings back 1.8.9's behaviour.
 */
public final class LookFix {
    private LookFix() {}

    private static final boolean VANILLA = EngineOptions.VANILLA_LOOK;

    /** EntityLivingBase.getLook(partialTicks): the same float operations in the same order. */
    public static Object look(Object self, float partialTicks) {
        EntityLivingBase e = (EntityLivingBase) self;
        boolean camera = !VANILLA && e instanceof EntityPlayerSP;
        float yaw = camera ? e.rotationYaw : e.rotationYawHead;
        if (partialTicks == 1.0F) {
            return e.getVectorForRotation(e.rotationPitch, yaw);
        }
        float prevYaw = camera ? e.prevRotationYaw : e.prevRotationYawHead;
        float pitch = e.prevRotationPitch + (e.rotationPitch - e.prevRotationPitch) * partialTicks;
        float y = prevYaw + (yaw - prevYaw) * partialTicks;
        return e.getVectorForRotation(pitch, y);
    }
}
