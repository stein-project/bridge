#version 450
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2026 Breno Xavier

// HDR output: the game's screen (gamma-encoded like every colour of the game, 1 = the paper white, more = a light)
// into scRGB for the swapchain: linear light where 1 is 80 nits. The sRGB curve goes on past 1 as its own power, so
// the lights keep their colour; the result never passes the peak.
layout(std140, set = 0, binding = 0) uniform Encode {
    vec4 scale;   // x: paper white / 80, y: peak / 80
};
layout(set = 0, binding = 1) uniform sampler2D screen;
layout(location = 0) out vec4 color;

vec3 decode(vec3 c) {
    vec3 low = c / 12.92;
    vec3 high = pow((c + 0.055) / 1.055, vec3(2.4));
    return mix(low, high, step(vec3(0.04045), c));
}

void main() {
    vec3 c = texelFetch(screen, ivec2(gl_FragCoord.xy), 0).rgb;
    c = any(isnan(c)) || any(isinf(c)) ? vec3(0.0) : max(c, vec3(0.0));
    color = vec4(min(decode(c) * scale.x, vec3(scale.y)), 1.0);
}
