/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

package dev.xavier.stein.bridge.engine;

import dev.xavier.stein.bridge.core.Log;

import net.minecraft.client.audio.SoundManager;

/**
 * Sound reload without two sound systems alive. The game's SoundManager.reloadSoundSystem shuts the
 * system down (unloadSoundSystem) and brings up another on a thread ("Sound Library Loader"); the
 * shutdown only acts on an already loaded system (the loaded field, which the thread sets at the
 * end). A reload that arrives while the thread is still bringing the system up — two resource
 * reloads in a row, as a mod does when registering its packs — skips the shutdown and brings up a
 * second system: the first is orphaned, with the music and looping sounds that were already playing
 * on top of the new ones (duplicate sound or sound at the wrong time). The log showed "Starting up
 * SoundSystem" without the "SoundSystem shutting down" before it.
 *
 * <p>Patcher rule (redirects-mods.tsv): the unloadSoundSystem of reloadSoundSystem goes through
 * {@link #unload}, which waits for the thread to finish (up to 10 s) and only then shuts down.
 */
public final class SoundGuard {

    private static final String LOADER_THREAD = "Sound Library Loader";
    private static final long WAIT_MS = 10_000L;

    private SoundGuard() {}

    /**
     * In place of unloadSoundSystem() in reloadSoundSystem: waits for the system that is still
     * coming up, and shuts down.
     */
    public static void unload(Object managerObject) throws InterruptedException {
        SoundManager manager = (SoundManager) managerObject;
        if (!manager.loaded) {
            for (Thread t : Thread.getAllStackTraces().keySet()) {
                if (LOADER_THREAD.equals(t.getName())
                        && t.isAlive()
                        && t != Thread.currentThread()) {
                    long start = System.nanoTime();
                    t.join(WAIT_MS);
                    Log.info(
                            "sound",
                            "reload waited for the sound system to finish starting ("
                                    + (System.nanoTime() - start) / 1_000_000L
                                    + " ms): no two live systems");
                }
            }
        }
        manager.unloadSoundSystem();
    }
}
