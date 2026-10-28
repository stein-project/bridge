/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import net.minecraft.client.renderer.chunk.ChunkCompileTaskGenerator;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;

import java.util.ArrayList;
import java.util.concurrent.BlockingQueue;

/**
 * ChunkRenderDispatcher.clearChunkUpdates as setupTerrain calls it on every frame, taking the
 * waiting build tasks out of the queue in one go.
 *
 * <p>The game empties the queue one task at a time: isEmpty and poll each take the queue's lock,
 * and finishing the task runs its finish runnable, which removes the task from the queue again (a
 * third lock and a walk over all the tasks still queued, which is a no-op since poll took it
 * already). That is three contended locks per task against the build threads waiting in take(), and
 * a quadratic walk with a full queue, on the render thread of every frame: 4.5 to 15.5% of it on
 * the benchmark in some scenes.
 *
 * <p>Here drainTo moves every waiting task out under one lock, and the tasks are finished in the
 * same order (the queue's FIFO order, as poll would give them); their finish runnables find the
 * queue without them, as before. The loop repeats until the queue is empty, as the game's does, so
 * a task queued meanwhile by another thread is finished too. A task a build thread took before the
 * drain is not in the queue, in both versions. Same tasks finished, in the same order, with the
 * same effects (the chunk marked for a new build, the task marked done). {@code
 * -Dsteinbridge.opt.vanillaClearUpdates=true} calls the game's method.
 */
public final class ChunkQueue {

    private static final boolean VANILLA = EngineOptions.VANILLA_CLEAR_UPDATES;

    /** The drained tasks; only the render thread calls setupTerrain. */
    private static final ArrayList<ChunkCompileTaskGenerator> DRAINED = new ArrayList<>();

    private ChunkQueue() {}

    /** renderDispatcher.clearChunkUpdates() in setupTerrain. */
    public static void clearChunkUpdates(Object dispatcherObj) {
        ChunkRenderDispatcher dispatcher = (ChunkRenderDispatcher) dispatcherObj;
        if (VANILLA) {
            dispatcher.clearChunkUpdates();
            return;
        }
        BlockingQueue<ChunkCompileTaskGenerator> queue = dispatcher.queueChunkUpdates;
        ArrayList<ChunkCompileTaskGenerator> drained = DRAINED;
        while (!queue.isEmpty()) {
            queue.drainTo(drained);
            try {
                for (int i = 0, n = drained.size(); i < n; i++) {
                    drained.get(i).finish();
                }
            } finally {
                drained.clear();
            }
        }
    }
}
