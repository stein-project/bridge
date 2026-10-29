/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

/**
 * Whether any of the Lens's light is on: sun shadows, glowing blocks, dynamic lights, moonlight,
 * darkness or the texture filters. The terrain and entity shaders come in two variants; the LENS
 * one reads the shadow maps, the glow mask and the extra Frame fields, the other is the game's
 * pixel at the game's cost.
 *
 * <p>Why: the Lens's code in the one shader cost the GPU 10-25% in light scenes with every option
 * off (more texture reads, more varyings, a longer pixel), against the rule that an option turned
 * off costs nothing.
 */
public final class SteinLensLight {

    private SteinLensLight() {}

    /** True when the LENS shader variants and their textures are needed now. */
    static boolean on() {
        return SteinShadows.level() > 0
                || SteinGlow.level() > 0
                || SteinDynamicLights.enabled()
                || SteinWorldLight.enabled()
                || SteinTextureFilter.anisotropy() > 1
                || SteinTextureFilter.supersample();
    }
}
