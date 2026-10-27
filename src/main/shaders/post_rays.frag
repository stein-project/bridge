#version 450
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2026 Breno Xavier

// Light shafts (SteinAtmosphere), at half the size: from each pixel, steps towards the sun (or the moon) on the screen,
// counting the sky seen on the way — the world's depth at its clear value. Where trees, mountains or clouds stand
// between, the count drops, so the light comes through their gaps as rays. Each pixel starts its steps at a slightly
// different point (a fine noise), so the blocks' square edges do not repeat along the rays as steps; the glow is
// strongest around the source itself.
// params: xy the sun on the screen (0..1, rows from the bottom), z strength, w the window's width / height.
// params2: rgb the rays' colour.
#include "common/post.glsl"
layout(set = 0, binding = 1) uniform sampler2D depth;   // the world's, before the hand
layout(location = 0) out vec4 color;

const int STEPS = 32;

void main() {
    vec2 uv = gl_FragCoord.xy * texel.zw;
    vec2 sun = params.xy;
    vec2 step = (sun - uv) / float(STEPS);
    float jitter = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
    vec2 p = uv + step * jitter;
    float weight = 1.0;
    float lit = 0.0;
    for (int i = 0; i < STEPS; i++) {
        p += step;
        if (p.x < 0.0 || p.y < 0.0 || p.x > 1.0 || p.y > 1.0) {
            break;
        }
        float sky = textureLod(depth, p, 0.0).r >= 0.99999 ? 1.0 : 0.0;
        lit += sky * weight;
        weight *= 0.95;
    }
    lit /= float(STEPS);
    // stronger around the sun itself
    vec2 d = (uv - sun) * vec2(params.w, 1.0);
    float near = exp(-length(d) * 3.0);
    color = vec4(params2.rgb * lit * params.z * (0.12 + near), 1.0);
}
