#version 450
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2026 Breno Xavier

// Bloom, going up: the smaller level read with a cubic B-spline (four bilinear taps; smooth in value and slope, so a
// strong light's halo is round, without the steps a bilinear read leaves between the small level's pixels) and added
// to the level of this size. The wide levels give the soft halo, the narrow ones keep it tight around the light.
#include "common/post.glsl"
layout(set = 0, binding = 1) uniform sampler2D src;    // the smaller level, already summed
layout(set = 0, binding = 2) uniform sampler2D level;  // this size's level, from the way down
layout(location = 0) out vec4 color;

vec3 smoothRead(vec2 uv) {
    vec2 size = 1.0 / texel.xy;
    vec2 p = uv * size - 0.5;
    vec2 i = floor(p);
    vec2 f = p - i;
    vec2 f2 = f * f;
    vec2 f3 = f2 * f;
    vec2 w0 = (1.0 - 3.0 * f + 3.0 * f2 - f3) / 6.0;
    vec2 w1 = (4.0 - 6.0 * f2 + 3.0 * f3) / 6.0;
    vec2 w2 = (1.0 + 3.0 * f + 3.0 * f2 - 3.0 * f3) / 6.0;
    vec2 w3 = f3 / 6.0;
    vec2 g0 = w0 + w1;
    vec2 g1 = w2 + w3;
    vec2 h0 = (i - 0.5 + w1 / g0) * texel.xy;
    vec2 h1 = (i + 1.5 + w3 / g1) * texel.xy;
    return (texture(src, vec2(h0.x, h0.y)).rgb * g0.x + texture(src, vec2(h1.x, h0.y)).rgb * g1.x) * g0.y
         + (texture(src, vec2(h0.x, h1.y)).rgb * g0.x + texture(src, vec2(h1.x, h1.y)).rgb * g1.x) * g1.y;
}

void main() {
    vec2 uv = gl_FragCoord.xy * texel.zw;
    color = vec4(texture(level, uv).rgb + smoothRead(uv), 1.0);
}
