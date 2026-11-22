#version 450
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2026 Breno Xavier

// Motion blur (SteinMotionBlur, "Motion"): each pixel blurs along the way the camera moved it on the screen.
// The world's depth puts the pixel back in the world (this frame's inverse view-projection); the previous camera
// (moved by params2.yzw, with this frame's projection: a FOV change alone does not blur) says where it was. That
// move, scaled to the shutter (params.z = shutter / frame time) and capped at params.y of the width, is the blur:
// one sample per 3 pixels of it (params.x at most), centred on the pixel, weighted by a tent and jittered per
// pixel so few samples read as smooth. Light is averaged in (nearly) linear (a blurred bright edge does not go
// dark). The hand never blurs, and never bleeds into what is around it.
// texel: xy 1 / size. params: x samples at most, y longest blur (share of the width), z shutter / frame time.
// params2: x hand apart (1) or no hand (0), yzw the camera's move since the previous frame (blocks).
layout(std140, set = 0, binding = 0) uniform Blur {
    vec4 texel;
    vec4 params;
    vec4 params2;
    mat4 inverseViewProjection;
    mat4 previousViewProjection;
};
layout(set = 0, binding = 1) uniform sampler2D scene;
layout(set = 0, binding = 2) uniform sampler2D second;  // the world's depth, before the hand
layout(set = 0, binding = 3) uniform sampler2D hand;    // the hand's depth: 1 wherever there is no hand
layout(location = 0) out vec4 color;

// The average is taken in (nearly) linear light, with gamma 2 for sRGB's curve: a square per sample and one square
// root at the end, instead of two powers per sample. Close enough for a blur, and exact at black and white.
vec3 toLinear(vec3 c) {
    return c * c;
}

vec3 fromLinear(vec3 l) {
    return sqrt(l);
}

// Interleaved gradient noise: a different offset per pixel, with no texture.
float noise(vec2 p) {
    return fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715))));
}

bool isHand(ivec2 p) {
    return params2.x > 0.5 && texelFetch(hand, p, 0).r < 1.0;
}

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec4 c = texelFetch(scene, p, 0);
    if (isHand(p)) {
        color = c;
        return;
    }
    vec2 uv = gl_FragCoord.xy * texel.xy;
    float d = texelFetch(second, p, 0).r;
    vec4 here = inverseViewProjection * vec4(uv * 2.0 - 1.0, d * 2.0 - 1.0, 1.0);
    here.xyz /= here.w;
    vec4 before = previousViewProjection * vec4(here.xyz + params2.yzw, 1.0);
    if (before.w <= 1e-5) {
        color = c;  // it was behind the previous camera
        return;
    }
    vec2 was = before.xy / before.w * 0.5 + 0.5;
    vec2 move = (uv - was) * params.z / texel.xy;  // in pixels, over the shutter
    float len = length(move);
    if (len < 0.5) {
        color = c;
        return;
    }
    float longest = params.y / texel.x;
    if (len > longest) {
        move *= longest / len;
        len = longest;
    }
    int n = int(clamp(ceil(len / 3.0), 4.0, params.x));
    float jitter = noise(gl_FragCoord.xy) - 0.5;
    ivec2 last = ivec2(1.0 / texel.xy) - 1;
    vec3 sum = toLinear(c.rgb);
    float total = 1.0;
    for (int i = 0; i < n; i++) {
        float t = (float(i) + 0.5 + jitter) / float(n) - 0.5;
        vec2 q = gl_FragCoord.xy + move * t;
        ivec2 qi = clamp(ivec2(q), ivec2(0), last);
        if (isHand(qi)) {
            continue;
        }
        float weight = 1.0 - abs(t);
        sum += toLinear(textureLod(scene, q * texel.xy, 0.0).rgb) * weight;
        total += weight;
    }
    color = vec4(fromLinear(sum / total), c.a);
}
