/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import net.minecraft.util.BlockPos;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraft.world.biome.WorldChunkManager;

/**
 * Serializes WorldChunkManager.getBiomeGenerator(pos, fallback) (aec.a) calls, one at a time.
 *
 * <p>Whoever builds chunks on the background threads asks for the grass and foliage biome colour,
 * including at the edge, where the neighbouring chunk may not have arrived (or arrived without a
 * biome). Then the game computes the biome through the world's BiomeCache, which is an ArrayList
 * and a LongHashMap with no locking at all: two threads writing together corrupted the list ({@code
 * ArrayIndexOutOfBoundsException} in ArrayList.add, "Getting biome"). The vanilla game has the same
 * race with 2 threads; with the N threads of {@link ChunkThreads} it started to happen — and the
 * crash report, produced on the chunk thread, silently closed the game (see GLCompat.glGetString).
 *
 * <p>Only the biome that was missing from the chunk goes through here: the lock is rare and short.
 */
public final class BiomeLock {

    private BiomeLock() {}

    /** In place of manager.getBiomeGenerator(pos, fallback). */
    public static Object biomeAt(Object manager, Object pos, Object fallback) {
        synchronized (manager) {
            return ((WorldChunkManager) manager)
                    .getBiomeGenerator((BlockPos) pos, (BiomeGenBase) fallback);
        }
    }
}
