// Copyright (c) 2026 Breno Xavier. All rights reserved.
// Proprietary software: see the LICENSE file at the root of this repository.
// Per-layer block for the terrain (SteinTerrain.writeFrame).
layout(std140, set = 0, binding = 0) uniform Frame {
    mat4 projection;
    mat4 modelview;     // the game's, with the camera's fractional part
    vec4 fogColor;
    vec4 fog;           // mode (0 linear, 1 exp, 2 exp2), density, start, end
    vec4 params;        // alpha reference, lightmap on, fog on, texture on
    vec4 anim;          // chunk fading in: now (s), duration (s; 0 turns it off)
};
