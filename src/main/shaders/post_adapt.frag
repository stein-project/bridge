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

void main() {
    float now = textureLod(src, vec2(0.5), params.y).r;
    float before = texelFetch(previous, ivec2(0), 0).r;
    // all the way (the first frame, after a pause): the last value is not read at all
    float mean = params.x >= 0.999 ? now : mix(before, now, params.x);
    color = vec4(mean, 0.0, 0.0, 1.0);
}
