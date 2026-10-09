/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

package dev.xavier.stein.gfx;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * A fixed group of (daemon) threads that splits an index loop across the cores: the pose of the
 * players in {@link SteinPlayerPose}. No allocation per call: the indices come from an atomic
 * counter, the game thread works alongside and waits for the last index to finish before returning.
 *
 * <p>The counter carries the generation in its upper 32 bits: a lagging thread from a previous
 * round never takes an index from the next round (the compareAndSet fails and it leaves). Nothing
 * here calls OpenGL: the threads only read fields and write to memory the game thread has already
 * reserved.
 *
 * <p>Pipeline: Stein GFX only.
 */
final class SteinPoseWorkers {

    /**
     * The work for one index; {@code worker} = 0 on the game thread, 1..n on the group's (for
     * per-thread scratch space).
     */
    interface Task {
        void run(int index, int worker) throws Throwable;
    }

    private static final AtomicLong NEXT = new AtomicLong();
    private static final AtomicInteger DONE = new AtomicInteger();
    private static volatile int generation;
    private static volatile Task task;

    /**
     * Generation (upper 32 bits) and end of the round: the end only counts for those of the same
     * generation.
     */
    private static volatile long limit;

    private static volatile Throwable failure;

    private static Thread[] threads = new Thread[0];

    private SteinPoseWorkers() {}

    /**
     * How many threads besides the game's (0 = only the game's). The group is created on the first
     * call with a positive count; a different {@code count} on later calls is ignored.
     *
     * @return the number of worker threads actually running
     */
    static synchronized int start(int count) {
        if (threads.length == 0 && count > 0) {
            Thread[] t = new Thread[count];
            for (int i = 0; i < count; i++) {
                int id = i + 1;
                t[i] = new Thread(() -> loop(id), "Stein pose " + id);
                t[i].setDaemon(true);
                t[i].setPriority(Thread.NORM_PRIORITY);
                t[i].start();
            }
            threads = t;
        }
        return threads.length;
    }

    static int count() {
        return threads.length;
    }

    /**
     * Runs {@code t} for the indices {@code [from, to)}, split between the game thread and the
     * group, and returns when all have finished. Returns the first error from any thread (or null);
     * the other indices run anyway.
     */
    static Throwable run(Task t, int from, int to) {
        int n = to - from;
        if (n <= 0) {
            return null;
        }
        failure = null;
        task = t;
        DONE.set(0);
        int gen = generation + 1;
        limit = ((long) gen << 32) | (to & 0xFFFFFFFFL);
        NEXT.set(((long) gen << 32) | (from & 0xFFFFFFFFL));
        generation = gen;
        Thread[] workers = threads;
        if (n > 1) {
            for (Thread thread : workers) {
                LockSupport.unpark(thread);
            }
        }
        work(gen, 0);
        // The other threads may still be on their last index.
        int spins = 0;
        while (DONE.get() < n) {
            if (++spins < 2000) {
                Thread.onSpinWait();
            } else {
                Thread.yield();
            }
        }
        task = null;
        return failure;
    }

    /** Worker body. The threads are daemons and live as long as the process; there is no stop. */
    private static void loop(int id) {
        int seen = 0;
        while (true) {
            int gen = generation;
            if (gen == seen) {
                LockSupport.park(SteinPoseWorkers.class);
                continue;
            }
            seen = gen;
            work(gen, id);
        }
    }

    private static void work(int gen, int id) {
        while (true) {
            long cur = NEXT.get();
            if ((int) (cur >>> 32) != gen) {
                return;
            }
            long lim = limit;
            int i = (int) cur;
            if ((int) (lim >>> 32) != gen || i >= (int) lim) {
                return;
            }
            if (!NEXT.compareAndSet(cur, cur + 1)) {
                continue;
            }
            Task t = task;
            try {
                t.run(i, id);
            } catch (Throwable e) {
                // Intentional: the first failure is handed to the caller of run().
                if (failure == null) {
                    failure = e;
                }
            } finally {
                DONE.incrementAndGet();
            }
        }
    }
}
