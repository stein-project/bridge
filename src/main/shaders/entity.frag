#version 450
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2026 Breno Xavier
// The pixel of the model parts. With the damage red (the game's setBrightness, three units in GL_COMBINE): texture unit
// 0 * color on rgb and ONLY the texture's alpha (REPLACE); unit 1 blends the constant color on top by its alpha
// (INTERPOLATE), without the lightmap; unit 2 multiplies by the lightmap. That is: mix before the lightmap, and the alpha without the color.
// GAME_TEX: the game's texture directly (sampler2D, the layer does not count) instead of the array.
#include "common/entity_frame.glsl"
#include "common/fog.glsl"
#include "common/dynamic_light.glsl"
#include "common/shadow.glsl"
#include "common/world_light.glsl"
#ifdef GAME_TEX
layout(set = 0, binding = 1) uniform sampler2D tex;
#else
layout(set = 0, binding = 1) uniform sampler2DArray tex;
#endif
layout(set = 0, binding = 2) uniform sampler2D lightmap;
layout(location = 0) in vec3 vUv;
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
#ifdef GAME_TEX
    vec4 t = texture(tex, vUv.xy);   // always sampled (derivatives outside of branches)
#else
    vec4 t = texture(tex, vUv);
#endif
    bool notex = (flags & 32) != 0;
    vec4 c = (notex ? vec4(1.0) : t) * vColor;
    bool overlay = (flags & 64) != 0 || vOverlay.a > 0.0;
    if (overlay && !notex) {
        c.a = t.a;   // without a texture unit 0 does not run: the alpha is the color's
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
