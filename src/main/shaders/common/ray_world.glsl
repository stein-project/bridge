// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2026 Breno Xavier
// The world as a ray sees it (SteinRayScene; Vulkan with ray tracing, GL_EXT_ray_query): needs `tex` and `lightmap`
// (the blocks' atlas and the lightmap) declared before it.
layout(set = 0, binding = 8) uniform accelerationStructureEXT rayScene;   // the terrain near the camera
// the terrain buffer: a 16-byte vertex as four words (x | y << 16, z | light << 16, colour, u | v << 16)
layout(std430, set = 0, binding = 9) readonly buffer TerrainVertices {
    uvec4 terrainVertex[];
};

// Where a ray from `from` (the world relative to the camera) along `dir` meets the terrain, up to tmax blocks: its
// texture's colour times the vertex's (albedo), the lightmap there (0..240 per level), the texture coordinate and the
// distance. A clear texel (leaves' holes) counts as a miss. The hit's vertex is rebuilt from the slice's first vertex
// (the instance's custom index), the quad (two triangles each, as the shared index buffer draws them: q, q+1, q+3 and
// q+1, q+2, q+3) and the barycentrics.
struct RayHit {
    bool hit;
    vec3 albedo;
    vec2 light;
    vec2 uv;
    float dist;
};

RayHit traceHit(vec3 from, vec3 dir, float tmax) {
    RayHit h;
    h.hit = false;
    h.albedo = vec3(0.0);
    h.light = vec2(0.0);
    h.uv = vec2(0.0);
    h.dist = tmax;
    rayQueryEXT q;
    rayQueryInitializeEXT(q, rayScene, gl_RayFlagsOpaqueEXT, 0xFF, from, 0.02, dir, tmax);
    while (rayQueryProceedEXT(q)) {
    }
    if (rayQueryGetIntersectionTypeEXT(q, true) != gl_RayQueryCommittedIntersectionTriangleEXT) {
        return h;
    }
    uint first = uint(rayQueryGetIntersectionInstanceCustomIndexEXT(q, true));
    int prim = rayQueryGetIntersectionPrimitiveIndexEXT(q, true);
    vec2 bc = rayQueryGetIntersectionBarycentricsEXT(q, true);
    uint quad = first + uint(prim / 2) * 4u;
    uvec3 ids = (prim & 1) == 0 ? uvec3(quad, quad + 1u, quad + 3u) : uvec3(quad + 1u, quad + 2u, quad + 3u);
    vec3 w = vec3(1.0 - bc.x - bc.y, bc.x, bc.y);
    vec2 uv = vec2(0.0);
    vec4 col = vec4(0.0);
    vec2 lt = vec2(0.0);
    for (int i = 0; i < 3; i++) {
        uvec4 v = terrainVertex[ids[i]];
        uv += w[i] * vec2(float(v.w & 0xFFFFu), float(v.w >> 16)) * (1.0 / 32768.0);
        col += w[i] * unpackUnorm4x8(v.z);
        uint light = v.y >> 16;
        lt += w[i] * vec2(float(light & 255u), float(light >> 8));
    }
    vec4 t = textureLod(tex, uv, 0.0);
    if (t.a < 0.1) {
        return h;
    }
    h.hit = true;
    h.albedo = t.rgb * col.rgb;
    h.light = lt;
    h.uv = uv;
    h.dist = rayQueryGetIntersectionTEXT(q, true);
    return h;
}

// The texture coordinate on a triangle: the slice's first vertex, the primitive, the barycentrics.
vec2 hitUv(uint first, int prim, vec2 bc) {
    uint quad = first + uint(prim / 2) * 4u;
    uvec3 ids = (prim & 1) == 0 ? uvec3(quad, quad + 1u, quad + 3u) : uvec3(quad + 1u, quad + 2u, quad + 3u);
    vec3 w = vec3(1.0 - bc.x - bc.y, bc.x, bc.y);
    vec2 uv = vec2(0.0);
    for (int i = 0; i < 3; i++) {
        uint word = terrainVertex[ids[i]].w;
        uv += w[i] * vec2(float(word & 0xFFFFu), float(word >> 16)) * (1.0 / 32768.0);
    }
    return uv;
}

#ifdef RAY_MATERIALS
// Whether the sun is shut out between `from` and tmax blocks along `dir`: each hit is looked at — a clear texel (the
// holes of leaves and plants) lets the light through, and so do the small plants (the material map's light through
// at 0.45: grass, flowers, ferns, crops; a field of tall grass would shade all the ground under a low sun). Needs
// `materialMap`.
bool sunBlocked(vec3 from, vec3 dir, float tmax) {
    rayQueryEXT q;
    rayQueryInitializeEXT(q, rayScene, gl_RayFlagsTerminateOnFirstHitEXT, 0xFF, from, 0.02, dir, tmax);
    while (rayQueryProceedEXT(q)) {
        if (rayQueryGetIntersectionTypeEXT(q, false) != gl_RayQueryCandidateIntersectionTriangleEXT) {
            continue;
        }
        vec2 uv = hitUv(uint(rayQueryGetIntersectionInstanceCustomIndexEXT(q, false)),
                rayQueryGetIntersectionPrimitiveIndexEXT(q, false), rayQueryGetIntersectionBarycentricsEXT(q, false));
        if (textureLod(tex, uv, 0.0).a < 0.1) {
            continue;
        }
        float through = textureLod(materialMap, uv, 0.0).a;
        if (abs(through - 0.45) < 0.02) {
            continue;
        }
        rayQueryConfirmIntersectionEXT(q);
    }
    return rayQueryGetIntersectionTypeEXT(q, true) == gl_RayQueryCommittedIntersectionTriangleEXT;
}
#endif

// Whether something solid stands between `from` and tmax blocks along `dir` (a clear texel lets the light through).
bool occluded(vec3 from, vec3 dir, float tmax) {
    return traceHit(from, dir, tmax).hit;
}

// What a ray sees: the terrain's colour as the game lights it (texture, vertex colour, lightmap) and 1 in w; 0 in w on
// a miss (the caller keeps the sky).
vec4 traceWorld(vec3 from, vec3 dir) {
    RayHit h = traceHit(from, dir, 64.0);
    if (!h.hit) {
        return vec4(0.0);
    }
    return vec4(h.albedo * texture(lightmap, (h.light + 8.0) / 256.0).rgb, 1.0);
}
