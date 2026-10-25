#version 450
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2026 Breno Xavier

// How bright the scene is, small: each pixel of a 64x64 image averages four points of the scene's copy; its mipmaps
// then give the mean luma the bloom's threshold follows (low at night, so the lights glow; high by day, so sky and
// sand do not).
#include "common/post.glsl"
layout(set = 0, binding = 1) uniform sampler2D src;
layout(location = 0) out vec4 color;

void main() {
    vec2 uv = gl_FragCoord.xy * texel.zw;
    vec2 q = texel.zw * 0.25;
    float l = luma(finite(texture(src, uv + vec2(-q.x, -q.y)).rgb))
            + luma(finite(texture(src, uv + vec2(q.x, -q.y)).rgb))
            + luma(finite(texture(src, uv + vec2(-q.x, q.y)).rgb))
            + luma(finite(texture(src, uv + vec2(q.x, q.y)).rgb));
    color = vec4(l * 0.25, 0.0, 0.0, 1.0);
}
