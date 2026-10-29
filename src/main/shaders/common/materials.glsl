// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2026 Breno Xavier
// The reflective materials (SteinMaterials; glow mask codes 6 gems, 7 gold, 8 iron, 9 ice, 10 glass, 11 polished
// stone), shared by the terrain's pixel and the light pass (SteinRayLight): the sky a face reflects, how much it
// reflects (Fresnel, by material; gems and metals mostly on their bright texels, so the pixel art stays as drawn; the
// metals tint it) and the sun's glint. Needs the terrain's Frame block (modelview, fogColor, cloudInfo, sunEye,
// sunColor).

// The material code in the glow mask's value (code * 16, the low bits are flags).
int materialCode(float mask) {
    return int(mask * (255.0 / 16.0) + 0.5);
}

bool reflective(int code) {
    return code >= 6 && code <= 11;
}

// The sky around reflected along r (eye space): the horizon the fog's colour, the zenith the frame's (cloudInfo.yzw),
// the ground below darker, as much as the sky light reaches (open, 0..1). A marked horizon is what reads as a
// reflection on a flat face.
vec3 skyReflected(vec3 r, float open) {
    float ry = dot(r, normalize(modelview[1].xyz));
    vec3 skyPart = mix(fogColor.rgb, cloudInfo.yzw, pow(clamp(ry, 0.0, 1.0), 0.45));
    vec3 ground = fogColor.rgb * 0.3;
    return mix(ground, skyPart, smoothstep(-0.04, 0.04, ry)) * (open * open);
}

// How much the face reflects (0..0.85) seen along v with the normal n towards the eye, and the tint of what it
// reflects.
float reflectStrength(int code, vec3 albedo, vec3 n, vec3 v, out vec3 tint) {
    float lum = dot(albedo, vec3(0.2126, 0.7152, 0.0722));
    float bright = smoothstep(0.25, 0.8, lum);
    bool metal = code == 7 || code == 8;
    bool gem = code == 6;
    float f0 = gem ? 0.35 : code == 7 ? 0.7 : code == 8 ? 0.6 : code == 9 ? 0.15 : 0.08;
    tint = code == 7 ? albedo * 1.4 : code == 8 ? mix(albedo, vec3(lum), 0.4) * 1.25 : gem ? albedo * 1.5 : vec3(1.0);
    float cosT = clamp(dot(-v, n), 0.0, 1.0);
    float k = f0 + (1.0 - f0) * pow(1.0 - cosT, 5.0);
    if (metal || gem) {
        k *= mix(0.3, 1.0, bright);
    }
    return clamp(k, 0.0, 0.85);
}

// The sun's glint (toSun: the reflected ray towards the sun): a wide warm band on metals and gems, tight on glass and
// ice.
vec3 sunGlint(int code, float toSun, vec3 tint) {
    bool wide = code == 6 || code == 7 || code == 8;
    float g = wide ? pow(toSun, 24.0) * 2.2 + pow(toSun, 200.0) * 4.0 : pow(toSun, 120.0) * 2.0;
    return sunColor.rgb * tint * g;
}
