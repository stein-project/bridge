#version 450
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2026 Breno Xavier

// HDR output: the game's screen (gamma-encoded like every colour of the game, 1 = the paper white, more = a light)
// into scRGB for the swapchain: linear light where 1 is 80 nits. The sRGB curve goes on past 1 as its own power, so
// the lights keep their colour. Above the knee (the paper white, or 60% of the peak if higher) the brightest channel
// bends towards the peak and never reaches it, with the other two scaled alike: bright lights stay lights with their
// colour and detail instead of flattening into one white at the screen's limit.
layout(std140, set = 0, binding = 0) uniform Encode {
    vec4 scale;   // x: paper white / 80, y: peak / 80, z: 1 during the calibration's peak test
    vec4 test;    // the peak test's rectangle in pixels (x0, y0, x1, y1), rows from the bottom
};

// The calibration's peak test: the patch at exactly the chosen peak, and on it a ring and a dot far brighter than any
// screen (10000 nits). Past the screen's real limit both clip to the same light and the symbol disappears; below it the
// symbol shows. No curve here: the test needs the raw values.
bool peakTest(out vec3 c) {
    vec2 p = gl_FragCoord.xy;
    if (scale.z < 0.5 || p.x < test.x || p.y < test.y || p.x >= test.z || p.y >= test.w) {
        return false;
    }
    vec2 size = test.zw - test.xy;
    vec2 q = (p - test.xy) / size - 0.5;
    float r = length(q);
    bool symbol = abs(r - 0.28) < 0.035 || r < 0.06;
    c = vec3(symbol ? 10000.0 / 80.0 : scale.y);
    return true;
}
layout(set = 0, binding = 1) uniform sampler2D screen;
layout(location = 0) out vec4 color;

vec3 decode(vec3 c) {
    vec3 low = c / 12.92;
    vec3 high = pow((c + 0.055) / 1.055, vec3(2.4));
    return mix(low, high, step(vec3(0.04045), c));
}

void main() {
    vec3 tested;
    if (peakTest(tested)) {
        color = vec4(tested, 1.0);
        return;
    }
    vec3 c = texelFetch(screen, ivec2(gl_FragCoord.xy), 0).rgb;
    c = any(isnan(c)) || any(isinf(c)) ? vec3(0.0) : max(c, vec3(0.0));
    vec3 lin = decode(c) * scale.x;
    float top = max(lin.r, max(lin.g, lin.b));
    float knee = max(scale.x, 0.6 * scale.y);
    if (top > knee) {
        float room = scale.y - knee;
        float bent = knee + room * (1.0 - exp(-(top - knee) / room));
        lin *= bent / top;
    }
    color = vec4(lin, 1.0);
}
