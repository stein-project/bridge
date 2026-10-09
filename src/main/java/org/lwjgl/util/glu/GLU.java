/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

package org.lwjgl.util.glu;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * The subset of LWJGL 2's GLU used by Minecraft 1.8.9. GLU was removed in LWJGL 3, so we
 * reimplement it in plain Java.
 */
public final class GLU {

    public static final int GL_NO_ERROR = 0;
    public static final int GL_INVALID_ENUM = 0x0500;
    public static final int GL_INVALID_VALUE = 0x0501;
    public static final int GL_INVALID_OPERATION = 0x0502;
    public static final int GL_STACK_OVERFLOW = 0x0503;
    public static final int GL_STACK_UNDERFLOW = 0x0504;
    public static final int GL_OUT_OF_MEMORY = 0x0505;
    public static final int GL_INVALID_FRAMEBUFFER_OPERATION = 0x0506;
    public static final int GL_TABLE_TOO_LARGE = 0x8031;

    private GLU() {}

    public static String gluErrorString(int error) {
        switch (error) {
            case GL_NO_ERROR:
                return "No error";
            case GL_INVALID_ENUM:
                return "Invalid enum";
            case GL_INVALID_VALUE:
                return "Invalid value";
            case GL_INVALID_OPERATION:
                return "Invalid operation";
            case GL_STACK_OVERFLOW:
                return "Stack overflow";
            case GL_STACK_UNDERFLOW:
                return "Stack underflow";
            case GL_OUT_OF_MEMORY:
                return "Out of memory";
            case GL_INVALID_FRAMEBUFFER_OPERATION:
                return "Invalid framebuffer operation";
            case GL_TABLE_TOO_LARGE:
                return "Table too large";
            default:
                return "Unknown error code " + error;
        }
    }

    /**
     * Converts window coordinates to object coordinates.
     *
     * <p>Reads the buffers by absolute index from {@code position()}, without consuming them —
     * LWJGL 2 behaves this way and Minecraft reuses the same buffers between calls.
     *
     * @return false if the combined matrix is not invertible or w comes out as zero
     */
    public static boolean gluUnProject(
            float winx,
            float winy,
            float winz,
            FloatBuffer modelMatrix,
            FloatBuffer projMatrix,
            IntBuffer viewport,
            FloatBuffer objPos) {
        float[] model = read16(modelMatrix);
        float[] proj = read16(projMatrix);
        int vp = viewport.position();
        int vx = viewport.get(vp);
        int vy = viewport.get(vp + 1);
        int vw = viewport.get(vp + 2);
        int vh = viewport.get(vp + 3);

        float[] combined = multiply(proj, model);
        float[] inverse = new float[16];
        if (!invert(combined, inverse)) {
            return false;
        }

        // Window -> NDC (cube -1..1)
        float[] in = {
            (winx - vx) / vw * 2.0f - 1.0f, (winy - vy) / vh * 2.0f - 1.0f, 2.0f * winz - 1.0f, 1.0f
        };

        float[] out = new float[4];
        transform(inverse, in, out);
        if (out[3] == 0.0f) {
            return false;
        }

        int op = objPos.position();
        objPos.put(op, out[0] / out[3]);
        objPos.put(op + 1, out[1] / out[3]);
        objPos.put(op + 2, out[2] / out[3]);
        return true;
    }

    private static float[] read16(FloatBuffer buf) {
        float[] m = new float[16];
        int p = buf.position();
        for (int i = 0; i < 16; i++) {
            m[i] = buf.get(p + i);
        }
        return m;
    }

    /** out = a * b, both column-major. */
    private static float[] multiply(float[] a, float[] b) {
        float[] r = new float[16];
        for (int c = 0; c < 4; c++) {
            for (int row = 0; row < 4; row++) {
                r[c * 4 + row] =
                        a[row] * b[c * 4]
                                + a[4 + row] * b[c * 4 + 1]
                                + a[8 + row] * b[c * 4 + 2]
                                + a[12 + row] * b[c * 4 + 3];
            }
        }
        return r;
    }

    /** out = m * v, with v treated as a column vector. */
    private static void transform(float[] m, float[] v, float[] out) {
        for (int row = 0; row < 4; row++) {
            out[row] = m[row] * v[0] + m[4 + row] * v[1] + m[8 + row] * v[2] + m[12 + row] * v[3];
        }
    }

    /**
     * 4x4 inverse by the adjugate matrix, adapted from gluInvertMatrix of the Mesa 3D GLU library
     * (SGI Free Software License B, equivalent to X11/MIT; see NOTICE). Returns false if the
     * determinant is zero.
     */
    private static boolean invert(float[] m, float[] invOut) {
        float[] inv = new float[16];

        inv[0] =
                m[5] * m[10] * m[15]
                        - m[5] * m[11] * m[14]
                        - m[9] * m[6] * m[15]
                        + m[9] * m[7] * m[14]
                        + m[13] * m[6] * m[11]
                        - m[13] * m[7] * m[10];
        inv[4] =
                -m[4] * m[10] * m[15]
                        + m[4] * m[11] * m[14]
                        + m[8] * m[6] * m[15]
                        - m[8] * m[7] * m[14]
                        - m[12] * m[6] * m[11]
                        + m[12] * m[7] * m[10];
        inv[8] =
                m[4] * m[9] * m[15]
                        - m[4] * m[11] * m[13]
                        - m[8] * m[5] * m[15]
                        + m[8] * m[7] * m[13]
                        + m[12] * m[5] * m[11]
                        - m[12] * m[7] * m[9];
        inv[12] =
                -m[4] * m[9] * m[14]
                        + m[4] * m[10] * m[13]
                        + m[8] * m[5] * m[14]
                        - m[8] * m[6] * m[13]
                        - m[12] * m[5] * m[10]
                        + m[12] * m[6] * m[9];
        inv[1] =
                -m[1] * m[10] * m[15]
                        + m[1] * m[11] * m[14]
                        + m[9] * m[2] * m[15]
                        - m[9] * m[3] * m[14]
                        - m[13] * m[2] * m[11]
                        + m[13] * m[3] * m[10];
        inv[5] =
                m[0] * m[10] * m[15]
                        - m[0] * m[11] * m[14]
                        - m[8] * m[2] * m[15]
                        + m[8] * m[3] * m[14]
                        + m[12] * m[2] * m[11]
                        - m[12] * m[3] * m[10];
        inv[9] =
                -m[0] * m[9] * m[15]
                        + m[0] * m[11] * m[13]
                        + m[8] * m[1] * m[15]
                        - m[8] * m[3] * m[13]
                        - m[12] * m[1] * m[11]
                        + m[12] * m[3] * m[9];
        inv[13] =
                m[0] * m[9] * m[14]
                        - m[0] * m[10] * m[13]
                        - m[8] * m[1] * m[14]
                        + m[8] * m[2] * m[13]
                        + m[12] * m[1] * m[10]
                        - m[12] * m[2] * m[9];
        inv[2] =
                m[1] * m[6] * m[15]
                        - m[1] * m[7] * m[14]
                        - m[5] * m[2] * m[15]
                        + m[5] * m[3] * m[14]
                        + m[13] * m[2] * m[7]
                        - m[13] * m[3] * m[6];
        inv[6] =
                -m[0] * m[6] * m[15]
                        + m[0] * m[7] * m[14]
                        + m[4] * m[2] * m[15]
                        - m[4] * m[3] * m[14]
                        - m[12] * m[2] * m[7]
                        + m[12] * m[3] * m[6];
        inv[10] =
                m[0] * m[5] * m[15]
                        - m[0] * m[7] * m[13]
                        - m[4] * m[1] * m[15]
                        + m[4] * m[3] * m[13]
                        + m[12] * m[1] * m[7]
                        - m[12] * m[3] * m[5];
        inv[14] =
                -m[0] * m[5] * m[14]
                        + m[0] * m[6] * m[13]
                        + m[4] * m[1] * m[14]
                        - m[4] * m[2] * m[13]
                        - m[12] * m[1] * m[6]
                        + m[12] * m[2] * m[5];
        inv[3] =
                -m[1] * m[6] * m[11]
                        + m[1] * m[7] * m[10]
                        + m[5] * m[2] * m[11]
                        - m[5] * m[3] * m[10]
                        - m[9] * m[2] * m[7]
                        + m[9] * m[3] * m[6];
        inv[7] =
                m[0] * m[6] * m[11]
                        - m[0] * m[7] * m[10]
                        - m[4] * m[2] * m[11]
                        + m[4] * m[3] * m[10]
                        + m[8] * m[2] * m[7]
                        - m[8] * m[3] * m[6];
        inv[11] =
                -m[0] * m[5] * m[11]
                        + m[0] * m[7] * m[9]
                        + m[4] * m[1] * m[11]
                        - m[4] * m[3] * m[9]
                        - m[8] * m[1] * m[7]
                        + m[8] * m[3] * m[5];
        inv[15] =
                m[0] * m[5] * m[10]
                        - m[0] * m[6] * m[9]
                        - m[4] * m[1] * m[10]
                        + m[4] * m[2] * m[9]
                        + m[8] * m[1] * m[6]
                        - m[8] * m[2] * m[5];

        float det = m[0] * inv[0] + m[1] * inv[4] + m[2] * inv[8] + m[3] * inv[12];
        if (det == 0.0f) {
            return false;
        }
        det = 1.0f / det;
        for (int i = 0; i < 16; i++) {
            invOut[i] = inv[i] * det;
        }
        return true;
    }
}
