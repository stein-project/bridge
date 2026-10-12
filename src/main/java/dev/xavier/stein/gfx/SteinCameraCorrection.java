/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import dev.xavier.stein.bridge.engine.GLMatrix;

import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;

import java.util.Arrays;

/**
 * The 3x4 correction that takes matrices recorded with one camera to the current camera, so a
 * retained tile entity can be re-emitted without running its renderer; one computation per pass is
 * cached.
 *
 * <p>Pipeline: Stein GFX only.
 */
final class SteinCameraCorrection {
    private SteinCameraCorrection() {}

    /** Impossible correction (matrix with no inverse): records again. */
    static final double[] NO_INVERSE = new double[0];

    private static final float[] cacheView = new float[16];
    private static double cacheSx;
    private static double cacheSy;
    private static double cacheSz;
    private static long cachePass = -1;
    private static double[] cacheFix;

    /**
     * The 3x4 correction (by rows) of the matrices recorded with the entry's camera to the current
     * camera: V1 * T(S0 - S1) * inverse(V0). Null = same camera (exact copy).
     */
    static double[] correction(SteinTileEntry e, long pass) {
        float[] now = GLMatrix.current();
        double nx = TileEntityRendererDispatcher.staticPlayerX;
        double ny = TileEntityRendererDispatcher.staticPlayerY;
        double nz = TileEntityRendererDispatcher.staticPlayerZ;
        if (e.sx == nx && e.sy == ny && e.sz == nz && Arrays.equals(e.view, now)) {
            return null;
        }
        // Entries recorded in the same pass have the same camera: one computation per pass.
        if (cachePass == pass
                && cacheSx == e.sx
                && cacheSy == e.sy
                && cacheSz == e.sz
                && Arrays.equals(cacheView, e.view)) {
            return cacheFix;
        }
        float[] v = e.view;
        // R0 (rows) and t0 of the recorded one; the inverse of R0 in double
        double a = v[0];
        double b = v[4];
        double c = v[8];
        double d = v[1];
        double f = v[5];
        double g = v[9];
        double h = v[2];
        double i = v[6];
        double j = v[10];
        double det = a * (f * j - g * i) - b * (d * j - g * h) + c * (d * i - f * h);
        double[] fix;
        if (det == 0.0 || Double.isNaN(det)) {
            fix = NO_INVERSE;
        } else {
            double inv = 1.0 / det;
            double[] ri = {
                (f * j - g * i) * inv, (c * i - b * j) * inv, (b * g - c * f) * inv,
                (g * h - d * j) * inv, (a * j - c * h) * inv, (c * d - a * g) * inv,
                (d * i - f * h) * inv, (b * h - a * i) * inv, (a * f - b * d) * inv
            };
            double tx = v[12];
            double ty = v[13];
            double tz = v[14];
            // w = (S0 - S1) - inverse(R0) * t0
            double wx = (e.sx - nx) - (ri[0] * tx + ri[1] * ty + ri[2] * tz);
            double wy = (e.sy - ny) - (ri[3] * tx + ri[4] * ty + ri[5] * tz);
            double wz = (e.sz - nz) - (ri[6] * tx + ri[7] * ty + ri[8] * tz);
            fix = new double[12];
            for (int r = 0; r < 3; r++) {
                double n0 = now[r];
                double n1 = now[4 + r];
                double n2 = now[8 + r];
                for (int col = 0; col < 3; col++) {
                    fix[r * 4 + col] = n0 * ri[col] + n1 * ri[3 + col] + n2 * ri[6 + col];
                }
                fix[r * 4 + 3] = n0 * wx + n1 * wy + n2 * wz + now[12 + r];
            }
        }
        System.arraycopy(e.view, 0, cacheView, 0, 16);
        cacheSx = e.sx;
        cacheSy = e.sy;
        cacheSz = e.sz;
        cachePass = pass;
        cacheFix = fix;
        return fix;
    }
}
