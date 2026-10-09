// Copyright (c) 2026 Breno Xavier. All rights reserved.
// Proprietary software: see the LICENSE file at the root of this repository.
// Fixed-function fog by distance to the eye (|z| in eye space, as OpenGL computes it by default).
// fogParams: mode (0 linear, 1 exp, 2 exp2), density, start, end.
vec3 applyFog(vec3 c, vec3 fogRgb, vec4 fogParams, float dist) {
    float f;
    if (fogParams.x < 0.5) {
        f = (fogParams.w - dist) / (fogParams.w - fogParams.z);
    } else if (fogParams.x < 1.5) {
        f = exp(-fogParams.y * dist);
    } else {
        float d = fogParams.y * dist;
        f = exp(-d * d);
    }
    return mix(fogRgb, c, clamp(f, 0.0, 1.0));
}
