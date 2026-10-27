// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2026 Breno Xavier

// The world's light from the game's lightmap (SteinWorldLight); needs sunColor, skyAmbient and lightFlags from the
// frame block. full: the lightmap at the point; block: the lightmap with the sky at zero; level: the block and sky
// levels (0..15); sun: how much of the sun reaches the point (sunShadow).
// - Darkness (lightFlags.x): the lightmap's weak colours fall towards black — a cave with no torch is black — while a
//   strong light stays as it is.
// - The block light stays whole; the sky's part splits into the shade (skyAmbient.rgb, cool) and the sun's or the
//   moon's direct light (sunColor.rgb, warm, above white) where it reaches.
// - Moonlight (skyAmbient.w): blue on what is open to the sky at night.
// - Glowing blocks (lightFlags.z): the block light stronger and warmer right next to a source (the level it ends at
//   is the game's: it does not reach farther).

// The sunlight thrown back by the lit ground and walls into the shade: warm, a little (no shadow is ever only sky).
const float BOUNCE = 0.15;

vec3 darkened(vec3 c) {
    float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
    return c * min(1.0, pow(max(l, 0.0) / 0.9, 0.9));
}

vec3 worldLight(vec3 full, vec3 block, vec2 level, float sun) {
    if (lightFlags.x > 0.5) {
        full = darkened(full);
        block = darkened(block);
    }
    vec3 skyPart = max(full - block, vec3(0.0));
    // the extra light right next to a source is a fire's: orange, not more white (next to lava the stone turned white)
    vec3 light = block * (vec3(1.0) + lightFlags.z * smoothstep(0.6, 1.0, level.x / 15.0) * vec3(0.95, 0.5, 0.16))
            + skyPart * (skyAmbient.rgb + sunColor.rgb * (BOUNCE + (1.0 - BOUNCE) * sun));
    // the moonlight: its own light, not the game's dim night sky; stronger where the moon reaches
    float open = clamp(level.y / 15.0, 0.0, 1.0);
    return light + skyAmbient.w * vec3(0.34, 0.30, 1.0) * (open * open) * mix(0.35, 1.0, sun);
}
