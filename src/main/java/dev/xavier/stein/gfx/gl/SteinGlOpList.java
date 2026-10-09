/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx.gl;

import org.lwjgl.system.MemoryUtil;

import java.util.Arrays;

/**
 * The recorded frame's command list ({@link SteinGlFrame}): the arguments of the commands in three
 * parallel arrays (ints, longs, objects), in the order they were recorded, plus the arena with the
 * data of the uploads. {@link SteinGlReplay} reads them back in the same order.
 *
 * <p>Pipeline: Stein GFX only.
 */
final class SteinGlOpList {

    private SteinGlOpList() {}

    static int[] ints = new int[256];
    static int intN;
    static long[] longs = new long[64];
    static int longN;
    static Object[] objs = new Object[32];
    static int objN;

    /** Commands in the list (for the "is there anything" check). */
    static int opN;

    /**
     * The data of the recorded uploads (our own memory; offsets, because it grows with realloc).
     */
    static long arena;

    static long arenaCap;
    static long arenaN;

    static void op(int code) {
        if (intN + 32 > ints.length) {
            ints = Arrays.copyOf(ints, ints.length * 2);
        }
        ints[intN++] = code;
        opN++;
    }

    static void i(int v) {
        if (intN == ints.length) {
            ints = Arrays.copyOf(ints, ints.length * 2);
        }
        ints[intN++] = v;
    }

    static void l(long v) {
        if (longN == longs.length) {
            longs = Arrays.copyOf(longs, longs.length * 2);
        }
        longs[longN++] = v;
    }

    static void o(Object v) {
        if (objN == objs.length) {
            objs = Arrays.copyOf(objs, objs.length * 2);
        }
        objs[objN++] = v;
    }

    /** Copies {@code bytes} from {@code src} into the arena; returns the offset there. */
    static long keep(long src, long bytes) {
        if (arenaN + bytes > arenaCap) {
            long cap = Math.max(arenaN + bytes, Math.max(1L << 20, arenaCap * 2));
            arena = arena == 0 ? MemoryUtil.nmemAlloc(cap) : MemoryUtil.nmemRealloc(arena, cap);
            if (arena == 0) {
                throw new OutOfMemoryError("recorded frame: arena of " + cap + " bytes");
            }
            arenaCap = cap;
        }
        long at = arenaN;
        MemoryUtil.memCopy(src, arena + at, bytes);
        arenaN += (bytes + 15) & ~15L;
        return at;
    }

    /** The list is empty again (after the replay). */
    static void reset() {
        intN = 0;
        longN = 0;
        Arrays.fill(objs, 0, objN, null);
        objN = 0;
        opN = 0;
        arenaN = 0;
    }
}
