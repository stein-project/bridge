/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package org.lwjgl.opengl;

import java.util.Objects;

/** Video mode. Equivalent of the LWJGL 2 class; in LWJGL 3 this comes from GLFWVidMode. */
public final class DisplayMode {

    private final int width;
    private final int height;
    private final int bpp;
    private final int freq;
    private final boolean fullscreenCapable;

    /**
     * Windowed-only mode — this is the constructor Minecraft uses when applying the resolution
     * saved in the options.
     */
    public DisplayMode(int width, int height) {
        this(width, height, 0, 0, false);
    }

    DisplayMode(int width, int height, int bpp, int freq, boolean fullscreenCapable) {
        this.width = width;
        this.height = height;
        this.bpp = bpp;
        this.freq = freq;
        this.fullscreenCapable = fullscreenCapable;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public int getBitsPerPixel() {
        return bpp;
    }

    public int getFrequency() {
        return freq;
    }

    public boolean isFullscreenCapable() {
        return fullscreenCapable;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof DisplayMode d
                && width == d.width
                && height == d.height
                && bpp == d.bpp
                && freq == d.freq;
    }

    @Override
    public int hashCode() {
        return Objects.hash(width, height, bpp, freq);
    }

    @Override
    public String toString() {
        return width + " x " + height + " x " + bpp + " @" + freq + "Hz";
    }
}
