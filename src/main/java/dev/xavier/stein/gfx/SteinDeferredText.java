/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import static dev.xavier.stein.gfx.SteinDeferredDynamic.*;
import static dev.xavier.stein.gfx.SteinDeferredGroups.*;
import static dev.xavier.stein.gfx.SteinDeferredKeys.*;
import static dev.xavier.stein.gfx.SteinDeferredResources.*;
import static dev.xavier.stein.gfx.SteinDeferredStats.*;
import static dev.xavier.stein.gfx.SteinEntityDeferred.*;
import static dev.xavier.stein.gfx.SteinInstanceWriter.*;

import dev.xavier.stein.bridge.engine.GLMatrix;

import org.lwjgl.system.MemoryUtil;

import java.util.Arrays;

/**
 * Text resident on the GPU for {@link SteinEntityDeferred}: one instance record per name (reused
 * when equal), the direct label records of {@link SteinNameLabels} and the probe that captures a
 * name's commits.
 *
 * <p>Pipeline: Stein GFX only.
 */
final class SteinDeferredText {

    // ---- text resident on the GPU (SteinTextGpu): one record per name ----

    /**
     * Flushes done (the {@link SteinTextGpu} does not overwrite a run that a not-yet-flushed draw
     * reads).
     */
    static long flushSerial;

    /** Closed regions: a text record is only reused within the region it was written in. */
    static long regionSerial;

    /**
     * The last text record: instance index, its region and a CPU copy of the bytes (reading from
     * the mapping would be slow).
     */
    static int textInst = -1;

    static long textRegion = -1;
    static final long TEXT_LAST = MemoryUtil.nmemAlloc(INSTANCE_BYTES);
    static final long TEXT_NEW = MemoryUtil.nmemAlloc(INSTANCE_BYTES);
    static long textRecords;
    static long textReused;

    static long flushSerial() {
        return flushSerial;
    }

    /** The resident text pipeline compiles (the most common one: blending, like the name). */
    static boolean textPipelineValid() {
        return dynamicReady && pipeline(OWN_BLEND_KEY | KEY_DYNAMIC | KEY_TEXT).valid();
    }

    /**
     * A resident run ({@link SteinTextGpu}): {@code verts} vertices from {@code first} in its
     * buffer, in the state read by {@link SteinEntityDeferred#beginDynamic}. The record (the
     * instance) is that of the usual dynamic draw; if it comes out equal to the last text one (the
     * other pass of the same name, the shadow, the background before the text), the index is
     * reused. {@code background}: the name background, with the "no texture" on the vertex (the
     * record stays equal to the text's). False = did not fit (the caller follows the copy path).
     */
    static boolean commitText(int first, int verts, boolean background) {
        SteinTileRetain.taint(); // retention does not keep a resident run (SteinTextGpu avoids it
        // by recording)
        int flags = dynFlags;
        if (background) {
            dynFlags = flags & ~FLAG_NOTEX;
        }
        putDynamic(TEXT_NEW);
        dynFlags = flags;
        if (probing) {
            probeAdd(dynKey | KEY_TEXT, first, verts, background);
        }
        int inst;
        if (textInst >= 0 && textRegion == regionSerial && same(TEXT_NEW, TEXT_LAST)) {
            inst = textInst;
            textReused++;
        } else {
            long at = reserveInstance();
            if (at < 0) {
                return false;
            }
            MemoryUtil.memCopy(TEXT_NEW, instances.address(at), INSTANCE_BYTES);
            MemoryUtil.memCopy(TEXT_NEW, TEXT_LAST, INSTANCE_BYTES);
            inst = (int) (at / INSTANCE_BYTES);
            textInst = inst;
            textRegion = regionSerial;
            textRecords++;
        }
        command(group(dynKey | KEY_TEXT), verts, first, inst);
        dynDraws++;
        return true;
    }

    // ---- direct label (SteinNameLabels): the record and commands of a name, no game code ----

    /**
     * Recording the commitTexts of a name drawn through the game's path: the key, the first vertex,
     * the vertices, whether it is the background and the candidate record (before reuse) of each
     * one. {@link SteinNameLabels} takes the label's template from there (keys and the record
     * without a matrix) and, in the check, compares it with what it would generate itself.
     */
    static boolean probing;

    static int probeN;
    static long[] probeKey = new long[32];
    static int[] probeFirst = new int[32];
    static int[] probeVerts = new int[32];
    static boolean[] probeBg = new boolean[32];
    static long probeRec = MemoryUtil.nmemAlloc(32L * INSTANCE_BYTES);
    static long probeDraws;
    static long probeRejects;

    static void probeBegin() {
        probing = true;
        probeN = 0;
        probeDraws = dynDraws;
        probeRejects = dynRejected;
    }

    /**
     * End of recording: how many commitTexts; -1 if something of the name went out through another
     * path (copy, immediate, refused), and what was recorded does not describe the whole label.
     */
    static int probeEnd() {
        probing = false;
        if (dynDraws - probeDraws != probeN || dynRejected != probeRejects) {
            return -1;
        }
        return probeN;
    }

    static void probeAdd(long key, int first, int verts, boolean background) {
        if (probeN == probeKey.length) {
            int n = probeN * 2;
            probeKey = Arrays.copyOf(probeKey, n);
            probeFirst = Arrays.copyOf(probeFirst, n);
            probeVerts = Arrays.copyOf(probeVerts, n);
            probeBg = Arrays.copyOf(probeBg, n);
            long rec = MemoryUtil.nmemAlloc((long) n * INSTANCE_BYTES);
            MemoryUtil.memCopy(probeRec, rec, (long) probeN * INSTANCE_BYTES);
            MemoryUtil.nmemFree(probeRec);
            probeRec = rec;
        }
        probeKey[probeN] = key;
        probeFirst[probeN] = first;
        probeVerts[probeN] = verts;
        probeBg[probeN] = background;
        MemoryUtil.memCopy(TEXT_NEW, probeRec + (long) probeN * INSTANCE_BYTES, INSTANCE_BYTES);
        probeN++;
    }

    static long probeKey(int i) {
        return probeKey[i];
    }

    static int probeFirst(int i) {
        return probeFirst[i];
    }

    static int probeVerts(int i) {
        return probeVerts[i];
    }

    static boolean probeBackground(int i) {
        return probeBg[i];
    }

    /**
     * The candidate record of commit {@code i} (128 bytes, address valid until the next
     * probeBegin).
     */
    static long probeRecord(int i) {
        return probeRec + (long) i * INSTANCE_BYTES;
    }

    /**
     * The key without the game's texture and its filter (the template of a text pass, valid for any
     * page).
     */
    static long textKeyBase(long key) {
        return key & ~KEY_TEXTURE_BITS;
    }

    /**
     * The template key with texture {@code tex} (and its current filter), as beginDynamic would
     * build it; -1 if out.
     */
    static long textKeyWith(long base, int tex) {
        if (tex <= 0 || tex >= MAX_KEY_TEXTURE) {
            return -1L;
        }
        return base | textureBits(tex) | filterBits(SteinTessCapture.filterCode(tex));
    }

    /**
     * A label's record in {@code out}: the template {@code template} with the current matrix (as
     * putDynamic writes it) and the current lightmap.
     */
    static void labelFill(long template, long out) {
        MemoryUtil.memCopy(template, out, INSTANCE_BYTES);
        putMatrixRows(out, GLMatrix.current(), 0);
        MemoryUtil.memPutFloat(out + RECORD_LIGHT, SteinGameState.lightmapU());
        MemoryUtil.memPutFloat(out + RECORD_LIGHT + 4, SteinGameState.lightmapV());
    }

    /**
     * The label's record (template + current matrix and lightmap), reused if equal to the last one;
     * -1 = did not fit.
     */
    static int labelRecord(long template) {
        labelFill(template, TEXT_NEW);
        if (textInst >= 0 && textRegion == regionSerial && same(TEXT_NEW, TEXT_LAST)) {
            textReused++;
            return textInst;
        }
        long at = reserveInstance();
        if (at < 0) {
            return -1;
        }
        MemoryUtil.memCopy(TEXT_NEW, instances.address(at), INSTANCE_BYTES);
        MemoryUtil.memCopy(TEXT_NEW, TEXT_LAST, INSTANCE_BYTES);
        textInst = (int) (at / INSTANCE_BYTES);
        textRegion = regionSerial;
        textRecords++;
        return textInst;
    }

    /**
     * A label command: {@code verts} resident vertices from {@code first}, in group {@code key}.
     */
    static void labelCommand(long key, int first, int verts, int inst) {
        SteinTileRetain.taint();
        command(group(key), verts, first, inst);
        dynDraws++;
    }

    static boolean same(long a, long b) {
        for (int i = 0; i < INSTANCE_BYTES; i += 8) {
            if (MemoryUtil.memGetLong(a + i) != MemoryUtil.memGetLong(b + i)) {
                return false;
            }
        }
        return true;
    }

    /** Text records written and reused since the last time (numbers from {@link SteinTextGpu}). */
    static String takeTextRecords() {
        String s = textRecords + " records (" + textReused + " reused)";
        textRecords = 0;
        textReused = 0;
        return s;
    }
}
