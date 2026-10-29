// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2026 Breno Xavier
// The Stein mode's lava (stage 3 of docs/stein-mode-plan.md; terrain.frag with GBUFFER): the game's lava texture is
// not used. A dark crust that cracks open over hot, flowing veins, on the block's pixel grid (crisp), above white in
// the 16-bit scene so the bloom and the light around take it. Needs the terrain's Frame block (anim.x: time).

float lavaHash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

// Smooth noise read at the texel's centre: smooth patterns, hard pixels.
float lavaNoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float a = lavaHash(i);
    float b = lavaHash(i + vec2(1.0, 0.0));
    float c = lavaHash(i + vec2(0.0, 1.0));
    float d = lavaHash(i + vec2(1.0, 1.0));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

// The lava's colour at a world point (blocks); top: an upward face (the flowing pattern lies flat), else the sides.
vec3 lava(vec3 world, bool top) {
    float t = anim.x;
    vec3 q = (floor(world * 16.0) + 0.5) / 16.0;
    vec2 p = top ? q.xz : vec2(q.x + q.z, q.y);
    vec2 flow = top ? vec2(t * 0.06, t * 0.035) : vec2(0.0, t * 0.25);
    float heat = lavaNoise(p * 1.3 + flow) * 0.6 + lavaNoise(p * 3.1 - flow * 1.7) * 0.4;
    float pulse = 0.85 + 0.15 * sin(t * 1.7 + lavaHash(floor(p * 2.0)) * 6.28);
    float hot = smoothstep(0.3, 0.75, heat) * pulse;
    vec3 crust = vec3(0.2, 0.045, 0.018);
    vec3 glow = mix(vec3(1.05, 0.26, 0.03), vec3(1.5, 0.75, 0.18), smoothstep(0.8, 1.0, hot));
    return mix(crust, glow, hot);
}
