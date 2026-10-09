#version 450
// Copyright (c) 2026 Breno Xavier. All rights reserved.
// Proprietary software: see the LICENSE file at the root of this repository.
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
