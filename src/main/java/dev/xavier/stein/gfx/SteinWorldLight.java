/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import net.minecraft.client.multiplayer.WorldClient;

import org.lwjgl.system.MemoryUtil;

/**
 * The world's light from the game's lightmap ({@code common/world_light.glsl}): the block light
 * stays whole; the sky's part splits into what stays in the shade (a cool ambient) and the direct
 * light of the sun or the moon, warm and above white, where it reaches ({@link SteinShadows}).
 * Standing in the shade, the sun outside is strong. Without the sun's shadows the sky's part is the
 * game's.
 *
 * <p>Two Lens options act here too: the moonlight (open sky turns blue at night) and the darkness
 * (the weak lights of the lightmap fall towards black: a cave without a torch is black, and the
 * torch stands out). Only the picture changes: the world's light (where mobs spawn) is the game's.
 *
 * <p>Only while the world is drawn (the {@link SteinShadows} frame).
 */
public final class SteinWorldLight {

    /** Bytes of the block: direct light, ambient + moonlight, flags. */
    static final int BYTES = 16 * 3;

    /** What stays of the sky's light in the shade at full sun: cool, a moonlit violet. */
    private static final float[] SHADE = {0.40f, 0.36f, 0.62f};

    /** The sun's direct light: warm at the horizon, yellow high (times the sky's part). */
    private static final float[] SUN_LOW = {1.15f, 0.62f, 0.10f};

    private static final float[] SUN_HIGH = {1.05f, 0.90f, 0.30f};

    /** The moon's direct light, cold. */
    private static final float[] MOON = {0.72f, 0.58f, 1.45f};

    private static volatile boolean moonlight;
    private static volatile boolean darkness;

    // this frame
    private static final float[] DIRECT = new float[3];
    private static final float[] AMBIENT = {1f, 1f, 1f};
    private static float moonGlow;
    private static float night;
    private static boolean moonDirect;

    private SteinWorldLight() {}

    /** The Lens options (any thread). */
    public static void configure(boolean moon, boolean dark) {
        moonlight = moon;
        darkness = dark;
    }

    /** Whether the moonlight or the darkness is on. */
    static boolean enabled() {
        return moonlight || darkness;
    }

    /** A frame without a world, or one where none of this acts. */
    static void none() {
        DIRECT[0] = DIRECT[1] = DIRECT[2] = 0f;
        AMBIENT[0] = AMBIENT[1] = AMBIENT[2] = 1f;
        moonGlow = 0f;
        night = 0f;
    }

    /**
     * The frame's light: {@code strength} of the sun's shadows (0 off), whether the light is the
     * moon's, its height over the horizon (0..1), and the world for the hour, the rain and the
     * moon's phase.
     */
    /** The direct light's colour of this frame (the sun's or the moon's), for SteinVolume. */
    static float[] direct() {
        return DIRECT;
    }

    /** The direct light of this frame is the moon's. */
    static boolean moonDirect() {
        return moonDirect;
    }

    static void frame(WorldClient world, float strength, boolean moon, float height, float rain) {
        moonDirect = moon;
        float k = strength;
        float[] c = moon ? MOON : null;
        for (int i = 0; i < 3; i++) {
            float direct;
            if (moon) {
                direct = c[i];
            } else {
                float t = smooth(clamp01((height - 0.05f) / 0.45f));
                direct = SUN_LOW[i] + (SUN_HIGH[i] - SUN_LOW[i]) * t;
            }
            DIRECT[i] = direct * k;
            // the shade: the cool ambient at full strength, the game's sky light without the sun
            AMBIENT[i] = 1f + (SHADE[i] - 1f) * k;
        }
        float sunUp = (float) Math.cos(world.getCelestialAngle(0f) * Math.PI * 2);
        night = smooth(clamp01((0.15f - sunUp) / 0.35f));
        float phase = world.getCurrentMoonPhaseFactor();
        moonGlow = moonlight ? night * (0.45f + 0.55f * phase) * (1f - 0.7f * rain) : 0f;
    }

    /** Writes the block at {@code p} ({@code world}: false for the hand, the HUD, the screens). */
    static void write(long p, boolean world) {
        for (int i = 0; i < 3; i++) {
            MemoryUtil.memPutFloat(p + i * 4L, world ? DIRECT[i] : 0f);
            MemoryUtil.memPutFloat(p + 16 + i * 4L, world ? AMBIENT[i] : 1f);
        }
        MemoryUtil.memPutFloat(p + 12, 0f);
        MemoryUtil.memPutFloat(p + 28, world ? moonGlow : 0f);
        MemoryUtil.memPutFloat(p + 32, world && darkness ? 1f : 0f);
        MemoryUtil.memPutFloat(p + 36, world ? night : 0f);
        // glowing blocks: the block light's boost by a source, the glow's strength
        int glow = world ? SteinGlow.level() : 0;
        MemoryUtil.memPutFloat(p + 40, glow == 2 ? 1.1f : glow == 1 ? 0.5f : 0f);
        MemoryUtil.memPutFloat(p + 44, glow == 2 ? 4.5f : glow == 1 ? 3f : 0f);
    }

    private static float smooth(float t) {
        return t * t * (3f - 2f * t);
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
