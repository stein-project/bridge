/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

package dev.xavier.stein.gfx;

/**
 * A chunk layer on the GPU: the region, the vertex range and the chunk's origin in the world.
 *
 * <p>Pipeline: Stein GFX only.
 */
final class SteinTerrainSlice {
    final SteinTerrainRegion region;
    final int first;
    final int count;
    final int x;
    final int y;
    final int z;

    /**
     * When the chunk first appeared on screen ({@link SteinChunkFade#now}; 0: not yet; null: no
     * animation), the same in all layers and in reassemblies.
     */
    final float[] born;

    SteinTerrainSlice(SteinTerrainRegion region, int first, int count, int x, int y, int z) {
        this.region = region;
        this.first = first;
        this.count = count;
        this.x = x;
        this.y = y;
        this.z = z;
        this.born = SteinChunkFade.arrival(x, y, z);
    }
}
