#version 450
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2026 Breno Xavier

// The corner shading at half the size (SteinAo): from the world's depth, the point seen and its face, then eight taps in
// the half-ball over the face, turned per pixel; a tap that the depth shows behind something nearer is shut in. 1 is
// open, towards 0 shut. The sky stays open; far away the shading fades out (the haze takes over).
// params: x how dark a shut corner gets, y the half-ball's radius (blocks), z and w the fade's start and end (blocks).
// params2: the projection's x, y and depth terms.
#include "common/post.glsl"
layout(set = 0, binding = 1) uniform sampler2D depth;   // the world's, before the hand
layout(location = 0) out vec4 color;

// The half-ball's taps over the face (z up), shorter ones first: most of the shading comes from close by.
const vec3 KERNEL[8] = vec3[8](
        vec3(0.20, 0.05, 0.20), vec3(-0.15, 0.25, 0.30), vec3(-0.35, -0.20, 0.25), vec3(0.30, -0.35, 0.35),
        vec3(0.55, 0.30, 0.30), vec3(-0.40, 0.60, 0.45), vec3(-0.70, -0.35, 0.40), vec3(0.45, -0.75, 0.55));

// The point in eye space for a texture coordinate and a depth.
vec3 eyeAt(vec2 uv, float d) {
    float z = abs(params2.w / (d * 2.0 - 1.0 + params2.z));
    vec2 ndc = uv * 2.0 - 1.0;
    return vec3(ndc.x / params2.x * z, ndc.y / params2.y * z, -z);
}

// The point of a full-size texel, at that texel's own centre: a point built from one place and the depth of another
// sits off its surface where the depth runs fast (a floor seen low), and the floor then shut itself in stripes.
vec3 eyeOf(ivec2 t) {
    return eyeAt((vec2(t) + 0.5) * texel.xy, texelFetch(depth, t, 0).r);
}

void main() {
    ivec2 last = textureSize(depth, 0) - 1;
    ivec2 fp = min(ivec2(gl_FragCoord.xy) * 2, last);
    vec2 uv = (vec2(fp) + 0.5) * texel.xy;
    float d = texelFetch(depth, fp, 0).r;
    if (d >= 0.99999) {
        color = vec4(1.0);
        return;
    }
    vec3 p = eyeAt(uv, d);
    float dist = -p.z;
    if (dist > params.w) {
        color = vec4(1.0);
        return;
    }
    // the face from the neighbours on the side nearer in depth (no halo where two surfaces meet)
    vec3 r = eyeOf(min(fp + ivec2(1, 0), last)) - p;
    vec3 l = p - eyeOf(max(fp - ivec2(1, 0), ivec2(0)));
    vec3 u = eyeOf(min(fp + ivec2(0, 1), last)) - p;
    vec3 b = p - eyeOf(max(fp - ivec2(0, 1), ivec2(0)));
    vec3 dx = abs(r.z) < abs(l.z) ? r : l;
    vec3 dy = abs(u.z) < abs(b.z) ? u : b;
    vec3 cr = cross(dx, dy);
    if (dot(cr, cr) < 1e-12) {
        color = vec4(1.0);
        return;
    }
    vec3 n = normalize(cr);
    if (dot(n, p) > 0.0) {
        n = -n;   // the side towards the camera
    }
    // a basis over the face, turned by a fine noise per pixel
    float a = 6.2831853 * fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
    vec3 helper = abs(n.y) < 0.9 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
    vec3 t = normalize(cross(helper, n));
    vec3 bt = cross(n, t);
    vec3 tt = t * cos(a) + bt * sin(a);
    vec3 bb = cross(n, tt);
    float shut = 0.0;
    for (int i = 0; i < 8; i++) {
        vec3 k = KERNEL[i];
        vec3 s = p + (tt * k.x + bb * k.y + n * k.z) * params.y;
        vec2 suv = vec2(params2.x * s.x, params2.y * s.y) / -s.z * 0.5 + 0.5;
        if (suv.x < 0.0 || suv.y < 0.0 || suv.x > 1.0 || suv.y > 1.0) {
            continue;
        }
        ivec2 st = clamp(ivec2(suv / texel.xy), ivec2(0), last);
        float sd = texelFetch(depth, st, 0).r;
        if (sd >= 0.99999) {
            continue;
        }
        float sz = -eyeAt((vec2(st) + 0.5) * texel.xy, sd).z;
        float tz = -s.z;
        // in front of the tap by more than the depth's own step there (it grows with the distance), and within
        // reach (a far wall behind does not shut it)
        float inFront = step(0.04 + 0.008 * dist, tz - sz);
        float reach = smoothstep(0.0, 1.0, params.y / max(abs(dist - sz), 1e-3));
        shut += inFront * reach;
    }
    float open = 1.0 - shut / 8.0;
    float fade = smoothstep(params.z, params.w, dist);
    color = vec4(vec3(mix(open, 1.0, fade)), 1.0);
}
