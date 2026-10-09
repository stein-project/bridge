/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

package dev.xavier.stein.bridge.engine;

import net.minecraft.world.World;

/**
 * Game rain visible or not ("Rain" option in the Video Settings; the Fast preset turns it off).
 * When off, WorldClient.getRainStrength returns 0 to whoever draws (patcher rule, {@link
 * #rainStrength}): no drop, no snow, no splash, no rain sound (the game only plays it while
 * raining) and a clear sky. The world keeps raining for the server and for the rest of the game.
 */
public final class Rain {

    /** No rain: the client sees the world as if it were not raining. */
    private static volatile boolean hidden;

    private Rain() {}

    public static void setHidden(boolean on) {
        hidden = on;
    }

    /**
     * In place of WorldClient.getRainStrength where the client draws: the
     * EntityRenderer.renderRainSnow (the drop and the flake), the EntityRenderer.addRainParticles
     * (the splash and the sound) and the RenderGlobal.renderSky (the overcast sky). With the rain
     * hidden, zero; otherwise, the game's value.
     */
    public static float rainStrength(Object world, float partialTicks) {
        return hidden ? 0.0F : ((World) world).getRainStrength(partialTicks);
    }
}
