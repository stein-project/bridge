// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2026 Breno Xavier

// How much of the sun's direct light reaches what is drawn (SteinShadows): 1 all, 0 none. Needs shadowMatrix, sunEye,
// shadowInfo and the casters' block from the frame block (shadowInfo: x strength, 0 none; y one texel of the map, in
// its coordinates; z a texel in blocks).
// A face turned away from the sun gets none (the shade's light, SteinWorldLight); one that sees it gets what the map
// lets through. The point is moved a little out of its face (more when the light grazes it), so a face does not
// shadow itself, and the taps of the map around it soften the shadow's edge.
// The normal comes from the screen-space derivatives: call it outside of branches that depend on the pixel.
#include "common/shadow_distort.glsl"
layout(set = 0, binding = 3) uniform sampler2DShadow shadowMap;
layout(set = 0, binding = 4) uniform sampler2DShadow casterMap;   // entities and the player

// Twelve taps on a disc, turned per pixel (the noise hides the pattern; the bilinear compare smooths each tap).
const vec2 SHADOW_DISC[12] = vec2[](
        vec2(-0.326, -0.406), vec2(-0.840, -0.074), vec2(-0.696, 0.457), vec2(-0.203, 0.621),
        vec2(0.962, -0.195), vec2(0.473, -0.480), vec2(0.519, 0.767), vec2(0.185, -0.893),
        vec2(0.507, 0.064), vec2(0.896, 0.412), vec2(-0.322, -0.933), vec2(-0.792, -0.598));

float shadowTaps(vec3 c) {
    float a = 6.2831853 * fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
    mat2 turn = mat2(cos(a), sin(a), -sin(a), cos(a));
    float lit = 0.0;
    for (int i = 0; i < 12; i++) {
        lit += textureLod(shadowMap, vec3(c.xy + turn * SHADOW_DISC[i] * (1.0 * shadowInfo.y), c.z), 0.0);
    }
    return lit / 12.0;
}

float casterTaps(vec3 c) {
    float a = 6.2831853 * fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
    mat2 turn = mat2(cos(a), sin(a), -sin(a), cos(a));
    float lit = 0.0;
    for (int i = 0; i < 12; i += 2) {
        lit += textureLod(casterMap, vec3(c.xy + turn * SHADOW_DISC[i] * (1.2 * casterInfo.y), c.z), 0.0);
    }
    return lit / 6.0;
}

// axisFaces: the terrain's faces are axis-aligned; one that is not (a cross plant) has no useful normal for the
// facing and keeps only the map.
float sunShadow(vec3 eye, bool axisFaces) {
    vec3 cr = cross(dFdx(eye), dFdy(eye));
    if (shadowInfo.x <= 0.0) {
        return 1.0;
    }
    // seen exactly edge-on the derivatives cancel: up, instead of a NaN
    vec3 n = dot(cr, cr) > 1e-20 ? normalize(cr) : vec3(0.0, 1.0, 0.0);
    if (dot(n, eye) > 0.0) {
        n = -n;   // the visible side: towards the camera
    }
    float facing = dot(n, sunEye.xyz);
    vec3 an = abs(n);
    bool plant = axisFaces && max(an.x, max(an.y, an.z)) < 0.95;
    // the light turns towards the face in a short soft step (no stripes where it grazes)
    float towards = plant ? 1.0 : smoothstep(-0.2, 0.35, facing);
    if (towards <= 0.0) {
        return 0.0;
    }
    float graze = plant ? 0.5 : 1.0 - clamp(facing, 0.0, 1.0);
    // a texel of the bent map, in blocks, where the point is: SHADOW_NEAR of the plain one at the centre, larger out
    float k = shadowStretch((shadowMatrix * vec4(eye, 1.0)).xy);
    vec3 p = eye + n * (shadowInfo.z * k * k / SHADOW_NEAR) * (1.0 + 2.5 * graze);
    vec3 s = (shadowMatrix * vec4(p, 1.0)).xyz;
    vec3 c = vec3(shadowDistort(s.xy), s.z) * 0.5 + 0.5;
    if (c.x < 0.0 || c.y < 0.0 || c.x > 1.0 || c.y > 1.0 || c.z > 1.0) {
        return towards;   // past the map: no shadow known
    }
    float lit = shadowTaps(c);
    if (casterInfo.x > 0.0) {
        // the entities' map (plain, finer): the darker of the two
        vec3 q = (casterMatrix * vec4(p, 1.0)).xyz * 0.5 + 0.5;
        if (q.x > 0.0 && q.y > 0.0 && q.x < 1.0 && q.y < 1.0 && q.z < 1.0) {
            lit = min(lit, casterTaps(q));
        }
    }
    // towards the map's edge the shadow fades out instead of ending in a line
    lit = mix(lit, 1.0, smoothstep(0.8, 0.95, length(s.xy)));
    return lit * towards;
}
