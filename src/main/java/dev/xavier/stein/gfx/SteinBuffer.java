/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import dev.xavier.stein.bridge.bench.BenchCounters;
import dev.xavier.stein.bridge.core.Log;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * A GPU buffer. The size is fixed at creation (as in Vulkan). Where it lives, by {@link Memory}:
 *
 * <ul>
 *   <li>{@link Memory#GPU}: only the GPU reads; the CPU writes through {@link #upload} (geometry
 *       that does not change);
 *   <li>{@link Memory#UPLOAD}: mapped on the CPU and read by the GPU (a persistent buffer when the
 *       device has one, otherwise a copy through {@link #upload}). With {@link #ring}, it becomes a
 *       ring of fenced regions: the CPU writes straight into {@link #address}, frame after frame,
 *       with no copy and without the GPU reading what is being written.
 * </ul>
 *
 * <p>Pipeline: Stein GFX only.
 */
public abstract class SteinBuffer {

    /** What the buffer is for (the backend may pick memory and targets accordingly). */
    public enum Usage {
        VERTEX,
        INDEX,
        UNIFORM,
        STORAGE,
        INDIRECT,

        /**
         * {@link Memory#UPLOAD} that the GPU writes too (a compute shader fills part of it). The
         * backend may then keep what the GPU reads in device memory: {@link #address} stays the
         * CPU's side, and what the CPU wrote reaches the GPU only at {@link #commit} (or {@link
         * #commitRing}), which every writer already calls before the draw that reads the range.
         * Ranges written by the GPU must not be read back through {@link #address}.
         */
        GPU_WRITE
    }

    public enum Memory {
        GPU,
        UPLOAD
    }

    public static final class Desc {
        public final String name;
        public final long size;
        public final Set<Usage> usage;
        public final Memory memory;

        public Desc(String name, long size, Memory memory, Usage first, Usage... rest) {
            this.name = name;
            this.size = size;
            this.memory = memory;
            this.usage = Collections.unmodifiableSet(EnumSet.of(first, rest));
        }
    }

    protected final Desc desc;

    protected SteinBuffer(Desc desc) {
        this.desc = desc;
    }

    public Desc desc() {
        return desc;
    }

    public long size() {
        return desc.size;
    }

    /** The backend id (OpenGL: the buffer name). */
    public abstract long handle();

    /** Copies {@code data} (from its position up to its limit) to {@code offset}. */
    public abstract void upload(long offset, ByteBuffer data);

    /** Copies floats to {@code offset}. */
    public abstract void upload(long offset, float[] data, int count);

    /**
     * CPU address of {@code offset} ({@link Memory#UPLOAD}: the persistent mapping, or the CPU copy
     * that {@link #commit} uploads; otherwise 0).
     */
    public abstract long address(long offset);

    /**
     * Has an {@link #address} (write there and, without persistent mapping, call {@link #commit}).
     */
    public abstract boolean mapped();

    /**
     * Takes to the GPU what the CPU wrote in {@code [offset, offset + bytes)} through {@link
     * #address}. With persistent, coherent mapping it does nothing (the GPU already sees it);
     * without it (4.1), {@code address} is a CPU copy and the range is uploaded here. Call before
     * the draw that reads the range.
     */
    public void commit(long offset, long bytes) {}

    /** {@link #commit} of what the current ring region received since the last time. */
    public void commitRing() {
        Ring r = ring;
        if (r == null || r.head == r.committed && r.wrapEnd < 0) {
            return;
        }
        if (r.wrapEnd >= 0) {
            // the reservation went back to the start of the buffer since the last upload: uploads
            // the end of the buffer and then the start
            commit(r.committed, r.wrapEnd - r.committed);
            r.committed = 0;
            r.wrapEnd = -1;
        }
        commit(r.committed, r.head - r.committed);
        r.committed = r.head;
    }

    public abstract void destroy();

    // ---- ring (UPLOAD) ----
    //
    // The whole buffer is one continuous ring: each region (what goes from one nextRegion to the
    // next, usually a frame) takes up only what it wrote, right after the previous one, and
    // closes with a fence. The CPU only writes to a range again after the fence of whatever was
    // there has passed; with small regions (the common case) many frames fit in flight and the
    // CPU does not wait for the GPU. A region's limit (size / regions) is the same as before: -2
    // and -1 come out at the same points, so the mid-pass flushes stay where they were.

    /**
     * Closed regions in flight at most; with more, the CPU waits for the oldest (the GPU fell
     * frames behind).
     */
    private static final int MAX_IN_FLIGHT = 8;

    /** Longest wait for one fence. */
    private static final long FENCE_WAIT_NANOS = 1_000_000_000L;

    /** Interval between diagnostics reports. */
    private static final long REPORT_INTERVAL_NANOS = 5_000_000_000L;

    private Ring ring;

    /**
     * Ring with regions of up to {@code size / regions} bytes, one fence per region (only {@link
     * Memory#UPLOAD}).
     */
    public SteinBuffer ring(int regions) {
        this.ring = new Ring(desc.size, desc.size / regions);
        return this;
    }

    /**
     * Reserves {@code bytes} in the current region. Returns the offset in the buffer; -2 if the
     * region is full (the caller draws what is pending in it and calls {@link #nextRegion()}); -1
     * if the request is larger than a region.
     */
    public long reserve(long bytes) {
        return reserve(bytes, 1);
    }

    /**
     * Reserves with alignment (uniform buffer): the returned offset is a multiple of {@code
     * alignment}.
     */
    public long reserve(long bytes, int alignment) {
        Ring r = ring;
        long pad = alignment <= 1 ? 0 : (alignment - (r.written % alignment)) % alignment;
        if (bytes + pad > r.regionBytes) {
            return -1;
        }
        if (r.written + pad + bytes > r.regionBytes) {
            return -2;
        }
        long at = r.place(bytes, Math.max(alignment, 1), desc.name);
        if (at < 0) {
            // The whole buffer is taken by the current region, or it would wrap: flush and close.
            return -2;
        }
        r.written += pad + bytes;
        return at;
    }

    /**
     * Closes the current region with a fence (the GPU has already received the draws that read from
     * it) and opens the next one, right after. Releases the fences that have already passed,
     * without waiting; only waits if there are too many regions in flight.
     */
    public void nextRegion() {
        Ring r = ring;
        if (r.written == 0) {
            // Region with nothing written: the GPU reads nothing from it, it needs no fence or
            // switch.
            return;
        }
        commitRing();
        r.push(r.openStart, SteinDevice.get().fence());
        r.openStart = r.head;
        r.written = 0;
        while (r.count > 0 && r.fences[r.first].done()) {
            r.retire(false, desc.name);
        }
        if (r.count >= MAX_IN_FLIGHT) {
            r.retire(true, desc.name);
        }
        if (TIMING) {
            report();
        }
    }

    private static final class Ring {
        final long size;

        /** The most a region receives (as before: size / regions). */
        final long regionBytes;

        /**
         * Closed regions in flight, from oldest to newest (circular queue): start and fence of
         * each.
         */
        final long[] starts = new long[MAX_IN_FLIGHT];

        final SteinFence[] fences = new SteinFence[MAX_IN_FLIGHT];
        int first;
        int count;

        /** Start of the open region, and where the next reservation goes. */
        long openStart;

        long head;

        /**
         * Bytes of the open region counted as before (alignment relative to it): decides the -2 and
         * the -1.
         */
        long written;

        /** How far the current region has already been uploaded ({@link #commitRing}). */
        long committed;

        /**
         * Where what was not uploaded stopped when the reservation went back to the start of the
         * buffer; -1 if it did not wrap.
         */
        long wrapEnd = -1;

        Ring(long size, long regionBytes) {
            this.size = size;
            this.regionBytes = regionBytes;
        }

        void push(long start, SteinFence fence) {
            int i = (first + count) % starts.length;
            starts[i] = start;
            fences[i] = fence;
            count++;
        }

        /**
         * Releases the oldest closed region; {@code block}: waits for its fence if it has not
         * passed yet.
         */
        void retire(boolean block, String name) {
            SteinFence f = fences[first];
            if (block && !f.done()) {
                long t0 = System.nanoTime();
                f.waitFor(FENCE_WAIT_NANOS);
                waited(name, System.nanoTime() - t0);
                BenchCounters.ringWait(System.nanoTime() - t0);
            }
            f.destroy();
            fences[first] = null;
            first = (first + 1) % starts.length;
            count--;
        }

        /**
         * Contiguous room for {@code bytes} (aligned) where the GPU no longer reads anything:
         * between the open region and the oldest in flight. Releases old regions (waiting for the
         * fence, if needed) until it fits; -1 if the open region alone already fills the buffer.
         */
        long place(long bytes, int alignment, String name) {
            for (; ; ) {
                if (count == 0 && head == openStart) {
                    head = openStart = committed = 0; // nothing in use: restarts from the start
                    wrapEnd = -1;
                }
                long tail = count > 0 ? starts[first] : openStart;
                long at = (head + alignment - 1) / alignment * alignment;
                long found = -1;
                if (head >= tail) {
                    // in use [tail, head); free [head, end) and [0, tail) (strict: head == tail
                    // only with an empty ring)
                    if (at + bytes <= size) {
                        found = at;
                    } else if (head != openStart) {
                        // The open region does not wrap in the middle: the caller draws several
                        // reservations in a row at once (the commands of a multi-draw from the
                        // first one) and counts on them being contiguous. -1 becomes -2:
                        // flushes, closes the region and the next one starts at the start of the
                        // buffer.
                        return -1;
                    } else if (bytes < tail) {
                        found = 0;
                    }
                } else if (at + bytes < tail) {
                    found = at; // in use [tail, end) and [0, head); free [head, tail)
                }
                if (found >= 0) {
                    if (found < head) {
                        wrapEnd = head; // wrapped to the start: commitRing uploads both ends
                    }
                    if (head == openStart) {
                        openStart = found; // first reservation of the region: it starts here
                    }
                    head = found + bytes;
                    return found;
                }
                if (count == 0) {
                    return -1;
                }
                retire(true, name);
            }
        }
    }

    // ---- diagnostics (-Dsteinbridge.gfx.passTiming=true): ring fence waits, every 5 s ----

    private static final boolean TIMING = SteinGfxOptions.PASS_TIMING;
    private static final Map<String, long[]> WAITS = new TreeMap<>();
    private static long reportAt;

    private static void waited(String name, long nanos) {
        if (!TIMING) {
            return;
        }
        long[] w = WAITS.computeIfAbsent(name, k -> new long[3]);
        w[0]++;
        w[1] += nanos;
        w[2] = Math.max(w[2], nanos);
    }

    private static void report() {
        long now = System.nanoTime();
        if (reportAt == 0L) {
            reportAt = now;
            return;
        }
        if (now - reportAt < REPORT_INTERVAL_NANOS) {
            return;
        }
        long n = 0;
        long ns = 0;
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, long[]> e : WAITS.entrySet()) {
            long[] w = e.getValue();
            n += w[0];
            ns += w[1];
            b.append(
                    String.format(
                            Locale.ROOT,
                            "; %s %d (%.2f ms, max %.2f)",
                            e.getKey(),
                            w[0],
                            w[1] / 1e6,
                            w[2] / 1e6));
        }
        Log.info(
                "gfx",
                String.format(
                        Locale.ROOT,
                        "ring fences in %.1f s: %d waits, %.2f ms%s",
                        (now - reportAt) / 1e9,
                        n,
                        ns / 1e6,
                        b));
        WAITS.clear();
        reportAt = now;
    }
}
