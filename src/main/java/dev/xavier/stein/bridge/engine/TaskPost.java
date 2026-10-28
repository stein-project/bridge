/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import dev.xavier.stein.bridge.core.Log;

import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;

import java.util.concurrent.LinkedBlockingQueue;

/**
 * Two daemon threads that hand Stein's tasks to the integrated server's task queue and to the
 * client's, so the thread that posts never waits on the queue's lock.
 *
 * <p>In 1.8.9 both queues run every task while holding the queue's monitor:
 * MinecraftServer.updateTimeLightAndEntities (and IntegratedServer.tick while paused) drain
 * futureTaskQueue inside {@code synchronized (futureTaskQueue)}, and Minecraft.runTick drains
 * scheduledTasks the same way. addScheduledTask takes that same monitor to add, so whoever posts
 * waits for as long as any task of the queue runs: a long command, a dimension change, the
 * benchmark's biome search. JFR showed the render thread blocked about 12 s at a time in
 * MinecraftServer.addScheduledTask, posting the {@link ServerWatch} probe. The game's own posts
 * come from the network threads, never from the render thread; Stein's now come from here.
 *
 * <p>Each destination has its own thread, so a client queue busy with packets never delays a post
 * to the server, and tasks to one destination keep the order in which they were posted. The task
 * reaches the queue as soon as the lock is free, which is when the caller's own {@code
 * addScheduledTask} would have returned, plus the hand-over to this thread (microseconds): the task
 * can land one tick later than before only when the queue is drained in exactly that gap. Only
 * Stein's own tasks (the watch probes, the benchmark's scenery) go through here; the game's posts
 * do not change.
 *
 * <p>The call is the game's own {@code addScheduledTask}, so a server that has already stopped runs
 * the task on the calling thread, as it did before, only now that thread is this one.
 */
public final class TaskPost {

    private static final Lane SERVER = new Lane("stein-server-post");
    private static final Lane CLIENT = new Lane("stein-client-post");

    private TaskPost() {}

    /** In place of {@code server.addScheduledTask(task)} from the render or the server thread. */
    public static void toServer(MinecraftServer server, Runnable task) {
        SERVER.post(() -> server.addScheduledTask(task));
    }

    /** In place of {@code mc.addScheduledTask(task)} from the server thread. */
    public static void toClient(Minecraft mc, Runnable task) {
        CLIENT.post(() -> mc.addScheduledTask(task));
    }

    /** One destination: a queue of posts and the thread that hands them over, in order. */
    private static final class Lane {
        private final String name;
        private final LinkedBlockingQueue<Runnable> posts = new LinkedBlockingQueue<>();
        private volatile boolean started;

        Lane(String name) {
            this.name = name;
        }

        void post(Runnable post) {
            posts.add(post);
            if (!started) {
                start();
            }
        }

        private synchronized void start() {
            if (started) {
                return;
            }
            Thread t = new Thread(this::run, name);
            t.setDaemon(true);
            t.start();
            started = true;
        }

        private void run() {
            while (true) {
                Runnable post;
                try {
                    post = posts.take();
                } catch (InterruptedException e) {
                    return;
                }
                try {
                    post.run();
                } catch (Throwable t) {
                    // a failed post must not end the thread: later posts would wait forever
                    Log.warn("server", name + ": " + t);
                }
            }
        }
    }
}
