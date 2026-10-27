/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.client.renderer.culling.ClippingHelper;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.culling.ICamera;

/**
 * Frustum.isBoundingBoxInFrustum for the chunk visibility search, with one dot product per plane.
 *
 * <p>The game's ClippingHelper tests the box's 8 corners against each of the 6 planes until one is
 * in front; a box outside costs up to 48 dot products, and on the bench at distance 32 the test was
 * 46% of the render thread. A box has a corner in front of a plane exactly when the corner furthest
 * along the plane's normal (max x where the normal's x is positive, min x otherwise, and so on) is
 * in front, so only that one is tested. It is the same expression in the same precision as the
 * game's (the float coefficients widened to double, the box already moved by the frustum's
 * position), and rounding keeps the order of the corners' values, so the answer is the same in
 * every case. Read once per search: the planes do not change during it. A camera that is not a
 * Frustum goes through its own method.
 */
final class FrustumBox {

    private static ICamera camera;
    private static boolean own;
    private static final double[] PLANES = new double[24];
    private static double fx;
    private static double fy;
    private static double fz;

    private FrustumBox() {}

    /** Before a search: the camera's planes and position. */
    static void begin(ICamera c) {
        camera = c;
        own = c instanceof Frustum;
        if (!own) {
            return;
        }
        Frustum f = (Frustum) c;
        ClippingHelper h = f.clippingHelper;
        for (int i = 0; i < 6; i++) {
            float[] p = h.frustum[i];
            PLANES[i * 4] = p[0];
            PLANES[i * 4 + 1] = p[1];
            PLANES[i * 4 + 2] = p[2];
            PLANES[i * 4 + 3] = p[3];
        }
        fx = f.xPosition;
        fy = f.yPosition;
        fz = f.zPosition;
    }

    /**
     * The chunk's bounding box, from its corner ({@link ChunkCorner}): setPosition makes the box
     * from the position to the position plus 16, so the doubles are the same.
     */
    static boolean inside(RenderChunk rc) {
        if (!own) {
            return camera.isBoundingBoxInFrustum(rc.boundingBox);
        }
        ChunkCorner.sync(rc);
        int x = ChunkCorner.x(rc);
        int y = ChunkCorner.y(rc);
        int z = ChunkCorner.z(rc);
        double minX = (double) x - fx;
        double minY = (double) y - fy;
        double minZ = (double) z - fz;
        double maxX = (double) (x + 16) - fx;
        double maxY = (double) (y + 16) - fy;
        double maxZ = (double) (z + 16) - fz;
        double[] p = PLANES;
        for (int i = 0; i < 24; i += 4) {
            double a = p[i];
            double b = p[i + 1];
            double c = p[i + 2];
            // ClippingHelper.dot: a * x + b * y + c * z + d, left to right
            double d =
                    a * (a >= 0 ? maxX : minX)
                            + b * (b >= 0 ? maxY : minY)
                            + c * (c >= 0 ? maxZ : minZ)
                            + p[i + 3];
            if (!(d > 0.0)) {
                return false;
            }
        }
        return true;
    }
}
