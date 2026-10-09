/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

package dev.xavier.stein.gfx;

import static dev.xavier.stein.gfx.SteinTessCapture.VERTEX_BYTES;

import dev.xavier.stein.bridge.core.Log;

import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;

/**
 * Converts the game's Tessellator vertices (any {@link SteinCaptureFormat}, any draw mode) into
 * triangles and line segments in the capture's own 32-byte vertex format, plus the check of the
 * dedicated quad loop.
 *
 * <p>Pipeline: Stein GFX only.
 */
final class SteinVertexConverter {
    private SteinVertexConverter() {}

    /**
     * Quads ({@code count} vertices) into triangles (0, 1, 2) and (2, 3, 0), like {@link #convert},
     * with no detours.
     */
    static void convertQuads(long src, SteinCaptureFormat f, int count, long dst) {
        int s = f.stride;
        int uv = f.uv;
        int col = f.color;
        long d = dst;
        for (int q = 0; q + 3 < count; q += 4) {
            long p0 = src + (long) q * s;
            for (int k = 0; k < 6; k++, d += VERTEX_BYTES) {
                // 0, 1, 2, 2, 3, 0
                long p = p0 + (long) (k < 3 ? k : k == 5 ? 0 : k - 1) * s;
                MemoryUtil.memPutLong(d, MemoryUtil.memGetLong(p));
                MemoryUtil.memPutInt(d + 8, MemoryUtil.memGetInt(p + 8));
                if (uv >= 0) {
                    MemoryUtil.memPutLong(d + 12, MemoryUtil.memGetLong(p + uv));
                }
                if (col >= 0) {
                    MemoryUtil.memPutInt(d + 20, MemoryUtil.memGetInt(p + col));
                }
            }
        }
    }

    private static long quadScratch;
    private static int quadScratchVerts;
    private static long quadChecked;
    private static long quadBad;
    private static long quadReportAt = System.nanoTime();

    /**
     * Check of the dedicated loop: the generic one in a scratch buffer, compared on the format's
     * fields.
     */
    static void checkQuads(long src, SteinCaptureFormat f, int count, long dst, int out) {
        if (quadScratchVerts < out) {
            if (quadScratch != 0L) {
                MemoryUtil.nmemFree(quadScratch);
            }
            quadScratchVerts = Math.max(out, 1024);
            quadScratch = MemoryUtil.nmemAlloc((long) quadScratchVerts * VERTEX_BYTES);
        }
        convert(src, f, GL11.GL_QUADS, count, quadScratch, false);
        boolean same = true;
        for (int v = 0; v < out && same; v++) {
            long a = dst + (long) v * VERTEX_BYTES;
            long b = quadScratch + (long) v * VERTEX_BYTES;
            same =
                    MemoryUtil.memGetLong(a) == MemoryUtil.memGetLong(b)
                            && MemoryUtil.memGetInt(a + 8) == MemoryUtil.memGetInt(b + 8)
                            && (f.uv < 0
                                    || MemoryUtil.memGetLong(a + 12)
                                            == MemoryUtil.memGetLong(b + 12))
                            && (f.color < 0
                                    || MemoryUtil.memGetInt(a + 20)
                                            == MemoryUtil.memGetInt(b + 20));
        }
        if (same) {
            quadChecked++;
        } else if (quadBad++ < 10) {
            Log.info(
                    "gfx",
                    "quad from the own loop differs from the generic converter (format of "
                            + f.stride
                            + " bytes, "
                            + count
                            + " vertices)");
        }
        long now = System.nanoTime();
        if (now - quadReportAt > 5_000_000_000L) {
            quadReportAt = now;
            Log.info(
                    "gfx",
                    "quad check (5 s): " + quadChecked + " equal, " + quadBad + " different");
            quadChecked = 0;
        }
    }

    /**
     * Vertices in triangles for {@code count} vertices in mode {@code mode}; -1 = a mode we do not
     * record.
     */
    static int triangles(int mode, int count) {
        return switch (mode) {
            case GL11.GL_TRIANGLES -> count / 3 * 3;
            case GL11.GL_TRIANGLE_STRIP, GL11.GL_TRIANGLE_FAN -> Math.max(0, count - 2) * 3;
            case GL11.GL_QUADS -> count / 4 * 6;
            default -> -1;
        };
    }

    /** Vertices in a line list for {@code count} vertices in line mode {@code mode}. */
    static int lineVertices(int mode, int count) {
        return switch (mode) {
            case GL11.GL_LINES -> count / 2 * 2;
            case GL11.GL_LINE_STRIP -> Math.max(0, count - 1) * 2;
            default ->
                    count >= 2 ? count * 2 : 0; // GL_LINE_LOOP: the strip plus the closing segment
        };
    }

    /**
     * Lines (the fishing rod line, the F3+B boxes) into a segment list. With {@code flat}, the
     * segment takes the color of the vertex GL would use (the second).
     */
    static void convertLines(
            long src, SteinCaptureFormat f, int mode, int count, long dst, boolean flat) {
        int s = f.stride;
        long d = dst;
        if (mode == GL11.GL_LINES) {
            for (int i = 0; i + 1 < count; i += 2) {
                int pv = flat ? i + 1 : -1;
                d = vertex(src, s, i, f, d, pv);
                d = vertex(src, s, i + 1, f, d, pv);
            }
            return;
        }
        for (int i = 0; i + 1 < count; i++) {
            int pv = flat ? i + 1 : -1;
            d = vertex(src, s, i, f, d, pv);
            d = vertex(src, s, i + 1, f, d, pv);
        }
        if (mode == GL11.GL_LINE_LOOP && count >= 2) {
            int pv = flat ? 0 : -1;
            d = vertex(src, s, count - 1, f, d, pv);
            vertex(src, s, 0, f, d, pv);
        }
    }

    /**
     * Converts {@code count} vertices in {@code src} (format {@code f}, mode {@code mode}) into
     * triangles in our format in {@code dst}. With {@code flat}, each triangle takes the color of
     * the vertex GL would use (the last of the triangle; for a quad, the last of the quad).
     */
    static void convert(
            long src, SteinCaptureFormat f, int mode, int count, long dst, boolean flat) {
        int s = f.stride;
        long d = dst;
        switch (mode) {
            case GL11.GL_TRIANGLES -> {
                for (int i = 0; i + 2 < count; i += 3) {
                    int pv = flat ? i + 2 : -1;
                    d = vertex(src, s, i, f, d, pv);
                    d = vertex(src, s, i + 1, f, d, pv);
                    d = vertex(src, s, i + 2, f, d, pv);
                }
            }
            case GL11.GL_TRIANGLE_STRIP -> {
                // triangle i: (i, i+1, i+2) if even, (i+1, i, i+2) if odd — like GL
                for (int i = 0; i + 2 < count; i++) {
                    int pv = flat ? i + 2 : -1;
                    boolean odd = (i & 1) != 0;
                    d = vertex(src, s, odd ? i + 1 : i, f, d, pv);
                    d = vertex(src, s, odd ? i : i + 1, f, d, pv);
                    d = vertex(src, s, i + 2, f, d, pv);
                }
            }
            case GL11.GL_TRIANGLE_FAN -> {
                for (int i = 0; i + 2 < count; i++) {
                    int pv = flat ? i + 2 : -1;
                    d = vertex(src, s, 0, f, d, pv);
                    d = vertex(src, s, i + 1, f, d, pv);
                    d = vertex(src, s, i + 2, f, d, pv);
                }
            }
            default -> { // quads: (0, 1, 2) and (2, 3, 0), the same winding as the quad
                for (int i = 0; i + 3 < count; i += 4) {
                    int pv = flat ? i + 3 : -1;
                    d = vertex(src, s, i, f, d, pv);
                    d = vertex(src, s, i + 1, f, d, pv);
                    d = vertex(src, s, i + 2, f, d, pv);
                    d = vertex(src, s, i + 2, f, d, pv);
                    d = vertex(src, s, i + 3, f, d, pv);
                    d = vertex(src, s, i, f, d, pv);
                }
            }
        }
    }

    /** A vertex {@code v} of {@code src} in our format at {@code d}; returns the next {@code d}. */
    private static long vertex(
            long src, int stride, int v, SteinCaptureFormat f, long d, int provoking) {
        if (f.particle && provoking < 0) {
            // position and texture (20 bytes) and the color in sequence; normal zeroed; light at
            // the end
            long q = src + (long) v * 28;
            MemoryUtil.memPutLong(d, MemoryUtil.memGetLong(q));
            MemoryUtil.memPutLong(d + 8, MemoryUtil.memGetLong(q + 8));
            MemoryUtil.memPutLong(d + 16, MemoryUtil.memGetLong(q + 16));
            MemoryUtil.memPutInt(d + 24, 0);
            MemoryUtil.memPutInt(d + 28, MemoryUtil.memGetInt(q + 24));
            return d + VERTEX_BYTES;
        }
        long p = src + (long) v * stride;
        long a = p + f.pos;
        MemoryUtil.memPutFloat(d, MemoryUtil.memGetFloat(a));
        MemoryUtil.memPutFloat(d + 4, MemoryUtil.memGetFloat(a + 4));
        MemoryUtil.memPutFloat(d + 8, MemoryUtil.memGetFloat(a + 8));
        if (f.uv >= 0) {
            MemoryUtil.memPutFloat(d + 12, MemoryUtil.memGetFloat(p + f.uv));
            MemoryUtil.memPutFloat(d + 16, MemoryUtil.memGetFloat(p + f.uv + 4));
        }
        if (f.color >= 0) {
            long c = (provoking >= 0 ? src + (long) provoking * stride : p) + f.color;
            MemoryUtil.memPutInt(d + 20, MemoryUtil.memGetInt(c)); // r, g, b, a in memory order
        }
        if (f.normal >= 0) {
            long n = p + f.normal;
            MemoryUtil.memPutByte(d + 24, MemoryUtil.memGetByte(n));
            MemoryUtil.memPutByte(d + 25, MemoryUtil.memGetByte(n + 1));
            MemoryUtil.memPutByte(d + 26, MemoryUtil.memGetByte(n + 2));
            MemoryUtil.memPutByte(d + 27, (byte) 0);
        }
        if (f.light >= 0) {
            MemoryUtil.memPutShort(d + 28, MemoryUtil.memGetShort(p + f.light));
            MemoryUtil.memPutShort(d + 30, MemoryUtil.memGetShort(p + f.light + 2));
        }
        return d + VERTEX_BYTES;
    }
}
