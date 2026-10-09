/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

package dev.xavier.stein.gfx;

import java.util.HashMap;
import java.util.Map;

/**
 * Chunk fade-in (as in newer versions): the first time a chunk appears on screen it goes from the
 * sky color to its own. Keeps, per chunk position, when it appeared and how many of its layers are
 * alive, so a rebuild of the chunk does not animate it again.
 *
 * <p>Pipeline: Stein GFX only.
 */
final class SteinChunkFade {
    private SteinChunkFade() {}

    /**
     * Duration of the animation in seconds ({@code -Dsteinbridge.gfx.chunkFade=false} turns it off;
     * the time in {@code steinbridge.gfx.chunkFadeMs}, default 700).
     */
    static final float FADE_SECONDS =
            SteinGfxOptions.CHUNK_FADE ? Math.max(0, SteinGfxOptions.CHUNK_FADE_MS) / 1000f : 0f;

    /**
     * A chunk that disappears and returns within this does not animate again: a rebuild (broken
     * block, light, texture pack switch — the game redoes them all) releases the old layer and
     * sends another, sometimes seconds later in the queue.
     */
    private static final long REBORN_NS = 4_000_000_000L;

    private static final long CLOCK_ZERO = System.nanoTime();

    /**
     * Per chunk (position): when it appeared (s), how many live layers it has, and when it was left
     * with none (nanoTime).
     */
    private static final Map<Long, float[]> BORN = new HashMap<>();

    private static final Map<Long, long[]> LIVE = new HashMap<>();

    static float now() {
        // 1 instead of 0 at the start: 0 at the origin means "no animation"
        return (System.nanoTime() - CLOCK_ZERO) / 1e9f + 1f;
    }

    private static long chunkKey(int x, int y, int z) {
        return ((long) (x >> 4) & 0x3FFFFF) << 42
                | ((long) (z >> 4) & 0x3FFFFF) << 20
                | ((long) (y >> 4) & 0xFFFFF);
    }

    /**
     * A chunk layer arrived: the record of when it appears (new, still without a time, if the chunk
     * was not there or vanished long ago). The time is only set on the first draw ({@link
     * SteinTerrainLayers#writeOrigins}): a chunk built out of view animates when the player looks
     * at it, not when it arrived.
     */
    static float[] arrival(int x, int y, int z) {
        if (FADE_SECONDS <= 0f) {
            return null;
        }
        long key = chunkKey(x, y, z);
        long[] live = LIVE.computeIfAbsent(key, k -> new long[] {0, 0});
        float[] born = BORN.get(key);
        long t = System.nanoTime();
        if (born == null || live[0] == 0 && t - live[1] > REBORN_NS) {
            born = new float[1];
            BORN.put(key, born);
        }
        live[0]++;
        return born;
    }

    /** A chunk layer left. */
    static void departure(int x, int y, int z) {
        if (FADE_SECONDS <= 0f) {
            return;
        }
        long[] live = LIVE.get(chunkKey(x, y, z));
        if (live != null && --live[0] <= 0) {
            live[0] = 0;
            live[1] = System.nanoTime();
        }
    }

    /** Forgets the chunks that vanished long ago (once per second). */
    static void forgetGone() {
        long t = System.nanoTime();
        if (t - forgetAt < 1_000_000_000L) {
            return;
        }
        forgetAt = t;
        LIVE.entrySet()
                .removeIf(
                        e -> {
                            long[] v = e.getValue();
                            if (v[0] == 0 && t - v[1] > REBORN_NS) {
                                BORN.remove(e.getKey());
                                return true;
                            }
                            return false;
                        });
    }

    private static long forgetAt;
}
