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
    vec4 scale;   // x: paper white / 80, y: peak / 80, z: 1 during the calibration's peak test, w: the shadows' power
    vec4 test;    // the peak test's rectangle in pixels (x0, y0, x1, y1), rows from the bottom
    vec4 look;    // x: 1 for Stein's look, y: 1 to use the lights layer, z: 1 to dither, w: 1 to show the layer (diag)
};
layout(set = 0, binding = 2) uniform sampler2D lights;  // quick quality: the lights above white (post_composite)

// The calibration's peak test: the patch at exactly the chosen peak, and on it the outline of a block far brighter than
// any screen (10000 nits). Past the screen's real limit both clip to the same light and the symbol disappears; below it the
// symbol shows. No curve here: the test needs the raw values.
float segment(vec2 p, vec2 a, vec2 b) {
    vec2 ab = b - a;
    float t = clamp(dot(p - a, ab) / dot(ab, ab), 0.0, 1.0);
    return length(p - a - ab * t);
}

// A block seen from above a corner: the hexagon and the three edges that meet at the near corner.
bool block(vec2 q) {
    const float s = 0.3;
    const float w = 0.866 * s;
    vec2 top = vec2(0.0, s), ur = vec2(w, 0.5 * s), lr = vec2(w, -0.5 * s);
    vec2 bottom = vec2(0.0, -s), ll = vec2(-w, -0.5 * s), ul = vec2(-w, 0.5 * s), c = vec2(0.0);
    float d = min(min(segment(q, top, ur), segment(q, ur, lr)), min(segment(q, lr, bottom), segment(q, bottom, ll)));
    d = min(d, min(segment(q, ll, ul), segment(q, ul, top)));
    d = min(d, min(segment(q, c, ul), min(segment(q, c, ur), segment(q, c, bottom))));
    return d < 0.022;
}

bool peakTest(out vec3 c) {
    vec2 p = gl_FragCoord.xy;
    if (scale.z < 0.5 || p.x < test.x || p.y < test.y || p.x >= test.z || p.y >= test.w) {
        return false;
    }
    vec2 size = test.zw - test.xy;
    vec2 q = (p - test.xy) / size - 0.5;
    c = vec3(block(q) ? 10000.0 / 80.0 : scale.y);
    return true;
}
layout(set = 0, binding = 1) uniform sampler2D screen;
layout(location = 0) out vec4 color;

vec3 decode(vec3 c) {
    vec3 low = c / 12.92;
    vec3 high = pow((c + 0.055) / 1.055, vec3(2.4));
    return mix(low, high, step(vec3(0.04045), c));
}

// Stein's look (in units of the paper white, before the calibration's scale): soft colours, a little more vivid and a
// little darker, without hard contrast. The saturation rises most where it is low (the dull greens and browns gain
// colour, what is already vivid does not burn), the mid tones go a step down towards the shadows, and the lowest
// shadows are lifted a touch so the darkening does not crush them: moody, not contrasty.
vec3 steinLook(vec3 lin) {
    float l = dot(lin, vec3(0.2126, 0.7152, 0.0722));
    float hi = max(lin.r, max(lin.g, lin.b));
    float lo = min(lin.r, min(lin.g, lin.b));
    float sat = hi > 0.0 ? (hi - lo) / hi : 0.0;
    lin = max(mix(vec3(l), lin, 1.0 + 0.3 * (1.0 - sat)), vec3(0.0));
    // a gentle curve under the white: 0 and 1 stay, the middle goes down about 12%; and a lift of 0.4% at black
    vec3 below = clamp(lin, 0.0, 1.0);
    vec3 moody = below * (0.88 + 0.12 * below);
    lin = lin - below + moody;
    return lin + 0.004 * (1.0 - clamp(lin, 0.0, 1.0));
}

void main() {
    vec3 tested;
    if (peakTest(tested)) {
        color = vec4(tested, 1.0);
        return;
    }
    ivec2 px = ivec2(gl_FragCoord.xy);
    vec3 c = texelFetch(screen, px, 0).rgb;
    c = any(isnan(c)) || any(isinf(c)) ? vec3(0.0) : max(c, vec3(0.0));
    if (look.y > 0.5) {
        // quick quality: where the 8-bit screen still holds what the post-processing wrote (nothing drew over it
        // since: no HUD, no menu), the full light from the layer
        vec4 l = texelFetch(lights, px, 0);
        // all three channels within the 8 bits' rounding of the light clipped at white
        bool same = all(lessThan(abs(c - min(l.rgb, vec3(1.0))), vec3(1.5 / 255.0)));
        if (same) {
            c = max(l.rgb, vec3(0.0));
        }
        if (look.w > 0.5) {
            color = vec4(same ? vec3(0.0, 1.0, 0.0) : c * 0.5, 1.0);
            return;
        }
    }
    if (look.z > 0.5) {
        // an 8-bit picture whose shadows the encode stretches: a noise of half a step breaks the bands
        float n = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
        c = max(c + (n - 0.5) / 255.0, vec3(0.0));
    }
    vec3 lin = decode(c);
    // the shadows (the calibration's black): below a mid grey (18% of the paper white) the brightest channel goes
    // through a power and the others follow it, so the colour holds; black and everything from the mid grey up do not
    // move, so the contrast of the picture stays
    const float toe = 0.18;
    float m = max(lin.r, max(lin.g, lin.b));
    if (m > 0.0 && m < toe) {
        lin *= toe * pow(m / toe, scale.w) / m;
    }
    if (look.x > 0.5) {
        lin = steinLook(lin);
    }
    lin *= scale.x;
    float top = max(lin.r, max(lin.g, lin.b));
    // Stein's look bends the lights earlier: softer highlights, the same peak
    float knee = look.x > 0.5 ? max(0.85 * scale.x, 0.45 * scale.y) : max(scale.x, 0.6 * scale.y);
    if (top > knee) {
        float room = scale.y - knee;
        float bent = knee + room * (1.0 - exp(-(top - knee) / room));
        lin *= bent / top;
    }
    color = vec4(lin, 1.0);
}
