#version 450
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2026 Breno Xavier

// The corner shading spread back to full size (SteinAo) and multiplied into the scene (the pipeline blends src * dst):
// nine taps of the half-size shading around the pixel, each weighed by how close its depth is to the pixel's own, so
// the shading stays on its own surface and never leaks over an edge; then the strength. The hand is left as drawn.
// params: x how dark a shut corner gets. params2: the projection's terms. params3.x: 1 when handDepth is the hand's.
#include "common/post.glsl"
layout(set = 0, binding = 1) uniform sampler2D ao;
layout(set = 0, binding = 2) uniform sampler2D depth;       // the world's
layout(set = 0, binding = 3) uniform sampler2D handDepth;   // the hand's alone (params3.x > 0)
layout(location = 0) out vec4 color;

float distAt(float d) {
    return abs(params2.w / (d * 2.0 - 1.0 + params2.z));
}

void main() {
    vec2 uv = gl_FragCoord.xy * texel.zw;
    ivec2 p = ivec2(gl_FragCoord.xy);
    if (params3.x > 0.0 && texelFetch(handDepth, p, 0).r < 1.0) {
        color = vec4(1.0);
        return;
    }
    float d = texelFetch(depth, p, 0).r;
    if (d >= 0.99999) {
        color = vec4(1.0);
        return;
    }
    float z = distAt(d);
    float sum = 0.0;
    float weight = 0.0;
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            vec2 q = uv + vec2(x, y) * texel.xy;
            float qd = textureLod(depth, q, 0.0).r;
            float w = 1.0 / (1e-3 + abs(distAt(qd) - z) / max(z, 1.0) * 40.0);
            sum += textureLod(ao, q, 0.0).r * w;
            weight += w;
        }
    }
    float open = weight > 0.0 ? sum / weight : 1.0;
    color = vec4(vec3(mix(1.0, open, params.x)), 1.0);
}
