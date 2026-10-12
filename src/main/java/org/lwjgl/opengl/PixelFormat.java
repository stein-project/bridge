/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package org.lwjgl.opengl;

/**
 * Framebuffer format requested when the context is created. Immutable: the {@code withX} methods
 * return a new instance, as in LWJGL 2.
 *
 * <p>Minecraft 1.8.9 uses {@code new PixelFormat().withDepthBits(24)}.
 */
public final class PixelFormat {

    private final int bpp;
    private final int alpha;
    private final int depth;
    private final int stencil;
    private final int samples;

    public PixelFormat() {
        this(0, 8, 0, 0, 0);
    }

    private PixelFormat(int bpp, int alpha, int depth, int stencil, int samples) {
        this.bpp = bpp;
        this.alpha = alpha;
        this.depth = depth;
        this.stencil = stencil;
        this.samples = samples;
    }

    public PixelFormat withDepthBits(int depth) {
        return new PixelFormat(bpp, alpha, depth, stencil, samples);
    }

    public PixelFormat withStencilBits(int stencil) {
        return new PixelFormat(bpp, alpha, depth, stencil, samples);
    }

    public PixelFormat withSamples(int samples) {
        return new PixelFormat(bpp, alpha, depth, stencil, samples);
    }

    public PixelFormat withAlphaBits(int alpha) {
        return new PixelFormat(bpp, alpha, depth, stencil, samples);
    }

    public int getBitsPerPixel() {
        return bpp;
    }

    public int getAlphaBits() {
        return alpha;
    }

    public int getDepthBits() {
        return depth;
    }

    public int getStencilBits() {
        return stencil;
    }

    public int getSamples() {
        return samples;
    }
}
