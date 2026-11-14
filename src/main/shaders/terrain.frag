#version 450
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2026 Breno Xavier
// The terrain pixel. ALPHA_TEST: the variant with discard (only on the cutout layers: discard turns off the GPU's
// early depth test). DEPTH_ONLY: the cutout depth pre-pass (SteinTerrain): only the alpha test, no colour.
// FILTER: the texture filters (SteinTextureFilter): anisotropic filtering (anim.w taps) or the supersampled pixels
// (anim.z); they keep the centre tap's alpha, so the depth pre-pass discards the same pixels.
#include "common/terrain_frame.glsl"
#include "common/fog.glsl"
layout(set = 0, binding = 1) uniform sampler2D tex;
layout(set = 0, binding = 2) uniform sampler2D lightmap;
layout(location = 0) in vec4 vColor;
layout(location = 1) in vec2 vUv;
layout(location = 2) in vec2 vLight;
layout(location = 3) in float vFogDist;
layout(location = 4) in float vFade;
layout(location = 0) out vec4 color;

#ifdef FILTER
// The texture's own cell of the atlas around uv, as the range a tap may take (half a texel in from its sides): with
// mipmaps the game places every texture on a grid of 2^levels pixels, so a tap kept inside never reads the
// neighbouring texture (the graphics card's own anisotropy, or a tap left free, draws its colour as lines along the
// blocks' edges). False without mipmaps: the textures are packed without a grid and the cell is unknown.
bool cell(vec2 uv, out vec2 lo, out vec2 hi) {
    int levels = textureQueryLevels(tex);
    if (levels <= 1) {
        return false;
    }
    vec2 size = vec2(textureSize(tex, 0));
    float side = float(1 << (levels - 1));
    vec2 origin = floor(uv * size / side) * side;
    lo = (origin + 0.5) / size;
    hi = (origin + side - 0.5) / size;
    return true;
}

// Supersampled pixels (SteinTextureFilter): four taps in a rotated grid inside the screen pixel's footprint on the
// texture, kept in the texture's cell. Up close they all land on the same texel, so the pixels stay hard; far away and
// at a grazing angle one tap would jump between texels from frame to frame (the shimmer), four average it out. The
// colour is weighted by alpha, so the clear texels of leaves and flowers do not darken the edges; the alpha (the
// cutout) is the centre's.
vec4 supersampled(vec2 uv) {
    vec2 lo;
    vec2 hi;
    if (!cell(uv, lo, hi)) {
        return texture(tex, uv);
    }
    vec2 dx = dFdx(uv);
    vec2 dy = dFdy(uv);
    vec4 a = texture(tex, clamp(uv + 0.125 * dx + 0.375 * dy, lo, hi));
    vec4 b = texture(tex, clamp(uv + 0.375 * dx - 0.125 * dy, lo, hi));
    vec4 c = texture(tex, clamp(uv - 0.125 * dx - 0.375 * dy, lo, hi));
    vec4 d = texture(tex, clamp(uv - 0.375 * dx + 0.125 * dy, lo, hi));
    float w = a.a + b.a + c.a + d.a;
    vec3 rgb = w > 0.0 ? (a.rgb * a.a + b.rgb * b.a + c.rgb * c.a + d.rgb * d.a) / w : a.rgb;
    return vec4(rgb, texture(tex, uv).a);
}

// Anisotropic filtering (SteinTextureFilter): up to anim.w taps along the long side of the pixel's footprint, each at
// the mipmap of the short side, so a surface at a grazing angle keeps its detail; every tap in the texture's cell.
// Colour weighted by alpha; the cutout is the centre's.
vec4 anisotropic(vec2 uv, float taps) {
    vec2 size = vec2(textureSize(tex, 0));
    vec2 dx = dFdx(uv);
    vec2 dy = dFdy(uv);
    float lx = length(dx * size);
    float ly = length(dy * size);
    float longest = max(lx, ly);
    float ratio = longest / max(min(lx, ly), 1e-6);
    // most of a frame: a texel over several pixels, or a face seen square on, where one tap gives the
    // same picture (explicit gradients: this branch differs inside a pixel quad)
    if (longest <= 1.0 || ratio < 1.25) {
        return textureGrad(tex, uv, dx, dy);
    }
    vec2 lo;
    vec2 hi;
    if (!cell(uv, lo, hi)) {
        return textureGrad(tex, uv, dx, dy);
    }
    bool alongX = lx >= ly;
    float n = clamp(ceil(ratio), 1.0, taps);
    vec2 major = alongX ? dx : dy;
    vec2 gx = alongX ? dx / n : dx;
    vec2 gy = alongX ? dy : dy / n;
    vec4 sum = vec4(0.0);
    for (int i = 0; i < int(n); i++) {
        float t = (float(i) + 0.5) / n - 0.5;
        vec4 s = textureGrad(tex, clamp(uv + major * t, lo, hi), gx, gy);
        sum += vec4(s.rgb * s.a, s.a);
    }
#ifdef ALPHA_TEST
    vec4 centre = textureGrad(tex, uv, dx, dy);   // what the cutout tests
    return vec4(sum.a > 0.0 ? sum.rgb / sum.a : centre.rgb, centre.a);
#else
    return vec4(sum.a > 0.0 ? sum.rgb / sum.a : vec3(0.0), sum.a / n);
#endif
}
#endif

void main() {
#ifdef DEPTH_ONLY
    // the alpha of the full pixel, as below: the colour's alpha times the texture's
    float a = vColor.a;
    if (params.w > 0.5) {
        a *= texture(tex, vUv).a;
    }
    if (a <= params.x) {
        discard;
    }
    color = vec4(0.0);
#else
    vec4 c = vColor;
    if (params.w > 0.5) {
#ifdef FILTER
        c *= anim.w > 1.5 ? anisotropic(vUv, anim.w) : anim.z > 0.5 ? supersampled(vUv) : texture(tex, vUv);
#else
        c *= texture(tex, vUv);
#endif
    }
#ifdef ALPHA_TEST
    if (c.a <= params.x) {
        discard;
    }
#endif
    if (params.y > 0.5) {
        c.rgb *= texture(lightmap, (vLight + 8.0) / 256.0).rgb;
    }
    if (params.z > 0.5) {
        c.rgb = applyFog(c.rgb, fogColor.rgb, fog, vFogDist);
    }
    if (vFade < 1.0) {
        c.rgb = mix(fogColor.rgb, c.rgb, vFade);
    }
    color = c;
#endif
}
