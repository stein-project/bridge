#version 450
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2026 Breno Xavier

// The Lens's own sky (SteinSkyDome), in layers, over the whole screen at the start of the game's sky; the sun, the moon
// and the stars are drawn over it by the game. Colours in the game's (gamma-coded) space, from the game's own:
//   1. the gradient: the horizon is the fog's colour (the far land fades into it without a seam), the zenith the biome's
//      sky, deeper and a little richer; the band of horizon colour is thinner than the game's;
//   2. under the horizon: the horizon's colour darkening, instead of the game's dark void plane;
//   3. the sunset band: the game's sunrise colours, wider and warmer, strongest low and towards the sun;
//   4. the glow around the sun: soft, warm, gone at night and in the rain;
//   5. the night: darker, the zenith turning to a deep blue;
//   6. the sun: square like the game's and set the same way (its sides along the sun's path and across it), a crisp
//      edge, a core past white in the 16-bit scene, a square glow, warmer low; hidden under the horizon;
//   7. the moon's halo (the moon itself is the game's, drawn over).
// params: x, y the projection's x and y. params2..4: the camera's rotation (world -> eye), by columns. params5.rgb: the
// zenith. params6.rgb: the horizon. params7: the sunrise colours, a how much. params8: xyz the sun in the world, w the
// night (0..1). params9: x the rain (0..1), y the sun's core (times white), z the sun's glow by the hour (1 at sunrise,
// a balance at noon, weaker at sunset), w the sunset's share (its orange).
#include "common/post.glsl"
layout(location = 0) out vec4 color;

float lum(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

void main() {
    vec2 ndc = gl_FragCoord.xy * texel.zw * 2.0 - 1.0;
    vec3 eyeDir = normalize(vec3(ndc.x / params.x, ndc.y / params.y, -1.0));
    vec3 dir = transpose(mat3(params2.xyz, params3.xyz, params4.xyz)) * eyeDir;
    float up = dir.y;
    float night = params8.w;
    float rain = params9.x;
    vec3 sun = params8.xyz;

    // 1. the gradient
    vec3 horizon = params6.rgb;
    vec3 zenith = params5.rgb;
    zenith = mix(vec3(lum(zenith)), zenith, 1.18 - 0.4 * rain) * mix(0.92, 1.0, rain);
    vec3 c = mix(horizon, zenith, pow(clamp(up, 0.0, 1.0), 0.45));

    // 2. under the horizon
    if (up < 0.0) {
        c = horizon * mix(1.0, 0.55, smoothstep(0.0, 0.35, -up));
    }

    // 3. the sunset band: towards the sun along the horizon, fading upwards
    vec2 across = dir.xz;
    vec2 sunFlat = sun.xz;
    float towardFlat = 0.0;
    if (dot(across, across) > 1e-6 && dot(sunFlat, sunFlat) > 1e-6) {
        towardFlat = max(dot(normalize(across), normalize(sunFlat)), 0.0);
    }
    float band = params7.a * (0.35 + 0.65 * towardFlat * towardFlat) * exp(-max(up + 0.05, 0.0) * 3.5);
    vec3 warm = mix(params7.rgb, vec3(1.0, 0.55, 0.25), 0.25);
    c = mix(c, warm, clamp(band * 1.35, 0.0, 0.9));

    // 4. the glow around the sun (not its disc: the game draws that)
    float cosSun = max(dot(dir, sun), 0.0);
    float glow = (pow(cosSun, 40.0) * 0.28 + pow(cosSun, 6.0) * 0.06) * params9.z;
    c += mix(vec3(1.0, 0.82, 0.55), vec3(1.0, 0.5, 0.18), params9.w) * glow * (1.0 - night) * (1.0 - rain);

    // 5. the night: darker, a deep blue towards the zenith
    c *= mix(1.0, 0.7, night);
    c += vec3(0.004, 0.008, 0.03) * night * clamp(up, 0.0, 1.0) * (1.0 - rain);

    // 6. the sun: its square in the plane facing it, the game's sides (across the path: the world's z)
    float facing = dot(dir, sun);
    // only near the sun: towards 90 degrees from it the square's coordinates run to infinity, and their derivatives with
    // them (at noon a line of half a sun lay along the horizon)
    if (facing > 0.5) {
        vec3 side = vec3(0.0, 0.0, 1.0);
        vec3 along = cross(sun, side);
        vec2 q = vec2(dot(dir, side), dot(dir, along)) / facing;
        float d = max(abs(q.x), abs(q.y));
        const float SUN_HALF = 0.085;
        float edge = min(fwidth(d), 0.01);
        float core = 1.0 - smoothstep(SUN_HALF - edge, SUN_HALF + edge, d);
        float out1 = max(d - SUN_HALF, 0.0);
        float halo = exp(-out1 / 0.035) * 0.55 + exp(-out1 / 0.2) * 0.12;
        float low = 1.0 - smoothstep(0.0, 0.35, sun.y);
        vec3 tint = mix(vec3(1.0, 0.95, 0.82), mix(vec3(1.0, 0.7, 0.38), vec3(1.0, 0.45, 0.15), params9.w), low);
        halo *= params9.z;
        float shown = smoothstep(-0.03, 0.02, up) * (1.0 - rain);
        c = mix(c, tint * params9.y * (1.0 - 0.75 * params9.w), core * shown);   // a gentler disc at sunset
        c += tint * halo * (1.0 - core) * shown * (1.0 - night);
    }

    // 7. the moon's halo, cold, at night
    float cosMoon = max(-dot(dir, sun), 0.0);
    c += vec3(0.55, 0.62, 1.0) * (pow(cosMoon, 300.0) * 0.12 + pow(cosMoon, 30.0) * 0.025)
            * night * smoothstep(-0.03, 0.05, up) * (1.0 - rain);

    color = vec4(max(c, vec3(0.0)), 1.0);
}
