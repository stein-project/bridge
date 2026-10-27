// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2026 Breno Xavier

// The block every post-processing pass shares (SteinPostFx writes it per pass).
layout(std140, set = 0, binding = 0) uniform Post {
    vec4 texel;     // xy: 1 / size of the image read; zw: 1 / size of the target
    vec4 params;    // per pass
    vec4 params2;   // per pass
    vec4 params3;   // per pass
    vec4 params4;   // per pass
    vec4 params5;   // per pass (the composite: the atmosphere, SteinAtmosphere)
    vec4 params6;
    vec4 params7;
    vec4 params8;
    vec4 params9;   // the composite: xyz the sun (or moon) in eye space, w how much the mist thickens towards it
    vec4 params10;  // the composite: rgb the light the mist takes towards the sun (or moon)
};

// The luma from which the scene counts as a light: the mean luma (mipmap t.w of `average`) times t.y, between t.x
// and t.z. Low at night, so the lights pass; high by day, so the sky and the sand do not.
float lightThreshold(sampler2D average, vec4 t) {
    return clamp(textureLod(average, vec2(0.5), t.w).r * t.y, t.x, t.z);
}

// The game's colours are sRGB-encoded; light adds and multiplies only in linear. Past 1 both curves go on as the same
// power, so a light above white decodes and encodes back to itself (hdr_encode.frag decodes the same way).
vec3 srgbToLinear(vec3 c) {
    return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(vec3(0.04045), c));
}

vec3 linearToSrgb(vec3 l) {
    return mix(l * 12.92, 1.055 * pow(l, vec3(1.0 / 2.4)) - 0.055, step(vec3(0.0031308), l));
}

float luma(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

// One NaN or infinite pixel would spread over a whole square through the bloom levels.
vec3 finite(vec3 c) {
    return any(isnan(c)) || any(isinf(c)) ? vec3(0.0) : max(c, vec3(0.0));
}
