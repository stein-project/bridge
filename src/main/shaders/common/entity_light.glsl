// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2026 Breno Xavier
// The eye-space normal and the entities' light like the fixed-function pipeline: m is the normal through the instance matrix (rows,
// = scale * rotation). The inverse transpose that OpenGL uses is m / scale^2; with GL_RESCALE_NORMAL it removes the scale
// (m / scale, the length of the input normal stays); with GL_NORMALIZE, it normalizes. The scale comes from the length of the first
// row (uniform scale, as entities have).
vec3 lit(vec3 n0, int flags, vec4 row0, vec4 row1, vec4 row2, vec3 c) {
    vec3 m = vec3(dot(row0.xyz, n0), dot(row1.xyz, n0), dot(row2.xyz, n0));
    vec3 n;
    if ((flags & 256) != 0) {
        n = m / dot(row0.xyz, row0.xyz);
    } else if ((flags & 128) != 0) {
        n = m * inversesqrt(dot(row0.xyz, row0.xyz));
    } else {
        n = normalize(m);
    }
    vec3 l = ambient.rgb + max(dot(n, light0.xyz), 0.0) * diffuse0.rgb
            + max(dot(n, light1.xyz), 0.0) * diffuse1.rgb;
    return clamp(l * c, 0.0, 1.0);
}
