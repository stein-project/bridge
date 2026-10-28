/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import net.minecraft.block.state.IBlockState;

import java.util.Arrays;

/**
 * The two 8000-entry arrays of a chunk build's RegionRenderCache (the combined lights and the block
 * states), reused by the next build on the same thread instead of allocated for every build.
 *
 * <p>RenderChunk.rebuildChunk makes a new RegionRenderCache each time, and its constructor
 * allocates an int[8000] and an IBlockState[8000]: about 23% of the allocation of the chunk build
 * threads on the benchmark. The patcher hands those two allocations here (redirects-opt.tsv, mode
 * "new", only inside RegionRenderCache's constructor) and marks the start and the end of each
 * rebuildChunk call (its only call site, ChunkRenderWorker.processTask). The region is a local of
 * rebuildChunk: it goes to the block renderers of that build and is dropped when the call returns
 * (BiomeColors only holds it weakly and compares it by identity). So the arrays are given to at
 * most one region per build, the first one made while the build runs on that thread, and the
 * previous region using them is already finished. Any other region (outside a build, or a second
 * one inside it) gets new arrays, as in the game. The arrays come back as new ones would be: the
 * constructor fills the lights with -1 itself, and the states are cleared to null here. {@code
 * -Dsteinbridge.opt.vanillaRegionArrays=true} allocates them for every region again.
 */
public final class RegionArrays {

    private static final boolean VANILLA = EngineOptions.VANILLA_REGION_ARRAYS;

    private static final ThreadLocal<Pool> POOL = ThreadLocal.withInitial(Pool::new);

    private RegionArrays() {}

    /** Before RenderChunk.rebuildChunk: this thread's arrays may go to the build's region. */
    public static void buildBegin() {
        if (!VANILLA) {
            Pool p = POOL.get();
            p.lightsFree = true;
            p.statesFree = true;
        }
    }

    /** After RenderChunk.rebuildChunk: no region outside a build gets the arrays. */
    public static void buildEnd() {
        if (!VANILLA) {
            Pool p = POOL.get();
            p.lightsFree = false;
            p.statesFree = false;
        }
    }

    /** new int[length] for RegionRenderCache.combinedLights (the constructor fills it with -1). */
    public static Object lights(int length) {
        if (VANILLA) {
            return new int[length];
        }
        Pool p = POOL.get();
        if (!p.lightsFree) {
            return new int[length];
        }
        p.lightsFree = false;
        if (p.lights == null || p.lights.length != length) {
            p.lights = new int[length];
        }
        return p.lights;
    }

    /** new IBlockState[length] for RegionRenderCache.blockStates. */
    public static Object states(int length) {
        if (VANILLA) {
            return new IBlockState[length];
        }
        Pool p = POOL.get();
        if (!p.statesFree) {
            return new IBlockState[length];
        }
        p.statesFree = false;
        if (p.states == null || p.states.length != length) {
            p.states = new IBlockState[length];
        } else {
            Arrays.fill(p.states, null);
        }
        return p.states;
    }

    /** One thread's arrays and whether the current build may still take them. */
    private static final class Pool {
        int[] lights;
        IBlockState[] states;
        boolean lightsFree;
        boolean statesFree;
    }
}
