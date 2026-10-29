#version 450
#ifdef WATER
#extension GL_EXT_ray_query : require
#endif
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2026 Breno Xavier
// The terrain pixel. ALPHA_TEST: the variant with discard (only on the cutout layers: discard turns off the GPU's
// early depth test). DEPTH_ONLY: the cutout depth pre-pass (SteinTerrain): only the alpha test, no colour.
// WATER (with LENS, the Stein mode's translucent layer, Vulkan with ray tracing): the water faces are ours
// (common/water.glsl); no discard here (a shader with ray queries must not, on the RX 7600): a texel under the alpha
// reference goes out with alpha 0.
// GBUFFER (with LENS, the Stein mode on Vulkan): also writes what the pixel is (its colour, face, light and material)
// for the light pass (SteinRayLight), which traces the rays and mixes the reflections.
// LENS: the variant with the Lens's light (sun shadows, glow, dynamic lights, texture filters); without it the pixel is
// the game's, at the game's cost (SteinLensLight picks the variant).
#include "common/terrain_frame.glsl"
#include "common/fog.glsl"
#ifdef LENS
#include "common/dynamic_light.glsl"
#include "common/shadow.glsl"
#include "common/world_light.glsl"
#include "common/glow.glsl"
#endif
layout(set = 0, binding = 1) uniform sampler2D tex;
layout(set = 0, binding = 2) uniform sampler2D lightmap;
layout(location = 0) in vec4 vColor;
layout(location = 1) in vec2 vUv;
layout(location = 2) in vec2 vLight;
layout(location = 3) in float vFogDist;
layout(location = 4) in float vFade;
#ifdef LENS
layout(location = 5) in vec3 vEye;
#endif
layout(location = 0) out vec4 color;

#ifdef LENS
#include "common/materials.glsl"
#ifdef WATER
layout(location = 6) in vec3 vWorld;   // the world point (blocks; the camera's block mod 4096)
layout(set = 0, binding = 11) uniform sampler2D sceneCopy;     // the image before the water (SteinRayLight)
layout(set = 0, binding = 12) uniform sampler2D opaqueDepth;   // the depth before it
#include "common/ray_world.glsl"
#include "common/water.glsl"
#endif
#ifdef GBUFFER
#include "common/gbuffer.glsl"
#include "common/lava.glsl"
layout(set = 0, binding = 10) uniform sampler2D materialMap;   // SteinMaterialMap: smooth, metal, own light, through
layout(location = 1) out vec4 gAlbedo;
layout(location = 2) out vec4 gNormal;
layout(location = 3) out vec4 gLight;
layout(location = 4) out vec4 gShade;   // the entities' and the clouds' shadow (the rays cast the blocks')
layout(location = 5) out vec4 gDist;    // the point's exact distance from the eye (the rays start there)
#endif

// The reflections, the Stein mode's only (SteinMaterials; glow mask codes 6 gems, 7 gold, 8 iron, 9 ice, 10 glass, 11
// polished stone): on the opaque layers the light pass traces them (GBUFFER: only the gems' sparkle here), on the
// translucent one (WATER: ice, stained glass) a ray here; the sky reflected by the face — the horizon is the fog's colour, the zenith the frame's (cloudInfo.yzw), the ground
// below darker — only as much as the sky light reaches; the sun's highlight where the sun reaches; strength by the
// angle (Fresnel) and the material, gems and metals mostly on their bright texels (the pixel art stays as drawn), the
// metals tinting what they reflect.
vec3 environment(vec3 lit, vec3 albedo, vec3 n, float sky, float shadow) {
#if !defined(GBUFFER) && !defined(WATER)
    return lit;   // without ray tracing nothing reflects: the reflections are the Stein mode's only
#else
    int code = materialCode(textureLod(glowMask, vUv, 0.0).r);
    if (!reflective(code)) {
        return lit;
    }
    vec3 v = normalize(vEye);
    if (dot(n, v) > 0.0) {
        n = -n;
    }
    vec3 r = reflect(v, n);
    float open = clamp(sky / 15.0, 0.0, 1.0);
    vec3 tint;
    float k = reflectStrength(code, albedo, n, v, tint);
    float toSun = max(dot(r, sunEye.xyz), 0.0);
#ifdef WATER
    // the translucent layer (ice, stained glass): the world by a ray, the sky where it meets nothing
    vec3 env = skyReflected(r, open);
    vec4 seen = traceWorld(pointFromCamera(vEye + n * 0.02), transpose(mat3(modelview)) * r);
    if (seen.w > 0.0) {
        env = seen.rgb;
    }
    vec3 c = mix(lit, env * tint, k);
    c += sunGlint(code, toSun, tint) * shadow * open;
#else
    vec3 c = lit;   // the light pass adds what the face reflects and the sun's glint (by the material map)
#endif
    // gems sparkle: a few bright texels light up and go out as the eye moves (per texel: crisp)
    if (code == 6) {
        vec2 texel = floor(vUv * vec2(textureSize(tex, 0)));
        vec3 turn = floor(v * 9.0);
        float h = fract(sin(dot(vec3(texel, dot(turn, vec3(1.0, 7.0, 13.0))), vec3(12.9898, 78.233, 37.719))) * 43758.5453);
        float bright = smoothstep(0.25, 0.8, dot(albedo, vec3(0.2126, 0.7152, 0.0722)));
        c += albedo * sunColor.rgb * step(0.94, h) * bright * (0.4 + 2.0 * toSun) * open * shadow;
    }
    return c;
#endif
}

// How much of the sun a leaf seen against it lets through, at most (of its own colour times the sun's light).
const float LEAF_THROUGH = 1.0;

// Sunlight through the leaves (glow mask code 5): looking towards the sun, a leaf the sun reaches from behind glows with
// its own colour in the sun's (golden low, faint at noon, cold and weak under the moon); the inside of a thick crown
// stays dark (the sun does not reach its far side). Only with the sun's shadows (shadowInfo.x).
vec3 leafLight(vec3 albedo, float sky) {
    int code = int(textureLod(glowMask, vUv, 0.0).r * (255.0 / 16.0) + 0.5);
    if (code != 5) {
        return vec3(0.0);
    }
    float toward = max(dot(normalize(vEye), sunEye.xyz), 0.0);
    float lobe = toward * toward * toward;
    if (lobe < 0.01) {
        return vec3(0.0);
    }
    return albedo * sunColor.rgb * (LEAF_THROUGH * shadowInfo.x * lobe * sunBehind(vEye, sky));
}


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
    vec2 lo;
    vec2 hi;
    if (!cell(uv, lo, hi)) {
        return texture(tex, uv);
    }
    vec2 size = vec2(textureSize(tex, 0));
    vec2 dx = dFdx(uv);
    vec2 dy = dFdy(uv);
    float lx = length(dx * size);
    float ly = length(dy * size);
    bool alongX = lx >= ly;
    float ratio = max(lx, ly) / max(min(lx, ly), 1e-6);
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
    vec4 centre = texture(tex, uv);
    return vec4(sum.a > 0.0 ? sum.rgb / sum.a : centre.rgb, centre.a);
}
#endif

void main() {
#ifdef DEPTH_ONLY
    // the alpha of the full pixel, as below: the colour's alpha times the texture's (the filters below keep the
    // centre tap's alpha, so both passes discard the same pixels)
    float a = vColor.a;
    if (params.w > 0.5) {
        a *= texture(tex, vUv).a;
    }
    if (a <= params.x) {
        discard;
    }
    color = vec4(0.0);
#else
#ifdef LENS
    float shadow = sunShadow(vEye, true, vLight.y / 16.0);   // (derivatives: before any discard)
    // the face, for the reflections (derivatives: before any discard; the frame says whether they are on)
    vec3 faceN = vec3(0.0, 1.0, 0.0);
#if defined(GBUFFER) || defined(WATER)
    if (true) {
#else
    if (dot(cloudInfo.yzw, vec3(1.0)) > 0.0) {
#endif
        vec3 cr = cross(dFdx(vEye), dFdy(vEye));
        faceN = dot(cr, cr) > 1e-20 ? normalize(cr) : faceN;
    }
#endif
    vec4 c = vColor;
    if (params.w > 0.5) {
#ifdef LENS
        c *= anim.w > 1.5 ? anisotropic(vUv, anim.w) : anim.z > 0.5 ? supersampled(vUv) : texture(tex, vUv);
#else
        c *= texture(tex, vUv);
#endif
    }
#if defined(ALPHA_TEST) && !defined(WATER)
    if (c.a <= params.x) {
        discard;
    }
#endif
#ifdef WATER
    if (c.a <= params.x) {
        c.a = 0.0;
    }
#endif
#ifdef GBUFFER
    vec4 mat = textureLod(materialMap, vUv, 0.0);
    gAlbedo = vec4(c.rgb, mat.g);
    gNormal = vec4(octEncode(faceN), mat.r, shadow);
    gLight = vec4(vLight.y / 240.0, vLight.x / 240.0, mat.b, mat.a);
    gShade = vec4(casterShade(vEye));
    gDist = vec4(length(vEye));
#endif
    if (params.y > 0.5) {
#ifdef LENS
        vec2 light = vec2(dynamicBlockLight(vEye, vLight.x), vLight.y);
        vec3 full = texture(lightmap, (light + 8.0) / 256.0).rgb;
        vec3 block = texture(lightmap, vec2(light.x + 8.0, 8.0) / 256.0).rgb;
        vec3 albedo = c.rgb;
        c.rgb *= worldLight(full, block, light, shadow);
        if (lightFlags.w > 0.0) {
            c.rgb = glowing(c.rgb, albedo, vUv);
        }
#ifdef GBUFFER
        // our lava (glow mask code 3), at its world point
        if (int(textureLod(glowMask, vUv, 0.0).r * (255.0 / 16.0) + 0.5) == 3) {
            vec3 world = pointFromBlock(vEye) + floor(dynCount.yzw);
            c.rgb = lava(world, abs((transpose(mat3(modelview)) * faceN).y) > 0.5);
            gAlbedo.rgb = min(c.rgb, vec3(1.0));
        }
#endif
        if (shadowInfo.x > 0.0) {
            c.rgb += leafLight(albedo, light.y / 16.0);
        }
        if (dot(cloudInfo.yzw, vec3(1.0)) > 0.0) {
            c.rgb = environment(c.rgb, albedo, faceN, light.y / 16.0, shadow);
        }
#ifdef WATER
        if (int(textureLod(glowMask, vUv, 0.0).r * (255.0 / 16.0) + 0.5) == 12) {
            vec3 lit = worldLight(full, block, light, shadow);
            c = vec4(water(faceN, vWorld, vColor.rgb, lit, shadow, light.y / 240.0), 1.0);
        }
#endif
#else
        c.rgb *= texture(lightmap, (vLight + 8.0) / 256.0).rgb;
#endif
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
