/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import dev.xavier.stein.bridge.engine.GLMatrix;
import dev.xavier.stein.bridge.engine.ModelBatch;

import net.minecraft.client.model.ModelRenderer;

import java.util.Arrays;
import java.util.List;

/**
 * Entity drawing in the new renderer (step C1), in stages.
 *
 * <p>Stage 1: the game's {@code ModelRenderer.render} (bct.a(F)) is replaced. The game's walks the
 * part tree touching the matrix stack for every part (push, translate, rotate three times, draw,
 * pop) — with 400 players, ~17 thousand parts per frame. Here each part's matrix is computed in a
 * loop of our own, from the current matrix and the part's fields (offset, rotation point, angles),
 * and goes straight to the {@link SteinModelInstancer}; the game's stack is not touched. The order
 * is the game's: offset, rotation point, then Z, Y and X.
 *
 * <p>Only with per-instance parts on. A visible part that does not yet have its geometry on the GPU
 * (the first time it appears), an unusable matrix or a display list being built: the game's render,
 * as always.
 *
 * <p>Pipeline: both — forwards to the game's ModelRenderer.render in the fixed pipeline.
 */
public final class SteinEntityInstancer {

    /** A matrix stack of our own, only for the recursion (16 floats per level). */
    private static float[] stack = new float[16 * 32];

    private SteinEntityInstancer() {}

    /** ModelRenderer.render(scale), throughout the game (patcher rule). */
    public static void render(Object part, float scale) throws Throwable {
        boolean direct = SteinModelInstancer.active();
        // On 4.1 there is no direct per-instance draw, only the geometry on the GPU and the
        // deferred renderer.
        if (!direct && !(SteinEntityDeferred.capturing() && SteinModelInstancer.geometryReady())
                || !GLMatrix.batchable()
                || !check((ModelRenderer) part)) {
            SteinTileRetain
                    .taint(); // through the game's path (list not compiled yet): it is not retained
            ((ModelRenderer) part).render(scale);
            return;
        }
        // In the entity pass with the deferred renderer: it becomes data, drawn at the end grouped
        // by state.
        deferred = SteinEntityDeferred.capturing() && SteinEntityDeferred.beginModel();
        if (!deferred && !direct) {
            SteinTileRetain.taint();
            ((ModelRenderer) part).render(scale);
            return;
        }
        if (!deferred) {
            ModelBatch.flushBatch();
        }
        System.arraycopy(GLMatrix.current(), 0, stack, 0, 16);
        draw((ModelRenderer) part, scale, 0);
    }

    private static boolean deferred;

    /**
     * Are the visible parts of the tree compiled and with their geometry on the GPU? (hidden ones
     * do not count)
     */
    private static boolean check(ModelRenderer part) {
        if (part.isHidden || !part.showModel) {
            return true;
        }
        if (!part.compiled || !SteinModelInstancer.has(part.displayList)) {
            return false;
        }
        List<ModelRenderer> kids = part.childModels;
        if (kids != null) {
            for (int i = 0; i < kids.size(); i++) {
                if (!check(kids.get(i))) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Draws the part with the matrix of level {@code depth} of our stack (the parent) and its
     * children at the next level.
     */
    private static void draw(ModelRenderer part, float scale, int depth) {
        if (part.isHidden || !part.showModel) {
            return;
        }
        int at = depth * 16;
        int next = at + 16;
        if (next + 16 > stack.length) {
            stack = Arrays.copyOf(stack, stack.length * 2);
        }
        float[] s = stack;
        System.arraycopy(s, at, s, next, 16);
        translate(s, next, part.offsetX, part.offsetY, part.offsetZ);
        float rx = part.rotationPointX * scale;
        float ry = part.rotationPointY * scale;
        float rz = part.rotationPointZ * scale;
        if (rx != 0f || ry != 0f || rz != 0f) {
            translate(s, next, rx, ry, rz);
        }
        float az = part.rotateAngleZ;
        float ay = part.rotateAngleY;
        float ax = part.rotateAngleX;
        if (az != 0f) {
            rotateZ(s, next, az);
        }
        if (ay != 0f) {
            rotateY(s, next, ay);
        }
        if (ax != 0f) {
            rotateX(s, next, ax);
        }
        if (capture) {
            record(part.displayList, s, next);
        }
        if (deferred) {
            SteinEntityDeferred.add(part.displayList, s, next);
        } else {
            SteinModelInstancer.drawWith(part.displayList, s, next);
        }
        List<ModelRenderer> kids = part.childModels;
        if (kids != null) {
            for (int i = 0; i < kids.size(); i++) {
                draw(kids.get(i), scale, depth + 1);
            }
        }
    }

    // ---- capture for the pose check (SteinPlayerPose, -Dsteinbridge.gfx.poseCheck=true) ----

    private static boolean capture;
    private static int[] capList = new int[64];
    private static float[] capMat = new float[64 * 16];
    private static int capN;

    /**
     * From here to {@link #captureEnd()}, each part drawn is also recorded: display list and
     * matrix.
     */
    static void captureBegin() {
        capture = true;
        capN = 0;
    }

    /** Stops recording; returns how many parts were recorded. */
    static int captureEnd() {
        capture = false;
        return capN;
    }

    static int capturedList(int i) {
        return capList[i];
    }

    /** The recorded matrices, 16 floats per part. */
    static float[] capturedMatrices() {
        return capMat;
    }

    private static void record(int list, float[] m, int o) {
        if (capN == capList.length) {
            capList = Arrays.copyOf(capList, capN * 2);
            capMat = Arrays.copyOf(capMat, capN * 2 * 16);
        }
        capList[capN] = list;
        System.arraycopy(m, o, capMat, capN * 16, 16);
        capN++;
    }

    // ---- matrices (column by column, like OpenGL), in place ----
    // Stateless: SteinPlayerPose uses the same arithmetic, on its own threads, so that the same
    // matrix comes out bit for bit.

    static void translate(float[] m, int o, float x, float y, float z) {
        m[o + 12] += m[o] * x + m[o + 4] * y + m[o + 8] * z;
        m[o + 13] += m[o + 1] * x + m[o + 5] * y + m[o + 9] * z;
        m[o + 14] += m[o + 2] * x + m[o + 6] * y + m[o + 10] * z;
        m[o + 15] += m[o + 3] * x + m[o + 7] * y + m[o + 11] * z;
    }

    /** M * Rz: column 0 = c*c0 + s*c1, column 1 = -s*c0 + c*c1. */
    static void rotateZ(float[] m, int o, float a) {
        float c = (float) Math.cos(a);
        float s = (float) Math.sin(a);
        for (int r = 0; r < 4; r++) {
            float c0 = m[o + r];
            float c1 = m[o + 4 + r];
            m[o + r] = c * c0 + s * c1;
            m[o + 4 + r] = -s * c0 + c * c1;
        }
    }

    /** M * Ry: column 0 = c*c0 - s*c2, column 2 = s*c0 + c*c2. */
    static void rotateY(float[] m, int o, float a) {
        float c = (float) Math.cos(a);
        float s = (float) Math.sin(a);
        for (int r = 0; r < 4; r++) {
            float c0 = m[o + r];
            float c2 = m[o + 8 + r];
            m[o + r] = c * c0 - s * c2;
            m[o + 8 + r] = s * c0 + c * c2;
        }
    }

    /** M * Rx: column 1 = c*c1 + s*c2, column 2 = -s*c1 + c*c2. */
    static void rotateX(float[] m, int o, float a) {
        float c = (float) Math.cos(a);
        float s = (float) Math.sin(a);
        for (int r = 0; r < 4; r++) {
            float c1 = m[o + 4 + r];
            float c2 = m[o + 8 + r];
            m[o + 4 + r] = c * c1 + s * c2;
            m[o + 8 + r] = -s * c1 + c * c2;
        }
    }
}
