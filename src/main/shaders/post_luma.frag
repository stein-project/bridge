#version 450
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2026 Breno Xavier

// How bright the scene is, small: each pixel of a 64x64 image averages four points of the scene's copy; its mipmaps
// then give the means.
//  - r: the mean luma the bloom's threshold follows (low at night, so the lights glow; high by day, so sky and sand
//    do not).
//  - g / b: the mean of the log luma of the world near the camera alone (b is its share of the image): what the
//    exposure follows. With the sky in, the same land got darker as more sky came into view — looking a little up
//    or to the side changed the whole image's brightness. The clouds (they have a depth) and the far land, pale in
//    the haze, are left out too when the camera's place is known — by their height and by the distance; the log
//    keeps a patch of sand or snow from weighing like the rest of the view. The hand is not the world either: with
//    little world near in view, it was most of what was measured.
#include "common/post.glsl"
layout(set = 0, binding = 1) uniform sampler2D src;
layout(set = 0, binding = 2) uniform sampler2D depth;       // the world's depth (params.x > 0), without the hand
layout(set = 0, binding = 3) uniform sampler2D handDepth;   // the hand's alone (params.w > 0; 1 where it is not)
layout(location = 0) out vec4 color;

// How far the world counts, in blocks: past it the haze has paled the land.
const float REACH = 64.0;

// 1 on the world near the camera; 0 on the sky, on the clouds (what is at their layer or above) and past REACH
// (SteinAtmosphere.meterTerms; params.z zero when not known: then only the sky is left out).
float world(vec2 uv, float d) {
    if (d >= 0.99999) {
        return 0.0;
    }
    if (params.z == 0.0) {
        return 1.0;
    }
    float dist = abs(params.z / (d * 2.0 - 1.0 + params.y));
    vec2 ndc = uv * 2.0 - 1.0;
    vec3 eye = vec3(ndc.x / params2.x, ndc.y / params2.y, -1.0) * dist;
    // (the layer shows about an eye's height below its height: a margin of three blocks)
    return step(dot(eye, params3.xyz), params3.w - 3.0) * step(dist, REACH);
}

void main() {
    vec2 uv = gl_FragCoord.xy * texel.zw;
    vec2 q = texel.zw * 0.25;
    ivec2 last = textureSize(depth, 0) - 1;
    float l = 0.0;
    float logs = 0.0;
    float share = 0.0;
    for (int i = 0; i < 4; i++) {
        vec2 at = uv + vec2((i & 1) == 0 ? -q.x : q.x, i < 2 ? -q.y : q.y);
        float y = luma(finite(texture(src, at).rgb));
        // without the world's depth everything counts
        ivec2 t = clamp(ivec2(at / texel.xy), ivec2(0), last);
        float hand = params.w > 0.0 ? 1.0 - step(1.0, texelFetch(handDepth, t, 0).r) : 0.0;
        float w = params.x > 0.0 ? world(at, texelFetch(depth, t, 0).r) * (1.0 - hand) : 1.0;
        l += y;
        logs += w * log(max(y, 0.005));
        share += w;
    }
    color = vec4(l * 0.25, logs * 0.25, share * 0.25, 1.0);
}
