// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2026 Breno Xavier

// The shadow map's texels are spent where the camera is (SteinShadows): its clip xy is divided by a factor that grows
// with the distance from the centre, from SHADOW_NEAR there to 1 at the edge. Near the player a texel is SHADOW_NEAR of
// what it would be; far away the map still reaches the whole radius. The cast and the lookups bend the same way.
const float SHADOW_NEAR = 0.15;

float shadowStretch(vec2 c) {
    return SHADOW_NEAR + (1.0 - SHADOW_NEAR) * length(c);
}

vec2 shadowDistort(vec2 c) {
    return c / shadowStretch(c);
}
