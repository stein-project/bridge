/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

package dev.xavier.stein.gfx;

import org.lwjgl.system.MemoryUtil;

/**
 * Growth of the growable native (off-heap) scratch blocks kept by the capture classes. Each caller
 * decides its own capacity policy; this only does the allocate-or-reallocate step.
 *
 * <p>Pipeline: Stein GFX only.
 */
final class SteinNativeBuffer {
    private SteinNativeBuffer() {}

    /**
     * Returns a native block of {@code capacity} bytes: a new one when {@code address} is 0,
     * otherwise {@code address} reallocated (contents kept up to the old size).
     */
    static long grow(long address, long capacity) {
        return address == 0L
                ? MemoryUtil.nmemAlloc(capacity)
                : MemoryUtil.nmemRealloc(address, capacity);
    }
}
