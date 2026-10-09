/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

/**
 * The name in progress, shared by the steps of the direct label ({@link SteinNameLabels#draw},
 * prepare, the game's tail, the check) instead of passing the same dozen values from one to the
 * other. Static and reused: nothing is allocated per label. Only the game thread writes it, once
 * per name, before any step reads.
 *
 * <p>Pipeline: Stein GFX only.
 */
final class SteinLabelContext {

    static int kind;
    static boolean sneak;
    static Object render;
    static Object entity;

    /** The RenderManager of the name in progress (the matrix reads its camera). */
    static Object manager;

    static Object font;
    static boolean unicode;
    static String name;

    /** Position relative to the camera, and the squared distance to the viewer. */
    static double x;

    static double y;
    static double z;
    static double d;

    private SteinLabelContext() {}

    static void set(
            int kind,
            boolean sneak,
            Object render,
            Object entity,
            Object manager,
            Object font,
            boolean unicode,
            String name,
            double x,
            double y,
            double z,
            double d) {
        SteinLabelContext.kind = kind;
        SteinLabelContext.sneak = sneak;
        SteinLabelContext.render = render;
        SteinLabelContext.entity = entity;
        SteinLabelContext.manager = manager;
        SteinLabelContext.font = font;
        SteinLabelContext.unicode = unicode;
        SteinLabelContext.name = name;
        SteinLabelContext.x = x;
        SteinLabelContext.y = y;
        SteinLabelContext.z = z;
        SteinLabelContext.d = d;
    }

    /** End of the drain: does not hold the last entity and renderer. */
    static void clear() {
        render = null;
        entity = null;
        manager = null;
        font = null;
        name = null;
    }
}
