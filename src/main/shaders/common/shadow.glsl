// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2026 Breno Xavier

// How much of the sun's direct light reaches what is drawn (SteinShadows): 1 all, 0 none. Needs shadowMatrix, sunEye,
// shadowInfo and the casters' block from the frame block (shadowInfo: x strength, 0 none; y one texel of the map, in
// its coordinates; z a texel in blocks; w 1 when the shadows' edges open with the distance to the caster).
// A face turned away from the sun gets none (the shade's light, SteinWorldLight); one that sees it gets what the map
// lets through. The point is moved a little out of its face (more when the light grazes it), so a face does not
// shadow itself, and the taps of the map around it soften the shadow's edge.
// The normal comes from the screen-space derivatives: call it outside of branches that depend on the pixel.
#include "common/shadow_distort.glsl"
layout(set = 0, binding = 3) uniform sampler2DShadow shadowMap;
layout(set = 0, binding = 4) uniform sampler2DShadow casterMap;       // entities and the player
layout(set = 0, binding = 6) uniform sampler2DShadow shadowPrevMap;   // the map before the sun's last step, fading out
layout(set = 0, binding = 7) uniform sampler2D cloudMap;              // the game's clouds (SteinClouds)

// How much of the sun the clouds let through to a point (eye space): their texture where the line towards the sun
// crosses their layer, softened by the filter (a texel is 12 blocks).
float cloudShade(vec3 eye) {
    if (cloudInfo.x <= 0.0) {
        return 1.0;
    }
    vec2 uv = (cloudMatrix * vec4(eye, 1.0)).xy;
    return 1.0 - cloudInfo.x * textureLod(cloudMap, uv, 0.0).a;
}

// Twelve taps on a disc, turned per pixel (the noise hides the pattern; the bilinear compare smooths each tap).
const vec2 SHADOW_DISC[12] = vec2[](
        vec2(-0.326, -0.406), vec2(-0.840, -0.074), vec2(-0.696, 0.457), vec2(-0.203, 0.621),
        vec2(0.962, -0.195), vec2(0.473, -0.480), vec2(0.519, 0.767), vec2(0.185, -0.893),
        vec2(0.507, 0.064), vec2(0.896, 0.412), vec2(-0.322, -0.933), vec2(-0.792, -0.598));

// The map's depth range: DEPTH blocks each way from the anchor (SteinShadows), so a block is 1 / (2 * DEPTH) of it.
const float SHADOW_BLOCK = 1.0 / 512.0;

// How far towards the sun the nearest thing in the way is, in blocks, around c (radius `search` in the map's
// coordinates): the map is read compared, so a few distances are tried — a tap still in shadow with the point moved that
// far towards the light has its blocker farther than that.
float blockerDistance(sampler2DShadow map, vec3 c, mat2 turn, float search) {
    const float TRIES[3] = float[](1.5, 5.0, 14.0);
    float found = 0.0;
    for (int i = 0; i < 4; i++) {
        vec2 xy = c.xy + turn * SHADOW_DISC[i * 3] * search;
        for (int k = 0; k < 3; k++) {
            if (textureLod(map, vec3(xy, c.z - TRIES[k] * SHADOW_BLOCK), 0.0) < 0.5) {
                found = max(found, TRIES[k]);
            }
        }
    }
    return found;
}

// The map around c, as a soft edge `radius` texels wide (12 taps on a disc turned per pixel; every other one with
// `stride` 2, the half the other map's taps complete while two maps fade).
float shadowTaps(sampler2DShadow map, vec3 c, mat2 turn, float radius, int stride, int first) {
    float lit = 0.0;
    for (int i = first; i < 12; i += stride) {
        lit += textureLod(map, vec3(c.xy + turn * SHADOW_DISC[i] * (radius * shadowInfo.y), c.z), 0.0);
    }
    return lit * float(stride) / 12.0;
}

// How much of the sun one map lets through at p (eye space), through its matrix; -1 past the map. `s` gets the map's
// plain coordinates (for the fade at its edge).
float mapLit(sampler2DShadow map, mat4 toMap, vec3 p, mat2 turn, int stride, int first, float graze, out vec3 s) {
    s = (toMap * vec4(p, 1.0)).xyz;
    vec3 c = vec3(shadowDistort(s.xy), s.z) * 0.5 + 0.5;
    if (c.x < 0.0 || c.y < 0.0 || c.x > 1.0 || c.y > 1.0 || c.z > 1.0) {
        return -1.0;
    }
    // the receiver moved towards the light by its texel's depth, more where the light grazes the face: a texel of the
    // map covers a slope of the face, and without this the face shadowed itself in diagonal stripes (with the sun
    // nearly overhead the walls take it at a grazing angle)
    c.z -= (0.5 + 4.0 * graze) * shadowInfo.z * shadowStretch(s.xy) * SHADOW_BLOCK;
    // the edge opens with the distance to what casts the shadow, as light through an opening does: a block on the
    // ground keeps a sharp shadow, the patch a hole in a roof throws on a cave's floor gets a soft rim
    float texelBlocks = shadowInfo.z * shadowStretch(s.xy);
    // where the camera is enclosed (shadowInfo.w: a cave, a room): first the widest the edge can be — all lit or all in
    // shadow there needs no more; in the open the plain edge (under trees every pixel is an edge, and the search cost
    // a third of the frame)
    // ... and only near the camera: the land seen out of a cave's mouth, far, has big texels where the search only
    // added noise to the edges
    float widest = shadowInfo.w > 0.0 && dot(p, p) < 32.0 * 32.0 ? clamp(1.3 / texelBlocks, 1.0, 12.0) : 1.0;
    float lit = shadowTaps(map, c, turn, widest, stride, first);
    if (widest > 1.0 && lit > 0.0 && lit < 1.0) {
        float far = blockerDistance(map, c, turn, (2.5 / texelBlocks) * shadowInfo.y);
        lit = shadowTaps(map, c, turn, clamp(0.09 * far / texelBlocks, 1.0, widest), stride, first);
    }
    return lit;
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
    float a = 6.2831853 * fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
    mat2 turn = mat2(cos(a), sin(a), -sin(a), cos(a));
    vec3 s;
    // while two maps fade each reads half of the disc: the same taps as one map
    bool fading = shadowFade.x > 0.0;
    float lit = mapLit(shadowMap, shadowMatrix, p, turn, fading ? 2 : 1, 0, graze, s);
    float clouds = cloudShade(eye);
    if (lit < 0.0) {
        return towards * clouds;   // past the map: no shadow known but the clouds'
    }
    if (fading) {
        // just after a step of the sun the map before it still has a share: the shadows move over a second instead
        // of jumping (and trembling) at each step
        vec3 sp;
        float before = mapLit(shadowPrevMap, shadowPrev, p, turn, 2, 1, graze, sp);
        if (before >= 0.0) {
            lit = mix(lit, before, shadowFade.x);
        }
    }
    if (casterInfo.x > 0.0) {
        // the entities' map (plain, finer): the darker of the two
        vec3 q = (casterMatrix * vec4(p, 1.0)).xyz * 0.5 + 0.5;
        if (q.x > 0.0 && q.y > 0.0 && q.x < 1.0 && q.y < 1.0 && q.z < 1.0) {
            lit = min(lit, casterTaps(q));
        }
    }
    // towards the map's edge the shadow fades out instead of ending in a line
    lit = mix(lit, 1.0, smoothstep(0.8, 0.95, length(s.xy)));
    return lit * towards * clouds;
}
