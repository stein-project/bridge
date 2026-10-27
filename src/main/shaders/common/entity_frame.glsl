// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2026 Breno Xavier
// Per-dump block for entities and for the recorded passes (SteinEntityDeferred.writeFrame).
layout(std140, set = 0, binding = 0) uniform Frame {
    mat4 projection;
    vec4 light0;        // direction in eye space
    vec4 light1;
    vec4 diffuse0;
    vec4 diffuse1;
    vec4 ambient;       // scene ambient + the two lights'
    vec4 fogColor;
    vec4 fog;           // mode (0 linear, 1 exp, 2 exp2), density, start, end
    vec4 pad;
    vec4 dynCount;      // x: dynamic lights in use (SteinDynamicLights)
    vec4 dynLights[16]; // eye-space position, light level
    mat4 shadowMatrix;  // eye space -> the shadow map's clip space (SteinShadows)
    vec4 sunEye;        // xyz: towards the sun, in eye space
    vec4 shadowInfo;    // x strength (0: none), y a texel of the map, z a texel in blocks, w darkness
    mat4 casterMatrix;  // eye space -> the entities' shadow map (SteinEntityShadows)
    vec4 casterInfo;    // x on, y a texel of that map, z a texel in blocks
    vec4 sunColor;      // rgb: the sun's (or moon's) direct light at this strength (SteinWorldLight)
    vec4 skyAmbient;    // rgb: what stays of the sky's light in the shade; w: moonlight
    vec4 lightFlags;    // x darkness, y night, z block light boost, w glow strength (SteinGlow)
};
