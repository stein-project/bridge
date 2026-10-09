/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx.gl;

import dev.xavier.stein.gfx.SteinFence;

import org.lwjgl.opengl.GL32;

/**
 * OpenGL fence ({@code glFenceSync}).
 *
 * <p>With the recorded frame ({@link SteinGlFrame}) the fence does not go into the middle of the
 * queue: it stays pending and is inserted at the end of the next replay (or right before the frame
 * swap), a single one for all the pending ones ({@link Shared}). Measured on the RX 7600
 * (SteinGlFrame, "fence"): each glFenceSync in the middle of the draws makes the driver cut the
 * submission there, and the GPU sits idle until the rest arrives — one fence, ~19 us; three in a
 * row, ~127 us. It was the gap before the terrain's translucent pass: the end of the entity pass
 * closes the region of the rings (sky and clouds wrote to them) with three or four fences. A fence
 * inserted later signals later: whoever waits never sees the GPU ahead of time (it may only wait a
 * little longer). Until it is inserted it has not signaled ({@link #done} returns false without
 * forcing anything); waiting on it inserts the pending ones (and sends the list, if any).
 *
 * <p>Without the recorded frame, at the current point of the queue, as always.
 *
 * <p>Pipeline: Stein GFX only.
 */
final class SteinGlFence extends SteinFence {

    /**
     * One glFenceSync shared by the fences inserted together; deleted when the last is destroyed.
     */
    static final class Shared {
        final long sync;
        int refs;
        boolean passed;

        Shared(long sync) {
            this.sync = sync;
        }
    }

    private Shared shared;
    private boolean destroyed;

    SteinGlFence() {
        if (SteinGlFrame.deferFences()) {
            SteinGlFrame.deferFence(this);
        } else if (SteinGlFrame.pending()) {
            SteinGlFrame.fence(
                    this); // deferFences=false with a recorded frame: at the list's point, as
            // before
        } else {
            insertNow();
        }
    }

    /** A glFenceSync for this fence only, at the current point of the queue. */
    void insertNow() {
        if (shared == null && !destroyed) {
            insert(new Shared(GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)));
        }
    }

    boolean destroyed() {
        return destroyed;
    }

    boolean inserted() {
        return shared != null;
    }

    /** Receives the actual glFenceSync (right away, or the pending ones' one inserted together). */
    void insert(Shared s) {
        if (shared != null || destroyed) {
            return;
        }
        shared = s;
        s.refs++;
    }

    @Override
    public boolean done() {
        Shared s = shared;
        if (s == null) {
            return false; // pending: the GPU has not even received what comes before it
        }
        if (s.passed || s.sync == 0L) {
            return true;
        }
        // Only queries the state: no glClientWaitSync flush (the frame swap already sends the fence
        // to the GPU).
        s.passed = GL32.glGetSynci(s.sync, GL32.GL_SYNC_STATUS, null) == GL32.GL_SIGNALED;
        return s.passed;
    }

    @Override
    public boolean waitFor(long nanos) {
        if (shared == null) {
            SteinGlFrame.sync("wait on a pending fence");
            SteinGlFrame.flushFences();
            insertNow(); // already at the end of everything requested before it
        }
        Shared s = shared;
        if (s == null || s.passed || s.sync == 0L) {
            return true;
        }
        int r = GL32.glClientWaitSync(s.sync, GL32.GL_SYNC_FLUSH_COMMANDS_BIT, nanos);
        s.passed = r == GL32.GL_ALREADY_SIGNALED || r == GL32.GL_CONDITION_SATISFIED;
        return s.passed;
    }

    @Override
    public void destroy() {
        if (destroyed) {
            return;
        }
        destroyed = true;
        Shared s = shared;
        if (s != null && --s.refs == 0 && s.sync != 0L) {
            GL32.glDeleteSync(s.sync);
        }
    }
}
