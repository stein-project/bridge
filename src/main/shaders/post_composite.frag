#version 450
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2026 Breno Xavier

// Post-processing, last pass: the copy of the world's image back onto the game's, with
//  - edge smoothing only where the DEPTH breaks (an object's outline against what is behind it): textures keep every
//    pixel sharp, and so do strokes a few pixels wide (letters, wires). A plane's depth is affine on the screen, so its second difference is ~0; at an outline it is about
//    as large as the first difference. There the pixel is averaged along the edge (the luma gradient's normal).
//    It fades out with distance (params4.z blocks, from half of it): far away the outlines are dense and averaging
//    them only makes the distance look soft.
//  - bloom, added by "screen" (never past white). With HDR output the image stays the one without it, and only in
//    the dark a light rises above the paper white, in linear light, up to 1 + params2.w times, where the pixel is
//    bright AND the bloom says the area around it glows (params4.y times the bloom's luma): a white speck in sand or
//    a flower is not a light and stays as it is.
//  - sharpening, away from the outlines: the pixel pushed away from its four neighbours' mean, as much as the
//    neighbourhood leaves room for — fine detail moves the most, flat areas and strong contrasts little — so it brings
//    the texture's pixels out without a halo along the edges, and never far past the neighbours' range, so the higher
//    levels add detail rather than contrast.
//  - the atmosphere (SteinAtmosphere): a mist that gathers low, in the valleys and over the water, thicker at night and
//    at dawn — from the world's depth each pixel finds where it is and how much low air the view crosses to reach
//    it — and the light shafts drawn by post_rays, added by "screen".
// params: x bloom strength (0 off), y sharpening (0 off), w edges (0 off).
// params5: x mist density (0 off), y the camera's height, z the mist's base height, w how fast it thins upwards.
// params6: rgb the mist's colour, w 1 to add the rays. params7: the projection's x, y and depth terms.
// params8: xyz the world's up in eye space, w the farthest a sky pixel counts (blocks).
// params11..params13: the camera's rotation (world -> eye), by columns; params11.w the time (s), params12.w how enclosed
// the camera is, params13.w the dust motes' strength (0: none). params14.xyz: the camera in the world (SteinVolume.motes).
// params2: x debug (1: edges in red), y edge ratio, z smallest depth step that counts, w HDR boost (0: SDR).
// params3: the light threshold (post.glsl). params4: x the exposure (SteinExposure), y glow gain, z edge distance (blocks), w 1: the scene goes past
// white (the Stein light): the bloom adds its light, then an exposure that follows the scene's mean like an eye and a
// soft shoulder bring it back — what is below the shoulder stays as drawn; the sun and the lights roll off towards
// white (with HDR output, towards 1 + params2.w).
#include "common/post.glsl"
layout(set = 0, binding = 1) uniform sampler2D scene;
layout(set = 0, binding = 2) uniform sampler2D bloom;
layout(set = 0, binding = 3) uniform sampler2D depth;      // the world's, before the game cleared it for the hand
layout(set = 0, binding = 4) uniform sampler2D handDepth;  // after the hand: 1 wherever there is no hand
layout(set = 0, binding = 5) uniform sampler2D average;    // the scene's mean luma in its last mipmap
layout(set = 0, binding = 6) uniform sampler2D rays;       // the light shafts, at half the size
layout(location = 0) out vec4 color;
#ifdef HDR_LAYER
// HDR output, quick quality (SteinHdr): the game's image stays 8 bits, clipped at white; the full light goes into this
// layer (16 bits); clipped, it is what the game's image holds, so the HDR encode can tell a pixel nothing drew over
// since (the HUD, a menu) and only there use the light.
layout(location = 1) out vec4 lights;
#endif

// The light in the air (half the size) brought to the pixel: the 3x3 of its texels around it, each counted by its
// distance (the half-size pass keeps, in the alpha, the depth it walked to) against this pixel's, so the light keeps
// the edges of what stands in front at the full size (from inside a cave the opening's rim stepped), and by its
// nearness, which also smooths the steps' grain.
float viewDistance(float d) {
    return d >= 0.99999 ? 1e4 : abs(params7.w / (d * 2.0 - 1.0 + params7.z));
}

vec3 softRays(vec2 uv, ivec2 p, ivec2 last) {
    float zc = viewDistance(texelFetch(depth, p, 0).r);
    ivec2 size = textureSize(rays, 0);
    vec2 h = gl_FragCoord.xy * 0.5 - 0.5;   // this pixel in the half-size texels
    ivec2 base = ivec2(floor(h + 0.5));
    vec3 sum = vec3(0.0);
    float weights = 0.0;
    vec3 nearest = vec3(0.0);
    float best = 1e9;
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            ivec2 t = clamp(base + ivec2(x, y), ivec2(0, 1), size - 1);   // (the first row's first texel is the sun)
            vec4 r = texelFetch(rays, t, 0);
            vec2 off = vec2(t) - h;
            float dz = abs(r.a - zc);
            float w = exp(-dot(off, off) * 0.6) * exp(-dz / (0.03 * zc + 0.12));
            sum += w * finite(r.rgb);
            weights += w;
            if (dz < best) {
                best = dz;
                nearest = finite(r.rgb);
            }
        }
    }
    // no texel at this depth around (a thin edge): the closest in depth
    return weights > 1e-4 ? sum / weights : nearest;
}

// The hand where it was drawn, the world elsewhere (the hand's depth is from its own projection: always a step).
float depthAt(ivec2 p, ivec2 last) {
    ivec2 q = clamp(p, ivec2(0), last);
    float hand = texelFetch(handDepth, q, 0).r;
    return hand < 1.0 ? hand : texelFetch(depth, q, 0).r;
}

// Nearer than the depth on both sides, 2 to 5 pixels away along x or y: a stroke a few pixels wide — the letters of
// a name or a sign, a fence's wire, a flower. Smoothing would only blur it; it keeps its pixels.
bool thin(ivec2 p, ivec2 last, float c) {
    float step = params2.z;
    for (int r = 2; r <= 5; r++) {
        if (depthAt(p + ivec2(-r, 0), last) > c + step && depthAt(p + ivec2(r, 0), last) > c + step) {
            return true;
        }
        if (depthAt(p + ivec2(0, -r), last) > c + step && depthAt(p + ivec2(0, r), last) > c + step) {
            return true;
        }
    }
    return false;
}

bool outline(ivec2 p, ivec2 last) {
    float c = depthAt(p, last);
    float l = depthAt(p + ivec2(-1, 0), last);
    float r = depthAt(p + ivec2(1, 0), last);
    float d = depthAt(p + ivec2(0, -1), last);
    float u = depthAt(p + ivec2(0, 1), last);
    float sx = abs(l + r - 2.0 * c);
    float sy = abs(d + u - 2.0 * c);
    bool edge = sx > max(abs(r - l) * params2.y, params2.z) || sy > max(abs(u - d) * params2.y, params2.z);
    return edge && !thin(p, last, c);
}

vec3 smoothEdge(ivec2 p, vec2 uv, vec3 c) {
    ivec2 last = textureSize(scene, 0) - 1;
    float ln = luma(texelFetch(scene, clamp(p + ivec2(0, 1), ivec2(0), last), 0).rgb);
    float ls = luma(texelFetch(scene, clamp(p + ivec2(0, -1), ivec2(0), last), 0).rgb);
    float le = luma(texelFetch(scene, clamp(p + ivec2(1, 0), ivec2(0), last), 0).rgb);
    float lw = luma(texelFetch(scene, clamp(p + ivec2(-1, 0), ivec2(0), last), 0).rgb);
    vec2 g = vec2(le - lw, ln - ls);
    if (dot(g, g) < 1.0 / 1024.0) {
        return c; // the same colour on both sides: nothing to smooth
    }
    // half a pixel each way along the edge: the stairs go, the edge stays crisp (a wider average left the outlines
    // soft)
    vec2 along = normalize(vec2(-g.y, g.x)) * texel.xy;
    return 0.5 * (texture(scene, uv + along * 0.5).rgb + texture(scene, uv - along * 0.5).rgb);
}

vec3 sharpen(ivec2 p, vec3 c) {
    ivec2 last = textureSize(scene, 0) - 1;
    vec3 n = finite(texelFetch(scene, clamp(p + ivec2(0, 1), ivec2(0), last), 0).rgb);
    vec3 s = finite(texelFetch(scene, clamp(p + ivec2(0, -1), ivec2(0), last), 0).rgb);
    vec3 e = finite(texelFetch(scene, clamp(p + ivec2(1, 0), ivec2(0), last), 0).rgb);
    vec3 w = finite(texelFetch(scene, clamp(p + ivec2(-1, 0), ivec2(0), last), 0).rgb);
    vec3 lo = min(c, min(min(n, s), min(e, w)));
    vec3 hi = max(c, max(max(n, s), max(e, w)));
    // room before the neighbourhood's own black or white: little where it already spans the whole range
    vec3 room = sqrt(clamp(min(lo, 1.0 - hi) / max(hi, vec3(1e-4)), 0.0, 1.0));
    vec3 r = c + (c - 0.25 * (n + s + e + w)) * room * params.y * 2.0;
    // at most a quarter past the neighbourhood's own range: detail, not contrast
    vec3 reach = 0.25 * (hi - lo);
    return clamp(r, max(lo - reach, vec3(0.0)), min(hi + reach, vec3(params4.w > 0.0 ? 65504.0 : 1.0)));
}

// How much mist lies between the camera and the pixel at depth d (the world's): exponential in height, integrated
// along the view ray, so a valley seen from a hill fills with it and the air above stays clear.
float mist(vec2 uv, float d) {
    vec2 ndc = uv * 2.0 - 1.0;
    float z = d >= 0.99999 ? params8.w : params7.w / (d * 2.0 - 1.0 + params7.z);
    z = min(abs(z), params8.w);
    vec3 eye = vec3(ndc.x / params7.x, ndc.y / params7.y, -1.0) * z;
    float dist = length(eye);
    float k = 1.0 / params5.w;
    float dy = dot(eye, params8.xyz);
    float start = exp(-clamp(params5.y - params5.z, -2.0 * params5.w, 12.0 * params5.w) * k);
    float along = abs(dy * k) > 0.001 ? (1.0 - exp(-dy * k)) / (dy * k) : 1.0;
    return clamp(1.0 - exp(-params5.x * start * along * dist), 0.0, 0.85);
}

vec3 hash3(vec3 p) {
    p = fract(p * vec3(0.1031, 0.1030, 0.0973));
    p += dot(p, p.yxz + 33.33);
    return fract((p.xxy + p.yxx) * p.zyx);
}

// Dust motes where a beam crosses the air of an enclosed place, at full size (the volume's dust is at half the size and
// cannot hold a grain): a sparse lattice of motes fixed to the world and drifting slowly, looked up at a few distances
// along the pixel's ray up to 9 blocks. A mote is never drawn smaller than most of a pixel: farther ones keep their
// light spread over that pixel instead of flickering in and out between frames. They only show where the light in the
// air (`shafts`) is, in its colour, and twinkle as they turn.
const float MOTE_CELL = 0.45;     // blocks between motes, at most
const float MOTE_RADIUS = 0.006;  // blocks: two or three pixels a block away, never a soft blob

vec3 motes(vec2 uv, float air, vec3 shafts) {
    float lum = dot(shafts, vec3(0.2126, 0.7152, 0.0722));
    if (lum < 0.003) {
        return vec3(0.0);
    }
    vec2 ndc = uv * 2.0 - 1.0;
    vec3 dir = transpose(mat3(params11.xyz, params12.xyz, params13.xyz))
            * normalize(vec3(ndc.x / params7.x, ndc.y / params7.y, -1.0));
    vec3 cam = params14.xyz;
    vec3 drift = params11.w * vec3(0.05, -0.02, 0.035);
    float pixel = 2.0 * texel.y / params7.y;   // a pixel's size at a distance of one block
    float sum = 0.0;
    for (int k = 0; k < 6; k++) {
        float dist = pow(1.5, float(k));   // 1 to 7.6 blocks
        if (dist > air) {
            break;
        }
        vec3 cell = floor((cam + dir * dist + drift) / MOTE_CELL);
        vec3 h = hash3(cell);
        if (h.x > 0.5) {
            continue;   // sparse: most cells hold none
        }
        vec3 rel = (cell + 0.2 + 0.6 * hash3(cell + 17.0)) * MOTE_CELL - drift - cam;
        float along = dot(rel, dir);
        if (along < 0.8 || along > air) {
            continue;
        }
        float off = length(rel - dir * along);
        float r = max(MOTE_RADIUS, pixel * along * 0.7);
        float spread = (MOTE_RADIUS * MOTE_RADIUS) / (r * r);
        float twinkle = 0.55 + 0.45 * sin(params11.w * (1.5 + 2.0 * h.y) + h.z * 6.2831853);
        sum += exp(-(off * off) / (r * r)) * spread * twinkle * (1.0 - smoothstep(4.0, 9.0, along));
    }
    vec3 hue = shafts / max(lum, 1e-4);
    return hue * min(4.0 * sum, 1.5) * smoothstep(0.003, 0.05, lum) * params13.w;
}

// The scene past white, brought to the screen: linear light; the bloom's light added; the exposure of the place
// (params4.x); the shoulder from 0.6 up to the white of the output, keeping the hue, and the brightest going to white
// like a hot light.
// The glare of the sun (or moon) in the eyes: a round glow around it on the screen — a wide soft one and a tight hot
// one — as much as the sky around it is open (the depth at a few points around the sun; a tree in front hides it),
// in the haze's colour (params10: golden low, white-gold high, cold for the moon).
// Where the sun (or moon) is on the screen, and how much of its disc is seen: the depth at the centre (counted twice)
// and at eight points on the disc's rim. A gap of sky a little beside a sun behind a wall used to count, and the
// glare and the haze's glow showed over the rock.
vec2 sunOnScreen() {
    vec3 s = params9.xyz;
    return vec2(s.x * params7.x, s.y * params7.y) / -s.z * 0.5 + 0.5;
}

float sunOpen() {
    if (params6.w > 0.0) {
        return texelFetch(rays, ivec2(0), 0).a;   // the rays' pass measured it (in its first texel)
    }
    vec3 s = params9.xyz;
    if (s.z > -0.05) {
        return 0.0;
    }
    vec2 at = sunOnScreen();
    float open = 0.0;
    for (int i = 0; i < 10; i++) {
        float a = 0.785398 * float(i);
        vec2 q = at + (i < 8 ? vec2(cos(a), sin(a)) * vec2(texel.x / texel.y, 1.0) * 0.0045 : vec2(0.0));
        bool inside = q.x >= 0.0 && q.y >= 0.0 && q.x <= 1.0 && q.y <= 1.0;
        open += inside ? step(0.99999, texelFetch(depth, ivec2(q / texel.xy), 0).r) : 0.5;
    }
    open /= 10.0;
    return open * open;   // a sun half behind a hill: a quarter of the glare, not half
}

vec3 glare(vec2 uv, float open) {
    vec3 s = params9.xyz;
    if (s.z > -0.05 || dot(params10.rgb, vec3(1.0)) <= 0.0) {
        return vec3(0.0);   // behind the camera, or no haze light now
    }
    vec2 at = sunOnScreen();
    vec2 d = (uv - at) * vec2(texel.y / texel.x, 1.0);
    float r = length(d);
    float g = 0.35 * exp(-r * 5.0) + 1.6 * exp(-r * 28.0) + 4.0 * exp(-r * 90.0);
    return params10.rgb * g * open;
}

vec3 exposed(vec3 c, vec3 glow, vec2 uv, float open) {
    vec3 l = srgbToLinear(c) + srgbToLinear(min(glow, vec3(64.0))) * params.x * 0.6 + glare(uv, open);
    // the exposure from the place (SteinExposure: fixed by day outdoors, a little open enclosed or at night), not
    // from the image: turning the camera does not change the picture's brightness
    l *= params4.x;
    float white = 1.0 + params2.w;
    float m = max(l.r, max(l.g, l.b));
    float knee = 0.6 * white;
    if (m > knee) {
        float span = white - knee;
        float rolled = knee + span * (1.0 - exp(-(m - knee) / span));
        vec3 kept = l * (rolled / m);
        l = mix(kept, vec3(rolled), clamp((m - white) / (3.0 * white), 0.0, 0.85));
    }
    return linearToSrgb(l);
}

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec2 uv = gl_FragCoord.xy * texel.xy;
    vec4 base = texelFetch(scene, p, 0);
    vec3 c = finite(base.rgb);
    ivec2 last = textureSize(depth, 0) - 1;
    bool edge = params.w > 0.0 && outline(p, last);
    if (params.y > 0.0 && !edge) {
        c = sharpen(p, c);
    }
    if (edge) {
        // distance from the depth (the game's near plane is 0.05; the far plane barely counts)
        float z = 0.05 / max(1.0 - depthAt(p, last), 1e-7);
        float near = 1.0 - smoothstep(params4.z * 0.5, params4.z, z);
        if (params2.x > 0.0) {
            color = vec4(near, 0.0, 0.0, base.a);
            return;
        }
        c = mix(c, smoothEdge(p, uv, c), near);
    }
    // the air in front of the pixel: none on the hand, the whole distance on the sky
    float air = 0.0;
    if (params5.x > 0.0 || params6.w > 0.0) {
        ivec2 q = clamp(p, ivec2(0), last);
        if (texelFetch(handDepth, q, 0).r >= 1.0) {
            float d = texelFetch(depth, q, 0).r;
            air = d >= 0.99999 ? params8.w : abs(params7.w / (d * 2.0 - 1.0 + params7.z));
            if (params5.x > 0.0) {
                // towards the sun the air is lit: a wide warm glow and a tight bright one, and the mist
                // thicker there (forward scattering)
                vec2 ndc = uv * 2.0 - 1.0;
                vec3 ray = normalize(vec3(ndc.x / params7.x, ndc.y / params7.y, -1.0));
                float toward = max(dot(ray, params9.xyz), 0.0);
                float phase = pow(toward, 5.0) * 0.6 + pow(toward, 40.0) * 1.6;
                if (d < 0.99999) {
                    phase *= sunOpen();   // the glow on the land only when the sun is not behind it
                }
                float m = min(mist(uv, d) * (1.0 + params9.w * phase), 0.92);
                c = mix(c, params6.rgb + params10.rgb * phase, m);
            }
        }
    }
    if (params6.w > 0.0) {
        // the shafts are light in the air (post_rays leaves the near pixels out of the screen's rays itself, and
        // the light in the air is only where the air is)
        vec3 shafts = air > 0.0 ? softRays(uv, p, last) : vec3(0.0);   // none on the hand
        if (params13.w > 0.0 && air > 0.0) {
            shafts += motes(uv, air, shafts);
        }
        c = params4.w > 0.0 ? c + shafts : 1.0 - (1.0 - min(c, vec3(1.0))) * (1.0 - min(shafts, vec3(1.0)));
    }
    vec3 glow = finite(texture(bloom, uv).rgb);
    if (params4.w > 0.0) {
        color = vec4(exposed(c, glow, uv, sunOpen()), base.a);
#ifdef HDR_LAYER
        lights = vec4(color.rgb, 1.0);
        color.rgb = min(color.rgb, vec3(1.0));
#endif
        return;
    }
    c = min(c, vec3(1.0));
    float lit = smoothstep(lightThreshold(average, params3), 1.0, luma(c));
    if (params.x > 0.0) {
        c = 1.0 - (1.0 - c) * (1.0 - min(glow * params.x, vec3(1.0)));
    }
    if (params2.w > 0.0) {
        // the same image as without HDR; only a light gets stronger, in linear light
        float mean = textureLod(average, vec2(0.5), params3.w).r;
        float dark = 1.0 - smoothstep(0.15, 0.35, mean);
        float area = clamp(luma(glow) * params4.y, 0.0, 1.0);
        c = linearToSrgb(srgbToLinear(c) * (1.0 + params2.w * dark * lit * area));
    }
#ifdef HDR_LAYER
    vec3 shown = min(c, vec3(1.0));
    lights = vec4(c, 1.0);
    color = vec4(shown, base.a);
#else
    color = vec4(c, base.a);
#endif
}
