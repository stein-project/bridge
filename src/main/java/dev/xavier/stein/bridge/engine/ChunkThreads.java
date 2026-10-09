/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import dev.xavier.stein.bridge.core.Log;

import net.minecraft.client.renderer.RegionRenderCacheBuilder;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import net.minecraft.client.renderer.chunk.ChunkRenderWorker;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ThreadFactory;

/**
 * More threads building chunk geometry.
 *
 * <p>The 1.8.9 ChunkRenderDispatcher creates 2 build threads, a fixed number, and 5 geometry
 * builders (RegionRenderCacheBuilder, ~10 MB each) in a queue of capacity 5. On a 12-thread CPU,
 * the arrival of a whole area of chunks (joining a server, teleporting) waits on just 2 threads.
 *
 * <p>Two detours, only inside the constructor (patcher/redirects-opt.tsv):
 *
 * <ul>
 *   <li>the builders' queue grows to hold N + 3 (the game's slack: 3 beyond the 2);
 *   <li>when the second worker enters the list, N - 2 more workers are created by the game's own
 *       thread factory (daemon, "Chunk Batcher") and the missing builders go into the queue.
 * </ul>
 *
 * The rest is the game's: the same request queue, the same workers. The game was already built for
 * several workers; only the number was fixed.
 *
 * <p>{@code -Dsteinbridge.opt.chunkThreads=N}: number of threads (default: half the logical CPUs,
 * between 2 and 8). {@code -Dsteinbridge.opt.vanillaChunkThreads=true}: 2, as in the game.
 */
public final class ChunkThreads {

    private static final int VANILLA_THREADS = 2;
    private static final int VANILLA_BUILDERS = 5;
    private static final boolean VANILLA = EngineOptions.VANILLA_CHUNK_THREADS;
    static final int THREADS = VANILLA ? VANILLA_THREADS : Math.max(1, EngineOptions.CHUNK_THREADS);

    private ChunkThreads() {}

    /** In place of Queues.newArrayBlockingQueue in the constructor: the builders' queue grows. */
    public static Object queue(int capacity) {
        if (capacity == VANILLA_BUILDERS) {
            return new ArrayBlockingQueue<Object>(Math.max(VANILLA_BUILDERS, THREADS + 3));
        }
        return new ArrayBlockingQueue<Object>(capacity);
    }

    /** In place of listThreadedWorkers.add(worker) in the constructor: fills out the N threads. */
    @SuppressWarnings("unchecked")
    public static boolean addWorker(Object list, Object worker) {
        List<Object> workers = (List<Object>) list;
        boolean added = workers.add(worker);
        if (workers.size() == VANILLA_THREADS && THREADS > VANILLA_THREADS) {
            ChunkRenderDispatcher dispatcher = ((ChunkRenderWorker) worker).chunkRenderDispatcher;
            ThreadFactory factory = ChunkRenderDispatcher.threadFactory;
            BlockingQueue<RegionRenderCacheBuilder> builders = dispatcher.queueFreeRenderBuilders;
            for (int i = VANILLA_THREADS; i < THREADS; i++) {
                ChunkRenderWorker w = new ChunkRenderWorker(dispatcher);
                factory.newThread(w).start();
                workers.add(w);
                // The game's constructor adds 5 more after this loop: total THREADS + 3.
                builders.add(new RegionRenderCacheBuilder());
            }
            Log.info(
                    "chunks",
                    "" + THREADS + " chunk builder threads (game: " + VANILLA_THREADS + ")");
        }
        return added;
    }
}
