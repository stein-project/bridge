// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2026 Breno Xavier
// The Stein mode's water (stage 3 of docs/stein-mode-plan.md; terrain.frag with WATER, Vulkan with ray tracing): the
// game's water faces, drawn by us. Waves made in the shader on the block's pixel grid (crisp, like the game's textures;
// moving with time), the bottom seen through with the colour the depth gives (the biome's water colour from the
// vertex), the world reflected by a ray and the sky where it meets nothing (Fresnel), the sun's glitter, foam where it
// is shallow and on the falls. The game's water texture is not used: the colour is ours (WATER_COLOR) times the
// biome's (the vertex colour, greener in a swamp); what it absorbs follows from it. Needs the terrain's Frame block, materials.glsl, ray_world.glsl, `sceneCopy` (the image
// before the water) and `opaqueDepth` (the depth before it).

const float WATER_PIXELS = 16.0;   // the waves' grid: the game's texels per block
const vec3 WATER_COLOR = vec3(0.10, 0.44, 0.56);   // clear turquoise

float waterHash(vec3 p) {
    return fract(sin(dot(p, vec3(12.9898, 78.233, 37.719))) * 43758.5453);
}

// The height of the waves at a world point (blocks), moving with time.
float waterHeight(vec2 x, float t) {
    float h = sin(dot(x, vec2(0.82, 0.57)) * 1.7 + t * 1.3) * 0.5;
    h += sin(dot(x, vec2(-0.43, 0.9)) * 2.9 + t * 1.9) * 0.3;
    h += sin(dot(x, vec2(0.97, -0.24)) * 5.3 + t * 2.7) * 0.15;
    h += sin(dot(x, vec2(-0.71, -0.7)) * 9.1 + t * 3.4) * 0.07;
    return h;
}

// The waves' face on top of the water, on the pixel grid: the same for the whole texel. strength: how tall (far
// away they flatten out, or the pixel grid shimmers in lines).
vec3 waterNormal(vec2 world, float t, float strength) {
    vec2 q = (floor(world * WATER_PIXELS) + 0.5) / WATER_PIXELS;
    float e = 1.0 / WATER_PIXELS;
    float dx = waterHeight(q + vec2(e, 0.0), t) - waterHeight(q - vec2(e, 0.0), t);
    float dz = waterHeight(q + vec2(0.0, e), t) - waterHeight(q - vec2(0.0, e), t);
    return normalize(vec3(-dx * strength, 1.0, -dz * strength));
}

// The point in eye space for a screen coordinate (row 0 at the top) and a depth.
vec3 waterEyeAt(vec2 uv, float d) {
    float z = abs(projection[3][2] / (d * 2.0 - 1.0 + projection[2][2]));
    vec2 ndc = vec2(uv.x * 2.0 - 1.0, 1.0 - uv.y * 2.0);
    return vec3(ndc.x / projection[0][0] * z, ndc.y / projection[1][1] * z, -z);
}

// The water's colour at this pixel. faceN: the face in eye space; world: the world point (blocks); tint: the biome's
// (the vertex colour); lit: the light at the surface (lightmap and the Lens's world light); shadow: the sun's; sky: the sky
// light (0..1).
vec3 water(vec3 faceN, vec3 world, vec3 tint, vec3 lit, float shadow, float sky) {
    float t = anim.x;
    mat3 toWorld = transpose(mat3(modelview));
    vec3 v = normalize(vEye);
    vec3 nGeo = dot(faceN, v) > 0.0 ? -faceN : faceN;
    vec3 nGeoW = toWorld * nGeo;
    bool top = abs(nGeoW.y) > 0.5;
    float waves = 0.9 * clamp(1.0 - length(vEye) / 40.0, 0.1, 1.0);
    vec3 nW = top ? waterNormal(world.xz, t, waves) * vec3(1.0, sign(nGeoW.y), 1.0) : nGeoW;
    vec3 n = mat3(modelview) * nW;
    // the bottom: how much water the eye looks through, from the depth before the water
    vec2 size = vec2(textureSize(sceneCopy, 0));
    vec2 uv = gl_FragCoord.xy / size;
    float surface = length(vEye);
    float dBack = textureLod(opaqueDepth, uv, 0.0).r;
    float thick = dBack >= 1.0 ? 64.0 : max(length(waterEyeAt(uv, dBack)) - surface, 0.0);
    // bent by the waves, only towards what is behind the water
    vec2 bend = (n.xy - nGeo.xy) * vec2(0.06, -0.06) * clamp(thick, 0.0, 1.5);
    vec2 uvB = uv + bend;
    float dB = textureLod(opaqueDepth, uvB, 0.0).r;
    if (dB < 1.0 && length(waterEyeAt(uvB, dB)) < surface) {
        uvB = uv;
    }
    vec3 behind = textureLod(sceneCopy, uvB, 0.0).rgb;
    // red goes first, then green: clear and blue-green shallow, deep and dark far down
    vec3 own = WATER_COLOR * tint;
    vec3 hue = own / max(max(own.r, max(own.g, own.b)), 0.05);
    vec3 absorb = (1.0 - hue) * 0.55 + 0.03;
    vec3 through = exp(-absorb * thick);
    // the light the water itself sends back (it grows with the depth, whatever the colour): clear where it is
    // shallow, the water's own colour further down
    float body = 1.0 - exp(-0.32 * thick);
    vec3 refr = mix(behind * through, hue * lit * 0.42, body);
    // the reflection: the world where the ray meets it, the sky where it does not
    vec3 r = reflect(v, n);
    if (dot(r, nGeo) < 0.0) {
        r = reflect(r, nGeo);   // a wave tilted past the face: keep the ray above the water
    }
    float open = clamp(sky, 0.0, 1.0);
    vec3 env = skyReflected(r, open);
    vec4 seen = traceWorld(pointFromCamera(vEye + nGeo * 0.05), toWorld * r);
    if (seen.w > 0.0) {
        env = seen.rgb;
    }
    float cosT = clamp(dot(-v, n), 0.0, 1.0);
    float fres = 0.02 + 0.98 * pow(1.0 - cosT, 5.0);
    vec3 c = mix(refr, env, fres);
    // the sun's glitter: a sharp highlight on each wave's texel
    vec3 h = normalize(sunEye.xyz - v);
    float nh = max(dot(n, h), 0.0);
    c += sunColor.rgb * (pow(nh, 900.0) * 2.5 + pow(nh, 120.0) * 0.3) * shadow * open;
    // foam: where it is shallow (on the pixel grid, slowly changing) and on the falls
    vec3 cell = floor(world * WATER_PIXELS);
    float foam = 0.0;
    if (top) {
        float shore = 1.0 - smoothstep(0.02, 0.18, thick);
        foam = step(1.0 - shore * 0.35, waterHash(cell + floor(t * 1.5)));
    } else {
        float fall = waterHash(vec3(cell.x + cell.z * 7.0, floor(cell.y + t * 24.0), 3.0));
        foam = step(0.62, fall) * 0.8;
    }
    c = mix(c, lit * 0.8, foam * 0.7);   // the foam takes the light there, never past it
    return c;
}
