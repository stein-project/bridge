#version 450
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2026 Breno Xavier
// The pixel of the dynamic geometry: the same combiner as the model parts, with the game's texture directly.
#include "common/entity_frame.glsl"
#include "common/fog.glsl"
#include "common/dynamic_light.glsl"
#include "common/shadow.glsl"
#include "common/world_light.glsl"
layout(set = 0, binding = 1) uniform sampler2D tex;
layout(set = 0, binding = 2) uniform sampler2D lightmap;
layout(location = 0) in vec2 vUv;
layout(location = 1) in vec4 vColor;
layout(location = 2) in vec2 vLight;
layout(location = 3) in float vFogDist;
layout(location = 4) flat in vec4 vOverlay;
layout(location = 5) flat in vec2 vFlags;
layout(location = 6) in vec3 vEye;
layout(location = 0) out vec4 color;
void main() {
    float shadow = sunShadow(vEye, false);   // (derivatives: before any discard)
    int flags = int(vFlags.x);
    vec4 t = texture(tex, vUv);   // always sampled (derivatives outside of branches)
    bool notex = (flags & 32) != 0;
    vec4 c = (notex ? vec4(1.0) : t) * vColor;
    float boost = max(vColor.r, max(vColor.g, vColor.b));
    if (boost > 1.01 && !notex) {
        // past white (SteinGlow: the sun, the moon, the beacon's beam): only the texture's bright core burns, its dim
        // pixel halo stays as drawn (the bloom makes the round glow)
        float hot = pow(clamp(dot(t.rgb, vec3(0.2126, 0.7152, 0.0722)), 0.0, 1.0), 6.0);
        c.rgb = t.rgb * (vColor.rgb / boost) * (1.0 + (boost - 1.0) * hot);
    }
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
        vec2 light = vec2(dynamicBlockLight(vEye, vLight.x), vLight.y);
        vec3 full = texture(lightmap, (light + 8.0) / 256.0).rgb;
        vec3 block = texture(lightmap, vec2(light.x + 8.0, 8.0) / 256.0).rgb;
        c.rgb *= worldLight(full, block, light, shadow);
    }
    if ((flags & 4) != 0) {
        c.rgb = applyFog(c.rgb, fogColor.rgb, fog, vFogDist);
    }
    color = c;
}
