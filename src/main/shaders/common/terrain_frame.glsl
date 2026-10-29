// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2026 Breno Xavier
// Per-layer block for the terrain (SteinTerrain.writeFrame).
layout(std140, set = 0, binding = 0) uniform Frame {
    mat4 projection;
    mat4 modelview;     // the game's, with the camera's fractional part
    vec4 fogColor;
    vec4 fog;           // mode (0 linear, 1 exp, 2 exp2), density, start, end
    vec4 params;        // alpha reference, lightmap on, fog on, texture on
    vec4 anim;          // chunk fading in: now (s), duration (s; 0 turns it off); z: 1 supersampled pixels, w: anisotropic taps (1 off)
    vec4 dynCount;      // x: dynamic lights in use (SteinDynamicLights); yzw: the camera's position mod 4096 (the Stein mode)
    vec4 dynLights[16]; // eye-space position, light level
    mat4 shadowMatrix;  // eye space -> the shadow map's clip space (SteinShadows)
    vec4 sunEye;        // xyz: towards the sun, in eye space
    vec4 shadowInfo;    // x strength (0: none), y a texel of the map, z a texel in blocks, w wide edges
    mat4 casterMatrix;  // eye space -> the entities' shadow map (SteinEntityShadows)
    vec4 casterInfo;    // x on, y a texel of that map, z a texel in blocks
    vec4 sunColor;      // rgb: the sun's (or moon's) direct light at this strength (SteinWorldLight); w: warm fire
    vec4 skyAmbient;    // rgb: what stays of the sky's light in the shade; w: moonlight
    vec4 lightFlags;    // x darkness, y night, z block light boost, w glow strength (SteinGlow)
    mat4 shadowPrev;    // eye space -> the previous shadow map's clip space, while it fades (SteinShadows)
    vec4 shadowFade;    // x: the previous map's share (0: none)
    mat4 cloudMatrix;   // eye space -> the clouds' texture, along the sun up to their layer (SteinClouds)
    vec4 cloudInfo;     // x: the share of the sun a cloud takes (0: no cloud shadows); yzw: the sky's zenith (reflections, 0: off)
};

// The Stein mode: an eye-space point relative to the camera's block, and to the camera itself (the rays' scene is
// placed there). modelview is the game's matrix (its turn and its own moves: the first person's step back, the walk's
// bobbing, the third person's distance) after the camera's fraction: undoing the whole of it, not only the turn,
// keeps the rays on the world.
vec3 pointFromBlock(vec3 eye) {
    return transpose(mat3(modelview)) * (eye - modelview[3].xyz);
}

vec3 pointFromCamera(vec3 eye) {
    return pointFromBlock(eye) - fract(dynCount.yzw);
}
