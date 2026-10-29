#version 450
#extension GL_EXT_ray_query : require
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2026 Breno Xavier
// The Stein mode's light pass (SteinRayLight; Vulkan with ray tracing), after the opaque terrain: from what each pixel
// is (the G-buffer the terrain wrote, common/gbuffer.glsl, and the depth) it traces the rays: the sun's shadow by a
// ray (sharp and with no reach limit: the difference to the shadow map's is added), the bounced light (ray_gi.frag,
// settled over frames; spread over the neighbours at the same depth), the reflections by
// the material (SteinMaterialMap: smoothness and metal; Fresnel), the world where the ray meets it and the sky where
// it does not, and the sun's highlight on smooth faces; added over the scene with premultiplied alpha (what the face
// reflects replaces that much of its colour). Pixels with nothing to add give 0 (no discard: on the RX 7600 a shader
// with ray queries and a discard draws what it discards).
#include "common/terrain_frame.glsl"
#include "common/materials.glsl"
#include "common/gbuffer.glsl"
#include "common/world_light.glsl"
layout(set = 0, binding = 1) uniform sampler2D tex;        // the blocks' atlas
layout(set = 0, binding = 2) uniform sampler2D lightmap;
layout(set = 0, binding = 3) uniform sampler2D depthMap;   // the world's depth
layout(set = 0, binding = 4) uniform sampler2D gAlbedo;
layout(set = 0, binding = 5) uniform sampler2D gNormal;
layout(set = 0, binding = 6) uniform sampler2D gLight;
layout(set = 0, binding = 16) uniform sampler2D gDist;         // the exact distance
layout(set = 0, binding = 15) uniform sampler2D gShade;        // the entities' and the clouds' shadow
layout(set = 0, binding = 10) uniform sampler2D materialMap;   // SteinMaterialMap (the sun rays' plants)
layout(set = 0, binding = 7) uniform sampler2D giNow;      // the bounced light (ray_gi.frag); a: the distance
layout(set = 0, binding = 14) uniform sampler2D glossNow;  // the satin materials' blurred reflection (ray_gi.frag)
#define RAY_MATERIALS
#include "common/ray_world.glsl"
layout(location = 0) out vec4 color;

// The point in eye space for a texture coordinate (row 0 at the top) and a depth, from the projection's terms.
vec3 eyeAt(vec2 uv, float d) {
    float z = abs(projection[3][2] / (d * 2.0 - 1.0 + projection[2][2]));
    vec2 ndc = vec2(uv.x * 2.0 - 1.0, 1.0 - uv.y * 2.0);
    return vec3(ndc.x / projection[0][0] * z, ndc.y / projection[1][1] * z, -z);
}

const float PI = 3.14159265;
const float BOUNCED = 1.0;   // the bounced light's strength

// The bounced light (or the satin reflection) here: the 3x3 around, only where the point is at the same distance (no
// light across an edge).
vec3 settled(sampler2D giNow, ivec2 p, float dist) {
    ivec2 last = textureSize(giNow, 0) - 1;
    vec3 sum = vec3(0.0);
    float weight = 0.0;
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            vec4 g = texelFetch(giNow, clamp(p + ivec2(x, y), ivec2(0), last), 0);
            float w = (x == 0 && y == 0 ? 2.0 : 1.0) * step(abs(g.a - dist), 0.05 * dist + 0.05);
            sum += g.rgb * w;
            weight += w;
        }
    }
    return weight > 0.0 ? sum / weight : vec3(0.0);
}

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    float d = texelFetch(depthMap, p, 0).r;
    color = vec4(0.0);
    // the sky, or what the opaque terrain did not draw this frame (the clouds: drawn before it, with depth)
    if (d >= 1.0 || texelFetch(gDist, p, 0).r <= 0.0) {
        return;
    }
    vec4 a = texelFetch(gAlbedo, p, 0);
    vec4 g = texelFetch(gNormal, p, 0);
    vec4 l = texelFetch(gLight, p, 0);
    float smoothness = g.z;
    float metal = a.a;
    float shadow = g.w;
    vec2 uv = (vec2(p) + 0.5) / vec2(textureSize(depthMap, 0));
    vec3 eye = GBUFFER_EYE(uv, texelFetch(gDist, p, 0).r);
    vec3 v = normalize(eye);
    vec3 n = octDecode(g.xy);
    if (dot(n, v) > 0.0) {
        n = -n;
    }
    float open = clamp(l.x, 0.0, 1.0);
    float cosT = clamp(dot(-v, n), 0.0, 1.0);
    // Fresnel: dielectrics 4%, metals their own colour; a rough face's mirror image fades out (its blurred one is
    // the bounced light's, later)
    vec3 f0 = mix(vec3(0.04), a.rgb, metal);
    vec3 fres = f0 + (1.0 - f0) * pow(1.0 - cosT, 5.0);
    vec3 k = fres * smoothstep(0.15, 0.7, smoothness);
    vec3 add = vec3(0.0);
    float keep = 0.0;
    mat3 toWorld = transpose(mat3(modelview));
    // the light bounced off the world around
    add += a.rgb * settled(giNow, p, length(eye)) * (BOUNCED * (1.0 - l.z));   // (a light-giving face: its own)
    // the sun's shadow by a ray, in place of the map's (worldLight is linear in it: add the difference)
    float nlSun = dot(n, sunEye.xyz);
    float sunRay = shadow;
    if (nlSun > 0.02 && sunColor.r + sunColor.g + sunColor.b > 0.0) {
        sunRay = sunBlocked(pointFromCamera(eye + n * 0.03), toWorld * sunEye.xyz, 256.0) ? 0.0 : 1.0;
        sunRay *= texelFetch(gShade, p, 0).r;
        vec2 lt = vec2(l.y, l.x) * 240.0;
        vec3 full = texture(lightmap, (lt + 8.0) / 256.0).rgb;
        vec3 block = texture(lightmap, vec2(lt.x + 8.0, 8.0) / 256.0).rgb;
        add += a.rgb * (worldLight(full, block, lt / 16.0, sunRay) - worldLight(full, block, lt / 16.0, shadow));
    }
    shadow = sunRay;
    if (max(k.r, max(k.g, k.b)) > 0.01) {
        vec3 env;
        if (smoothness >= 0.92) {
            // a mirror: its own ray
            vec3 r = reflect(v, n);
            env = skyReflected(r, open);
            vec4 seen = traceWorld(pointFromCamera(eye + n * 0.02), toWorld * r);
            if (seen.w > 0.0) {
                env = seen.rgb;
            }
        } else {
            env = settled(glossNow, p, length(eye));   // satin: the blurred reflection, settled over frames
        }
        add = env * k;
        keep = dot(k, vec3(1.0 / 3.0));
    }
    // the sun's highlight (GGX), where the sun reaches
    vec3 sun = sunEye.xyz;
    float nl = dot(n, sun);
    if (nl > 0.0 && shadow > 0.0 && smoothness > 0.05) {
        float rough = max(1.0 - smoothness, 0.04);
        float a2 = rough * rough * rough * rough;
        vec3 h = normalize(sun - v);
        float nh = max(dot(n, h), 0.0);
        float den = nh * nh * (a2 - 1.0) + 1.0;
        float dist = a2 / (PI * den * den);
        vec3 fs = f0 + (1.0 - f0) * pow(1.0 - max(dot(h, -v), 0.0), 5.0);
        add += sunColor.rgb * fs * (dist * nl * 0.25) * shadow;
    }
    color = vec4(add, keep);
}
