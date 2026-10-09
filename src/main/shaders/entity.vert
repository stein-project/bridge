#version 450
// Copyright (c) 2026 Breno Xavier. All rights reserved.
// Proprietary software: see the LICENSE file at the root of this repository.
// Recorded model parts (SteinEntityDeferred): the part's fixed geometry + per-instance data.
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
void main() {
    vec4 p = vec4(aPos, 1.0);
    vec3 eye = vec3(dot(iRow0, p), dot(iRow1, p), dot(iRow2, p));
    gl_Position = projection * vec4(eye, 1.0);
    vec3 st = vec3(aUv, 1.0);
    vUv = vec3(dot(iTex0.xyz, st), dot(iTex1.xyz, st), iTex0.w);
    int flags = int(iLight.z);
    vec4 c = iColor;
    if ((flags & 1) != 0) {
        c = vec4(lit(aNormal, flags, iRow0, iRow1, iRow2, c.rgb), c.a);
    }
    vColor = c;
    vLight = iLight.xy;
    vFogDist = abs(eye.z);
    vOverlay = iOverlay;
    vFlags = vec2(iLight.z, iTex1.w);
}
