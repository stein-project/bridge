#version 450
// Copyright (c) 2026 Breno Xavier. All rights reserved.
// Proprietary software: see the LICENSE file at the root of this repository.
// API self-test (-Dsteinbridge.gfx.selfTest=true): squares per instance.
layout(location = 0) in vec2 aPos;
layout(location = 1) in vec2 aUv;
layout(location = 2) in vec4 iData;   // xy offset, layer, depth
layout(location = 0) out vec2 uv;
layout(location = 1) flat out float layer;
void main() {
    uv = aUv;
    layer = iData.z;
    gl_Position = vec4(aPos + iData.xy, iData.w, 1.0);
}
