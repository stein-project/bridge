/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

/**
 * Where a pass draws: our own color and depth textures ({@link SteinDevice#createTarget}) or the
 * target the game is already drawing to ({@link SteinDevice#gameTarget()}).
 *
 * <p>Pipeline: Stein GFX only.
 */
public abstract class SteinRenderTarget {

    public abstract int width();

    public abstract int height();

    /** Color texture {@code i}, or null (the game's target does not expose its own). */
    public abstract SteinTexture color(int i);

    public abstract SteinTexture depth();

    /** The backend id (OpenGL: the framebuffer; 0 = the screen). */
    public abstract long handle();

    public abstract void destroy();
}
