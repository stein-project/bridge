#version 450
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2026 Breno Xavier

// The entities' shadow map (SteinEntityShadows): only the depth, seen from the light. The skin's clear pixels are left
// out (the alpha test the part was recorded with, or at least half of the alpha).
// GAME_TEX: the game's texture directly instead of the array.
#include "common/entity_frame.glsl"
#ifdef GAME_TEX
layout(set = 0, binding = 1) uniform sampler2D tex;
#else
layout(set = 0, binding = 1) uniform sampler2DArray tex;
#endif
layout(location = 0) in vec3 vUv;
layout(location = 1) in vec4 vColor;
layout(location = 5) flat in vec2 vFlags;

void main() {
    if ((int(vFlags.x) & 32) != 0) {
        return;   // no texture: the color alone
    }
#ifdef GAME_TEX
    float a = texture(tex, vUv.xy).a;
#else
    float a = texture(tex, vUv).a;
#endif
    if (a * vColor.a < max(vFlags.y, 0.5)) {
        discard;
    }
}
