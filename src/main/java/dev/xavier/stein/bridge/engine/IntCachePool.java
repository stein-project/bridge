/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import net.minecraft.world.gen.layer.IntCache;

/**
 * The body of IntCache.resetIntCache (asc.a()), keeping every spare array.
 *
 * <p>The world generator's biome layers take their scratch arrays from IntCache, and every biome
 * question (getBiomesForGeneration, areBiomesViable, the biome cache) starts with a reset that
 * hands the arrays in use back to the free lists. The game's reset also throws away one free array
 * of each size first. A short question (areBiomesViable, asked many times per chunk by the
 * structure generators) leaves spare arrays behind, the reset drops one, and the next big question
 * allocates it again: about 40% of the integrated server's allocation (the GenLayer arrays) on the
 * benchmark.
 *
 * <p>Here the reset does the rest of the game's reset without the drop. The pool then holds at most
 * the most arrays a single question used, and it is still emptied whenever a larger size is asked
 * for (getIntCache, unchanged). The layers write every entry of an array before reading it — the
 * game already hands out recycled arrays with leftover contents on most calls, and a generated
 * world does not depend on the order chunks were generated in — so which recycled array a layer
 * gets does not change any biome. The method keeps the game's {@code synchronized} on IntCache: the
 * patcher replaces only the body. {@code -Dsteinbridge.opt.vanillaIntCache=true} drops the array as
 * the game does.
 */
public final class IntCachePool {

    private static final boolean VANILLA = EngineOptions.VANILLA_INT_CACHE;

    private IntCachePool() {}

    /** IntCache.resetIntCache, run while holding IntCache's monitor. */
    public static void reset() {
        if (VANILLA) {
            if (!IntCache.freeLargeArrays.isEmpty()) {
                IntCache.freeLargeArrays.remove(IntCache.freeLargeArrays.size() - 1);
            }
            if (!IntCache.freeSmallArrays.isEmpty()) {
                IntCache.freeSmallArrays.remove(IntCache.freeSmallArrays.size() - 1);
            }
        }
        IntCache.freeLargeArrays.addAll(IntCache.inUseLargeArrays);
        IntCache.freeSmallArrays.addAll(IntCache.inUseSmallArrays);
        IntCache.inUseLargeArrays.clear();
        IntCache.inUseSmallArrays.clear();
    }
}
