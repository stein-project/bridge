// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2026 Breno Xavier

// Glowing blocks (SteinGlow): the mask says which texels of the atlas give light (1 the bright part, 2 the whole
// texture, 3 lava, 4 fire); those shine at lightFlags.w times their colour — above white, no darkness of the lightmap
// on them — lava pulsing slowly, fire flickering. Needs anim (the time) and lightFlags from the frame block.
layout(set = 0, binding = 5) uniform sampler2D glowMask;

vec3 glowing(vec3 lit, vec3 albedo, vec2 uv) {
    int code = int(textureLod(glowMask, uv, 0.0).r * (255.0 / 32.0) + 0.5);
    if (code == 0) {
        return lit;
    }
    float lum = dot(albedo, vec3(0.2126, 0.7152, 0.0722));
    float part = code == 1 ? smoothstep(0.45, 0.8, lum) : smoothstep(0.05, 0.3, lum);
    float k = lightFlags.w;
    if (code == 3) {
        k *= 0.82 + 0.18 * sin(anim.x * 1.4 + uv.x * 900.0 + uv.y * 700.0);
    } else if (code == 4) {
        k *= 0.8 + 0.2 * sin(anim.x * 11.0 + uv.y * 2400.0);
    }
    return mix(lit, albedo * k, part);
}
