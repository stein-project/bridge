/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

package dev.xavier.stein.gfx;

import java.nio.ByteBuffer;

/**
 * A GPU texture: 2D or a 2D array (layers of the same size — the skins, the armor), color or depth,
 * with or without mipmaps. The size and the format are fixed at creation. How to sample (filter,
 * wrapping) belongs to the {@link SteinSampler}, not to the texture.
 *
 * <p>Pipeline: Stein GFX only.
 */
public abstract class SteinTexture {

    public enum Kind {
        TEXTURE_2D,
        TEXTURE_2D_ARRAY
    }

    public static final class Desc {
        public final String name;
        public final Kind kind;
        public final SteinFormat format;
        public final int width;
        public final int height;

        /** Layers (1 for {@link Kind#TEXTURE_2D}). */
        public final int layers;

        /** Mipmap levels (1 = none). */
        public final int mips;

        /** Can be a render target. */
        public final boolean renderTarget;

        public Desc(
                String name,
                Kind kind,
                SteinFormat format,
                int width,
                int height,
                int layers,
                int mips,
                boolean renderTarget) {
            this.name = name;
            this.kind = kind;
            this.format = format;
            this.width = width;
            this.height = height;
            this.layers = kind == Kind.TEXTURE_2D ? 1 : layers;
            this.mips = Math.max(1, mips);
            this.renderTarget = renderTarget;
        }

        public static Desc texture2D(String name, SteinFormat format, int w, int h, int mips) {
            return new Desc(name, Kind.TEXTURE_2D, format, w, h, 1, mips, false);
        }

        public static Desc array(
                String name, SteinFormat format, int w, int h, int layers, int mips) {
            return new Desc(name, Kind.TEXTURE_2D_ARRAY, format, w, h, layers, mips, false);
        }

        public static Desc target(String name, SteinFormat format, int w, int h) {
            return new Desc(name, Kind.TEXTURE_2D, format, w, h, 1, 1, true);
        }
    }

    protected final Desc desc;

    protected SteinTexture(Desc desc) {
        this.desc = desc;
    }

    public Desc desc() {
        return desc;
    }

    public int width() {
        return desc.width;
    }

    public int height() {
        return desc.height;
    }

    /** The backend id (OpenGL: the texture name). */
    public abstract long handle();

    /**
     * Writes RGBA8 pixels (or the texture's format) into a rectangle of one layer and level. {@code
     * pixels} holds {@code w * h * bytes} from its position up to its limit, row by row from top to
     * bottom as the game stores it.
     */
    public abstract void upload(
            int layer, int level, int x, int y, int w, int h, ByteBuffer pixels);

    /** Generates the mipmaps from level 0. */
    public abstract void generateMips();

    public abstract void destroy();
}
