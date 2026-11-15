/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.lens;

import dev.xavier.stein.gfx.SteinBackend;
import dev.xavier.stein.gfx.SteinPostFx;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.GameSettings;

import java.util.ArrayList;
import java.util.List;

/**
 * The video modes and their presets ({@link VideoModel}): one choice that sets the options of every
 * other topic, the game's and Stein's. The render distance, the frame limit and the screen are not
 * among them: they depend on the player's machine.
 *
 * <p><b>Performance</b> never turns the new light on; it is for frames first, PvP and low-end PCs.
 *
 * <ul>
 *   <li><b>Low</b>: the lightest the game can be — fast graphics, no clouds, few particles, no
 *       smooth lighting, no ground shadow, no rain, no enchantment glint, direct drawing (without
 *       the intermediate frame);
 *   <li><b>Medium</b>: the game as Mojang ships it (Fancy);
 *   <li><b>High</b>: Medium with the image filters (soft bloom, smooth edges, 8x anisotropic,
 *       supersampled pixels, light sharpening) and HDR on screens that have it.
 * </ul>
 *
 * <p><b>Quality</b> always has the new light; each preset includes the image of Performance High.
 *
 * <ul>
 *   <li><b>Low</b>: low sun shadows, dynamic lights;
 *   <li><b>Medium</b>: medium shadows, moonlight, darkness, the Stein sky, soft atmosphere;
 *   <li><b>High</b>: high shadows, strong bloom, 16x anisotropic, medium sharpening, HDR at maximum
 *       quality (the others: quick);
 *   <li><b>Ultra</b>: High with ray tracing (Vulkan, a GPU that has it; from the next start);
 *       offered only on such a GPU.
 * </ul>
 *
 * <p>A preset turns ray tracing on or off (Ultra is High with it), and that is all it says about
 * it: which ray traced effects stay on is the player's ({@link SteinRayOptions}).
 *
 * <p>When an option is changed by hand the preset becomes "Custom" ({@link #current} returns null):
 * the options are compared with each preset, only those a preset sets and that change the picture.
 * HDR is set but not compared: a player whose screen looks better without it keeps the preset.
 */
final class Presets {

    /** The two video modes. */
    enum Mode {
        PERFORMANCE,
        QUALITY
    }

    /** A preset; the order is the modes', each from the lightest. */
    enum Position {
        PERFORMANCE_LOW(
                Mode.PERFORMANCE,
                "low",
                false,
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
                0,
                false),
        PERFORMANCE_MEDIUM(
                Mode.PERFORMANCE,
                "medium",
                false,
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
                0,
                false),
        PERFORMANCE_HIGH(
                Mode.PERFORMANCE,
                "high",
                true,
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
                false,
                0,
                0,
                false),
        QUALITY_LOW(
                Mode.QUALITY,
                "low",
                true,
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
                0,
                1,
                false),
        QUALITY_MEDIUM(
                Mode.QUALITY,
                "medium",
                true,
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
                2,
                true),
        QUALITY_HIGH(
                Mode.QUALITY,
                "high",
                true,
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
                3,
                true),
        /** High with ray tracing; offered only on a GPU that has it. */
        QUALITY_ULTRA(
                Mode.QUALITY,
                "ultra",
                true,
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
                3,
                true);

        final Mode mode;
        private final String key;
        final boolean hdr;
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
        final boolean sky;

        Position(
                Mode mode,
                String key,
                boolean hdr,
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
                int sunShadows,
                boolean sky) {
            this.mode = mode;
            this.key = key;
            this.hdr = hdr;
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
            this.sky = sky;
        }

        /** Ultra: ray tracing on. */
        boolean rayTracing() {
            return this == QUALITY_ULTRA;
        }

        /** Name of the preset, for the translation key (steinlens.quality.&lt;key&gt;). */
        String key() {
            return key;
        }
    }

    private static final Position[] POSITIONS = Position.values();

    /** The preset at an index of all of them (0 = Performance Low), clamped. */
    static Position at(int index) {
        return POSITIONS[Math.clamp(index, 0, POSITIONS.length - 1)];
    }

    /** The presets of {@code mode} this PC can use, from the lightest. */
    static List<Position> of(Mode mode) {
        List<Position> out = new ArrayList<>(4);
        for (Position p : POSITIONS) {
            if (p.mode == mode && (!p.rayTracing() || SteinBackend.rayTracingSupported())) {
                out.add(p);
            }
        }
        return out;
    }

    /** The preset last used in {@code mode}: where the mode opens. */
    static Position last(LensConfig c, Mode mode) {
        List<Position> all = of(mode);
        int level = mode == Mode.QUALITY ? c.qualityLevel : c.performanceLevel;
        return all.get(Math.clamp(level, 0, all.size() - 1));
    }

    private Presets() {}

    /** The preset the options are at now, or null when they match none (Custom). */
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
                    && c.sky == p.sky
                    && c.anisotropy == p.anisotropy
                    && c.supersample == p.supersample
                    && c.sharpen == p.sharpen
                    && c.hdrQuick == p.hdrQuick
                    && c.dynamicLights == p.dynamicLights
                    && c.atmosphere == p.atmosphere
                    && c.shadows == p.sunShadows
                    && c.moonlight == (p.sunShadows >= 2)
                    && c.darkness == (p.sunShadows >= 2)
                    && c.glowingBlocks == glowOf(p)
                    && c.glint == glintOf(p)
                    && p.rayTracing() == SteinBackend.rayTracingRequested()) {
                return p;
            }
        }
        return null;
    }

    /**
     * Applies the preset: game and Stein options. Returns true when the terrain needs to be rebuilt
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
        c.sky = p.sky;
        c.anisotropy = p.anisotropy;
        c.supersample = p.supersample;
        c.sharpen = p.sharpen;
        c.hdr = p.hdr; // only where the screen has it: SteinHdr.available
        c.hdrQuick = p.hdrQuick;
        c.dynamicLights = p.dynamicLights;
        c.atmosphere = p.atmosphere;
        c.shadows = p.sunShadows;
        c.moonlight = p.sunShadows >= 2; // High and Ultra: the Stein night
        c.darkness = p.sunShadows >= 2;
        c.glowingBlocks = glowOf(p);
        if (p.rayTracing() != SteinBackend.rayTracingRequested()) {
            SteinBackend.setRayTracing(p.rayTracing()); // the device's, from the next start
        }
        c.qualityMode = p.mode == Mode.QUALITY;
        int level = of(p.mode).indexOf(p);
        if (p.mode == Mode.QUALITY) {
            c.qualityLevel = level;
        } else {
            c.performanceLevel = level;
        }
        boolean fast = p == Position.PERFORMANCE_LOW;
        c.directRender = fast;
        c.glint = glintOf(p);
        c.rain = !fast;
        if (fast) {
            c.fog = 0;
            c.cloudHeight = 0;
        }
        s.func_74303_b();
        mod.changed();
        return rebuild;
    }

    /** Enchantment glint: off in Performance Low, where it is measured frames (4%); else on. */
    private static int glintOf(Position p) {
        return p == Position.PERFORMANCE_LOW ? 2 : 0;
    }

    /** Glowing blocks: on from Medium, intense in Ultra. */
    private static int glowOf(Position p) {
        return p.sunShadows >= 3 ? 2 : p.sunShadows >= 1 ? 1 : 0;
    }
}
