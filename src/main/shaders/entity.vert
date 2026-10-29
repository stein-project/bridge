#version 450
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2026 Breno Xavier
// Recorded model parts (SteinEntityDeferred): the part's fixed geometry + per-instance data.
// GLINT2: both passes of the players' armor glint in one draw (SteinGlintMerge): the colour slot carries the second
// pass's texture matrix, and the colour is the glint's own (always the same, SteinPlayerPoseCollect.addGlint).
#include "common/clip_depth.glsl"
#include "common/entity_frame.glsl"
#include "common/entity_light.glsl"
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec2 aUv;
layout(location = 2) in vec3 aNormal;
layout(location = 3) in vec4 iRow0;
layout(location = 4) in vec4 iRow1;
layout(location = 5) in vec4 iRow2;
layout(location = 6) in vec4 iTex0;     // s = dot(xyz, (u, v, 1)), layer in w
layout(location = 7) in vec4 iTex1;     // t = dot(xyz, (u, v, 1)), alpha reference in w
layout(location = 8) in vec4 iColor;
layout(location = 9) in vec4 iLight;    // lightmap u, v, options
layout(location = 10) in vec4 iOverlay; // damage color and how much
layout(location = 0) out vec3 vUv;
layout(location = 1) out vec4 vColor;
layout(location = 2) out vec2 vLight;
layout(location = 3) out float vFogDist;
layout(location = 4) flat out vec4 vOverlay;
layout(location = 5) flat out vec2 vFlags;
#ifdef LENS
layout(location = 7) out vec3 vEye;
#endif
#ifdef GLINT2
layout(location = 6) out vec3 vUv2;
// 0.5F * 0.76F, 0.25F * 0.76F, 0.8F * 0.76F (SteinPlayerPose.GLINT_BRIGHTNESS): these decimals round to the same
// floats (0x3ec28f5c, 0x3e428f5c, 0x3f1ba5e3)
const vec4 GLINT_COLOR = vec4(0.38, 0.19, 0.608, 1.0);
#endif
void main() {
    vec4 p = vec4(aPos, 1.0);
    vec3 eye = vec3(dot(iRow0, p), dot(iRow1, p), dot(iRow2, p));
    gl_Position = clipDepth(projection * vec4(eye, 1.0));
#ifdef LENS
    vEye = eye;
#endif
    vec3 st = vec3(aUv, 1.0);
    vUv = vec3(dot(iTex0.xyz, st), dot(iTex1.xyz, st), iTex0.w);
    int flags = int(iLight.z);
#ifdef GLINT2
    // the second pass's rows: s = (t0, t4, t12), t = (t1, t5, t13) = (-t4, t0, t13) (a scaled rotation, checked on the CPU)
    vUv2 = vec3(dot(iColor.xyz, st), dot(vec3(-iColor.y, iColor.x, iColor.w), st), iTex0.w);
    vec4 c = GLINT_COLOR;
#else
    vec4 c = iColor;
#endif
    if ((flags & 1) != 0) {
        c = vec4(lit(aNormal, flags, iRow0, iRow1, iRow2, c.rgb), c.a);
    }
    vColor = c;
    vLight = iLight.xy;
    vFogDist = abs(eye.z);
    vOverlay = iOverlay;
    vFlags = vec2(iLight.z, iTex1.w);
}
