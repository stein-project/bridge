/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.bench;

import dev.xavier.stein.bridge.core.Log;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Every frame of the benchmark, one row each, streamed into {@code frames.csv} inside the report's
 * zip while the game runs. The render thread only copies numbers into a preallocated block; a
 * low-priority thread turns full blocks into text, so memory stays fixed whatever the frame rate
 * and the render thread never formats or writes. When the writer falls behind and no block is free,
 * rows are dropped and counted (the report says how many).
 */
final class BenchFrames {

    /** The fixed columns before the zones. */
    static final String[] HEAD = {"t_ms", "segment", "measured", "frame_us"};

    /** The columns after the zones. */
    static final String[] TAIL = {
        "ticks",
        "draws",
        "passes",
        "syncs",
        "render_breaks",
        "ring_waits",
        "ring_wait_us",
        "texture_uploads",
        "chunk_uploads",
        "chunks_visible",
        "entities_drawn",
        "entities_total",
        "tile_entities",
        "particles",
        "alloc_kb",
        "gc_events",
        "gc_pause_us",
        "focused",
        "minimized",
        "width",
        "height"
    };

    static final int T = 0;
    static final int SEGMENT = 1;
    static final int MEASURED = 2;
    static final int FRAME = 3;
    static final int ZONES = 4;
    static final int TAIL_AT = ZONES + BenchZones.COUNT;
    static final int COLS = TAIL_AT + TAIL.length;

    /** Which columns hold nanoseconds (written as microseconds or milliseconds). */
    private static final boolean[] NANOS = new boolean[COLS];

    static {
        NANOS[T] = true;
        NANOS[FRAME] = true;
        for (int z = 0; z < BenchZones.COUNT; z++) {
            NANOS[ZONES + z] = true;
        }
        NANOS[TAIL_AT + 6] = true; // ring_wait_us
        NANOS[TAIL_AT + 16] = true; // gc_pause_us
    }

    private static final int ROWS = 2048;
    private static final int BLOCKS = 16;

    /** A block of rows and how many of them are filled. */
    static final class Block {
        final long[] v = new long[ROWS * COLS];
        int rows;
    }

    private final BlockingQueue<Block> free = new ArrayBlockingQueue<>(BLOCKS);
    private final BlockingQueue<Block> full = new ArrayBlockingQueue<>(BLOCKS + 1);

    /** The empty block that marks the end of the stream. */
    private static final Block END = new Block();

    private final ZipOutputStream zip;
    private final List<String> segmentNames;
    private final Thread writer;
    private volatile IOException failure;

    private Block block;
    private volatile long written;
    private long dropped;

    BenchFrames(ZipOutputStream zip, List<String> segmentNames) {
        this.zip = zip;
        this.segmentNames = segmentNames;
        for (int i = 0; i < BLOCKS; i++) {
            free.add(new Block());
        }
        block = free.poll();
        writer = new Thread(this::write, "Stein Bench writer");
        writer.setDaemon(true);
        writer.setPriority(Thread.MIN_PRIORITY);
        writer.start();
    }

    /** The offset of a row to fill for this frame in {@link #values()}, or -1 when dropped. */
    int row() {
        if (block == null) {
            block = free.poll();
            if (block == null) {
                dropped++;
                return -1;
            }
        }
        return block.rows++ * COLS;
    }

    long[] values() {
        return block.v;
    }

    /** After the row has been filled: hands the block over when it is full. */
    void commit() {
        if (block != null && block.rows == ROWS) {
            full.add(block);
            block = null;
        }
    }

    /** Ends the stream: the partial block goes out and the writer closes the entry. */
    void finish() throws IOException {
        if (block != null && block.rows > 0) {
            full.add(block);
            block = null;
        }
        full.add(END);
        try {
            writer.join(TimeUnit.MINUTES.toMillis(2));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (failure != null) {
            throw failure;
        }
    }

    long written() {
        return written;
    }

    long dropped() {
        return dropped;
    }

    private void write() {
        try {
            zip.putNextEntry(new ZipEntry("frames.csv"));
            Writer w = new OutputStreamWriter(zip, StandardCharsets.UTF_8);
            StringBuilder line = new StringBuilder(1024);
            header(line);
            w.write(line.toString());
            for (; ; ) {
                Block b = full.take();
                if (b == END) {
                    break;
                }
                for (int r = 0; r < b.rows; r++) {
                    line.setLength(0);
                    row(line, b.v, r * COLS);
                    w.write(line.toString());
                }
                written += b.rows;
                b.rows = 0;
                free.add(b);
            }
            w.flush();
            zip.closeEntry();
        } catch (IOException e) {
            failure = e;
            Log.warn("bench", "frames.csv: " + e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void header(StringBuilder b) {
        for (String h : HEAD) {
            b.append(h).append(',');
        }
        for (String z : BenchZones.NAMES) {
            b.append(z).append("_us,");
        }
        for (int i = 0; i < TAIL.length; i++) {
            b.append(TAIL[i]).append(i + 1 < TAIL.length ? ',' : '\n');
        }
    }

    private void row(StringBuilder b, long[] v, int at) {
        for (int c = 0; c < COLS; c++) {
            long x = v[at + c];
            if (c == SEGMENT) {
                int s = (int) x;
                b.append(s >= 0 && s < segmentNames.size() ? segmentNames.get(s) : "-");
            } else if (c == T) {
                micros(b, x / 1000L); // milliseconds with three decimals
            } else if (NANOS[c]) {
                micros(b, x);
            } else {
                b.append(x);
            }
            b.append(c + 1 < COLS ? ',' : '\n');
        }
    }

    /** {@code nanos} as microseconds with one decimal ("12.3"). */
    private static void micros(StringBuilder b, long nanos) {
        if (nanos < 0) {
            b.append('-');
            nanos = -nanos;
        }
        long tenths = (nanos + 50) / 100;
        b.append(tenths / 10).append('.').append(tenths % 10);
    }
}
