/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

/**
 * How the terrain samples the blocks' textures (the Lens options), both done in {@code
 * terrain.frag}:
 *
 * <ul>
 *   <li><b>Anisotropic filtering</b>: 2 to 16 taps along the slant of the surface, each at the
 *       mipmap of the narrow side, so a floor or a wall seen at a grazing angle stays sharp instead
 *       of blurring into a small mipmap. The graphics card's own anisotropy cannot be used: it
 *       reaches across the atlas into the neighbouring textures (white streaks along the blocks'
 *       edges). Here every tap is kept inside the texture's own cell — with mipmaps the game places
 *       every texture on a grid of 2^levels pixels — so nothing bleeds. Only with mipmaps.
 *   <li><b>Supersampled pixels</b>: four taps in a rotated grid inside the pixel's footprint. Up
 *       close all four land on the same texel and the pixels stay hard; far away and at an angle
 *       the texture stops shimmering. Its taps are kept in the cell too (free, they drew the
 *       neighbouring textures as lines along the blocks' edges), so it also needs mipmaps.
 * </ul>
 *
 * <p>Pipeline: Stein GFX only.
 */
public final class SteinTextureFilter {

    private static volatile int anisotropy = 1;
    private static volatile boolean supersample;

    private SteinTextureFilter() {}

    /** The Lens options (any thread): anisotropy 1 (off), 2, 4, 8 or 16; the supersampling. */
    public static void configure(int aniso, boolean supersampled) {
        anisotropy = Math.max(1, Math.min(16, aniso));
        supersample = supersampled;
    }

    /** The anisotropic taps of the terrain (1: off). */
    public static int anisotropy() {
        return SteinGfxOptions.ENABLED ? anisotropy : 1;
    }

    /** Whether the terrain samples its pixels four times. */
    public static boolean supersample() {
        return supersample && SteinGfxOptions.ENABLED;
    }
}
