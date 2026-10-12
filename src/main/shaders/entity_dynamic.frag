#version 450
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2026 Breno Xavier
// The pixel of the dynamic geometry: the same combiner as the model parts, with the game's texture directly.
#include "common/entity_frame.glsl"
#include "common/fog.glsl"
layout(set = 0, binding = 1) uniform sampler2D tex;
layout(set = 0, binding = 2) uniform sampler2D lightmap;
layout(location = 0) in vec2 vUv;
layout(location = 1) in vec4 vColor;
layout(location = 2) in vec2 vLight;
layout(location = 3) in float vFogDist;
layout(location = 4) flat in vec4 vOverlay;
layout(location = 5) flat in vec2 vFlags;
layout(location = 0) out vec4 color;
void main() {
    int flags = int(vFlags.x);
    vec4 t = texture(tex, vUv);   // always sampled (derivatives outside of branches)
    bool notex = (flags & 32) != 0;
    vec4 c = (notex ? vec4(1.0) : t) * vColor;
    bool overlay = (flags & 64) != 0;
    if (overlay && !notex) {
        c.a = t.a;
    }
    if (vFlags.y >= 0.0 && c.a <= vFlags.y) {
        discard;
    }
    if ((flags & 2) != 0) {
        if (overlay) {
            c.rgb = mix(c.rgb, vOverlay.rgb, vOverlay.a);
        }
        c.rgb *= texture(lightmap, (vLight + 8.0) / 256.0).rgb;
    }
    if ((flags & 4) != 0) {
        c.rgb = applyFog(c.rgb, fogColor.rgb, fog, vFogDist);
    }
    color = c;
}
