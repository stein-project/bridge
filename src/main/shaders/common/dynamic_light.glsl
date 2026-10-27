// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2026 Breno Xavier

// Dynamic lights (SteinDynamicLights): a torch in a hand, a glowstone on the ground, a burning mob light what is around
// them as if the block were there, without touching the world's light. Each light falls one level per block, like the
// game's own block light; the pixel takes the brightest of them and of the light it already had. The block light is
// in the lightmap's units (level * 16). Needs dynCount and dynLights from the frame block, in eye space.
float dynamicBlockLight(vec3 eye, float block) {
    int n = int(dynCount.x);
    float best = 0.0;
    for (int i = 0; i < n; i++) {
        vec4 l = dynLights[i];
        best = max(best, l.w - distance(eye, l.xyz));
    }
    return max(block, clamp(best, 0.0, 15.0) * 16.0);
}
