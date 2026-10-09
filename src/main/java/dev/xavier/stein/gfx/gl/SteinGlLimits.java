/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

package dev.xavier.stein.gfx.gl;

/**
 * The OpenGL pass limits, defined once for the immediate pass ({@link SteinGlCommands}), the
 * recorded pass ({@link SteinGlRecorder}) and the shared VAO ({@link SteinGlVao}).
 *
 * <p>Pipeline: Stein GFX only.
 */
final class SteinGlLimits {

    private SteinGlLimits() {}

    /** Vertex bindings per pass. */
    static final int MAX_VERTEX_BINDINGS = 16;

    /** Texture units the pass knows how to save (the mask is a long). */
    static final int MAX_TEXTURE_UNITS = 64;

    /** Uniform and storage binding points each, for the pass kept by the recorder. */
    static final int BUFFER_POINTS = 64;
}
