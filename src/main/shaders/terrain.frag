#version 450
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2026 Breno Xavier
// The terrain pixel. ALPHA_TEST: the variant with discard (only on the cutout layers: discard turns off the GPU's
// early depth test). DEPTH_ONLY: the cutout depth pre-pass (SteinTerrain): only the alpha test, no colour.
#include "common/terrain_frame.glsl"
#include "common/fog.glsl"
layout(set = 0, binding = 1) uniform sampler2D tex;
layout(set = 0, binding = 2) uniform sampler2D lightmap;
layout(location = 0) in vec4 vColor;
layout(location = 1) in vec2 vUv;
layout(location = 2) in vec2 vLight;
layout(location = 3) in float vFogDist;
layout(location = 4) in float vFade;
layout(location = 0) out vec4 color;
void main() {
#ifdef DEPTH_ONLY
    // the alpha of the full pixel, as below: the colour's alpha times the texture's
    float a = vColor.a;
    if (params.w > 0.5) {
        a *= texture(tex, vUv).a;
    }
    if (a <= params.x) {
        discard;
    }
    color = vec4(0.0);
#else
    vec4 c = vColor;
    if (params.w > 0.5) {
        c *= texture(tex, vUv);
    }
#ifdef ALPHA_TEST
    if (c.a <= params.x) {
        discard;
    }
#endif
    if (params.y > 0.5) {
        c.rgb *= texture(lightmap, (vLight + 8.0) / 256.0).rgb;
    }
    if (params.z > 0.5) {
        c.rgb = applyFog(c.rgb, fogColor.rgb, fog, vFogDist);
    }
    if (vFade < 1.0) {
        c.rgb = mix(fogColor.rgb, c.rgb, vFade);
    }
    color = c;
#endif
}
