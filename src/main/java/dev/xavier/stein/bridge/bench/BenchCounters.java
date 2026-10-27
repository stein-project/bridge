/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.bench;

/**
 * What the renderer did in the frame, counted for the benchmark: draws, passes, the points where
 * the recorded frame had to go to the driver early, the breaks of a Vulkan render for an upload,
 * the waits for a ring buffer's fence, texture and chunk uploads. The renderer calls these where
 * the event happens; without {@code -Dsteinbridge.bench} each is a static final check the JIT
 * removes. On the render thread.
 */
public final class BenchCounters {

    private BenchCounters() {}

    private static final boolean ON = BenchOptions.ON;

    static int draws;
    static int passes;
    static int syncs;
    static int renderBreaks;
    static int ringWaits;
    static long ringWaitNanos;
    static int textureUploads;
    static int chunkUploads;

    /** A draw command (direct or indirect) reached the API. */
    public static void draw() {
        if (ON) {
            draws++;
        }
    }

    /** A pass of the API opened. */
    public static void pass() {
        if (ON) {
            passes++;
        }
    }

    /** The recorded OpenGL frame went to the driver before the end of the frame. */
    public static void sync() {
        if (ON) {
            syncs++;
        }
    }

    /** A Vulkan render was suspended for a copy in the middle of the frame. */
    public static void renderBreak() {
        if (ON) {
            renderBreaks++;
        }
    }

    /** A ring buffer had to wait for the GPU to free a region. */
    public static void ringWait(long nanos) {
        if (ON) {
            ringWaits++;
            ringWaitNanos += nanos;
        }
    }

    /** The game uploaded a texture (or part of one). */
    public static void textureUpload() {
        if (ON) {
            textureUploads++;
        }
    }

    /** A chunk layer's geometry went to the GPU. */
    public static void chunkUpload() {
        if (ON) {
            chunkUploads++;
        }
    }

    static void clear() {
        draws = 0;
        passes = 0;
        syncs = 0;
        renderBreaks = 0;
        ringWaits = 0;
        ringWaitNanos = 0L;
        textureUploads = 0;
        chunkUploads = 0;
    }
}
