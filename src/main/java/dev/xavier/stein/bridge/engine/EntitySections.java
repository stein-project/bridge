/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import net.minecraft.entity.Entity;
import net.minecraft.world.World;

import java.util.Arrays;
import java.util.List;

/**
 * The chunk sections of the client world that hold an entity, from the entities themselves.
 *
 * <p>Chunk.addEntity files an entity under one section of its chunk and records it in the entity
 * (chunkCoordX, chunkCoordY clamped to 0..15, chunkCoordZ, addedToChunk), and the world keeps those
 * fields in step with the lists when the entity moves or leaves. So the sections with an entity are
 * the ones the loaded entities name — a few hundred entities instead of a chunk map lookup for each
 * of the 10 to 14 thousand visible sections. Between a chunk unloading and the world removing its
 * entities (the next tick), a section can still be named here: the render loop reads the chunk's
 * own list and finds nothing there, as the game would.
 *
 * <p>Render thread only. The keys come back sorted and without repeats, so two calls can be
 * compared with {@link Arrays#equals(long[], int, int, long[], int, int)}, and {@link #index} puts
 * them in a hash table for the per-section lookups.
 */
final class EntitySections {

    private static long[] keys = new long[256];
    private static int count;

    // Open addressing over the collected keys (EMPTY never comes out of key()).
    private static final long EMPTY = Long.MIN_VALUE;
    private static long[] table = new long[1024];
    private static int mask = table.length - 1;

    private EntitySections() {}

    /** Fills the keys from the world's loaded entities; read them with {@link #keys()}. */
    static void collect(World world) {
        List<Entity> entities = world.loadedEntityList;
        int n = entities.size();
        if (keys.length < n) {
            keys = new long[Math.max(n, keys.length * 2)];
        }
        int k = 0;
        for (int i = 0; i < n; i++) {
            Entity e = entities.get(i);
            if (e.addedToChunk) {
                keys[k++] = key(e.chunkCoordX, e.chunkCoordY, e.chunkCoordZ);
            }
        }
        Arrays.sort(keys, 0, k);
        int unique = 0;
        for (int i = 0; i < k; i++) {
            if (unique == 0 || keys[unique - 1] != keys[i]) {
                keys[unique++] = keys[i];
            }
        }
        count = unique;
    }

    static long[] keys() {
        return keys;
    }

    static int count() {
        return count;
    }

    /** Builds the lookup table over the first {@code n} keys, for {@link #has}. */
    static void index(long[] sorted, int n) {
        int size = table.length;
        while (size < n * 4) {
            size <<= 1;
        }
        if (size != table.length) {
            table = new long[size];
            mask = size - 1;
        }
        Arrays.fill(table, EMPTY);
        for (int i = 0; i < n; i++) {
            int h = slot(sorted[i]);
            while (table[h] != EMPTY) {
                h = (h + 1) & mask;
            }
            table[h] = sorted[i];
        }
    }

    /** True if the section (chunk x, section y, chunk z) is in the last {@link #index}. */
    static boolean has(int cx, int sy, int cz) {
        long k = key(cx, sy, cz);
        int h = slot(k);
        long v;
        while ((v = table[h]) != EMPTY) {
            if (v == k) {
                return true;
            }
            h = (h + 1) & mask;
        }
        return false;
    }

    private static int slot(long k) {
        long h = k * 0x9E3779B97F4A7C15L;
        return (int) (h >>> 40) & mask;
    }

    private static long key(int cx, int sy, int cz) {
        return (long) cx << 36 ^ (long) (cz & 0xFFFFFFF) << 4 ^ (sy & 15);
    }
}
