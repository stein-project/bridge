/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

/**
 * Which ray traced effects the player keeps on. Each one is the player's own switch: the presets
 * only turn ray tracing itself on or off ({@link SteinBackend#setRayTracing}), never these.
 */
public final class SteinRayOptions {

    private static boolean sun = true;
    private static boolean bounce = true;
    private static boolean water = true;
    private static boolean volume = true;
    private static boolean leaves = true;

    private SteinRayOptions() {}

    /** The Lens's choices. */
    public static void configure(
            boolean sunOn, boolean bounceOn, boolean waterOn, boolean volumeOn, boolean leavesOn) {
        sun = sunOn;
        bounce = bounceOn;
        water = waterOn;
        volume = volumeOn;
        leaves = leavesOn;
    }

    /** The sun's shadow by a ray, in place of the shadow map's. */
    static boolean sun() {
        return sun;
    }

    /** The light bounced off the world. */
    static boolean bounce() {
        return bounce;
    }

    /** Our water, in place of the one Ultra draws. */
    static boolean water() {
        return water;
    }

    /** The light in the air and its dust. */
    static boolean volume() {
        return volume && SteinRayLight.active();
    }

    /** The light through the leaves. */
    static boolean leaves() {
        return leaves && SteinRayLight.active();
    }
}
