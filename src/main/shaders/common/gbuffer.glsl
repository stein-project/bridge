// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2026 Breno Xavier
// The Stein mode's G-buffer (SteinRayLight), what each pixel is:
//   gAlbedo (RGBA8):   rgb the texture's colour times the vertex's, a metal
//   gNormal (RGBA16F): xy the face in eye space (octahedral), z smoothness, w the sun's shadow
//   gLight (RGBA8):    x sky light, y block light (0..1), z its own light, w light through
//   gShade (R8):       the entities' and the clouds' shadow (the rays cast the blocks')
//   gDist (R32F):      the point's distance from the eye: the depth buffer is too coarse far away for a ray's start;
//                      0 where the opaque terrain drew nothing this frame (cleared at its first layer)
//
// The point in eye space from the screen coordinate and that distance (the texture's v runs with the eye's y: the
// Vulkan viewport is flipped to keep the game's projection).
// (needs the Frame block's projection)
#define GBUFFER_EYE(uv, dist) (normalize(vec3(((uv).x * 2.0 - 1.0) / projection[0][0], ((uv).y * 2.0 - 1.0) / projection[1][1], -1.0)) * (dist))

vec2 octEncode(vec3 n) {
    n /= abs(n.x) + abs(n.y) + abs(n.z);
    vec2 p = n.xy;
    if (n.z < 0.0) {
        p = (1.0 - abs(p.yx)) * vec2(p.x >= 0.0 ? 1.0 : -1.0, p.y >= 0.0 ? 1.0 : -1.0);
    }
    return p;
}

vec3 octDecode(vec2 p) {
    vec3 n = vec3(p, 1.0 - abs(p.x) - abs(p.y));
    if (n.z < 0.0) {
        n.xy = (1.0 - abs(n.yx)) * vec2(n.x >= 0.0 ? 1.0 : -1.0, n.y >= 0.0 ? 1.0 : -1.0);
    }
    return normalize(n);
}
