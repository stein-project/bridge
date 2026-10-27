/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.lens;

import dev.xavier.stein.gfx.SteinPostFx;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.GameSettings;

/**
 * The quality levels of the video settings ({@link VideoModel}): one choice that sets the options
 * of every other topic, the game's and Stein's, from the lightest to the richest. The render
 * distance is not one of them: it is the player's own choice, on its own line.
 *
 * <ul>
 *   <li><b>Fast</b>: the lightest the game can be — fast graphics, no clouds, few particles, no
 *       smooth lighting, no ground shadow, no rain, direct drawing (without the intermediate
 *       frame), compact angles;
 *   <li><b>Fancy</b>: the game as Mojang ships it;
 *   <li><b>Medium</b>: Fancy with a soft bloom and 4x anisotropic filtering;
 *   <li><b>High</b>: soft bloom, smooth edges, 8x anisotropic, supersampled pixels, light
 *       sharpening;
 *   <li><b>Ultra</b>: strong bloom, smooth edges, 16x anisotropic, supersampled pixels, medium
 *       sharpening, HDR at maximum quality (the others: quick).
 * </ul>
 *
 * <p>Light and shadows: Medium has low shadows; High medium shadows, moonlight and darkness; Ultra
 * high shadows, moonlight and darkness.
 *
 * <p>When an option is changed by hand the level becomes "Custom" ({@link #current} returns null):
 * the options are compared with each level, only those a level sets and that change the picture.
 */
final class Presets {

    /** A level; the order is the selector's, from the lightest. */
    enum Position {
        FAST(
                "fast",
                false,
                0,
                0,
                2,
                false,
                SteinPostFx.BLOOM_OFF,
                false,
                1,
                false,
                0,
                true,
                false,
                0,
                0),
        FANCY(
                "fancy",
                true,
                2,
                2,
                0,
                true,
                SteinPostFx.BLOOM_OFF,
                false,
                1,
                false,
                0,
                true,
                false,
                0,
                0),
        MEDIUM(
                "medium",
                true,
                2,
                2,
                0,
                true,
                SteinPostFx.BLOOM_LOW,
                false,
                4,
                false,
                0,
                true,
                true,
                0,
                1),
        HIGH(
                "high",
                true,
                2,
                2,
                0,
                true,
                SteinPostFx.BLOOM_LOW,
                true,
                8,
                true,
                1,
                true,
                true,
                1,
                2),
        ULTRA(
                "ultra",
                true,
                2,
                2,
                0,
                true,
                SteinPostFx.BLOOM_HIGH,
                true,
                16,
                true,
                2,
                false,
                true,
                2,
                3);

        private final String key;
        final boolean fancy;
        final int smoothLighting;
        final int clouds;
        final int particles;
        final boolean shadows;
        final int bloom;
        final boolean smoothEdges;
        final int anisotropy;
        final boolean supersample;
        final int sharpen;
        final boolean hdrQuick;
        final boolean dynamicLights;
        final int atmosphere;
        final int sunShadows;

        Position(
                String key,
                boolean fancy,
                int smoothLighting,
                int clouds,
                int particles,
                boolean shadows,
                int bloom,
                boolean smoothEdges,
                int anisotropy,
                boolean supersample,
                int sharpen,
                boolean hdrQuick,
                boolean dynamicLights,
                int atmosphere,
                int sunShadows) {
            this.key = key;
            this.fancy = fancy;
            this.smoothLighting = smoothLighting;
            this.clouds = clouds;
            this.particles = particles;
            this.shadows = shadows;
            this.bloom = bloom;
            this.smoothEdges = smoothEdges;
            this.anisotropy = anisotropy;
            this.supersample = supersample;
            this.sharpen = sharpen;
            this.hdrQuick = hdrQuick;
            this.dynamicLights = dynamicLights;
            this.atmosphere = atmosphere;
            this.sunShadows = sunShadows;
        }

        /** Name of the level, for the translation key (steinlens.quality.&lt;key&gt;). */
        String key() {
            return key;
        }
    }

    private static final Position[] POSITIONS = Position.values();

    /** Highest level, as an index. */
    static final int MAX_POSITION = POSITIONS.length - 1;

    /** The level at an index (0 = Fast), clamped. */
    static Position at(int index) {
        return POSITIONS[Math.clamp(index, 0, MAX_POSITION)];
    }

    private Presets() {}

    /** The level the options are at now, or null when they match none (Custom). */
    static Position current(LensConfig c) {
        GameSettings s = Minecraft.func_71410_x().field_71474_y;
        for (Position p : POSITIONS) {
            if (s.field_74347_j == p.fancy
                    && s.field_74348_k == p.smoothLighting
                    && s.field_74345_l == p.clouds
                    && s.field_74362_aa == p.particles
                    && s.field_181151_V == p.shadows
                    && c.bloom == p.bloom
                    && c.smoothEdges == p.smoothEdges
                    && c.anisotropy == p.anisotropy
                    && c.supersample == p.supersample
                    && c.sharpen == p.sharpen
                    && c.hdrQuick == p.hdrQuick
                    && c.dynamicLights == p.dynamicLights
                    && c.atmosphere == p.atmosphere
                    && c.shadows == p.sunShadows
                    && c.moonlight == (p.sunShadows >= 2)
                    && c.darkness == (p.sunShadows >= 2)
                    && c.glowingBlocks == glowOf(p)) {
                return p;
            }
        }
        return null;
    }

    /**
     * Applies the level: game and Stein options. Returns true when the terrain needs to be rebuilt
     * (fast/fancy graphics and smooth lighting change the chunk mesh).
     */
    static boolean apply(Lens mod, Position p) {
        Minecraft mc = Minecraft.func_71410_x();
        GameSettings s = mc.field_71474_y;
        LensConfig c = mod.config;
        boolean rebuild = s.field_74347_j != p.fancy || s.field_74348_k != p.smoothLighting;
        s.field_74347_j = p.fancy;
        s.field_74348_k = p.smoothLighting;
        s.field_74345_l = p.clouds;
        s.field_74362_aa = p.particles;
        s.field_181151_V = p.shadows;
        s.field_178881_t = true; // VBO (faster than lists)
        c.bloom = p.bloom;
        c.smoothEdges = p.smoothEdges;
        c.anisotropy = p.anisotropy;
        c.supersample = p.supersample;
        c.sharpen = p.sharpen;
        c.hdrQuick = p.hdrQuick;
        c.dynamicLights = p.dynamicLights;
        c.atmosphere = p.atmosphere;
        c.shadows = p.sunShadows;
        c.moonlight = p.sunShadows >= 2; // High and Ultra: the Stein night
        c.darkness = p.sunShadows >= 2;
        c.glowingBlocks = glowOf(p);
        boolean fast = p == Position.FAST;
        c.directRender = fast;
        c.compactAngles = fast;
        c.rain = !fast;
        if (fast) {
            c.fog = 0;
            c.cloudHeight = 0;
        }
        s.func_74303_b();
        mod.changed();
        return rebuild;
    }

    /** Glowing blocks: on from Medium, intense in Ultra. */
    private static int glowOf(Position p) {
        return p.sunShadows >= 3 ? 2 : p.sunShadows >= 1 ? 1 : 0;
    }
}
