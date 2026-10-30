#version 450
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2026 Breno Xavier

// Stein's loading screen (SteinLoadingScreen): the dark of Stein's panels, the "Stein" logo and a thin violet bar under
// it, in one pass over the target. The three layers are put together here (premultiplied) into one colour and one
// alpha, and the pass blends that over what the target holds: over a frozen picture, the dark builds up frame after
// frame; at the end, everything fades.
layout(std140, set = 0, binding = 0) uniform Loading {
    vec4 logoRect;  // x0, y0, width, height in pixels, rows from the bottom
    vec4 bar;       // x0, y0, x1, y1 in pixels, rows from the bottom
    vec4 state;     // x: the dark's alpha, y: progress, z: the logo's alpha, w: the bar's alpha
};
layout(set = 0, binding = 1) uniform sampler2D logo;
layout(location = 0) out vec4 color;

const vec3 BG = vec3(0x0A, 0x06, 0x14) / 255.0;
const vec3 INK = vec3(0xE8, 0xE1, 0xF5) / 255.0;
const vec3 ACCENT = vec3(0x7C, 0x4D, 0xFF) / 255.0;

// One layer over what is below, premultiplied.
void over(inout vec4 below, vec3 rgb, float a) {
    below = vec4(rgb * a + below.rgb * (1.0 - a), a + below.a * (1.0 - a));
}

void main() {
    vec2 p = gl_FragCoord.xy;
    vec4 c = vec4(BG * state.x, state.x);
    // the logo: its texture is the logo at exactly this size, top row first
    vec2 luv = (p - logoRect.xy) / logoRect.zw;
    vec4 s = texture(logo, vec2(clamp(luv.x, 0.0, 1.0), 1.0 - clamp(luv.y, 0.0, 1.0)));
    if (luv.x >= 0.0 && luv.x <= 1.0 && luv.y >= 0.0 && luv.y <= 1.0) {
        over(c, INK * s.rgb, s.a * state.z);
    }
    // the bar: the dim track, the violet up to the progress
    if (p.x >= bar.x && p.x < bar.z && p.y >= bar.y && p.y < bar.w) {
        float fill = bar.x + (bar.z - bar.x) * state.y;
        over(c, INK, 0.12 * state.w);
        if (p.x < fill) {
            over(c, ACCENT, state.w);
        }
    }
    color = c.a > 0.0 ? vec4(c.rgb / c.a, c.a) : vec4(0.0);
}
