/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

package dev.xavier.stein.bridge.engine;

/**
 * Hash of the 1.8.9 LongHashMap keys — the client's chunk map, among others.
 *
 * <p>The original does {@code (int)(key ^ key >>> 32)}, which for a chunk key is simply {@code x ^
 * z}, followed by a bijective bit mix that creates no diversity at all. The ~529 chunks around the
 * player land in 32 to 76 of the 4096 buckets, with lists of up to 23 entries: each chunk lookup
 * (the light of each entity and tile entity, every frame) walks 5 to 9 entries on average.
 * MurmurHash3's finalizer spreads them over ~500 buckets, ~1.1 comparisons per lookup. See the
 * simulation used to check it.
 *
 * <p>The patcher redirects LongHashMap.getHashedKey here. Correct by construction: put, get, remove
 * and the rehash all go through the same function.
 *
 * <p>{@code -Dsteinbridge.opt.vanillaChunkHash=true} restores the original hash, to measure A/B
 * with the same jar.
 */
public final class ChunkHash {

    private static final boolean VANILLA = EngineOptions.VANILLA_CHUNK_HASH;

    private ChunkHash() {}

    public static int hashedKey(long key) {
        if (VANILLA) {
            int i = (int) (key ^ key >>> 32);
            i = i ^ i >>> 20 ^ i >>> 12;
            return i ^ i >>> 7 ^ i >>> 4;
        }
        key ^= key >>> 33;
        key *= 0xff51afd7ed558ccdL;
        key ^= key >>> 33;
        key *= 0xc4ceb9fe1a85ec53L;
        key ^= key >>> 33;
        return (int) key;
    }
}
