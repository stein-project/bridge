#version 450
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2026 Breno Xavier

// The mean luma the bloom's threshold and HDR's lights are judged against, adapted like an eye: each frame it moves
// only part of the way (params.x) from the last value towards the scene's mean now (the last mipmap, params.y, of the
// luma image). Read raw, the mean jumped with every turn of the camera, and a bright surface right at the threshold —
// sand, snow, quartz — glowed and dimmed from one frame to the next.
#include "common/post.glsl"
layout(set = 0, binding = 1) uniform sampler2D src;       // the luma image, with mipmaps
layout(set = 0, binding = 2) uniform sampler2D previous;  // the adapted mean of the last frame (1x1)
layout(location = 0) out vec4 color;

// The world's mean log luma (g, the exposure's) moves the same way, in the log: as fast towards brighter as towards
// darker — and only as much as there is world in view to measure: a view of sky with a few treetops in a corner keeps
// about the last value (a handful of pixels made the whole image's exposure).
void main() {
    vec3 now = textureLod(src, vec2(0.5), params.y).rgb;
    vec2 before = texelFetch(previous, ivec2(0), 0).rg;
    float trust = smoothstep(0.002, 0.08, now.b);
    float lg = now.b > 0.002 ? now.g / now.b : before.g;
    // all the way (the first frame, after a pause): the last value is not read at all
    if (params.x >= 0.999) {
        color = vec4(now.r, trust > 0.0 ? lg : -1.0, 0.0, 1.0);   // nothing but sky at first: about the land by day
        return;
    }
    color = vec4(mix(before.r, now.r, params.x), mix(before.g, lg, params.x * trust), 0.0, 1.0);
}
