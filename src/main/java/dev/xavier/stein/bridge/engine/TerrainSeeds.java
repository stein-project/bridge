/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.culling.ICamera;
import net.minecraft.util.AxisAlignedBB;

/**
 * Fix for the world vanishing with the camera above the build limit or below 0: the start chunks of
 * the chunk search are tested as whole columns.
 *
 * <p>When the eye is outside 0..255 it has no section, so RenderGlobal.setupTerrain starts its
 * search from the edge layer (the sections at y 240..256, or 0..16 below the world) and keeps only
 * the ones whose own box is in the view frustum. Looking level from above the world, the nearby
 * sections of that layer are below the view and are left out; the entities above the limit are
 * filed in that top section and are drawn only through it, so they vanished with it, and the search
 * could only grow from the far ring of the layer. Here, during that seeding only, the box is
 * stretched to the whole column, so every column the view crosses seeds the search, as Mojang's
 * search does from about 1.14/1.15. The search itself is untouched.
 *
 * <p>The seeding is the stretch between {@link #begin} (the queue's creation) and {@link #end} (the
 * search loop's first queue.isEmpty()); the frustum tests of the search loop come after it. The
 * seeding loop only exists while the eye is outside 0..255 (inside, the eye's own section is the
 * start). Both pipelines: Stein's search ({@link ChunkSearch}) starts from the queue the game built
 * here. {@code -Dsteinbridge.opt.vanillaTerrainSeeds=true} brings back 1.8.9's seeds.
 */
public final class TerrainSeeds {
    private TerrainSeeds() {}

    private static final boolean VANILLA = RenderFixOptions.VANILLA_TERRAIN_SEEDS;

    /**
     * The column's extent: the world border's half size. Finite on purpose: an infinite Y times a
     * plane coefficient of exactly 0 (a side plane when looking level) is NaN, which fails the
     * test.
     */
    private static final double COLUMN = 3.0E7;

    /** Between the queue's creation and the search loop (render thread). */
    private static boolean seeding;

    /** Before Lists.newLinkedList() in setupTerrain: the start chunks are tested next. */
    public static void begin() {
        seeding = !VANILLA;
    }

    /** Before each queue.isEmpty() in setupTerrain: the search loop has begun. */
    public static void end() {
        seeding = false;
    }

    /** ICamera.isBoundingBoxInFrustum(box) in setupTerrain. */
    public static boolean inFrustum(Object camera, Object box) {
        ICamera c = (ICamera) camera;
        AxisAlignedBB b = (AxisAlignedBB) box;
        if (seeding && c instanceof Frustum f) {
            return f.isBoxInFrustum(b.minX, -COLUMN, b.minZ, b.maxX, COLUMN, b.maxZ);
        }
        return c.isBoundingBoxInFrustum(b);
    }
}
