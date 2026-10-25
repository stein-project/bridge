#version 450
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2026 Breno Xavier

// Bloom, going down: each pixel of the half-size level averages 13 bilinear taps of the level above (five overlapping
// 2x2 boxes: the middle one weighs half, the four corners an eighth each), wide enough that no pixel is skipped and
// nothing shimmers as the camera moves.
// FIRST (the scene into the first level): only what is brighter than a threshold passes, with a soft knee of params.y,
// and each box is weighted by 1 / (1 + luma) so a single bright pixel cannot become a flickering blob. The threshold
// follows the scene's mean luma (mipmap params2.x of `average`, its last): mean * params.z, between params.x and
// params.w.
#include "common/post.glsl"
layout(set = 0, binding = 1) uniform sampler2D src;
#ifdef FIRST
layout(set = 0, binding = 2) uniform sampler2D average;
#endif
layout(location = 0) out vec4 color;

vec3 tap(vec2 uv, vec2 offset) {
    return finite(texture(src, uv + offset * texel.xy).rgb);
}

#ifdef FIRST
vec3 box(vec3 a, vec3 b, vec3 c, vec3 d) {
    vec4 s = vec4(a, 1.0) / (1.0 + luma(a)) + vec4(b, 1.0) / (1.0 + luma(b))
           + vec4(c, 1.0) / (1.0 + luma(c)) + vec4(d, 1.0) / (1.0 + luma(d));
    return s.rgb / s.a;
}

// By luma, not the brightest channel: the game's blue sky has a full blue channel and must not glow.
vec3 bright(vec3 c) {
    float mean = textureLod(average, vec2(0.5), params2.x).r;
    float threshold = clamp(mean * params.z, params.x, params.w);
    float l = luma(c);
    float knee = params.y;
    float soft = clamp(l - threshold + knee, 0.0, 2.0 * knee);
    soft = soft * soft / (4.0 * knee + 1e-4);
    return c * (max(soft, l - threshold) / max(l, 1e-4));
}
#else
vec3 box(vec3 a, vec3 b, vec3 c, vec3 d) {
    return (a + b + c + d) * 0.25;
}
#endif

void main() {
    vec2 uv = gl_FragCoord.xy * texel.zw;
    vec3 a = tap(uv, vec2(-2.0, 2.0));
    vec3 b = tap(uv, vec2(0.0, 2.0));
    vec3 c = tap(uv, vec2(2.0, 2.0));
    vec3 d = tap(uv, vec2(-2.0, 0.0));
    vec3 e = tap(uv, vec2(0.0, 0.0));
    vec3 f = tap(uv, vec2(2.0, 0.0));
    vec3 g = tap(uv, vec2(-2.0, -2.0));
    vec3 h = tap(uv, vec2(0.0, -2.0));
    vec3 i = tap(uv, vec2(2.0, -2.0));
    vec3 j = tap(uv, vec2(-1.0, 1.0));
    vec3 k = tap(uv, vec2(1.0, 1.0));
    vec3 l = tap(uv, vec2(-1.0, -1.0));
    vec3 m = tap(uv, vec2(1.0, -1.0));
    vec3 sum = box(j, k, l, m) * 0.5
             + (box(a, b, d, e) + box(b, c, e, f) + box(d, e, g, h) + box(e, f, h, i)) * 0.125;
#ifdef FIRST
    sum = bright(sum);
#endif
    color = vec4(sum, 1.0);
}
