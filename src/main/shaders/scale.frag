#version 450
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2026 Breno Xavier

// The world drawn at another resolution (SteinRenderScale) brought to the window's: each pixel of the target averages
// params.x by params.x bilinear taps spread over its own footprint on the source. Smaller than the window (one tap) it
// is a smooth enlargement; larger, the taps cover every source pixel under the target's, so the reduction does not
// shimmer or alias.
layout(std140, set = 0, binding = 0) uniform Scale {
    vec4 texel;     // xy: 1 / size of the source; zw: 1 / size of the target
    vec4 params;    // x: taps per side
};
layout(set = 0, binding = 1) uniform sampler2D src;
layout(location = 0) out vec4 color;

void main() {
    vec2 uv = gl_FragCoord.xy * texel.zw;
    int n = int(params.x);
    if (n <= 1) {
        color = texture(src, uv);
        return;
    }
    vec4 sum = vec4(0.0);
    for (int y = 0; y < n; y++) {
        for (int x = 0; x < n; x++) {
            vec2 o = (vec2(x, y) + 0.5) / float(n) - 0.5;
            sum += texture(src, uv + o * texel.zw);
        }
    }
    color = sum / float(n * n);
}
