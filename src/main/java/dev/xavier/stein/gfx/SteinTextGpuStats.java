/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

package dev.xavier.stein.gfx;

/**
 * The counters of {@link SteinTextGpu} and their 5-second report (printed together with the text
 * cache numbers, with {@code -Dsteinbridge.gfx.deferredStats=true}).
 *
 * <p>Pipeline: Stein GFX only.
 */
final class SteinTextGpuStats {

    static long uploads;
    static long uploadBytes;
    static long refused;
    static long backgrounds;
    static long runsDrawn;
    static long fallbacks;

    private SteinTextGpuStats() {}

    /** The report line for the period; the counters start over. */
    static String report(long liveBytes, int runsInRing, int arenaBytes) {
        String s =
                "names on the GPU (5 s): "
                        + runsDrawn
                        + " runs and "
                        + backgrounds
                        + " resident backgrounds, "
                        + SteinEntityDeferred.takeTextRecords()
                        + ", "
                        + fallbacks
                        + " texts on the copy path, "
                        + uploads
                        + " uploads ("
                        + (uploadBytes >> 10)
                        + " KB), "
                        + refused
                        + " without room; ring "
                        + (liveBytes >> 10)
                        + "/"
                        + (arenaBytes >> 10)
                        + " KB in "
                        + runsInRing
                        + " runs";
        uploads = 0;
        uploadBytes = 0;
        refused = 0;
        backgrounds = 0;
        runsDrawn = 0;
        fallbacks = 0;
        return s;
    }
}
