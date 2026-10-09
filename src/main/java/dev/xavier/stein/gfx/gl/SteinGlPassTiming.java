/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

package dev.xavier.stein.gfx.gl;

import dev.xavier.stein.bridge.core.Log;

/**
 * CPU time to open and close the OpenGL pass ({@code -Dsteinbridge.gfx.passTiming=true},
 * diagnostic), summed and reported every 5 s in us per pass.
 *
 * <p>Opening: queries (reading what is followed and the cache), glPushAttrib (tracked in core:
 * scissor and whatever the game had enabled and the pass disables), the rest; closing: textures,
 * framebuffers, glPopAttrib (core: the fixed state restored), the rest.
 *
 * <p>Pipeline: Stein GFX only.
 */
final class SteinGlPassTiming {

    static final boolean ON = dev.xavier.stein.gfx.SteinGfxOptions.PASS_TIMING;

    private SteinGlPassTiming() {}

    private static final long[] T = new long[7];

    private static final String[] T_NAMES = {
        "queries", "pushAttrib", "open rest", "textures", "framebuffers", "popAttrib", "close rest"
    };
    private static long passes;
    private static long reportAt = System.nanoTime();

    /** Instants of the opening: start, after the queries, after glPushAttrib, end. */
    static void opened(long t0, long t1, long t2, long t3) {
        T[0] += t1 - t0;
        T[1] += t2 - t1;
        T[2] += t3 - t2;
        passes++;
    }

    /** Instants of the closing: start, after textures, after framebuffers, after the state, end. */
    static void closed(long e0, long e1, long e2, long e3, long e4) {
        T[3] += e1 - e0;
        T[4] += e2 - e1;
        T[5] += e3 - e2;
        T[6] += e4 - e3;
        report();
    }

    private static void report() {
        long now = System.nanoTime();
        if (now - reportAt < 5_000_000_000L || passes == 0) {
            return;
        }
        StringBuilder b = new StringBuilder("pass").append(" (tracked)").append(", us per pass:");
        for (int i = 0; i < T.length; i++) {
            b.append(' ').append(T_NAMES[i]).append(String.format(" %.2f", T[i] / 1e3 / passes));
            T[i] = 0;
        }
        Log.info("gfx", b.toString());
        passes = 0;
        reportAt = now;
    }
}
