/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import static dev.xavier.stein.gfx.SteinDeferredDynamic.*;
import static dev.xavier.stein.gfx.SteinDeferredFrame.*;
import static dev.xavier.stein.gfx.SteinDeferredGroups.*;
import static dev.xavier.stein.gfx.SteinDeferredKeys.*;
import static dev.xavier.stein.gfx.SteinDeferredModel.*;
import static dev.xavier.stein.gfx.SteinDeferredResources.*;
import static dev.xavier.stein.gfx.SteinDeferredSessions.*;
import static dev.xavier.stein.gfx.SteinDeferredStats.*;
import static dev.xavier.stein.gfx.SteinInstanceWriter.*;

import dev.xavier.stein.bridge.engine.GLMatrix;

import org.lwjgl.system.MemoryUtil;

/**
 * Retained tile entities for {@link SteinEntityDeferred} ({@link SteinTileRetain}): the annotated
 * recording of instances and dynamic draws, and the re-emission of a retained block in one ring
 * reservation.
 *
 * <p>Pipeline: Stein GFX only.
 */
final class SteinDeferredRetained {

    // ---- retained tile entities (SteinTileRetain): the annotated recording and re-emission ----

    /**
     * The instance assembled in memory of our own before going to the ring: the ring is mapped
     * write-only (reading back is undefined in OpenGL), so what retention keeps and what the ring
     * receives come from the same array.
     */
    static final float[] retainInst = new float[INSTANCE_BYTES / 4];

    /** The place in the ring of the reserved dynamic draw's vertices while retention records. */
    static long dynRing;

    /** {@link SteinEntityDeferred#add} with retention recording: the same instance, annotated. */
    static void addRetained(int list, int verts, int first, long inst, float[] m, int o) {
        float[] t = retainInst;
        putMatrixRows(t, 0, m, o);
        fillRest(t, 12, curTex);
        putFloats(instances.address(inst), t);
        command(verts, first, (int) (inst / INSTANCE_BYTES));
        if (recording) {
            mirror(verts, first, m, o);
        }
        boolean gameTex = (curKey & KEY_GAMETEX) != 0;
        int packed = curTexName < 0 ? curClass : gameTex ? -1 : curClass << 16 | (int) curLayer;
        SteinTileRetain.notePart(
                curKey, list, verts, curTexName, packed, (curFlags & FLAG_LIGHTMAP) != 0, t);
    }

    /**
     * {@link SteinEntityDeferred#commitDynamic} with retention recording: the vertices go from
     * memory of our own to the ring, annotated.
     */
    static void commitDynamicRetained(int verts, boolean texBaked) {
        long src = SteinTileRetain.scratchAddress();
        MemoryUtil.memCopy(src, dynRing, (long) verts * SteinTessCapture.VERTEX_BYTES);
        float[] t = retainInst;
        fillDynamic(t, texBaked);
        putFloats(instances.address(dynInst), t);
        command(group(dynKey), verts, dynFirst, (int) (dynInst / INSTANCE_BYTES));
        dynDraws++;
        dynVerts += verts;
        int tex = keyTexture(dynKey);
        int filter = keyFilter(dynKey);
        SteinTileRetain.noteDynamic(
                dynKey, verts, src, tex, filter, (dynFlags & FLAG_LIGHTMAP) != 0, t);
    }

    static void putFloats(long p, float[] t) {
        for (int i = 0; i < t.length; i++) {
            MemoryUtil.memPutFloat(p + i * 4L, t[i]);
        }
    }

    /** The same as {@link SteinDeferredModel#putRest}, into an array (starting at {@code at}). */
    static void fillRest(float[] d, int at, float[] t) {
        putTexture(d, at, t, curLayer, curAlphaRef);
        putMaterial(
                d, at + 8, curR, curG, curB, curA, curLmU, curLmV, curFlags, curOvR, curOvG, curOvB,
                curOvA);
    }

    /** The same as {@link SteinDeferredDynamic#putDynamic}, into an array. */
    static void fillDynamic(float[] d, boolean texBaked) {
        putMatrixRows(d, 0, GLMatrix.current(), 0);
        float[] t = texBaked ? null : GLMatrix.textureOrNull();
        putTexture(d, 12, t, 0f, dynAlphaRef);
        putMaterial(
                d, 20, dynR, dynG, dynB, dynA, dynLmU, dynLmV, dynFlags, dynNx, dynNy, dynNz,
                dynOvA);
    }

    /**
     * Re-emits a retained block at once: the {@code n} instances (128 bytes each, consecutive in
     * {@code iblob}, memory of our own, and the same floats in {@code data}) in a single ring
     * reservation; the dynamic vertices ({@code dynCount}, consecutive in {@code vblob}) likewise.
     * {@code fix}: the camera's 3x4 correction (by rows, the current one over the one at recording
     * time; null = the same camera, exact copy) rewrites the 3 matrix rows of each instance. {@code
     * gs}: the groups (from {@link SteinEntityDeferred#groupFor}, still valid: {@link
     * SteinEntityDeferred#groupEpoch}); {@code dyn[i]} = 1 for a dynamic draw, whose {@code ref[i]}
     * is the byte offset in {@code vblob}; model part: {@code ref[i]} is the display list. False =
     * did not fit in a region (the caller records again).
     */
    static boolean emitRetainedBatch(
            Object[] gs,
            int n,
            byte[] dyn,
            int[] verts,
            int[] ref,
            long iblob,
            float[] data,
            double[] fix,
            long vblob,
            int dynCount) {
        if (n == 0) {
            return true;
        }
        long ibytes = (long) n * INSTANCE_BYTES;
        long vbytes = (long) dynCount * SteinTessCapture.VERTEX_BYTES;
        long inst = 0L;
        long vat = 0L;
        for (int tries = 0; ; tries++) {
            inst = instances.reserve(ibytes);
            vat = inst < 0 || dynCount == 0 ? 0L : dynVertices.reserve(vbytes);
            if (inst == -1 || vat == -1) {
                return false; // larger than a region
            }
            if (inst == -2 || vat == -2) {
                if (tries > 0) {
                    return false;
                }
                flush();
                rotate();
                continue;
            }
            break;
        }
        long p = instances.address(inst);
        if (fix == null) {
            MemoryUtil.memCopy(iblob, p, ibytes);
        } else {
            writeCorrected(p, iblob, data, n, fix);
        }
        int vfirst = 0;
        if (dynCount > 0) {
            MemoryUtil.memCopy(vblob, dynVertices.address(vat), vbytes);
            vfirst = (int) (vat / SteinTessCapture.VERTEX_BYTES);
            dynVerts += dynCount;
        }
        int base = (int) (inst / INSTANCE_BYTES);
        for (int i = 0; i < n; i++) {
            Group g = (Group) gs[i];
            // what group() does in the entity pass (without strict order): it enters in the
            // frame's order
            if (!g.listed) {
                order.add(g);
                g.listed = true;
            }
            if (strictOrder && g != last) {
                // Just in case: retention belongs to the entity pass, outside strict order.
                openSegment(g);
            }
            last = g;
            int first;
            if (dyn[i] != 0) {
                first = vfirst + ref[i] / SteinTessCapture.VERTEX_BYTES;
                dynDraws++;
            } else {
                first = SteinModelInstancer.firstVertex(ref[i]);
                parts++;
            }
            command(g, verts[i], first, base + i);
        }
        return true;
    }

    /** The 3 corrected matrix rows of the instance being written. */
    private static final float[] rows = new float[12];

    /**
     * Writes the {@code n} retained instances to the ring at {@code p} with their matrix rows taken
     * to the current camera: row r of the new = sum_k fix[r][k] * row k of the recorded (+
     * fix[r][3] in the translation column), in double; {@link SteinTileReplayCheck} repeats it.
     *
     * <p>Each ring byte is written once, in order: the ring is mapped memory meant for streaming
     * writes, and copying the whole instance first and then rewriting its first 48 bytes stored
     * them twice. The recorded rows are read from {@code data}, the heap copy that holds the same
     * floats as {@code iblob} (both filled from it when recording), instead of reading native
     * memory.
     */
    private static void writeCorrected(long p, long iblob, float[] data, int n, double[] fix) {
        final int floats = INSTANCE_BYTES / 4;
        float[] t = rows;
        for (int i = 0; i < n; i++) {
            int s = i * floats;
            for (int r = 0; r < 3; r++) {
                double c0 = fix[r * 4];
                double c1 = fix[r * 4 + 1];
                double c2 = fix[r * 4 + 2];
                for (int c = 0; c < 4; c++) {
                    double v = c0 * data[s + c] + c1 * data[s + 4 + c] + c2 * data[s + 8 + c];
                    if (c == 3) {
                        v += fix[r * 4 + 3];
                    }
                    t[r * 4 + c] = (float) v;
                }
            }
            long src = iblob + (long) i * INSTANCE_BYTES;
            long dst = p + (long) i * INSTANCE_BYTES;
            for (int k = 0; k < 12; k++) {
                MemoryUtil.memPutFloat(dst + k * 4L, t[k]);
            }
            MemoryUtil.memCopy(src + 48, dst + 48, INSTANCE_BYTES - 48);
            if (SteinTileReplayCheck.ENABLED) {
                SteinTileReplayCheck.instance(src, fix, t);
            }
        }
    }

    /**
     * Retained block with lightmap instances: its texture, as beginModel/beginDynamic would have
     * stored it.
     */
    static void retainedLightmap() {
        if (SteinGameState.texture2D(1)) {
            lightmapId = SteinGameState.textureName(1);
        }
    }
}
