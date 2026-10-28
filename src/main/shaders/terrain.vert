#version 450
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2026 Breno Xavier
// The terrain (SteinTerrain): a 16-byte vertex, the chunk origin per instance.
// INVARIANT: the variant of the cutout depth pre-pass (SteinTerrain), whose two pipelines must compute the same
// depth bit for bit (the colour pass tests EQUAL against the pre-pass).
#include "common/terrain_frame.glsl"
#include "common/clip_depth.glsl"
layout(location = 0) in uvec4 aPos;     // local position * 1024 + 16 * 1024; light in w (block | sky << 8)
layout(location = 1) in vec4 aColor;
layout(location = 2) in uvec2 aUv;      // texture coordinate * 32768 (exact atlas edges)
layout(location = 3) in vec4 iOrigin;   // chunk origin - camera block (integers); w: when it appeared (s, 0: none)
layout(location = 0) out vec4 vColor;
layout(location = 1) out vec2 vUv;
layout(location = 2) out vec2 vLight;
layout(location = 3) out float vFogDist;
layout(location = 4) out float vFade;    // 0 just appeared, 1 fully in
#ifdef INVARIANT
invariant gl_Position;
#endif
void main() {
    vec3 p = vec3(aPos.xyz) * (1.0 / 1024.0) - 16.0 + iOrigin.xyz;   // exact: no gaps
    vec4 eye = modelview * vec4(p, 1.0);
    gl_Position = clipDepth(projection * eye);
    vColor = aColor;
    vUv = vec2(aUv) * (1.0 / 32768.0);
    vLight = vec2(float(aPos.w & 255u), float(aPos.w >> 8));
    vFogDist = abs(eye.z);
    // Chunk fading in (like newer versions): goes from the sky color to its own, fast at first and slow at the end.
    float t = (anim.y > 0.0 && iOrigin.w > 0.0) ? clamp((anim.x - iOrigin.w) / anim.y, 0.0, 1.0) : 1.0;
    vFade = 1.0 - (1.0 - t) * (1.0 - t);
}
