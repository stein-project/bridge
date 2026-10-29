/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;
import net.minecraft.world.EnumSkyBlock;

/**
 * Light in the air: the sun (or the moon) seen in the dust it crosses, part of the light shafts'
 * pass (post_rays.frag).
 *
 * <p>The screen's rays need the sun on the screen and sky around it; in a cave neither is there,
 * and the light coming in through an opening was invisible. Here each pixel walks the air between
 * the camera and what it shows, asks the sun's shadow map ({@link SteinShadows}) at each step
 * whether that point of the air is lit, and adds the light the dust there sends towards the camera
 * — more when looking towards the source. The light from an opening becomes a beam, with the
 * shadow's own edges.
 *
 * <p>How much dust: little in the open (the shafts between trees, without washing the picture out),
 * more where the camera has little sky above it — a cave, a room — measured by the sky light where
 * the camera is, eased over a couple of seconds. The dust is not even: slow clouds of it and fine
 * motes that glint in the light, both fixed to the world and drifting, so they stay in place as the
 * camera turns.
 *
 * <p>Needs the shadows (their map) and the atmosphere; off with either. The colour is the direct
 * light of {@link SteinWorldLight} (yellow sun, violet moon), which already fades with the hour and
 * the rain.
 */
final class SteinVolume {

    /**
     * Dust per block, per atmosphere level: in the open (where all the air is lit and more would
     * only veil the picture: near the sun the clouds turned yellow) and near the camera where it
     * sees no sky. The second fades into the first with the distance ({@link #NEAR_BLOCKS}), so a
     * half-roofed building does not light the world seen out of it.
     */
    private static final float[] OPEN = {0f, 0.0005f, 0.0008f};

    private static final float[] ENCLOSED = {0f, 0.08f, 0.14f};

    /** The enclosed dust halves over this many blocks from the camera. */
    private static final float NEAR_BLOCKS = 14f;

    /**
     * How bright the dust motes in the light are, per atmosphere level (post_composite.frag): only
     * where the camera is enclosed, where a beam crosses still air.
     */
    private static final float[] MOTES = {0f, 0.6f, 1.0f};

    /** The moon lights the air this much of what it lights the ground (it glowed violet). */
    private static final float MOON_AIR = 0.3f;

    /**
     * The farthest air walked, in blocks (the shadow map reaches at least 64 around the camera).
     */
    private static final float MAX_DISTANCE = 56f;

    /** Seconds for the dust to follow the camera between the open and a cave. */
    private static final float EASE_SECONDS = 2.5f;

    /** The world wraps for the noise every so many blocks (floats keep their precision). */
    private static final double WRAP = 1024.0;

    /** Blocks each way from the camera the ground's top is looked for (every other block). */
    private static final int ROOF_REACH = 8;

    private static float enclosed = -1f;
    private static float roof = Float.MAX_VALUE;
    private static int roofX = Integer.MIN_VALUE;
    private static int roofZ;
    private static long lastNs;
    private static double camX;
    private static double camY;
    private static double camZ;

    private SteinVolume() {}

    /** How enclosed the camera is, 0 (open sky) to 1 (no sky at all), eased; 0 before a world. */
    static float enclosed() {
        return Math.max(0f, enclosed);
    }

    /**
     * At the atmosphere's capture (the first terrain layer): where the camera is and how much sky
     * it has above it.
     */
    static void capture(WorldClient world, double x, double y, double z) {
        camX = x;
        camY = y;
        camZ = z;
        float sky = 15f;
        roof(world, x, z);
        if (world != null) {
            BlockPos at = new BlockPos(x, y, z);
            sky = world.isBlockLoaded(at) ? world.getLightFor(EnumSkyBlock.SKY, at) : 15f;
        }
        // full sky light: the open; below 8 hardly any sky reaches
        float now = 1f - clamp01((sky - 7f) / 8f);
        long t = System.nanoTime();
        if (enclosed < 0f) {
            enclosed = now;
        } else {
            float dt = Math.min(0.5f, (t - lastNs) / 1e9f);
            enclosed += (now - enclosed) * (1f - (float) Math.exp(-dt / (EASE_SECONDS / 3f)));
        }
        lastNs = t;
    }

    /**
     * The volume's part of the rays' block (params3 to params13, from {@code b[12]}); false when
     * there is nothing to draw: no shadow map, no light, the atmosphere off.
     */
    static boolean write(float[] b, int level, float[] proj, float[] view, float far) {
        float k = SteinShadows.drawnStrength();
        float[] light = SteinWorldLight.direct();
        if (SteinGfxOptions.NO_VOLUME
                || !SteinRayLight.active() // the light in the air: the Stein mode only
                || level <= 0
                || k <= 0f
                || light[0] + light[1] + light[2] <= 0.01f
                || enclosed < 0f) {
            return false;
        }
        float[] m = SteinShadows.drawnMatrix();
        System.arraycopy(m, 0, b, 12, 16); // params3..6: eye -> the map's clip, by columns
        float air = (SteinWorldLight.moonDirect() ? MOON_AIR : 1f) * SteinClouds.sunVisible();
        b[28] = light[0] * air;
        b[29] = light[1] * air;
        b[30] = light[2] * air;
        b[31] = OPEN[level] + (ENCLOSED[level] - OPEN[level]) * enclosed;
        b[32] = proj[0]; // params8: as the composite's params7
        b[33] = proj[1];
        b[34] = proj[2];
        b[35] = proj[3];
        // params9.xyz: the sun as drawn in the sky (SteinAtmosphere.volume)
        b[39] = Math.min(MAX_DISTANCE, far);
        b[40] = (float) (camX - Math.floor(camX / WRAP) * WRAP);
        b[41] = (float) camY;
        b[42] = (float) (camZ - Math.floor(camZ / WRAP) * WRAP);
        b[43] = (System.nanoTime() / 1_000_000L % 3_600_000L) / 1000f;
        // params11..13: the camera's rotation (world -> eye) by columns; the shader transposes it
        for (int c = 0; c < 3; c++) {
            b[44 + c * 4] = view[c * 3];
            b[45 + c * 4] = view[c * 3 + 1];
            b[46 + c * 4] = view[c * 3 + 2];
        }
        b[47] = OPEN[level]; // params11.w: the dust far from the camera
        b[51] = NEAR_BLOCKS / (float) Math.log(2); // params12.w: the near dust's fall-off
        b[55] = enclosed; // params13.w: how enclosed the camera is (the forward glow's share)
        // params14..17: the previous shadow map's matrix, params18.x its share (while it fades)
        System.arraycopy(SteinShadows.drawnPrevMatrix(), 0, b, 56, 16);
        b[72] = SteinShadows.drawnFade();
        b[74] = roof; // params18.z: the ground's top around the camera (the enclosed dust's
        // ceiling)
        return true;
    }

    /**
     * The motes' part of the composite's block (params11 to params14, from {@code b[44]}): the
     * camera's rotation (world -> eye, by columns) and its place in the world (wrapped as the
     * volume's), the time, how enclosed the camera is and the motes' strength (params13.w, 0: none,
     * the shader skips them). {@code view}: the camera's rotation as the atmosphere keeps it.
     */
    static void motes(float[] b, int level, float[] view, boolean volume) {
        float k =
                volume && enclosed > 0f && !SteinGfxOptions.NO_MOTES && SteinRayLight.active()
                        ? MOTES[level] * enclosed
                        : 0f;
        if (k <= 0f) {
            return; // (the block starts cleared: params13.w is 0)
        }
        for (int c = 0; c < 3; c++) {
            b[44 + c * 4] = view[c * 3];
            b[45 + c * 4] = view[c * 3 + 1];
            b[46 + c * 4] = view[c * 3 + 2];
        }
        b[47] = (System.nanoTime() / 1_000_000L % 3_600_000L) / 1000f;
        b[51] = enclosed;
        b[55] = k;
        b[56] = (float) (camX - Math.floor(camX / WRAP) * WRAP);
        b[57] = (float) camY;
        b[58] = (float) (camZ - Math.floor(camZ / WRAP) * WRAP);
    }

    /**
     * The top of the ground around the camera: the enclosed dust stays under it. Without it the air
     * walked out of a cave's opening, all of it in the sun, counted the cave's dust too, and the
     * sky seen through the opening came out white.
     */
    private static void roof(WorldClient world, double x, double z) {
        int bx = MathHelper.floor_double(x);
        int bz = MathHelper.floor_double(z);
        if (world == null) {
            roof = Float.MAX_VALUE;
            roofX = Integer.MIN_VALUE;
            return;
        }
        if (bx == roofX && bz == roofZ) {
            return;
        }
        roofX = bx;
        roofZ = bz;
        int top = 0;
        for (int dz = -ROOF_REACH; dz <= ROOF_REACH; dz += 2) {
            for (int dx = -ROOF_REACH; dx <= ROOF_REACH; dx += 2) {
                int wx = bx + dx;
                int wz = bz + dz;
                if (world.getChunkProvider().chunkExists(wx >> 4, wz >> 4)) {
                    top =
                            Math.max(
                                    top,
                                    world.getChunkFromChunkCoords(wx >> 4, wz >> 4)
                                            .getHeightValue(wx & 15, wz & 15));
                }
            }
        }
        roof = top > 0 ? top : Float.MAX_VALUE;
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : v > 1f ? 1f : v;
    }
}
