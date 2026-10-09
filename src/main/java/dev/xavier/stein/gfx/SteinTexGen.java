/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

package dev.xavier.stein.gfx;

import dev.xavier.stein.bridge.engine.GLMatrix;

import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;

/**
 * Texture-coordinate generation of the fixed-function pipeline (glTexGen) done on the CPU, for the
 * dynamic geometry recorded by {@link SteinTessCapture} — the End portal case
 * (TileEntityEndPortalRenderer: S, T and R by object plane, Q by eye plane, and the animated
 * texture matrix per layer).
 *
 * <p>As OpenGL does it, per vertex: each enabled coordinate comes from its plane — object (plane .
 * vertex) or eye (eye plane . modelview * vertex; the eye plane is the one given, through the
 * inverse of the modelview at the time it was given, {@link SteinGlTrack#glTexGenfv}); a disabled
 * one comes from the vertex (S, T from its coordinate; R = 0, Q = 1). Then the texture matrix (the
 * whole 4x4) and the division by q. The division is done per vertex: OpenGL interpolates s, t and q
 * and divides per pixel, which gives the same result when q is constant across the draw (the
 * portal: the eye-plane Q is the height, and the face is horizontal). A Q that varies across the
 * draw is drawn immediately.
 *
 * <p>Pipeline: Stein GFX only.
 */
final class SteinTexGen {

    private SteinTexGen() {}

    private static final float[] plane = new float[16];
    private static final boolean[] on = new boolean[4];
    private static final boolean[] eye = new boolean[4];
    private static float[] modelview;
    private static float[] texture;

    /**
     * Reads the state (modes, planes, matrices) for the current draw; false = a mode we do not
     * reproduce (sphere, reflection map...), a plane never given, or S/T disabled with no
     * coordinate on the vertex.
     */
    static boolean prepare(boolean hasUv) {
        for (int c = 0; c < 4; c++) {
            on[c] = SteinGameState.texGen(c);
            if (!on[c]) {
                if (c < 2 && !hasUv) {
                    return false;
                }
                continue;
            }
            int mode = SteinGameState.texGenMode(c);
            if (mode == GL11.GL_OBJECT_LINEAR) {
                eye[c] = false;
            } else if (mode == GL11.GL_EYE_LINEAR) {
                eye[c] = true;
            } else {
                return false;
            }
            if (!SteinGlTrack.plane(c, eye[c], plane, c * 4)) {
                return false;
            }
        }
        modelview = GLMatrix.current();
        texture = GLMatrix.textureOrNull();
        return true;
    }

    /**
     * Replaces the texture coordinate of the {@code verts} vertices already converted in {@code
     * dst} ({@link SteinTessCapture} format: position at 0, coordinate at 12) with the generated
     * one. false = q is not constant (or is zero): nothing usable, the caller draws immediately.
     */
    static boolean bake(long dst, int verts) {
        float q0 = 0f;
        float[] m = modelview;
        float[] t = texture;
        for (int i = 0; i < verts; i++) {
            long d = dst + (long) i * SteinTessCapture.VERTEX_BYTES;
            float x = MemoryUtil.memGetFloat(d);
            float y = MemoryUtil.memGetFloat(d + 4);
            float z = MemoryUtil.memGetFloat(d + 8);
            float ex = 0f;
            float ey = 0f;
            float ez = 0f;
            float ew = 0f;
            if (eye[0] && on[0] || eye[1] && on[1] || eye[2] && on[2] || eye[3] && on[3]) {
                ex = m[0] * x + m[4] * y + m[8] * z + m[12];
                ey = m[1] * x + m[5] * y + m[9] * z + m[13];
                ez = m[2] * x + m[6] * y + m[10] * z + m[14];
                ew = m[3] * x + m[7] * y + m[11] * z + m[15];
            }
            float s = coord(0, x, y, z, ex, ey, ez, ew, MemoryUtil.memGetFloat(d + 12));
            float tt = coord(1, x, y, z, ex, ey, ez, ew, MemoryUtil.memGetFloat(d + 16));
            float r = coord(2, x, y, z, ex, ey, ez, ew, 0f);
            float q = coord(3, x, y, z, ex, ey, ez, ew, 1f);
            float os = s;
            float ot = tt;
            float oq = q;
            if (t != null) {
                os = t[0] * s + t[4] * tt + t[8] * r + t[12] * q;
                ot = t[1] * s + t[5] * tt + t[9] * r + t[13] * q;
                oq = t[3] * s + t[7] * tt + t[11] * r + t[15] * q;
            }
            if (i == 0) {
                q0 = oq;
                if (q0 == 0f || Float.isNaN(q0)) {
                    return false;
                }
            } else if (Math.abs(oq - q0) > 1e-4f * Math.abs(q0)) {
                return false;
            }
            MemoryUtil.memPutFloat(d + 12, os / oq);
            MemoryUtil.memPutFloat(d + 16, ot / oq);
        }
        return true;
    }

    private static float coord(
            int c,
            float x,
            float y,
            float z,
            float ex,
            float ey,
            float ez,
            float ew,
            float fallback) {
        if (!on[c]) {
            return fallback;
        }
        int o = c * 4;
        if (eye[c]) {
            return plane[o] * ex + plane[o + 1] * ey + plane[o + 2] * ez + plane[o + 3] * ew;
        }
        return plane[o] * x + plane[o + 1] * y + plane[o + 2] * z + plane[o + 3];
    }
}
