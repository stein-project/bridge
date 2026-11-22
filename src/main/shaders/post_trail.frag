#version 450
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2026 Breno Xavier

// Motion blur (SteinMotionBlur, "Trail"): each frame mixed with the ones before it, so what moves leaves a short
// trail. The past keeps params.w of itself per frame, already scaled to the frame time (the same trail at any frame
// rate; 0 on the first frame, when there is no past). It is kept in linear light in 16 bits, so the trail fades
// without bands in the dark. The hand never trails: it is drawn as it is, and the past under it is kept for when it
// moves away.
// texel: xy 1 / size. params: w the past's share. params2: x hand apart (1) or no hand (0).
layout(std140, set = 0, binding = 0) uniform Blur {
    vec4 texel;
    vec4 params;
    vec4 params2;
    mat4 inverseViewProjection;
    mat4 previousViewProjection;
};
layout(set = 0, binding = 1) uniform sampler2D scene;
layout(set = 0, binding = 2) uniform sampler2D second;  // the past (linear)
layout(set = 0, binding = 3) uniform sampler2D hand;    // the hand's depth: 1 wherever there is no hand
layout(location = 0) out vec4 color;
layout(location = 1) out vec4 past;

vec3 srgbToLinear(vec3 c) {
    return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(vec3(0.04045), c));
}

vec3 linearToSrgb(vec3 l) {
    return mix(l * 12.92, 1.055 * pow(l, vec3(1.0 / 2.4)) - 0.055, step(vec3(0.0031308), l));
}

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec4 c = texelFetch(scene, p, 0);
    vec3 now = srgbToLinear(c.rgb);
    vec3 before = params.w > 0.0 ? texelFetch(second, p, 0).rgb : now;
    if (params2.x > 0.5 && texelFetch(hand, p, 0).r < 1.0) {
        color = c;
        past = vec4(before, 1.0);
        return;
    }
    vec3 mixed = mix(now, before, params.w);
    color = vec4(linearToSrgb(mixed), c.a);
    past = vec4(mixed, 1.0);
}
