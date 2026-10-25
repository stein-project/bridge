#version 450
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2026 Breno Xavier

// Post-processing, last pass: the copy of the world's image back onto the game's, with
//  - edge smoothing only where the DEPTH breaks (an object's outline against what is behind it): textures keep every
//    pixel sharp. A plane's depth is affine on the screen, so its second difference is ~0; at an outline it is about
//    as large as the first difference. There the pixel is averaged along the edge (the luma gradient's normal).
//    It fades out with distance (params4.z blocks, from half of it): far away the outlines are dense and averaging
//    them only makes the distance look soft.
//  - colour: saturation and an S-shaped contrast curve;
//  - bloom, added by "screen" (never past white). With HDR output the world gets darker (a power of params4.x on
//    its colour: Stein's gloomy look) and, only in the dark, a light rises above the paper white (up to 1 +
//    params2.w times) where the pixel is bright AND the bloom says the area around it glows (params4.y times the
//    bloom's luma): a white speck in sand or a flower is not a light and stays as it is. The bloom is added as is.
// params: x bloom strength (0 off), y saturation (1 unchanged), z contrast (0 unchanged), w edges (0 off).
// params2: x debug (1: edges in red), y edge ratio, z smallest depth step that counts, w HDR boost (0: SDR).
// params3: the light threshold (post.glsl). params4: x HDR darkness power, y glow gain, z edge distance (blocks).
#include "common/post.glsl"
layout(set = 0, binding = 1) uniform sampler2D scene;
layout(set = 0, binding = 2) uniform sampler2D bloom;
layout(set = 0, binding = 3) uniform sampler2D depth;      // the world's, before the game cleared it for the hand
layout(set = 0, binding = 4) uniform sampler2D handDepth;  // after the hand: 1 wherever there is no hand
layout(set = 0, binding = 5) uniform sampler2D average;    // the scene's mean luma in its last mipmap
layout(location = 0) out vec4 color;

// The hand where it was drawn, the world elsewhere (the hand's depth is from its own projection: always a step).
float depthAt(ivec2 p, ivec2 last) {
    ivec2 q = clamp(p, ivec2(0), last);
    float hand = texelFetch(handDepth, q, 0).r;
    return hand < 1.0 ? hand : texelFetch(depth, q, 0).r;
}

bool outline(ivec2 p, ivec2 last) {
    float c = depthAt(p, last);
    float l = depthAt(p + ivec2(-1, 0), last);
    float r = depthAt(p + ivec2(1, 0), last);
    float d = depthAt(p + ivec2(0, -1), last);
    float u = depthAt(p + ivec2(0, 1), last);
    float sx = abs(l + r - 2.0 * c);
    float sy = abs(d + u - 2.0 * c);
    return sx > max(abs(r - l) * params2.y, params2.z) || sy > max(abs(u - d) * params2.y, params2.z);
}

vec3 smoothEdge(ivec2 p, vec2 uv, vec3 c) {
    ivec2 last = textureSize(scene, 0) - 1;
    float lc = luma(c);
    float ln = luma(texelFetch(scene, clamp(p + ivec2(0, 1), ivec2(0), last), 0).rgb);
    float ls = luma(texelFetch(scene, clamp(p + ivec2(0, -1), ivec2(0), last), 0).rgb);
    float le = luma(texelFetch(scene, clamp(p + ivec2(1, 0), ivec2(0), last), 0).rgb);
    float lw = luma(texelFetch(scene, clamp(p + ivec2(-1, 0), ivec2(0), last), 0).rgb);
    vec2 g = vec2(le - lw, ln - ls);
    if (dot(g, g) < 1.0 / 1024.0) {
        return c; // the same colour on both sides: nothing to smooth
    }
    vec2 along = normalize(vec2(-g.y, g.x)) * texel.xy;
    vec3 near = 0.5 * (texture(scene, uv + along * 0.5).rgb + texture(scene, uv - along * 0.5).rgb);
    vec3 far = 0.5 * near + 0.25 * (texture(scene, uv + along * 2.0).rgb + texture(scene, uv - along * 2.0).rgb);
    float lo = min(lc, min(min(ln, ls), min(le, lw)));
    float hi = max(lc, max(max(ln, ls), max(le, lw)));
    float lf = luma(far);
    // the wide average crossed another edge when it leaves the neighbourhood's range
    return lf < lo || lf > hi ? near : far;
}

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec2 uv = gl_FragCoord.xy * texel.xy;
    vec4 base = texelFetch(scene, p, 0);
    vec3 c = finite(base.rgb);
    ivec2 last = textureSize(depth, 0) - 1;
    if (params.w > 0.0 && outline(p, last)) {
        // distance from the depth (the game's near plane is 0.05; the far plane barely counts)
        float z = 0.05 / max(1.0 - depthAt(p, last), 1e-7);
        float near = 1.0 - smoothstep(params4.z * 0.5, params4.z, z);
        if (params2.x > 0.0) {
            color = vec4(near, 0.0, 0.0, base.a);
            return;
        }
        c = mix(c, smoothEdge(p, uv, c), near);
    }
    float l = luma(c);
    c = max(mix(vec3(l), c, params.y), vec3(0.0));
    c = min(c, vec3(1.0));
    c = mix(c, c * c * (3.0 - 2.0 * c), params.z);
    vec3 glow = finite(texture(bloom, uv).rgb);
    vec3 b = glow * params.x;
    if (params2.w > 0.0) {
        float mean = textureLod(average, vec2(0.5), params3.w).r;
        float dark = 1.0 - smoothstep(0.15, 0.35, mean);
        float lit = smoothstep(lightThreshold(average, params3), 1.0, luma(c));
        float area = clamp(luma(glow) * params4.y, 0.0, 1.0);
        c = pow(c, vec3(params4.x));
        c *= 1.0 + params2.w * dark * lit * area;
        c += b;
    } else if (params.x > 0.0) {
        c = 1.0 - (1.0 - c) * (1.0 - min(b, vec3(1.0)));
    }
    color = vec4(c, base.a);
}
