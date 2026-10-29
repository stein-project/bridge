#version 450
#extension GL_EXT_ray_query : require
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2026 Breno Xavier
// The Stein mode's bounced light (SteinRayLight; Vulkan with ray tracing; stage 4 of docs/stein-mode-plan.md): from
// each opaque pixel a few rays go out over its face (cosine-weighted, turned per pixel and frame); where one meets the
// terrain it brings back that surface's light — its colour lit by the sky and the sun there (the game's block light
// already reaches around by itself) and its own light (the material map: lava, glowstone) — so the lit wall puts its colour on the floor and the lava lights the cave orange.
// The sky's own light is the game's (the lightmap), so a ray that meets nothing brings nothing. Each frame's few rays
// go into what the pixel had the frame before (reprojected from the last camera; dropped where the point was not
// there), so it settles without noise. Out: rgb the bounced light, a the point's distance from the eye. Second out:
// the blurred reflection of the satin materials (smoothness between GLOSS_FROM and SHARP: wood, quartz, polished
// stone), one ray a frame around the mirror direction, as wide as the face is rough, settled the same way (stage 5).
#include "common/terrain_frame.glsl"
#include "common/world_light.glsl"
#include "common/gbuffer.glsl"
#include "common/materials.glsl"
layout(set = 0, binding = 1) uniform sampler2D tex;          // the blocks' atlas
layout(set = 0, binding = 2) uniform sampler2D lightmap;
layout(set = 0, binding = 3) uniform sampler2D depthMap;     // the world's depth
layout(set = 0, binding = 4) uniform sampler2D gAlbedo;
layout(set = 0, binding = 5) uniform sampler2D gNormal;
layout(set = 0, binding = 6) uniform sampler2D gLight;
layout(set = 0, binding = 7) uniform sampler2D giPrev;       // last frame's out
layout(set = 0, binding = 10) uniform sampler2D materialMap; // SteinMaterialMap: smooth, metal, own light, through
layout(set = 0, binding = 16) uniform sampler2D gDist;       // the exact distance
layout(set = 0, binding = 14) uniform sampler2D glossPrev;   // last frame's second out
#include "common/ray_world.glsl"
layout(std140, set = 0, binding = 13) uniform History {
    mat4 prevProjection;
    mat4 prevModelview;
    vec4 camDelta;   // xyz: the camera's block now minus last frame's; w: the frame's number
    vec4 info;       // x: 1 when last frame's out can be read
};
layout(location = 0) out vec4 color;
layout(location = 1) out vec4 gloss;

const int RAYS = 2;
const float REACH = 24.0;      // blocks a bounce ray goes
const float OWN_LIGHT = 0.7;   // a light-giving surface's light, times its colour
const float KEEP = 0.9;        // the share of the past in the settled light
const float GLOSS_FROM = 0.15; // the roughest face with a reflection
const float SHARP = 0.92;      // from here the light pass traces the mirror itself

vec3 eyeAt(vec2 uv, float d) {
    float z = abs(projection[3][2] / (d * 2.0 - 1.0 + projection[2][2]));
    vec2 ndc = vec2(uv.x * 2.0 - 1.0, 1.0 - uv.y * 2.0);
    return vec3(ndc.x / projection[0][0] * z, ndc.y / projection[1][1] * z, -z);
}

float rand(inout uint s) {
    s = s * 747796405u + 2891336453u;
    uint w = ((s >> ((s >> 28u) + 4u)) ^ s) * 277803737u;
    return float((w >> 22u) ^ w) / 4294967295.0;
}

// A direction over the face n, more of them near the normal (cosine-weighted).
vec3 overFace(vec3 n, float u1, float u2) {
    float r = sqrt(u1);
    float phi = 6.2831853 * u2;
    vec3 t = normalize(abs(n.y) < 0.99 ? cross(n, vec3(0.0, 1.0, 0.0)) : cross(n, vec3(1.0, 0.0, 0.0)));
    vec3 b = cross(n, t);
    return normalize(t * (r * cos(phi)) + b * (r * sin(phi)) + n * sqrt(max(0.0, 1.0 - u1)));
}

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    float d = texelFetch(depthMap, p, 0).r;
    gloss = vec4(0.0);
    if (d >= 1.0 || texelFetch(gDist, p, 0).r <= 0.0) {
        color = vec4(0.0, 0.0, 0.0, 1e4);
        return;
    }
    vec2 size = vec2(textureSize(depthMap, 0));
    vec2 uv = (vec2(p) + 0.5) / size;
    vec3 eye = GBUFFER_EYE(uv, texelFetch(gDist, p, 0).r);
    vec3 n = octDecode(texelFetch(gNormal, p, 0).xy);
    if (dot(n, eye) > 0.0) {
        n = -n;
    }
    mat3 toWorld = transpose(mat3(modelview));
    vec3 nW = toWorld * n;
    vec3 from = pointFromCamera(eye) + nW * 0.03;   // relative to the camera (the rays' scene)
    uint seed = uint(p.x) * 1973u + uint(p.y) * 9277u + uint(camDelta.w) * 26699u;
    vec3 sum = vec3(0.0);
    for (int i = 0; i < RAYS; i++) {
        vec3 dir = overFace(nW, rand(seed), rand(seed));
        RayHit h = traceHit(from, dir, REACH);
        if (!h.hit) {
            continue;
        }
        // the sky's part of the lightmap only (block light 0)
        vec3 full = texture(lightmap, vec2(8.0, h.light.y + 8.0) / 256.0).rgb;
        vec3 block = texture(lightmap, vec2(8.0, 8.0) / 256.0).rgb;
        vec2 level = vec2(0.0, h.light.y / 16.0);
        // the sun at the hit: as much as the sky reaches there (no second ray)
        float sun = 0.6 * smoothstep(12.0, 15.0, level.y);
        float own = textureLod(materialMap, h.uv, 0.0).b;
        sum += h.albedo * worldLight(full, block, level, sun) + h.albedo * own * OWN_LIGHT;
    }
    vec3 now = sum / float(RAYS);
    // last frame's light at this point: the world point (from the camera's block) seen by the last camera
    vec3 point = pointFromBlock(eye);
    vec4 eyePrev = prevModelview * vec4(point + camDelta.xyz, 1.0);
    vec4 clip = prevProjection * eyePrev;
    float dist = length(eye);
    color = vec4(now, dist);
    // the satin reflection: one ray around the mirror direction, the wider the rougher
    float smoothness = texelFetch(gNormal, p, 0).z;
    vec3 glossNow = vec3(0.0);
    bool satin = smoothness > GLOSS_FROM && smoothness < SHARP;
    if (satin) {
        vec3 v = normalize(eye);
        vec3 rW = toWorld * reflect(v, n);
        float rough = 1.0 - smoothness;
        vec3 dir = normalize(mix(rW, overFace(nW, rand(seed), rand(seed)), rough * rough));
        RayHit h = traceHit(from, dir, 48.0);
        if (h.hit) {
            float own = textureLod(materialMap, h.uv, 0.0).b;
            glossNow = h.albedo * (texture(lightmap, (h.light + 8.0) / 256.0).rgb + own * OWN_LIGHT);
        } else {
            float open = clamp(texelFetch(gLight, p, 0).x, 0.0, 1.0);
            glossNow = skyReflected(mat3(modelview) * dir, open);
        }
    }
    gloss = vec4(glossNow, dist);
    if (info.x > 0.5 && clip.w > 0.0) {
        vec2 ndc = clip.xy / clip.w;
        vec2 uvPrev = ndc * 0.5 + 0.5;
        if (all(greaterThanEqual(uvPrev, vec2(0.0))) && all(lessThanEqual(uvPrev, vec2(1.0)))) {
            vec4 past = textureLod(giPrev, uvPrev, 0.0);
            float was = length(eyePrev.xyz);
            if (abs(past.a - was) < 0.04 * was + 0.08) {
                color.rgb = mix(now, past.rgb, KEEP);
                if (satin) {
                    gloss.rgb = mix(glossNow, textureLod(glossPrev, uvPrev, 0.0).rgb, 0.8);
                }
            }
        }
    }
}
