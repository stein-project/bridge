/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx.gl;

import dev.xavier.stein.gfx.SteinCaps;

/**
 * The validations of a pass that the immediate pass ({@link SteinGlCommands}) and the recorded one
 * ({@link SteinGlRecorder}) share, with the same messages: the recorded pass raises the errors at
 * recording time, as the immediate one would.
 *
 * <p>Pipeline: Stein GFX only.
 */
final class SteinGlPassRules {

    private SteinGlPassRules() {}

    static void notEnded(boolean ended) {
        if (ended) {
            throw new IllegalStateException("pass already ended");
        }
    }

    static void vertexBinding(int index) {
        if (index < 0 || index >= SteinGlLimits.MAX_VERTEX_BINDINGS) {
            throw new IllegalArgumentException("vertex binding out of range: " + index);
        }
    }

    static void textureSlot(int slot) {
        if (slot < 0 || slot >= SteinGlLimits.MAX_TEXTURE_UNITS) {
            throw new IllegalArgumentException("texture slot out of range: " + slot);
        }
    }

    static void storageSupported(SteinCaps caps) {
        if (!caps.compute) {
            throw new UnsupportedOperationException("storage buffer needs GL 4.3");
        }
    }

    static void indirectSupported(SteinCaps caps) {
        if (!caps.multiDrawIndirect) {
            throw new UnsupportedOperationException("indirect draw needs GL 4.3");
        }
    }

    static void computeSupported(SteinCaps caps) {
        if (!caps.compute) {
            throw new UnsupportedOperationException("compute needs GL 4.3");
        }
    }

    /** A draw with a compute pipeline bound (no pipeline: no error, the draw is skipped). */
    static void notComputeBound(SteinGlPipeline p) {
        if (p != null && p.isCompute()) {
            throw new IllegalStateException("draw with a compute pipeline bound: " + p.desc().name);
        }
    }

    /** A dispatch with a graphics pipeline bound (no pipeline: no error). */
    static void notGraphicsBound(SteinGlPipeline p) {
        if (p != null && !p.isCompute()) {
            throw new IllegalStateException(
                    "dispatch with a graphics pipeline bound: " + p.desc().name);
        }
    }

    /** {@code call}: the name of the indexed draw ("drawIndexed", "drawIndexedIndirect"). */
    static void indexRequired(String call, int indexHandle, SteinGlPipeline p) {
        if (indexHandle == 0) {
            throw new IllegalStateException(call + " without an index buffer: " + p.desc().name);
        }
    }
}
