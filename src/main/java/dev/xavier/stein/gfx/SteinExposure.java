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
import net.minecraft.util.BlockPos;
import net.minecraft.world.EnumSkyBlock;

/**
 * The exposure of the Stein light's composite (SteinPostFx, the scene past white), from the place
 * the camera is in: fixed by day under the open sky, opening a little where the camera is enclosed
 * (a cave, a house) and at night.
 *
 * <p>Why: the exposure used to follow the image's mean luma, so the same land got brighter or
 * darker as the camera turned (more sky in view, a bright patch of sand); four ways of metering
 * each opened a new case. The place does not change when the camera turns, so the picture keeps its
 * brightness, as in a game with a set look.
 */
final class SteinExposure {

    /**
     * By day under the open sky: where the old image meter settled on open land (its ceiling), so
     * the day keeps the brightness it had.
     */
    private static final float BASE = 1.12f;

    /** Most the exposure opens over {@link #BASE}, enclosed or at night: caves stay dark. */
    private static final float OPEN = 0.08f;

    /** The night opens only part of {@link #OPEN}: the night stays night. */
    private static final float NIGHT_SHARE = 0.6f;

    /** Seconds for about two thirds of the way between the open and a cave. */
    private static final float EASE_SECONDS = 0.8f;

    /** The camera's block, reused (once per frame: nothing allocated). */
    private static final BlockPos.MutableBlockPos AT = new BlockPos.MutableBlockPos();

    private static float enclosed = -1f;
    private static long lastNs;

    private SteinExposure() {}

    /**
     * The exposure for this frame ({@link #BASE} by day outdoors); once per frame, render thread.
     */
    static float value() {
        Minecraft mc = Minecraft.getMinecraft();
        WorldClient world = mc.theWorld;
        Entity view = mc.getRenderViewEntity();
        if (world == null || view == null) {
            enclosed = -1f;
            return BASE;
        }
        float pt = mc.timer.renderPartialTicks;
        BlockPos at =
                AT.set(
                        floor(view.lastTickPosX + (view.posX - view.lastTickPosX) * pt),
                        floor(
                                view.lastTickPosY
                                        + (view.posY - view.lastTickPosY) * pt
                                        + view.getEyeHeight()),
                        floor(view.lastTickPosZ + (view.posZ - view.lastTickPosZ) * pt));
        float sky = world.isBlockLoaded(at) ? world.getLightFor(EnumSkyBlock.SKY, at) : 15f;
        // full sky light: the open; below 8 hardly any sky reaches
        float now = 1f - clamp01((sky - 7f) / 8f);
        long t = System.nanoTime();
        if (enclosed < 0f) {
            enclosed = now;
        } else {
            float dt = Math.min(0.5f, (t - lastNs) / 1e9f);
            enclosed += (now - enclosed) * (1f - (float) Math.exp(-dt / EASE_SECONDS));
        }
        lastNs = t;
        float sunUp = (float) Math.cos(world.getCelestialAngle(pt) * 2.0 * Math.PI);
        float night = smooth(clamp01((0.15f - sunUp) / 0.35f));
        return BASE + OPEN * Math.max(enclosed, NIGHT_SHARE * night);
    }

    private static int floor(double v) {
        return (int) Math.floor(v);
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static float smooth(float t) {
        return t * t * (3f - 2f * t);
    }
}
