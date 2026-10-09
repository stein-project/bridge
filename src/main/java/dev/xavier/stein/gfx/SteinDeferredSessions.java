/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

package dev.xavier.stein.gfx;

import static dev.xavier.stein.gfx.SteinDeferredFrame.*;
import static dev.xavier.stein.gfx.SteinDeferredGroups.*;
import static dev.xavier.stein.gfx.SteinDeferredLights.*;
import static dev.xavier.stein.gfx.SteinDeferredResources.*;
import static dev.xavier.stein.gfx.SteinEntityDeferred.*;
import static dev.xavier.stein.gfx.SteinPassTiming.*;

/**
 * The extra recording passes of {@link SteinEntityDeferred} (everything outside the entity pass):
 * opening and closing a session around a draw that is recorded in strict order.
 *
 * <p>Pipeline: Stein GFX only.
 */
final class SteinDeferredSessions {

    /** Inside an extra pass (particles, sky, clouds), outside the entity pass. */
    static boolean session;

    /**
     * In the exact order of the game: everything in a single loop (without separating the
     * non-blended ones), and a group that comes back after another first flushes what was already
     * recorded. The sky needs this (the dark void goes out over the stars).
     */
    static boolean strictOrder;

    /** Draws that went out immediately inside the extra passes (statistics). */
    static long sessionImmediate;

    static void sessionBegin(boolean strict) {
        if (capturing || !ready() || !dynamicReady) {
            return;
        }
        session = true;
        strictOrder = strict;
        last = null; // the first group of the session opens the first segment (strict order)
        // Like begin(), without reading the lights: most extra passes draw nothing with light
        // (sky, rain, outline), and reading the two lights and the driver's ambient is ~7 queries
        // per pass. Read at the first draw with light ({@link SteinDeferredLights#needLights}).
        capturing = true;
        SteinLazyGl.activate();
        lightsStale = true;
        SteinGlTrack.forgetNormal();
    }

    /**
     * What comes outside a pass (loading screens, the menu panorama, F3 and the profiler graph)
     * becomes a pass of its own: nothing goes to the driver through the fixed-function pipeline (in
     * core there would be no way to). False = did not open.
     */
    static boolean oneShotBegin() {
        if (capturing) {
            return false;
        }
        passName = "loose";
        sessionBegin(true);
        return session;
    }

    static void oneShotEnd() {
        sessionEnd();
    }

    static void sessionEnd() {
        if (!session) {
            return;
        }
        // Players with their own pose drawn in the pass (the inventory doll) wait in the pose
        // queue: they go out first, still recording (otherwise the queue is discarded and the
        // player vanishes).
        SteinPlayerPose.drain();
        session = false;
        capturing = false;
        SteinLazyGl.deactivate();
        try {
            flush();
        } finally {
            strictOrder = false;
        }
    }
}
