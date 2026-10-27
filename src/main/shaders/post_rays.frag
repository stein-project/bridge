#version 450
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2026 Breno Xavier

// The light in the air, at half the size, in two parts.
//
// The screen's shafts (SteinAtmosphere): from each pixel, steps towards the sun (or the moon) on the screen, counting
// the sky seen on the way — the world's depth at its clear value. Where trees, mountains or clouds stand between, the
// count drops, so the light comes through their gaps as rays. Each pixel starts its steps at a slightly different point
// (a fine noise), so the blocks' square edges do not repeat along the rays as steps; the glow is strongest around the
// source itself. Nothing on what is near: they are the far air's.
//
// The volume (SteinVolume): from the camera to what the pixel shows, steps through the air asking the sun's shadow map
// whether each point is lit, and adds what the dust there sends towards the camera. The light from a cave's opening
// becomes a beam with soft edges. The dust thickens in slow clouds, fixed to the world and drifting.
//
// params: xy the sun on the screen (0..1, rows from the bottom), z the screen's shafts strength (0 none), w the
// window's width / height. params2: rgb the screen's shafts colour.
// params3..params6: eye space -> the shadow map's clip space, by columns. params7: rgb the direct light, w the dust per
// block at the camera (0: no volume). params8: the projection's x, y and depth terms (as the composite's params7). params9: xyz the
// sun (or the moon) in eye space, w the farthest air walked. params10: xyz the camera in the world (x and z wrapped),
// w the time in seconds (params18.z: the ground's top, the enclosed dust's ceiling). params11..params13: xyz the camera's rotation (world -> eye), by columns; params11.w the dust
// per block far from the camera, params12.w the near dust's fall-off (blocks), params13.w how enclosed the camera is.
#include "common/post.glsl"
#include "common/shadow_distort.glsl"
layout(set = 0, binding = 1) uniform sampler2D depth;            // the world's, before the hand
layout(set = 0, binding = 2) uniform sampler2DShadow shadowMap;      // the sun's, of the world just drawn
layout(set = 0, binding = 3) uniform sampler2DShadow shadowPrevMap;  // the one before the sun's last step, fading out
layout(location = 0) out vec4 color;

const int SHAFT_STEPS = 32;
const int AIR_STEPS = 40;

// A fixed fine noise per pixel (interleaved gradient): where each pixel's steps start.
float jitter() {
    return fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
}

float screenShafts(vec2 uv) {
    vec2 sun = params.xy;
    vec2 step = (sun - uv) / float(SHAFT_STEPS);
    vec2 p = uv + step * jitter();
    float weight = 1.0;
    float lit = 0.0;
    for (int i = 0; i < SHAFT_STEPS; i++) {
        p += step;
        if (p.x < 0.0 || p.y < 0.0 || p.x > 1.0 || p.y > 1.0) {
            break;
        }
        float sky = textureLod(depth, p, 0.0).r >= 0.99999 ? 1.0 : 0.0;
        lit += sky * weight;
        weight *= 0.95;
    }
    lit /= float(SHAFT_STEPS);
    // stronger around the sun itself
    vec2 d = (uv - sun) * vec2(params.w, 1.0);
    float near = exp(-length(d) * 3.0);
    return lit * params.z * (0.12 + near);
}

float hash(vec3 p) {
    p = fract(p * vec3(0.1031, 0.1030, 0.0973));
    p += dot(p, p.yxz + 33.33);
    return fract((p.x + p.y) * p.z);
}

// Value noise, 0..1, smooth between the lattice's points.
float noise(vec3 p) {
    vec3 i = floor(p);
    vec3 f = p - i;
    f = f * f * (3.0 - 2.0 * f);
    float a = mix(hash(i), hash(i + vec3(1, 0, 0)), f.x);
    float b = mix(hash(i + vec3(0, 1, 0)), hash(i + vec3(1, 1, 0)), f.x);
    float c = mix(hash(i + vec3(0, 0, 1)), hash(i + vec3(1, 0, 1)), f.x);
    float d = mix(hash(i + vec3(0, 1, 1)), hash(i + vec3(1, 1, 1)), f.x);
    return mix(mix(a, b, f.y), mix(c, d, f.y), f.z);
}

// The dust at a point of the world, relative to the mean: slow, soft clouds of it at two sizes (no grain: a mote
// smaller than a pixel cannot be drawn at half the size, and bigger ones read as pixels).
float dust(vec3 world) {
    float t = params10.w;
    float big = noise(world * 0.12 + t * vec3(0.035, 0.010, 0.025));
    float small = noise(world * 0.45 + t * vec3(-0.06, 0.02, 0.05));
    return 0.6 + 0.55 * big + 0.25 * small;
}

// One map's light at a point of the air (eye space), through its matrix, faded out at its edge. Each step reads the
// map a little apart from the others (a turn of `spin` and a radius of `soft` texels), so the beam's edge is soft and
// the light spreads out of an opening instead of keeping the blocks' square edge.
float mapAt(sampler2DShadow map, mat4 toMap, vec3 eye, vec2 o) {
    vec3 s = (toMap * vec4(eye, 1.0)).xyz;
    vec3 c = vec3(shadowDistort(s.xy), s.z) * 0.5 + 0.5;
    if (c.x < 0.0 || c.y < 0.0 || c.x > 1.0 || c.y > 1.0 || c.z > 1.0) {
        return 0.0;   // past the map: no light known, none added
    }
    float lit = 0.5 * textureLod(map, vec3(c.xy + o, c.z - 0.0004), 0.0)
            + 0.5 * textureLod(map, vec3(c.xy - o.yx, c.z - 0.0004), 0.0);
    return lit * (1.0 - smoothstep(0.8, 0.95, length(s.xy)));
}

// Whether the sun reaches a point of the air: the map, and while it fades the one before the sun's last step.
float sunAt(vec3 eye, float spin, float soft) {
    vec2 o = vec2(cos(spin), sin(spin)) * soft / vec2(textureSize(shadowMap, 0));
    float lit = mapAt(shadowMap, mat4(params3, params4, params5, params6), eye, o);
    if (params18.x > 0.0) {
        lit = mix(lit, mapAt(shadowPrevMap, mat4(params14, params15, params16, params17), eye, o), params18.x);
    }
    return lit;
}

vec3 airLight(vec2 uv, float d) {
    vec2 ndc = uv * 2.0 - 1.0;
    vec3 dir = vec3(ndc.x / params8.x, ndc.y / params8.y, -1.0);
    float z = d >= 0.99999 ? params9.w : abs(params8.w / (d * 2.0 - 1.0 + params8.z));
    vec3 ray = dir * min(z, params9.w);   // to the pixel, or as far as the air is walked
    float len = length(ray);
    vec3 view = ray / max(len, 1e-4);
    mat3 toWorld = transpose(mat3(params11.xyz, params12.xyz, params13.xyz));
    float stepLen = len / float(AIR_STEPS);
    float j = jitter();
    float sum = 0.0;
    for (int i = 0; i < AIR_STEPS; i++) {
        float at = (float(i) + j) / float(AIR_STEPS);
        vec3 eye = ray * at;
        // golden-angle turns per step: the taps of one pixel fill a disc
        float lit = sunAt(eye, 6.2831853 * j + 2.3999632 * float(i), 1.0 + 3.0 * fract(j * 7.0 + float(i) * 0.618));
        if (lit > 0.0) {
            // dense near the camera where it sees no sky, the open air's little farther away
            // and only under the ground's top around (params18.z): the air out of a cave's opening is the open's
            vec3 world = toWorld * eye + params10.xyz;
            float under = 1.0 - smoothstep(params18.z - 1.0, params18.z + 2.0, world.y);
            float dense = params11.w + (params7.w - params11.w) * exp(-len * at / params12.w) * under;
            sum += lit * dense * dust(world);
        }
    }
    // a little to every side, more towards the light (the dust scatters forward); the bright lobe around the source only
    // where the camera is enclosed (in the open the sun's own glare is there, and it veiled the clouds)
    float toward = max(dot(view, params9.xyz), 0.0);
    float phase = 0.5 + 1.0 * toward * toward + 3.0 * params13.w * pow(toward, 10.0);
    // the dust lit by the sun reads golden: the light's own colour, a third of the way to a warm white of its brightness
    vec3 light = mix(params7.rgb, vec3(1.0, 0.86, 0.62) * dot(params7.rgb, vec3(0.3333)), 0.35);
    return light * phase * (1.0 - exp(-sum * stepLen));
}

// How much of the sun's (or moon's) disc is seen: the depth at its centre (counted twice) and at eight points on its
// rim; for the composite's glare and haze glow (in the alpha, the same everywhere). A gap of sky a little beside a sun
// behind a wall must not count: the glare showed over the rock.
float sunOpen() {
    vec3 s = params9.xyz;
    if (s.z > -0.05 || params8.x == 0.0) {
        return 0.0;
    }
    vec2 at = vec2(s.x * params8.x, s.y * params8.y) / -s.z * 0.5 + 0.5;
    float open = 0.0;
    for (int i = 0; i < 10; i++) {
        float a = 0.785398 * float(i);
        vec2 q = at + (i < 8 ? vec2(cos(a) * texel.z / texel.w, sin(a)) * 0.0045 : vec2(0.0));
        bool inside = q.x >= 0.0 && q.y >= 0.0 && q.x <= 1.0 && q.y <= 1.0;
        open += inside ? step(0.99999, textureLod(depth, q, 0.0).r) : 0.5;
    }
    open /= 10.0;
    // a sun half behind a hill: a quarter of the glare, not half; and dimmed by a cloud in front (params18.y)
    return open * open * params18.y;
}

void main() {
    vec2 uv = gl_FragCoord.xy * texel.zw;
    float d = textureLod(depth, uv, 0.0).r;
    vec3 c = vec3(0.0);
    if (params.z > 0.0) {
        float air = d >= 0.99999 ? 1e4 : abs(params8.w / (d * 2.0 - 1.0 + params8.z));
        c += params2.rgb * screenShafts(uv) * smoothstep(8.0, 64.0, air);
    }
    if (params7.w > 0.0) {
        c += airLight(uv, d);
    }
    // the alpha: the distance this texel's air was walked to, for the composite to put each pixel with the texels at its
    // own depth (the cave's mouth seen from inside stepped at half the size); the first texel holds the sun's measure
    float distance = d >= 0.99999 ? 1e4 : abs(params8.w / (d * 2.0 - 1.0 + params8.z));
    color = vec4(c, all(lessThan(gl_FragCoord.xy, vec2(1.0))) ? sunOpen() : distance);
}
