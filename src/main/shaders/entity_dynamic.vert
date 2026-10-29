#version 450
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2026 Breno Xavier
// Recorded dynamic geometry (Tessellator, font, items, particles, sky, HUD...): a 32-byte vertex with its own color and
// lightmap, and the same per-instance data as the model parts.
//
// TEXT (entity_text.vert, SteinTextGpu): the glyph runs resident on the GPU. A 48-byte vertex: position,
// texture, the glyph color as float (the same one the copy path would put in the instance: the pixel does not change), the color as
// bytes and a marker in the normal's w. With the marker the vertex belongs to the name's background: no texture and with the byte color,
// as the background Tessellator would give. No per-vertex lightmap (neither the text nor the background carries one). Without TEXT the shader is
// the usual one, line by line.
#include "common/clip_depth.glsl"
#include "common/entity_frame.glsl"
#include "common/entity_light.glsl"
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec2 aUv;
#ifdef TEXT
layout(location = 2) in vec4 aNormalMark;   // xyz unused (lit text uses the current normal); w: 1 = background
#else
layout(location = 2) in vec3 aNormal;
#endif
layout(location = 3) in vec4 iRow0;
layout(location = 4) in vec4 iRow1;
layout(location = 5) in vec4 iRow2;
layout(location = 6) in vec4 iTex0;
layout(location = 7) in vec4 iTex1;
layout(location = 8) in vec4 iColor;
layout(location = 9) in vec4 iLight;
layout(location = 10) in vec4 iOverlay;
layout(location = 11) in vec4 aColor;
#ifdef TEXT
layout(location = 12) in vec4 aColorByte;   // the background color, as a normalized byte like in the Tessellator
#else
layout(location = 12) in vec2 aLight;   // normalized ushort: back to 0..65535
#endif
layout(location = 0) out vec2 vUv;
layout(location = 1) out vec4 vColor;
layout(location = 2) out vec2 vLight;
layout(location = 3) out float vFogDist;
layout(location = 4) flat out vec4 vOverlay;
layout(location = 5) flat out vec2 vFlags;
#ifdef LENS
layout(location = 6) out vec3 vEye;
#endif
void main() {
    vec4 p = vec4(aPos, 1.0);
    vec3 eye = vec3(dot(iRow0, p), dot(iRow1, p), dot(iRow2, p));
    gl_Position = clipDepth(projection * vec4(eye, 1.0));
#ifdef LENS
    vEye = eye;
#endif
    vec3 st = vec3(aUv, 1.0);
    vUv = vec2(dot(iTex0.xyz, st), dot(iTex1.xyz, st));
    vOverlay = (int(iLight.z) & 64) != 0 ? iOverlay : vec4(0.0);
    int flags = int(iLight.z);
#ifdef TEXT
    // The background shares the record (the instance) with the name's text: the "no texture" flag and the byte color come from the vertex.
    bool background = aNormalMark.w > 0.5;
    if (background) {
        flags |= 32;
    }
    vec3 aNormal = aNormalMark.xyz;
#ifdef LENS
    // the instance colour also tints the vertex colours: past white for what glows (SteinGlow)
    vec4 c = (flags & 8) != 0 ? (background ? aColorByte : aColor) * iColor : iColor;
#else
    vec4 c = (flags & 8) != 0 ? (background ? aColorByte : aColor) : iColor;
#endif
#else
#ifdef LENS
    vec4 c = (flags & 8) != 0 ? aColor * iColor : iColor;
#else
    vec4 c = (flags & 8) != 0 ? aColor : iColor;
#endif
#endif
    if ((flags & 1) != 0) {
        vec3 n0 = (flags & 512) != 0 ? iOverlay.xyz : aNormal;
        c = vec4(lit(n0, flags, iRow0, iRow1, iRow2, c.rgb), c.a);
    }
    vColor = c;
#ifdef TEXT
    vLight = iLight.xy;
    vFogDist = abs(eye.z);
    vFlags = vec2(float(flags), iTex1.w);
#else
    vLight = (flags & 16) != 0 ? aLight * 65535.0 : iLight.xy;
    vFogDist = abs(eye.z);
    vFlags = vec2(iLight.z, iTex1.w);
#endif
}
