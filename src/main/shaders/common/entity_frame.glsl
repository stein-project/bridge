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
};
