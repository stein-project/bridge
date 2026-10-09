/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
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
     * {@code iblob}, memory of our own) in a single ring reservation and a single copy; the dynamic
     * vertices ({@code dynCount}, consecutive in {@code vblob}) likewise. {@code fix}: the camera's
     * 3x4 correction (by rows, the current one over the one at recording time; null = the same
     * camera, exact copy) rewrites the 3 matrix rows of each instance. {@code gs}: the groups (from
     * {@link SteinEntityDeferred#groupFor}, still valid: {@link SteinEntityDeferred#groupEpoch});
     * {@code dyn[i]} = 1 for a dynamic draw, whose {@code ref[i]} is the byte offset in {@code
     * vblob}; model part: {@code ref[i]} is the display list. False = did not fit in a region (the
     * caller records again).
     */
    static boolean emitRetainedBatch(
            Object[] gs,
            int n,
            byte[] dyn,
            int[] verts,
            int[] ref,
            long iblob,
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
        MemoryUtil.memCopy(iblob, p, ibytes);
        if (fix != null) {
            // row r of the new = sum_k fix[r][k] * row k of the recorded (+ fix[r][3] in the
            // translation column)
            for (int i = 0; i < n; i++) {
                long src = iblob + (long) i * INSTANCE_BYTES;
                long dst = p + (long) i * INSTANCE_BYTES;
                for (int r = 0; r < 3; r++) {
                    double c0 = fix[r * 4];
                    double c1 = fix[r * 4 + 1];
                    double c2 = fix[r * 4 + 2];
                    for (int c = 0; c < 4; c++) {
                        double v =
                                c0 * MemoryUtil.memGetFloat(src + c * 4L)
                                        + c1 * MemoryUtil.memGetFloat(src + 16 + c * 4L)
                                        + c2 * MemoryUtil.memGetFloat(src + 32 + c * 4L);
                        if (c == 3) {
                            v += fix[r * 4 + 3];
                        }
                        MemoryUtil.memPutFloat(dst + (r * 4L + c) * 4L, (float) v);
                    }
                }
            }
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
            if (g.n == 0) {
                order.add(g);
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
