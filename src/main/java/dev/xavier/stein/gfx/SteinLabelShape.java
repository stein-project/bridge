/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import dev.xavier.stein.bridge.engine.NameStyle;

import net.minecraft.client.renderer.WorldRenderer;

import org.lwjgl.system.MemoryUtil;

/**
 * The background of a styled name label ({@link NameStyle}): the game's rectangle grown by the
 * padding, with the corners rounded by quads only — horizontal strips whose left and right edges
 * follow the arc (a trapezoid per arc segment), so the label's Tessellator, the background table
 * and the direct label all keep handling quads of position and color.
 *
 * <p>The direct label ({@link SteinTextBackgrounds}) and the game path ({@link SteinLabelTail})
 * both build it here, from the same integers, so the floats and the vertex order are the same: the
 * order of the game's quad, (left, top), (left, bottom), (right, bottom), (right, top).
 *
 * <p>Results in static arrays: one label at a time, on the game thread.
 *
 * <p>Pipeline: both (the game path also runs on the fixed-function pipeline).
 */
final class SteinLabelShape {

    /** Arc segments per corner: smooth at the label's size, and few vertices. */
    private static final int SEGMENTS = 4;

    /** Quads of the largest shape: the strips of both arcs and the middle. */
    static final int MAX_QUADS = 2 * SEGMENTS + 1;

    /** The arc's points from the top (0) to the side (SEGMENTS): inset and height, radius 1. */
    private static final float[] ARC_INSET = new float[SEGMENTS + 1];

    private static final float[] ARC_DOWN = new float[SEGMENTS + 1];

    static {
        for (int k = 0; k <= SEGMENTS; k++) {
            double a = Math.PI / 2 * (SEGMENTS - k) / SEGMENTS;
            ARC_INSET[k] = (float) (1.0 - Math.cos(a));
            ARC_DOWN[k] = (float) (1.0 - Math.sin(a));
        }
        ARC_INSET[0] = 1F;
        ARC_DOWN[0] = 0F;
        ARC_INSET[SEGMENTS] = 0F;
        ARC_DOWN[SEGMENTS] = 1F;
    }

    /** Per quad: left and right at the top edge, left and right at the bottom edge, top, bottom. */
    static final float[] topLeft = new float[MAX_QUADS];

    static final float[] topRight = new float[MAX_QUADS];
    static final float[] bottomLeft = new float[MAX_QUADS];
    static final float[] bottomRight = new float[MAX_QUADS];
    static final float[] top = new float[MAX_QUADS];
    static final float[] bottom = new float[MAX_QUADS];

    private SteinLabelShape() {}

    /**
     * The shape of the background the game would give as the quad (-j - 1, y0) to (j + 1, y1), with
     * the current style; the number of quads.
     */
    static int build(int j, int y0, int y1) {
        int px = NameStyle.paddingX();
        int py = NameStyle.paddingY();
        float left = (float) (-j - 1 - px);
        float right = (float) (j + 1 + px);
        float t = (float) (y0 - py);
        float b = (float) (y1 + py);
        float r = Math.min(NameStyle.radius(), Math.min((b - t) / 2F, (right - left) / 2F));
        if (r <= 0F) {
            put(0, left, right, left, right, t, b);
            return 1;
        }
        int n = 0;
        for (int k = 0; k < SEGMENTS; k++) {
            float i0 = r * ARC_INSET[k];
            float i1 = r * ARC_INSET[k + 1];
            put(
                    n++,
                    left + i0,
                    right - i0,
                    left + i1,
                    right - i1,
                    t + r * ARC_DOWN[k],
                    t + r * ARC_DOWN[k + 1]);
        }
        if (b - r > t + r) {
            put(n++, left, right, left, right, t + r, b - r);
        }
        for (int k = SEGMENTS; k > 0; k--) {
            float i0 = r * ARC_INSET[k];
            float i1 = r * ARC_INSET[k - 1];
            put(
                    n++,
                    left + i0,
                    right - i0,
                    left + i1,
                    right - i1,
                    b - r * ARC_DOWN[k],
                    b - r * ARC_DOWN[k - 1]);
        }
        return n;
    }

    private static void put(int q, float tl, float tr, float bl, float br, float t, float b) {
        topLeft[q] = tl;
        topRight[q] = tr;
        bottomLeft[q] = bl;
        bottomRight[q] = br;
        top[q] = t;
        bottom[q] = b;
    }

    /**
     * The {@code n} quads already as {@link SteinTessCapture} converts them, (0, 1, 2) and (2, 3,
     * 0) each, at {@code tmp} (zeroed): position and the style's byte color. Returns the vertices.
     */
    static int writeTriangles(long tmp, int n) {
        int color = NameStyle.backgroundBytes();
        long d = tmp;
        for (int q = 0; q < n; q++) {
            float x0 = topLeft[q];
            float x1 = bottomLeft[q];
            float x2 = bottomRight[q];
            float x3 = topRight[q];
            float yt = top[q];
            float yb = bottom[q];
            d = vertex(d, x0, yt, color);
            d = vertex(d, x1, yb, color);
            d = vertex(d, x2, yb, color);
            d = vertex(d, x2, yb, color);
            d = vertex(d, x3, yt, color);
            d = vertex(d, x0, yt, color);
        }
        return n * 6;
    }

    private static long vertex(long d, float x, float y, int color) {
        MemoryUtil.memPutFloat(d, x);
        MemoryUtil.memPutFloat(d + 4, y);
        MemoryUtil.memPutFloat(d + 8, 0f);
        MemoryUtil.memPutInt(d + 20, color);
        return d + SteinTessCapture.VERTEX_BYTES;
    }

    /** The {@code n} quads into the WorldRenderer (GL_QUADS, POSITION_COLOR already begun). */
    static void writeQuads(WorldRenderer wr, int n) {
        int r = NameStyle.backgroundR();
        int g = NameStyle.backgroundG();
        int b = NameStyle.backgroundB();
        int a = NameStyle.backgroundA();
        for (int q = 0; q < n; q++) {
            wr.pos(topLeft[q], top[q], 0.0).color(r, g, b, a).endVertex();
            wr.pos(bottomLeft[q], bottom[q], 0.0).color(r, g, b, a).endVertex();
            wr.pos(bottomRight[q], bottom[q], 0.0).color(r, g, b, a).endVertex();
            wr.pos(topRight[q], top[q], 0.0).color(r, g, b, a).endVertex();
        }
    }
}
