/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

package dev.xavier.stein.gfx.gl;

import java.util.Arrays;

/**
 * A Stein VAO and what is attached to it, tracked in Java ({@code -Dsteinbridge.gfx.bindCache}).
 * Pipelines with the same layout share the VAO ({@link SteinGlDevice#acquireVao}): switching
 * between them neither changes VAO nor rebinds the buffers. The vertex buffers (buffer, offset,
 * stride per binding, on the GL 4.3 path) and the index buffer are VAO state and persist between
 * passes: what is already attached is not sent to the driver again.
 *
 * <p>Only Stein binds its VAOs, so the tracked state is exact. A deleted buffer stays attached to a
 * VAO that is not bound, and its name can be reused for another buffer: every deleted buffer
 * advances {@link SteinGlBuffer#generation}, and tracked state with an old generation is dropped.
 *
 * <p>Pipeline: Stein GFX only.
 */
final class SteinGlVao {

    static final int MAX_BINDINGS = SteinGlLimits.MAX_VERTEX_BINDINGS;

    final int id;
    int refs;
    private int generation = -1;
    private final int[] boundBuffer = new int[MAX_BINDINGS];
    private final long[] boundOffset = new long[MAX_BINDINGS];
    private final int[] boundStride = new int[MAX_BINDINGS];
    private int index;

    SteinGlVao(int id) {
        this.id = id;
    }

    private void dropStaleState() {
        if (generation != SteinGlBuffer.generation) {
            generation = SteinGlBuffer.generation;
            Arrays.fill(boundBuffer, 0);
            Arrays.fill(boundOffset, 0L);
            Arrays.fill(boundStride, 0);
            index = 0;
        }
    }

    /** Is the vertex buffer already attached this way? If not, records it (the caller binds). */
    boolean vertexBound(int binding, int handle, long offset, int stride) {
        dropStaleState();
        if (boundBuffer[binding] == handle
                && boundOffset[binding] == offset
                && boundStride[binding] == stride) {
            return true;
        }
        boundBuffer[binding] = handle;
        boundOffset[binding] = offset;
        boundStride[binding] = stride;
        return false;
    }

    /** Is the index buffer already attached? If not, records it (the caller binds). */
    boolean indexBound(int handle) {
        dropStaleState();
        if (index == handle) {
            return true;
        }
        index = handle;
        return false;
    }
}
