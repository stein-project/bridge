#version 450
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2026 Breno Xavier

// The shadow map (SteinShadows): only the depth, seen from the sun. The texture's clear pixels — leaves, flowers, glass
// panes' frames — are left out, so the light comes through them.
#include "common/terrain_frame.glsl"
layout(set = 0, binding = 1) uniform sampler2D tex;
layout(location = 0) in vec4 vColor;
layout(location = 1) in vec2 vUv;

void main() {
    if (texture(tex, vUv).a < params.x) {
        discard;
    }
}
