/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

/**
 * A set of chunk columns (x, z) on primitive arrays: open addressing with linear probing and
 * backward-shift removal. {@link #contains} is asked for every block the player's movement reads
 * (several hundred per tick), so it must neither box a key nor allocate; the set only grows (by
 * doubling) when columns are added.
 *
 * <p>Not thread safe: one thread owns it (the game thread for {@link ChunkKeep}'s columns).
 */
final class ColumnSet {

    private static final int INITIAL = 64;

    /** Column keys, x in the high half and z in the low half. */
    private long[] keys = new long[INITIAL];

    /** Slot in use. */
    private boolean[] used = new boolean[INITIAL];

    private int size;

    static long key(int x, int z) {
        return (long) x << 32 | (z & 0xFFFFFFFFL);
    }

    private static int mix(long key, int mask) {
        long h = key * 0x9E3779B97F4A7C15L;
        return (int) (h ^ (h >>> 29)) & mask;
    }

    int size() {
        return size;
    }

    boolean isEmpty() {
        return size == 0;
    }

    boolean contains(int x, int z) {
        if (size == 0) {
            return false;
        }
        long k = key(x, z);
        int mask = keys.length - 1;
        for (int i = mix(k, mask); used[i]; i = (i + 1) & mask) {
            if (keys[i] == k) {
                return true;
            }
        }
        return false;
    }

    void add(int x, int z) {
        if ((size + 1) * 4 > keys.length * 3) {
            grow();
        }
        insert(key(x, z));
    }

    private boolean insert(long k) {
        int mask = keys.length - 1;
        int i = mix(k, mask);
        while (used[i]) {
            if (keys[i] == k) {
                return false;
            }
            i = (i + 1) & mask;
        }
        used[i] = true;
        keys[i] = k;
        size++;
        return true;
    }

    void remove(int x, int z) {
        if (size == 0) {
            return;
        }
        long k = key(x, z);
        int mask = keys.length - 1;
        int i = mix(k, mask);
        while (used[i] && keys[i] != k) {
            i = (i + 1) & mask;
        }
        if (!used[i]) {
            return;
        }
        used[i] = false;
        size--;
        // Backward shift: later entries of the same probe run move up into the hole, so a lookup
        // never stops early at it.
        int hole = i;
        for (int j = (i + 1) & mask; used[j]; j = (j + 1) & mask) {
            int home = mix(keys[j], mask);
            boolean movable = hole <= j ? (home <= hole || home > j) : (home <= hole && home > j);
            if (movable) {
                keys[hole] = keys[j];
                used[hole] = true;
                used[j] = false;
                hole = j;
            }
        }
    }

    void clear() {
        if (size != 0) {
            java.util.Arrays.fill(used, false);
            size = 0;
        }
    }

    private void grow() {
        long[] oldKeys = keys;
        boolean[] oldUsed = used;
        keys = new long[oldKeys.length * 2];
        used = new boolean[oldKeys.length * 2];
        size = 0;
        for (int i = 0; i < oldKeys.length; i++) {
            if (oldUsed[i]) {
                insert(oldKeys[i]);
            }
        }
    }
}
