#version 450
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2026 Breno Xavier

// Bloom, going up: the smaller level spread by a 3x3 tent (1 2 1 / 2 4 2 / 1 2 1) and added to the level of this size.
// The wide levels give the soft halo, the narrow ones keep it tight around the light.
#include "common/post.glsl"
layout(set = 0, binding = 1) uniform sampler2D src;    // the smaller level, already summed
layout(set = 0, binding = 2) uniform sampler2D level;  // this size's level, from the way down
layout(location = 0) out vec4 color;

void main() {
    vec2 uv = gl_FragCoord.xy * texel.zw;
    vec2 o = texel.xy;
    vec3 t = texture(src, uv).rgb * 4.0
           + (texture(src, uv + vec2(o.x, 0.0)).rgb + texture(src, uv - vec2(o.x, 0.0)).rgb
              + texture(src, uv + vec2(0.0, o.y)).rgb + texture(src, uv - vec2(0.0, o.y)).rgb) * 2.0
           + texture(src, uv + o).rgb + texture(src, uv - o).rgb
           + texture(src, uv + vec2(o.x, -o.y)).rgb + texture(src, uv + vec2(-o.x, o.y)).rgb;
    color = vec4(texture(level, uv).rgb + t / 16.0, 1.0);
}
