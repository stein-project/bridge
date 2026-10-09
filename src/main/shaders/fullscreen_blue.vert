#version 450
// Copyright (c) 2026 Breno Xavier. All rights reserved.
// Proprietary software: see the LICENSE file at the root of this repository.
// Full-screen triangle from the vertex index (self-test).
void main() {
    vec2 p = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
