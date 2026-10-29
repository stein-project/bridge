/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
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

    /**
     * The leading vertices that draw without the alpha test ({@link SteinOpaqueCutout}; 0: none).
     */
    final int opaque;

    final int x;
    final int y;
    final int z;

    /**
     * When the chunk first appeared on screen ({@link SteinChunkFade#now}; 0: not yet; null: no
     * animation), the same in all layers and in reassemblies.
     */
    final float[] born;

    /** Its place among the shadows' casters (SteinShadows), -1 for none. */
    int shadowIndex = -1;

    /** Seen in the translucent layer: it casts no shadow. */
    boolean translucent;

    /** Its bottom-level acceleration structure (SteinRayScene), or null. */
    dev.xavier.stein.gfx.vk.SteinVkRay.Bottom ray;

    /** Its place among the rays' built slices, -1 for none. */
    int rayIndex = -1;

    SteinTerrainSlice(
            SteinTerrainRegion region, int first, int count, int opaque, int x, int y, int z) {
        this.region = region;
        this.first = first;
        this.count = count;
        this.opaque = opaque;
        this.x = x;
        this.y = y;
        this.z = z;
        this.born = SteinChunkFade.arrival(x, y, z);
    }
}
