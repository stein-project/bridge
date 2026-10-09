/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

package dev.xavier.stein.gfx.gl;

import java.util.Arrays;

/**
 * The uniform and storage binding points that already hold a buffer range ({@code bindCache}): only
 * Stein binds these points, so they stay between passes and a pass does not send to the driver a
 * range the point already has. A buffer generation that changed ({@link SteinGlBuffer#generation})
 * invalidates the entry.
 *
 * <p>Pipeline: Stein GFX only.
 */
final class SteinGlBindPoints {

    private SteinGlBindPoints() {}

    /** Uniform and storage binding points with what is bound tracked. */
    private static final int CACHED_SLOTS = 16;

    private static final int[] UBO_HANDLE = new int[CACHED_SLOTS];
    private static final long[] UBO_OFFSET = new long[CACHED_SLOTS];
    private static final long[] UBO_SIZE = new long[CACHED_SLOTS];
    private static final int[] UBO_GEN = new int[CACHED_SLOTS];
    private static final int[] SSBO_HANDLE = new int[CACHED_SLOTS];
    private static final long[] SSBO_OFFSET = new long[CACHED_SLOTS];
    private static final long[] SSBO_SIZE = new long[CACHED_SLOTS];
    private static final int[] SSBO_GEN = new int[CACHED_SLOTS];

    static {
        Arrays.fill(UBO_GEN, -1);
        Arrays.fill(SSBO_GEN, -1);
    }

    /**
     * True if the uniform point already has this range; otherwise it is noted as bound (the caller
     * binds it). A point outside the cached ones is never "already bound".
     */
    static boolean uniformBound(int slot, int h, long offset, long size) {
        return slot >= 0
                && slot < CACHED_SLOTS
                && bound(UBO_GEN, UBO_HANDLE, UBO_OFFSET, UBO_SIZE, slot, h, offset, size);
    }

    /** Same, for the storage point. */
    static boolean storageBound(int slot, int h, long offset, long size) {
        return slot >= 0
                && slot < CACHED_SLOTS
                && bound(SSBO_GEN, SSBO_HANDLE, SSBO_OFFSET, SSBO_SIZE, slot, h, offset, size);
    }

    private static boolean bound(
            int[] gen,
            int[] handle,
            long[] offsets,
            long[] sizes,
            int slot,
            int h,
            long offset,
            long size) {
        if (gen[slot] == SteinGlBuffer.generation
                && handle[slot] == h
                && offsets[slot] == offset
                && sizes[slot] == size) {
            return true; // the point already has this range (points stay between passes)
        }
        gen[slot] = SteinGlBuffer.generation;
        handle[slot] = h;
        offsets[slot] = offset;
        sizes[slot] = size;
        return false;
    }
}
