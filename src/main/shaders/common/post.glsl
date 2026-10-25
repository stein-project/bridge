// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2026 Breno Xavier

// The block every post-processing pass shares (SteinPostFx writes it per pass).
layout(std140, set = 0, binding = 0) uniform Post {
    vec4 texel;     // xy: 1 / size of the image read; zw: 1 / size of the target
    vec4 params;    // per pass
    vec4 params2;   // per pass
};

float luma(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

// One NaN or infinite pixel would spread over a whole square through the bloom levels.
vec3 finite(vec3 c) {
    return any(isnan(c)) || any(isinf(c)) ? vec3(0.0) : max(c, vec3(0.0));
}
