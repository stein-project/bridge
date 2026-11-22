/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import java.util.HashMap;
import java.util.Map;

/**
 * Chunk fade-in (as in newer versions): the first time a chunk appears on screen it goes from the
 * sky color to its own. Keeps, per chunk position, when it appeared and how many of its layers are
 * alive, so a rebuild of the chunk does not animate it again.
 *
 * <p>The position is a 16-block section, and a section with no blocks has no geometry: the first
 * block placed in one (building a tower, 12 blocks above a superflat floor) made it "appear" and
 * fade in from the fog, as if it had just loaded (testers' report, 16/11/2026). A section whose
 * column is already on screen, past the fade, appears at once ({@link #SETTLED}); only a column
 * arriving fades in.
 *
 * <p>Pipeline: Stein GFX only.
 */
public final class SteinChunkFade {
    private SteinChunkFade() {}

    /**
     * The player's choice (Video settings, World): off draws every chunk at once. The bookkeeping
     * goes on either way, so turning it back on does not fade in what is already on screen.
     */
    private static volatile boolean enabled = true;

    /** Turns the fade-in on or off (Stein Lens). */
    public static void setEnabled(boolean on) {
        enabled = on;
    }

    /** The fade's duration this frame, in seconds: 0 draws the chunks without it. */
    static float seconds() {
        return enabled ? Duration.SECONDS : 0f;
    }

    /**
     * Duration of the animation in seconds ({@code -Dsteinbridge.gfx.chunkFade=false} turns it off;
     * the time in {@code steinbridge.gfx.chunkFadeMs}, default 700). In a holder of its own:
     * SteinGfxOptions may only load once the window exists, and Lens calls {@link #setEnabled}
     * before that.
     */
    private static final class Duration {
        static final float SECONDS =
                SteinGfxOptions.CHUNK_FADE
                        ? Math.max(0, SteinGfxOptions.CHUNK_FADE_MS) / 1000f
                        : 0f;
    }

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

    /**
     * Born long ago: drawn without the animation. Shared, never written ({@link SteinTerrainLayers}
     * only fills a 0).
     */
    private static final float[] SETTLED = {Float.MIN_NORMAL};

    /** Sections in a column (the world is 256 blocks high). */
    private static final int COLUMN_SECTIONS = 16;

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
        if (Duration.SECONDS <= 0f) {
            return null;
        }
        long key = chunkKey(x, y, z);
        long[] live = LIVE.computeIfAbsent(key, k -> new long[] {0, 0});
        float[] born = BORN.get(key);
        long t = System.nanoTime();
        if (born == null || live[0] == 0 && t - live[1] > REBORN_NS) {
            born = columnShown(x, z) ? SETTLED : new float[1];
            BORN.put(key, born);
        }
        live[0]++;
        return born;
    }

    /** A chunk layer left. */
    /**
     * Is another section of the column at (x, z) alive and on screen for longer than the fade? Only
     * asked when a section is born, so 16 lookups are cheap.
     */
    private static boolean columnShown(int x, int z) {
        float now = now();
        for (int sy = 0; sy < COLUMN_SECTIONS; sy++) {
            long key = chunkKey(x, sy << 4, z);
            long[] live = LIVE.get(key);
            float[] born = BORN.get(key);
            if (live != null
                    && live[0] > 0
                    && born != null
                    && born[0] != 0f
                    && now - born[0] >= Duration.SECONDS) {
                return true;
            }
        }
        return false;
    }

    static void departure(int x, int y, int z) {
        if (Duration.SECONDS <= 0f) {
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
