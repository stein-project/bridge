/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import dev.xavier.stein.bridge.core.Log;

/**
 * The counters of the direct name labels ({@link SteinNameLabels}) and their 5-second report (with
 * {@code -Dsteinbridge.gfx.deferredStats=true} or the label check).
 *
 * <p>Pipeline: Stein GFX only.
 */
final class SteinLabelStats {

    private static final boolean STATS = SteinGfxOptions.DEFERRED_STATS;
    private static final long STATS_INTERVAL_NANOS = 5_000_000_000L;

    static long direct;
    static long viaGame;
    static long learned;
    static long checkedOk;
    static long checkedBad;
    static long checkedSkip;
    static int reported;

    /** {@code -Dsteinbridge.gfx.nameTextCheck}: kept formatted names equal / different. */
    static long nameSame;

    static long nameDifferent;

    /** {@code -Dsteinbridge.gfx.labelBackgroundCheck}: backgrounds by size equal / different. */
    static long backgroundSame;

    static long backgroundDifferent;

    private static long statsAt = System.nanoTime();

    private SteinLabelStats() {}

    static void report() {
        boolean names = SteinLabelSlots.checkingNames();
        boolean backgrounds = SteinTextBackgrounds.checking();
        if (!STATS && !SteinNameLabels.CHECK && !names && !backgrounds) {
            return;
        }
        long now = System.nanoTime();
        if (now - statsAt < STATS_INTERVAL_NANOS) {
            return;
        }
        statsAt = now;
        if (names) {
            Log.info(
                    "gfx",
                    "name text check (5 s): "
                            + nameSame
                            + " equal, "
                            + nameDifferent
                            + " different");
            nameSame = 0;
            nameDifferent = 0;
        }
        if (backgrounds) {
            Log.info(
                    "gfx",
                    "label background check (5 s): "
                            + backgroundSame
                            + " equal, "
                            + backgroundDifferent
                            + " different");
            backgroundSame = 0;
            backgroundDifferent = 0;
        }
        if (!STATS && !SteinNameLabels.CHECK) {
            return;
        }
        Log.info(
                "gfx",
                "labels (5 s): "
                        + direct
                        + " direct, "
                        + viaGame
                        + " via game, "
                        + learned
                        + " templates"
                        + (SteinNameLabels.CHECK
                                ? "; check: "
                                        + checkedOk
                                        + " equal, "
                                        + checkedBad
                                        + " different, "
                                        + checkedSkip
                                        + " without a template or outside the common case"
                                : ""));
        direct = 0;
        viaGame = 0;
        learned = 0;
        checkedOk = 0;
        checkedBad = 0;
        checkedSkip = 0;
    }
}
