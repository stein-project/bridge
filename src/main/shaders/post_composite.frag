#version 450
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2026 Breno Xavier

// Post-processing, last pass: the copy of the world's image back onto the game's, with
//  - edge smoothing only where the DEPTH breaks (an object's outline against what is behind it): textures keep every
//    pixel sharp, and so do strokes a few pixels wide (letters, wires). A plane's depth is affine on the screen, so its
//    second difference is ~0; at an outline it is about as large as the first difference. There the pixel is averaged
//    along the edge (the luma gradient's normal). It fades out with distance (params4.z blocks, from half of it): far
//    away the outlines are dense and averaging them only makes the distance look soft.
//  - bloom, added by "screen" (never past white).
//  - sharpening, away from the outlines: the pixel pushed away from its four neighbours' mean, as much as the
//    neighbourhood leaves room for — fine detail moves the most, flat areas and strong contrasts little — so it brings
//    the texture's pixels out without a halo along the edges, and never far past the neighbours' range.
// HDR (SteinHdr): the image stays the one without HDR, and only in the dark a light rises above the paper white, in
// linear light, up to 1 + params2.w times, where the pixel is bright AND the bloom says the area around it glows
// (params4.y times the bloom's luma): a white speck in sand or a flower is not a light and stays as it is.
// HDR_LAYER (HDR's quick quality): the game's image stays 8 bits, clipped at white; the full light goes into a second
// layer (16 bits), which the HDR encode uses where nothing drew over it since (the HUD, a menu).
// params: x bloom strength (0 off), y sharpening (0 off), w edges (0 off).
// params2: y edge ratio, z smallest depth step that counts, w HDR boost.
// params3: the light threshold (post.glsl). params4: y glow gain, z edge distance (blocks).
#include "common/post.glsl"
layout(set = 0, binding = 1) uniform sampler2D scene;
layout(set = 0, binding = 2) uniform sampler2D bloom;
layout(set = 0, binding = 3) uniform sampler2D depth;      // the world's, before the game cleared it for the hand
layout(set = 0, binding = 4) uniform sampler2D handDepth;  // after the hand: 1 wherever there is no hand
#ifdef HDR
layout(set = 0, binding = 5) uniform sampler2D average;    // the scene's mean luma in its last mipmap
#endif
layout(location = 0) out vec4 color;
#ifdef HDR_LAYER
layout(location = 1) out vec4 lights;
#endif

// The hand where it was drawn, the world elsewhere (the hand's depth is from its own projection: always a step).
float depthAt(ivec2 p, ivec2 last) {
    ivec2 q = clamp(p, ivec2(0), last);
    float hand = texelFetch(handDepth, q, 0).r;
    return hand < 1.0 ? hand : texelFetch(depth, q, 0).r;
}

// Nearer than the depth on both sides, 2 to 5 pixels away along x or y: a stroke a few pixels wide — the letters of
// a name or a sign, a fence's wire, a flower. Smoothing would only blur it; it keeps its pixels.
bool thin(ivec2 p, ivec2 last, float c) {
    float step = params2.z;
    for (int r = 2; r <= 5; r++) {
        if (depthAt(p + ivec2(-r, 0), last) > c + step && depthAt(p + ivec2(r, 0), last) > c + step) {
            return true;
        }
        if (depthAt(p + ivec2(0, -r), last) > c + step && depthAt(p + ivec2(0, r), last) > c + step) {
            return true;
        }
    }
    return false;
}

bool outline(ivec2 p, ivec2 last) {
    float c = depthAt(p, last);
    float l = depthAt(p + ivec2(-1, 0), last);
    float r = depthAt(p + ivec2(1, 0), last);
    float d = depthAt(p + ivec2(0, -1), last);
    float u = depthAt(p + ivec2(0, 1), last);
    float sx = abs(l + r - 2.0 * c);
    float sy = abs(d + u - 2.0 * c);
    bool edge = sx > max(abs(r - l) * params2.y, params2.z) || sy > max(abs(u - d) * params2.y, params2.z);
    return edge && !thin(p, last, c);
}

#if defined(HDR) && !defined(HDR_LAYER)
#define NEIGHBOUR(c) finite(c)  // the scene is 16 bits at maximum quality
#else
#define NEIGHBOUR(c) (c)
#endif

// The four neighbours are read once for the edges and the sharpening, and where their colour is flat there is nothing
// to smooth: the depths, ten reads a pixel, are left alone over most of the screen.
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec2 uv = gl_FragCoord.xy * texel.xy;
    vec4 base = texelFetch(scene, p, 0);
    vec3 c = NEIGHBOUR(base.rgb);
    if (params.y > 0.0 || params.w > 0.0) {
        ivec2 last = textureSize(scene, 0) - 1;
        vec3 n = NEIGHBOUR(texelFetch(scene, clamp(p + ivec2(0, 1), ivec2(0), last), 0).rgb);
        vec3 s = NEIGHBOUR(texelFetch(scene, clamp(p + ivec2(0, -1), ivec2(0), last), 0).rgb);
        vec3 e = NEIGHBOUR(texelFetch(scene, clamp(p + ivec2(1, 0), ivec2(0), last), 0).rgb);
        vec3 w = NEIGHBOUR(texelFetch(scene, clamp(p + ivec2(-1, 0), ivec2(0), last), 0).rgb);
        vec2 g = vec2(luma(e) - luma(w), luma(n) - luma(s));
        if (params.w > 0.0 && dot(g, g) >= 1.0 / 1024.0 && outline(p, last)) {
            float z = 0.05 / max(1.0 - depthAt(p, last), 1e-7);
            float near = 1.0 - smoothstep(params4.z * 0.5, params4.z, z);
            vec2 along = normalize(vec2(-g.y, g.x)) * texel.xy * 0.5;
            vec3 soft = 0.5 * (textureLod(scene, uv + along, 0.0).rgb + textureLod(scene, uv - along, 0.0).rgb);
            c = mix(c, soft, near);
        } else if (params.y > 0.0) {
            vec3 lo = min(c, min(min(n, s), min(e, w)));
            vec3 hi = max(c, max(max(n, s), max(e, w)));
            vec3 room = sqrt(clamp(min(lo, 1.0 - hi) / max(hi, vec3(1e-4)), 0.0, 1.0));
            vec3 r = c + (c - 0.25 * (n + s + e + w)) * room * params.y * 2.0;
            vec3 reach = 0.25 * (hi - lo);
            c = clamp(r, max(lo - reach, vec3(0.0)), min(hi + reach, vec3(1.0)));
        }
    }
#ifdef HDR
    c = min(c, vec3(1.0));
    float lit = smoothstep(lightThreshold(average, params3), 1.0, luma(c));
    if (params.x > 0.0 || lit > 0.0) {
        vec3 glow = finite(textureLod(bloom, uv, 0.0).rgb);
        if (params.x > 0.0) {
            c = 1.0 - (1.0 - c) * (1.0 - min(glow * params.x, vec3(1.0)));
        }
        if (lit > 0.0) {
            float mean = textureLod(average, vec2(0.5), params3.w).r;
            float gain = (1.0 - smoothstep(0.15, 0.35, mean)) * lit * clamp(luma(glow) * params4.y, 0.0, 1.0);
            if (gain > 0.0) {
                c = linearToSrgb(srgbToLinear(c) * (1.0 + params2.w * gain));
            }
        }
    }
#ifdef HDR_LAYER
    lights = vec4(c, 1.0);
    color = vec4(min(c, vec3(1.0)), base.a);
#else
    color = vec4(c, base.a);
#endif
#else
    if (params.x > 0.0) {
        vec3 glow = textureLod(bloom, uv, 0.0).rgb;
        c = 1.0 - (1.0 - c) * (1.0 - min(glow * params.x, vec3(1.0)));
    }
    color = vec4(c, base.a);
#endif
}
