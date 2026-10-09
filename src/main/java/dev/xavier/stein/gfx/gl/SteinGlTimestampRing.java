/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx.gl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The bookkeeping that the GPU timers ({@link SteinGlGpuTiming}, {@link SteinGlOpTiming}) share: a
 * ring of timestamp slots (label and frame of each), read frames later and never waited for, and
 * the totals per label. The query objects themselves stay with whoever owns the ring.
 *
 * <p>Pipeline: Stein GFX only.
 */
final class SteinGlTimestampRing {

    final int slots;
    private final int[] slotLabel;
    private final long[] slotFrame;
    private int head;
    private int tail;
    private int count;

    /** Measurements discarded because the ring was full (reset by {@link #takeDropped()}). */
    private long dropped;

    private final Map<String, Integer> ids = new HashMap<>();
    final List<String> names = new ArrayList<>();

    /** Per label id: time summed, how many measurements, and (the pass timer) time spent before. */
    long[] sum;

    long[] hits;
    long[] gap;

    SteinGlTimestampRing(int slots, int initialLabels) {
        this.slots = slots;
        this.slotLabel = new int[slots];
        this.slotFrame = new long[slots];
        this.sum = new long[initialLabels];
        this.hits = new long[initialLabels];
        this.gap = new long[initialLabels];
    }

    /** Takes the next slot in {@code frame}; -1 (and counts it) if the ring is full. */
    int claim(long frame) {
        if (count == slots) {
            dropped++;
            return -1;
        }
        int slot = head;
        head = (head + 1) % slots;
        count++;
        slotFrame[slot] = frame;
        return slot;
    }

    void setLabel(int slot, int label) {
        slotLabel[slot] = label;
    }

    int label(int slot) {
        return slotLabel[slot];
    }

    /** True while there are slots waiting to be read. */
    boolean any() {
        return count > 0;
    }

    /**
     * How many slots the oldest frame has, if it is older than {@code currentFrame} (otherwise 0:
     * the current one is still open).
     */
    int oldestFrameSize(long currentFrame) {
        long f = slotFrame[tail];
        if (f >= currentFrame) {
            return 0;
        }
        int n = 0;
        while (n < count && slotFrame[(tail + n) % slots] == f) {
            n++;
        }
        return n;
    }

    /** The slot at position {@code i} of the oldest frame. */
    int slotAt(int i) {
        return (tail + i) % slots;
    }

    /** The oldest frame was read: its {@code n} slots go back to the ring. */
    void release(int n) {
        tail = (tail + n) % slots;
        count -= n;
    }

    long takeDropped() {
        long d = dropped;
        dropped = 0;
        return d;
    }

    /** The id of the label, created on the first use (the totals arrays grow with it). */
    int id(String label) {
        Integer i = ids.get(label);
        if (i != null) {
            return i;
        }
        int n = names.size();
        ids.put(label, n);
        names.add(label);
        if (n >= sum.length) {
            sum = Arrays.copyOf(sum, n * 2);
            hits = Arrays.copyOf(hits, n * 2);
            gap = Arrays.copyOf(gap, n * 2);
        }
        return n;
    }
}
