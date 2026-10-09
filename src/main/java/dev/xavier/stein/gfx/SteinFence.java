/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
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
