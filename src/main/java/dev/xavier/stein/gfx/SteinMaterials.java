/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.Entity;
import net.minecraft.util.Vec3;

import org.lwjgl.system.MemoryUtil;

/**
 * The Lens's reflections (the "Reflections" option): blocks of shiny materials — gems (diamond,
 * emerald, lapis), gold, iron, ice, glass and polished stone (quartz, obsidian) — reflect the sky
 * around them and the sun (terrain.frag, {@code environment}). Which texels are of which material
 * is in {@link SteinGlow}'s mask (codes 6 to 11), by the sprites' names, so a resource pack's
 * textures keep their material. The sky reflected is the Stein sky's own colours: the horizon is
 * the fog's colour and the zenith the biome's sky, carried here in the frame block; only where the
 * sky light reaches (no bright sky in a cave). Gems and metals reflect mostly on their bright
 * texels, so the pixel art stays as drawn.
 *
 * <p>Off, the frame carries no zenith and the shader skips it. Pipeline: Stein GFX only.
 */
public final class SteinMaterials {

    private static volatile boolean on;

    /** The zenith of this frame's sky (the biome's, the game's colour space). */
    private static final float[] ZENITH = new float[3];

    private SteinMaterials() {}

    /** The Lens option (any thread). */
    public static void configure(boolean enabled) {
        on = enabled;
    }

    /** Whether the reflections act (the option on, the Stein mode: ray tracing). */
    static boolean on() {
        return on && SteinGfxOptions.ENABLED && SteinRayLight.active();
    }

    /** Once per world frame (the shadows' prepare): the sky's zenith for the reflections. */
    static void frame(WorldClient world) {
        Minecraft mc = Minecraft.getMinecraft();
        Entity view = mc.getRenderViewEntity();
        if (!on() || world == null || view == null) {
            ZENITH[0] = ZENITH[1] = ZENITH[2] = 0f;
            return;
        }
        Vec3 sky = world.getSkyColor(view, mc.timer.renderPartialTicks);
        // never all zero while on: the shader takes zero for "off" (a black night sky stays a
        // trace above it)
        ZENITH[0] = Math.max(1e-3f, (float) sky.xCoord);
        ZENITH[1] = Math.max(1e-3f, (float) sky.yCoord);
        ZENITH[2] = Math.max(1e-3f, (float) sky.zCoord);
    }

    /** The zenith into the frame block at {@code p} (cloudInfo.yzw); zero on the HUD or off. */
    static void write(long p, boolean world) {
        MemoryUtil.memPutFloat(p, world ? ZENITH[0] : 0f);
        MemoryUtil.memPutFloat(p + 4, world ? ZENITH[1] : 0f);
        MemoryUtil.memPutFloat(p + 8, world ? ZENITH[2] : 0f);
    }
}
