/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

package dev.xavier.stein.gfx;

/**
 * A point in the GPU queue: the CPU knows when the GPU has gone past it (the earlier work
 * finished).
 *
 * <p>Pipeline: Stein GFX only.
 */
public abstract class SteinFence {

    /**
     * Has the GPU gone past here? (without waiting and without forcing the queue to be submitted;
     * to wait, {@link #waitFor})
     */
    public abstract boolean done();

    /** Waits up to {@code nanos}; true if it passed. */
    public abstract boolean waitFor(long nanos);

    public abstract void destroy();
}
