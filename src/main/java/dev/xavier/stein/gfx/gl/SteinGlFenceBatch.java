/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx.gl;

import dev.xavier.stein.gfx.SteinGfxOptions;

import org.lwjgl.opengl.GL32;

import java.util.ArrayList;
import java.util.List;

/**
 * The pending fences ({@link SteinGlFence}): inserted together at the end of the replay, never in
 * the middle of the queue.
 *
 * <p>Pipeline: Stein GFX only.
 */
final class SteinGlFenceBatch {

    private SteinGlFenceBatch() {}

    private static final List<SteinGlFence> FENCES = new ArrayList<>();

    /**
     * {@code -Dsteinbridge.gfx.deferFences=false} puts the fence back at the point where it was
     * requested. Enabled (the default with the renderer), the fences wait for the end of the
     * recorded frame's replay — or, in immediate submission, the frame swap — because a glFenceSync
     * in the middle of the draws cuts the submission on AMD and stalls the GPU.
     */
    static final boolean DEFER_FENCES = SteinGfxOptions.feature("deferFences", true);

    static void add(SteinGlFence f) {
        FENCES.add(f);
    }

    static boolean isEmpty() {
        return FENCES.isEmpty();
    }

    /**
     * Inserts the pending fences: a single glFenceSync for all of them, at the current point of the
     * queue (the end of the replay, or just before the frame swap). They all pass together, after
     * everything that came before them.
     */
    static void flush() {
        if (FENCES.isEmpty()) {
            return;
        }
        SteinGlFence.Shared s = null;
        for (int k = 0; k < FENCES.size(); k++) {
            SteinGlFence f = FENCES.get(k);
            if (f.destroyed() || f.inserted()) {
                continue;
            }
            if (s == null) {
                s =
                        new SteinGlFence.Shared(
                                GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0));
            }
            f.insert(s);
        }
        FENCES.clear();
        if (SteinGlFrameStats.ON && s != null) {
            SteinGlFrameStats.fenceFlushes++;
        }
    }
}
