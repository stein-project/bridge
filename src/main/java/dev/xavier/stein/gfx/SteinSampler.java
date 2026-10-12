/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

/**
 * How to sample a texture: filter, mipmap, wrapping and anisotropy (separate from the texture, as
 * in Vulkan).
 *
 * <p>Pipeline: Stein GFX only.
 */
public abstract class SteinSampler {

    public enum Filter {
        NEAREST,
        LINEAR
    }

    public enum Mip {
        NONE,
        NEAREST,
        LINEAR
    }

    public enum Wrap {
        REPEAT,
        CLAMP
    }

    public static final class Desc {
        public final Filter min;
        public final Filter mag;
        public final Mip mip;
        public final Wrap wrapU;
        public final Wrap wrapV;

        /** 1 = no anisotropy. */
        public final float anisotropy;

        /** Depth comparison (shadow map). */
        public final boolean compare;

        public Desc(
                Filter min,
                Filter mag,
                Mip mip,
                Wrap wrapU,
                Wrap wrapV,
                float anisotropy,
                boolean compare) {
            this.min = min;
            this.mag = mag;
            this.mip = mip;
            this.wrapU = wrapU;
            this.wrapV = wrapV;
            this.anisotropy = anisotropy;
            this.compare = compare;
        }

        /** Per pixel, no mipmap, repeating: the one used by the game's textures. */
        public static Desc pixel() {
            return new Desc(
                    Filter.NEAREST, Filter.NEAREST, Mip.NONE, Wrap.REPEAT, Wrap.REPEAT, 1f, false);
        }

        public static Desc linearClamp() {
            return new Desc(
                    Filter.LINEAR, Filter.LINEAR, Mip.NONE, Wrap.CLAMP, Wrap.CLAMP, 1f, false);
        }
    }

    protected final Desc desc;

    protected SteinSampler(Desc desc) {
        this.desc = desc;
    }

    public Desc desc() {
        return desc;
    }

    public abstract long handle();

    public abstract void destroy();
}
