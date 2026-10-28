/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import dev.xavier.stein.bridge.core.Log;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.texture.TextureUtil;

import org.lwjgl.system.MemoryUtil;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * The opaque faces of the alpha-tested terrain layers ({@code terrainOpaqueCutout}): which atlas
 * textures have no transparent texel at any mipmap level, and which faces of a cutout chunk layer
 * sample only those, so they can draw before the rest without the alpha test.
 *
 * <p>The flag of each texture comes from the pixels the game uploads when it stitches the atlas
 * ({@link #uploadMipmap}, every level it generated), and for the animated ones from all their
 * frames; a face qualifies when its texture rectangle lies inside one flagged texture and its four
 * vertex colours are fully opaque. Without the discard such a face shades exactly the same: the
 * alpha it would test is 1 at every texel the filter can read.
 *
 * <p>Pipeline: Stein GFX only.
 */
public final class SteinOpaqueCutout {
    private SteinOpaqueCutout() {}

    static final boolean ENABLED = SteinGfxOptions.TERRAIN_OPAQUE_CUTOUT;

    /** The game's vertex (BLOCK format): color at 12, texture at 16 and 20. */
    private static final int GAME_VERTEX = 28;

    /** Lookup grids larger than this give up (an atlas of odd-sized textures): nothing moves. */
    private static final int MAX_CELLS = 1 << 22;

    /** The atlas as stitched: a grid of cells, each naming the texture that covers it. */
    private static final class Atlas {
        final int width;
        final int height;
        final int cell;
        final int columns;

        /** Per cell: the texture index plus one (0: no texture). */
        final int[] grid;

        /** Per texture: the rectangle in texture coordinates (u0, u1, v0, v1). */
        final float[] rects;

        final boolean[] opaque;

        Atlas(int width, int height, int cell, int[] grid, float[] rects, boolean[] opaque) {
            this.width = width;
            this.height = height;
            this.cell = cell;
            this.columns = width / cell;
            this.grid = grid;
            this.rects = rects;
            this.opaque = opaque;
        }
    }

    private static volatile Atlas atlas;

    /** While stitching: the opacity of each uploaded texture, by its origin in the atlas. */
    private static Map<Long, Boolean> stitched;

    // ---- the atlas (game thread, on every resource reload) ----

    /** Before the block atlas is stitched (TextureMap.loadTextureAtlas). */
    public static void beginStitch() {
        atlas = null; // the chunks built from now on wait for the new atlas
        stitched = ENABLED ? new HashMap<>() : null;
    }

    /**
     * In place of TextureUtil.uploadTextureMipmap in TextureMap.loadTextureAtlas: notes whether the
     * texture at {@code x, y} has only opaque texels at every level, then uploads it as before.
     */
    public static void uploadMipmap(
            Object data, int width, int height, int x, int y, boolean blur, boolean clamp) {
        int[][] levels = (int[][]) data;
        Map<Long, Boolean> s = stitched;
        if (s != null) {
            s.put(origin(x, y), opaque(levels));
        }
        TextureUtil.uploadTextureMipmap(levels, width, height, x, y, blur, clamp);
    }

    /** After the block atlas is stitched: builds the lookup from what {@link #uploadMipmap} saw. */
    public static void endStitch(Object mapObj) {
        Map<Long, Boolean> s = stitched;
        stitched = null;
        if (s == null) {
            return;
        }
        try {
            atlas = build((TextureMap) mapObj, s);
        } catch (RuntimeException e) {
            atlas = null;
            Log.error("gfx", "terrain: opaque cutout faces off (atlas not understood)", e);
        }
    }

    private static Atlas build(TextureMap map, Map<Long, Boolean> uploaded) {
        TextureAtlasSprite[] sprites =
                map.mapUploadedSprites.values().toArray(new TextureAtlasSprite[0]);
        int width = 0;
        int height = 0;
        int align = 0;
        for (TextureAtlasSprite t : sprites) {
            int w = t.getIconWidth();
            int h = t.getIconHeight();
            if (w <= 0 || h <= 0) {
                continue;
            }
            if (width == 0 && t.getMaxU() > t.getMinU() && t.getMaxV() > t.getMinV()) {
                // initSprite insets each side by 0.01 texel: the rectangle spans w - 0.02 texels.
                width = Math.round((w - 0.02f) / (t.getMaxU() - t.getMinU()));
                height = Math.round((h - 0.02f) / (t.getMaxV() - t.getMinV()));
            }
            align |= t.getOriginX() | t.getOriginY() | w | h;
        }
        if (width <= 0 || height <= 0 || align == 0) {
            return null;
        }
        int cell = Integer.lowestOneBit(align);
        long cells = (long) (width / cell) * (height / cell);
        if (width % cell != 0 || height % cell != 0 || cells > MAX_CELLS) {
            Log.info("gfx", "terrain: opaque cutout faces off (atlas grid too fine)");
            return null;
        }
        int[] grid = new int[(int) cells];
        float[] rects = new float[sprites.length * 4];
        boolean[] opaque = new boolean[sprites.length];
        int columns = width / cell;
        int flagged = 0;
        for (int i = 0; i < sprites.length; i++) {
            TextureAtlasSprite t = sprites[i];
            int ox = t.getOriginX();
            int oy = t.getOriginY();
            int w = t.getIconWidth();
            int h = t.getIconHeight();
            if (w <= 0 || h <= 0 || ox + w > width || oy + h > height) {
                continue;
            }
            rects[i * 4] = (float) ox / width;
            rects[i * 4 + 1] = (float) (ox + w) / width;
            rects[i * 4 + 2] = (float) oy / height;
            rects[i * 4 + 3] = (float) (oy + h) / height;
            opaque[i] = Boolean.TRUE.equals(uploaded.get(origin(ox, oy))) && allFramesOpaque(t);
            if (opaque[i]) {
                flagged++;
            }
            for (int cy = oy / cell; cy < (oy + h) / cell; cy++) {
                Arrays.fill(grid, cy * columns + ox / cell, cy * columns + (ox + w) / cell, i + 1);
            }
        }
        Log.info(
                "gfx",
                "terrain: opaque cutout faces on, %d of %d atlas textures opaque at every mipmap"
                        + " level (%dx%d)",
                flagged,
                sprites.length,
                width,
                height);
        return new Atlas(width, height, cell, grid, rects, opaque);
    }

    /** An animated texture: every frame (only the first one goes up when stitching). */
    private static boolean allFramesOpaque(TextureAtlasSprite t) {
        int frames = t.getFrameCount();
        for (int f = 1; f < frames; f++) {
            if (!opaque(t.getFrameTextureData(f))) {
                return false;
            }
        }
        return true;
    }

    private static boolean opaque(int[][] levels) {
        if (levels == null) {
            return false;
        }
        for (int[] level : levels) {
            if (level == null) {
                continue;
            }
            for (int argb : level) {
                if (argb >>> 24 != 0xFF) {
                    return false;
                }
            }
        }
        return true;
    }

    private static long origin(int x, int y) {
        return (long) x << 32 | (y & 0xFFFFFFFFL);
    }

    // ---- the faces (game thread, at upload) ----

    /** Per quad of the last {@link #classify}: 1 = opaque. */
    private static byte[] marks = new byte[4096];

    static long classified;
    static long moved;

    /** Whether the faces of a layer can be classified now (the atlas is understood). */
    static boolean ready() {
        return ENABLED && atlas != null;
    }

    /**
     * Marks the opaque quads among {@code count} game vertices at {@code src}; returns how many
     * vertices they hold. {@link #opaqueQuad} then answers per quad.
     */
    static int classify(long src, int count) {
        Atlas a = atlas;
        int quads = count / 4;
        if (a == null || quads * 4 != count) {
            return 0;
        }
        if (marks.length < quads) {
            marks = new byte[Math.max(quads, marks.length * 2)];
        }
        int opaque = 0;
        for (int q = 0; q < quads; q++) {
            boolean o = opaqueQuad(a, src + (long) q * 4 * GAME_VERTEX);
            marks[q] = (byte) (o ? 1 : 0);
            if (o) {
                opaque++;
            }
        }
        classified += count;
        moved += opaque * 4L;
        return opaque * 4;
    }

    static boolean opaqueQuad(int q) {
        return marks[q] != 0;
    }

    private static boolean opaqueQuad(Atlas a, long v) {
        float u0 = Float.POSITIVE_INFINITY;
        float u1 = Float.NEGATIVE_INFINITY;
        float v0 = Float.POSITIVE_INFINITY;
        float v1 = Float.NEGATIVE_INFINITY;
        for (int k = 0; k < 4; k++) {
            long p = v + (long) k * GAME_VERTEX;
            // RGBA bytes: the alpha is the top byte; below 1 the alpha test could discard
            if (MemoryUtil.memGetInt(p + 12) >>> 24 != 0xFF) {
                return false;
            }
            float u = MemoryUtil.memGetFloat(p + 16);
            float t = MemoryUtil.memGetFloat(p + 20);
            u0 = Math.min(u0, u);
            u1 = Math.max(u1, u);
            v0 = Math.min(v0, t);
            v1 = Math.max(v1, t);
        }
        int cx = (int) ((u0 + u1) * 0.5f * a.width) / a.cell;
        int cy = (int) ((v0 + v1) * 0.5f * a.height) / a.cell;
        if (cx < 0 || cy < 0 || cx >= a.columns || cy >= a.height / a.cell) {
            return false;
        }
        int i = a.grid[cy * a.columns + cx] - 1;
        if (i < 0 || !a.opaque[i]) {
            return false;
        }
        // the whole rectangle inside that texture (a hair of slack for the float coordinates)
        float e = 1e-5f;
        float[] r = a.rects;
        return u0 >= r[i * 4] - e
                && u1 <= r[i * 4 + 1] + e
                && v0 >= r[i * 4 + 2] - e
                && v1 <= r[i * 4 + 3] + e;
    }
}
