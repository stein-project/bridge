/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import java.util.Arrays;

/**
 * A 4x4 float matrix, column-major, with OpenGL 1.x semantics: each operation multiplies the
 * current matrix on the right (M = M x T), like glTranslatef, glRotatef, glScalef, glMultMatrixf
 * and glOrtho.
 *
 * <p>Stateless and free of OpenGL, so it can be checked in isolation.
 */
public final class Mat4 {

    private Mat4() {}

    public static void identity(float[] m) {
        Arrays.fill(m, 0, 16, 0f);
        m[0] = 1f;
        m[5] = 1f;
        m[10] = 1f;
        m[15] = 1f;
    }

    /** M = M x T(x, y, z). */
    public static void translate(float[] m, float x, float y, float z) {
        m[12] += m[0] * x + m[4] * y + m[8] * z;
        m[13] += m[1] * x + m[5] * y + m[9] * z;
        m[14] += m[2] * x + m[6] * y + m[10] * z;
        m[15] += m[3] * x + m[7] * y + m[11] * z;
    }

    /** M = M x S(x, y, z). */
    public static void scale(float[] m, float x, float y, float z) {
        for (int i = 0; i < 4; i++) {
            m[i] *= x;
            m[4 + i] *= y;
            m[8 + i] *= z;
        }
    }

    /** M = M x R(angle in degrees, axis). As in glRotatef, the axis is normalised. */
    public static void rotate(float[] m, float angle, float x, float y, float z) {
        float len = (float) Math.sqrt(x * x + y * y + z * z);
        if (len == 0f) {
            return;
        }
        if (len != 1f) {
            x /= len;
            y /= len;
            z /= len;
        }
        double rad = Math.toRadians(angle);
        float c = (float) Math.cos(rad);
        float s = (float) Math.sin(rad);
        float t = 1f - c;
        // Columns 0..2 of R (the 4th is the identity).
        float r0 = x * x * t + c;
        float r1 = y * x * t + z * s;
        float r2 = x * z * t - y * s;
        float r4 = x * y * t - z * s;
        float r5 = y * y * t + c;
        float r6 = y * z * t + x * s;
        float r8 = x * z * t + y * s;
        float r9 = y * z * t - x * s;
        float r10 = z * z * t + c;
        for (int i = 0; i < 4; i++) {
            float a = m[i];
            float b = m[4 + i];
            float d = m[8 + i];
            m[i] = a * r0 + b * r1 + d * r2;
            m[4 + i] = a * r4 + b * r5 + d * r6;
            m[8 + i] = a * r8 + b * r9 + d * r10;
        }
    }

    /** M = M x N. */
    public static void mul(float[] m, float[] n) {
        for (int i = 0; i < 4; i++) {
            float a0 = m[i];
            float a1 = m[4 + i];
            float a2 = m[8 + i];
            float a3 = m[12 + i];
            m[i] = a0 * n[0] + a1 * n[1] + a2 * n[2] + a3 * n[3];
            m[4 + i] = a0 * n[4] + a1 * n[5] + a2 * n[6] + a3 * n[7];
            m[8 + i] = a0 * n[8] + a1 * n[9] + a2 * n[10] + a3 * n[11];
            m[12 + i] = a0 * n[12] + a1 * n[13] + a2 * n[14] + a3 * n[15];
        }
    }

    /** M = M x O, where O is the orthographic projection of glOrtho. */
    public static void ortho(
            float[] m, float left, float right, float bottom, float top, float near, float far) {
        float[] o = new float[16];
        o[0] = 2f / (right - left);
        o[5] = 2f / (top - bottom);
        o[10] = -2f / (far - near);
        o[12] = -(right + left) / (right - left);
        o[13] = -(top + bottom) / (top - bottom);
        o[14] = -(far + near) / (far - near);
        o[15] = 1f;
        mul(m, o);
    }
}
