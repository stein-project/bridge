#version 450
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2026 Breno Xavier
layout(set = 0, binding = 1) uniform sampler2DArray tex;
layout(std140, set = 0, binding = 0) uniform Tint {
    vec4 tint;
};
layout(location = 0) in vec2 uv;
layout(location = 1) flat in float layer;
layout(location = 0) out vec4 color;
void main() {
    color = texture(tex, vec3(uv, layer)) * tint;
}
