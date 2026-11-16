/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import static dev.xavier.stein.gfx.SteinTerrain.INITIAL_VERTICES;
import static dev.xavier.stein.gfx.SteinTerrain.ONE_BUFFER;
import static dev.xavier.stein.gfx.SteinTerrain.VERTEX;

import dev.xavier.stein.bridge.core.Log;

import java.util.Map;
import java.util.TreeMap;

/**
 * A vertex buffer of the terrain and the free-list allocator over it: where the chunks' geometry
 * lives. It grows by copying on the GPU; ranges are returned (coalesced) after the GPU is done with
 * them.
 *
 * <p>Pipeline: Stein GFX only.
 */
final class SteinTerrainRegion {
    /**
     * Single buffer: starts at 16 MB (distance-12 terrain already exceeds 30) and doubles up to 256
     * MB.
     */
    private static final int ONE_INITIAL_VERTICES = 1 << 20;

    /**
     * After that it grows 128 MB at a time: growing copies the whole buffer on the GPU, with the
     * old and the new one together in memory during the copy; doubling a large buffer (distance 32)
     * would ask for twice as much at once.
     */
    private static final int ONE_GROW_STEP = 1 << 23;

    SteinBuffer buffer;
    int capacity;
    int used;

    /** Free ranges: start -> size, coalesced. */
    final TreeMap<Integer, Integer> free = new TreeMap<>();

    /** Frame in which it entered the layer's region list, and the position in it. */
    int mark = -1;

    int slot;

    SteinTerrainRegion() {
        capacity = ONE_BUFFER ? ONE_INITIAL_VERTICES : INITIAL_VERTICES;
        buffer = create(capacity);
        free.put(0, capacity);
    }

    private static SteinBuffer create(int vertices) {
        return SteinDevice.get()
                .createBuffer(
                        new SteinBuffer.Desc(
                                "terrain: region",
                                (long) vertices * VERTEX,
                                SteinBuffer.Memory.GPU,
                                SteinBuffer.Usage.VERTEX));
    }

    /**
     * Best fit: the smallest free range that holds {@code count} (an exact one ends the search).
     * First fit split the large ranges at the start over and over: in a long session of blocks
     * placed and broken (an arena), the free space became slivers no slice fit in, and the buffer
     * grew by 128 MB each time while most of it stood empty.
     */
    int alloc(int count) {
        int bestFirst = -1;
        int bestSize = Integer.MAX_VALUE;
        for (Map.Entry<Integer, Integer> e : free.entrySet()) {
            int size = e.getValue();
            if (size >= count && size < bestSize) {
                bestFirst = e.getKey();
                bestSize = size;
                if (size == count) {
                    break;
                }
            }
        }
        if (bestFirst < 0) {
            grow(count);
            return alloc(count);
        }
        free.remove(bestFirst);
        if (bestSize > count) {
            free.put(bestFirst + count, bestSize - count);
        }
        used += count;
        return bestFirst;
    }

    /** Larger buffer, with the geometry copied on the GPU. */
    private void grow(int needed) {
        int oldCapacity = capacity;
        int step = ONE_BUFFER && oldCapacity >= 2 * ONE_GROW_STEP ? ONE_GROW_STEP : oldCapacity;
        int vertices = oldCapacity + Math.max(step, needed);
        SteinBuffer old = buffer;
        buffer = create(vertices);
        SteinDevice.get().copyBuffer(old, 0, buffer, 0, (long) oldCapacity * VERTEX);
        old.destroy();
        capacity = vertices;
        addFree(oldCapacity, vertices - oldCapacity);
        // Each growth is a new GPU allocation of the whole buffer: with the use beside it, the log
        // shows whether the terrain needs the room or the room went to fragments.
        Log.info(
                "gfx",
                "terrain buffer grew to "
                        + ((long) capacity * VERTEX >> 20)
                        + " MB (in use "
                        + ((long) used * VERTEX >> 20)
                        + " MB, "
                        + free.size()
                        + " free range(s), asked for "
                        + ((long) needed * VERTEX >> 10)
                        + " KB)");
    }

    void release(int first, int count) {
        used -= count;
        addFree(first, count);
    }

    private void addFree(int first, int count) {
        Map.Entry<Integer, Integer> before = free.floorEntry(first);
        if (before != null && before.getKey() + before.getValue() == first) {
            first = before.getKey();
            count += before.getValue();
            free.remove(first);
        }
        Integer after = free.get(first + count);
        if (after != null) {
            free.remove(first + count);
            count += after;
        }
        free.put(first, count);
    }

    void delete() {
        buffer.destroy();
        buffer = null;
    }
}
