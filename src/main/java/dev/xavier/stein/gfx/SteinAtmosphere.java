/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import dev.xavier.stein.bridge.engine.Visuals;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.Entity;

import java.nio.FloatBuffer;

/**
 * The atmosphere (the Lens option), drawn by the post-processing ({@link SteinPostFx}):
 *
 * <ul>
 *   <li>a <b>mist</b> that gathers low — in the valleys, over the water — thicker at night and at
 *       dawn, thin by day, in the colour of the sky's horizon; from the world's depth each pixel
 *       finds how much low air the view crosses to reach it ({@code post_composite.frag});
 *   <li><b>light shafts</b> from the sun, or faintly from the moon, through the gaps of trees,
 *       mountains and clouds ({@code post_rays.frag}): strongest when the sun is low, none in the
 *       rain.
 * </ul>
 *
 * <p>Once per world drawing, at the terrain's first layer ({@link #capture}), the camera's
 * projection, its rotation and the fog's colour are kept; the post-processing reads them after the
 * world. Only the picture: nothing of the world changes.
 *
 * <p>Pipeline: Stein GFX only.
 */
public final class SteinAtmosphere {

    public static final int OFF = 0;
    public static final int SOFT = 1;
    public static final int STRONG = 2;

    /** Mist density at its base height (per block), per level. */
    private static final float[] MIST = {0f, 0.009f, 0.017f};

    /** Rays' strength, per level. */
    private static final float[] RAYS = {0f, 0.3f, 0.55f};

    /** The mist's base: the sea level; it thins upwards over this many blocks. */
    private static final float MIST_BASE = 63f;

    private static final float MIST_HEIGHT = 7f;

    private static volatile int level;

    /** Kept at the first terrain layer of this world drawing. */
    private static boolean captured;

    private static final float[] PROJ = new float[4];
    private static final float[] VIEW = new float[9];
    private static final float[] FOG = new float[3];
    private static float cameraY;
    private static float far;

    /**
     * The drawn world's hour and rain, kept at the capture: the post-processing runs after the
     * video settings' landscape gave the game its world back, whose hour is another.
     */
    private static float celestial;

    private static float rain;

    private SteinAtmosphere() {}

    /** The Lens option (any thread): off, soft or strong. */
    public static void configure(int atmosphere) {
        level = Math.max(OFF, Math.min(STRONG, atmosphere));
    }

    /** Whether it draws this frame: on, and a world was drawn (its camera kept). */
    static boolean active() {
        return level != OFF && captured && SteinGfxOptions.ENABLED;
    }

    /** Whether the post-processing needs the world's depth for it. */
    static boolean wanted() {
        return level != OFF && SteinGfxOptions.ENABLED;
    }

    /**
     * What the exposure's meter needs to place a pixel in the world (post_luma's block): the
     * projection's terms (params.yz, params2.xy), the world's up in eye space (params3.xyz) and the
     * clouds' layer above the camera (params3.w); left at zero when not captured this frame.
     */
    static void meterTerms(float[] b) {
        if (!captured) {
            return;
        }
        b[5] = PROJ[2];
        b[6] = PROJ[3];
        b[8] = PROJ[0];
        b[9] = PROJ[1];
        b[12] = VIEW[3];
        b[13] = VIEW[4];
        b[14] = VIEW[5];
        WorldClient world = Minecraft.getMinecraft().theWorld;
        b[15] = world == null ? 1e4f : Visuals.cloudHeight(world.provider) - cameraY;
    }

    /**
     * At each terrain layer's Frame block: the first time in a world drawing, keeps the projection
     * ({@code proj}, column major), the camera's matrix {@code m} and the fog's colour.
     */
    static void capture(FloatBuffer proj, float[] m, FloatBuffer fog) {
        if (captured || level == OFF) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        Entity view = mc.getRenderViewEntity();
        if (view == null) {
            return;
        }
        captured = true;
        PROJ[0] = proj.get(0);
        PROJ[1] = proj.get(5);
        PROJ[2] = proj.get(10);
        PROJ[3] = proj.get(14);
        for (int c = 0; c < 3; c++) {
            for (int r = 0; r < 3; r++) {
                VIEW[c * 3 + r] = m[c * 4 + r];
            }
        }
        FOG[0] = fog.get(0);
        FOG[1] = fog.get(1);
        FOG[2] = fog.get(2);
        float pt = mc.timer.renderPartialTicks;
        cameraY =
                (float)
                        (view.lastTickPosY
                                + (view.posY - view.lastTickPosY) * pt
                                + view.getEyeHeight());
        far = Math.max(64f, mc.gameSettings.renderDistanceChunks * 16f);
        WorldClient world = mc.theWorld;
        celestial = world == null ? 0f : world.getCelestialAngle(pt);
        rain = world == null ? 0f : world.getRainStrength(pt);
        SteinVolume.capture(
                world,
                view.lastTickPosX + (view.posX - view.lastTickPosX) * pt,
                cameraY,
                view.lastTickPosZ + (view.posZ - view.lastTickPosZ) * pt);
    }

    /**
     * The light in the air's part of the rays' block (SteinVolume), at this world's camera; the
     * projection's terms (params8) always, the screen's shafts use them too.
     */
    static boolean volume(float[] b) {
        b[32] = PROJ[0];
        b[33] = PROJ[1];
        b[34] = PROJ[2];
        b[35] = PROJ[3];
        // params9.xyz: the sun (or the moon) in eye space, as the sky draws it (the glare's
        // measure)
        float angle = celestial * (float) (Math.PI * 2);
        float sx = (float) -Math.sin(angle);
        float sy = (float) Math.cos(angle);
        if (sy < -0.05f) {
            sx = -sx;
            sy = -sy;
        }
        b[36] = VIEW[0] * sx + VIEW[3] * sy;
        b[37] = VIEW[1] * sx + VIEW[4] * sy;
        b[38] = VIEW[2] * sx + VIEW[5] * sy;
        b[73] =
                SteinClouds
                        .sunVisible(); // params18.y: the sun past the clouds (the glare's measure)
        return SteinVolume.write(b, level, PROJ, VIEW, far);
    }

    /** After the world's drawing and its post-processing: the next drawing captures again. */
    static void worldEnd() {
        captured = false;
    }

    /**
     * The composite's atmosphere into {@code b} (params5 to params8, from {@code b[20]}): the
     * mist's density for the hour, the camera's height, the projection, the world's up in eye
     * space; {@code rays} whether the light shafts are added.
     */
    static void composite(float[] b, boolean rays) {
        float sun = (float) Math.cos(celestial * (float) (Math.PI * 2));
        // thin by day, full at night; a little more around the sunrise
        float night = clamp01((0.25f - sun) / 0.5f);
        float c = celestial - 0.77f;
        float dawn = (float) Math.exp(-(c * c) / (0.035f * 0.035f));
        float hour = 0.25f + 0.75f * night + 0.4f * dawn;
        b[20] = MIST[level] * hour;
        b[21] = cameraY;
        b[22] = MIST_BASE;
        b[23] = MIST_HEIGHT;
        // the horizon's colour, a little darker: the mist hides the distance, it does not light the
        // map up
        b[24] = FOG[0] * 0.85f;
        b[25] = FOG[1] * 0.85f;
        b[26] = FOG[2] * 0.88f;
        b[27] = rays ? 1f : 0f;
        b[28] = PROJ[0];
        b[29] = PROJ[1];
        b[30] = PROJ[2];
        b[31] = PROJ[3];
        // the world's up (0, 1, 0) in eye space: the matrix's second column
        b[32] = VIEW[3];
        b[33] = VIEW[4];
        b[34] = VIEW[5];
        b[35] = far;
        // the light in the air towards the sun (golden, strongest low) or the moon (cold, faint)
        float angle = celestial * (float) (Math.PI * 2);
        float sx = (float) -Math.sin(angle);
        float sy = (float) Math.cos(angle);
        boolean moon = sy < -0.05f;
        float k;
        if (moon) {
            sx = -sx;
            sy = -sy;
            k = 0.35f;
        } else {
            k = 1f - 0.6f * clamp01((sy - 0.15f) / 0.6f);
        }
        k *= clamp01(sy / 0.08f) * (1f - rain);
        b[36] = VIEW[0] * sx + VIEW[3] * sy;
        b[37] = VIEW[1] * sx + VIEW[4] * sy;
        b[38] = VIEW[2] * sx + VIEW[5] * sy;
        b[39] = 2.5f * k;
        float[] tint = moon ? MOON_HAZE : SUN_HAZE;
        b[40] = tint[0] * k;
        b[41] = tint[1] * k;
        b[42] = tint[2] * k;
    }

    /** The haze's light towards the sun (golden) and the moon (cold). */
    private static final float[] SUN_HAZE = {1.35f, 1.0f, 0.32f};

    private static final float[] MOON_HAZE = {0.42f, 0.34f, 0.90f};

    /**
     * The rays' pass into {@code b} (params and params2, from {@code b[4]}); false when there are
     * none to draw (the sun and the moon away from the view, rain, the level).
     */
    static boolean rays(float[] b, int w, int h) {
        float angle = celestial * (float) (Math.PI * 2);
        // the sky turns the sun about the world's x axis, after a quarter turn about y
        float sx = (float) -Math.sin(angle);
        float sy = (float) Math.cos(angle);
        boolean moon = sy < -0.05f;
        if (moon) {
            sx = -sx;
            sy = -sy;
        }
        // into eye space, then the screen
        float ex = VIEW[0] * sx + VIEW[3] * sy;
        float ey = VIEW[1] * sx + VIEW[4] * sy;
        float ez = VIEW[2] * sx + VIEW[5] * sy;
        if (ez >= -0.05f) {
            return false; // behind, or beside the camera
        }
        float u = 0.5f + 0.5f * (PROJ[0] * ex / -ez);
        float v = 0.5f + 0.5f * (PROJ[1] * ey / -ez);
        // fades as the source leaves the screen
        float edge = Math.max(Math.abs(u - 0.5f), Math.abs(v - 0.5f));
        float onScreen = clamp01((0.9f - edge) / 0.4f);
        // strongest low over the horizon
        float low = (float) Math.exp(-(sy * sy) / 0.12f);
        float strength =
                RAYS[level]
                        * onScreen
                        * (1f - rain)
                        * (moon ? 0.3f : 0.5f + 0.8f * low)
                        * SteinClouds.sunVisible();
        if (strength <= 0.01f) {
            return false;
        }
        b[4] = u;
        b[5] = v;
        b[6] = strength;
        b[7] = w / (float) h;
        if (moon) {
            b[8] = 0.55f;
            b[9] = 0.65f;
            b[10] = 0.85f;
        } else {
            // warm near the horizon, nearly white high up
            b[8] = 1f;
            b[9] = 0.78f + 0.17f * (1f - low);
            b[10] = 0.5f + 0.38f * (1f - low);
        }
        return true;
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
