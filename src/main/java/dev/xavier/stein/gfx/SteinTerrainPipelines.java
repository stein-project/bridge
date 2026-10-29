/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import static dev.xavier.stein.gfx.SteinTerrain.ORIGIN_BYTES;
import static dev.xavier.stein.gfx.SteinTerrain.VERTEX;

import org.lwjgl.opengl.GL11;

import java.util.HashMap;
import java.util.Map;

/**
 * The terrain's shaders, vertex layout and pipelines, one pipeline per distinct state of the game's
 * GlStateManager (alpha test, blending, depth, culling) packed into an int key.
 *
 * <p>Pipeline: Stein GFX only.
 */
final class SteinTerrainPipelines {
    private SteinTerrainPipelines() {}

    // Layout of the state key (one pipeline per distinct value).
    private static final int ALPHA_BIT = 1;
    private static final int BLEND_BIT = 2;
    private static final int SRC_SHIFT = 2;
    private static final int DST_SHIFT = 6;
    private static final int SRC_ALPHA_SHIFT = 10;
    private static final int DST_ALPHA_SHIFT = 14;
    private static final int DEPTH_TEST_BIT = 1 << 18;
    private static final int FUNC_SHIFT = 19;
    private static final int MASK_BIT = 1 << 22;
    private static final int CULL_BIT = 1 << 23;
    private static final int BACK_BIT = 1 << 24;

    /** The solid layer's state: depth test LEQUAL with writes, back-face culling, no blending. */
    static final int DEFAULT_SOLID_KEY =
            DEPTH_TEST_BIT | 3 << FUNC_SHIFT | MASK_BIT | CULL_BIT | BACK_BIT;

    /** The cutout layer's state: the solid one with the alpha test. */
    static final int DEFAULT_CUTOUT_KEY = DEFAULT_SOLID_KEY | ALPHA_BIT;

    private static SteinShader vertex;
    private static SteinShader fragment;
    private static SteinShader fragmentAlpha;
    private static SteinShader vertexInvariant;
    private static SteinShader fragmentDepth;

    // the LENS variants (SteinLensLight): the same pixel with the Lens's light
    private static SteinShader vertexLens;
    private static SteinShader fragmentLens;
    private static SteinShader fragmentAlphaLens;
    private static SteinShader vertexInvariantLens;
    private static SteinVertexLayout layout;
    private static SteinPipelineCache pipelines;
    private static final Map<Integer, SteinPipeline> byKey = new HashMap<>();
    private static final Map<Integer, SteinPipeline> prepassByKey = new HashMap<>();
    private static final Map<Integer, SteinPipeline> afterPrepassByKey = new HashMap<>();
    private static final Map<Integer, SteinPipeline> opaqueByKey = new HashMap<>();
    private static final Map<Integer, SteinPipeline> lensByKey = new HashMap<>();
    private static final Map<Integer, SteinPipeline> lensAfterPrepassByKey = new HashMap<>();
    private static final Map<Integer, SteinPipeline> lensOpaqueByKey = new HashMap<>();

    /** The terrain's vertex layout (the shadow map draws the same geometry). */
    static SteinVertexLayout layout() {
        return layout;
    }

    /** Loads the terrain shaders and builds the vertex layout and the pipeline cache. */
    static void create(SteinDevice dev) {
        // src/main/shaders/terrain.vert and terrain.frag (ALPHA_TEST: the variant with discard),
        // compiled in the build
        vertex = SteinShader.load(SteinShader.Stage.VERTEX, "terrain.vert");
        fragment = SteinShader.load(SteinShader.Stage.FRAGMENT, "terrain.frag");
        fragmentAlpha = SteinShader.load(SteinShader.Stage.FRAGMENT, "terrain_alpha.frag");
        vertexLens = SteinShader.load(SteinShader.Stage.VERTEX, "terrain_lens.vert");
        fragmentLens = SteinShader.load(SteinShader.Stage.FRAGMENT, "terrain_lens.frag");
        fragmentAlphaLens = SteinShader.load(SteinShader.Stage.FRAGMENT, "terrain_alpha_lens.frag");
        if (SteinGfxOptions.TERRAIN_CUTOUT_PREPASS) {
            vertexInvariant = SteinShader.load(SteinShader.Stage.VERTEX, "terrain_inv.vert");
            fragmentDepth = SteinShader.load(SteinShader.Stage.FRAGMENT, "terrain_depth.frag");
            vertexInvariantLens =
                    SteinShader.load(SteinShader.Stage.VERTEX, "terrain_inv_lens.vert");
        }
        layout =
                SteinVertexLayout.builder()
                        .binding(0, VERTEX, false)
                        .attribute(0, 0, SteinFormat.USHORT4, 0)
                        .attribute(1, 0, SteinFormat.UBYTE4_NORM, 8)
                        .attribute(2, 0, SteinFormat.USHORT2, 12)
                        .binding(1, ORIGIN_BYTES, true)
                        .attribute(3, 1, SteinFormat.FLOAT4, 0)
                        .build();
        pipelines = new SteinPipelineCache(dev);
    }

    /**
     * Alpha test that discards (GREATER, and the rest treated like it; ALWAYS discards nothing).
     */
    static boolean alphaTest() {
        return SteinGameState.alphaTest() && SteinGameState.alphaFunc() != GL11.GL_ALWAYS;
    }

    private static final SteinPipeline.Depth[] DEPTHS = {
        SteinPipeline.Depth.NEVER,
        SteinPipeline.Depth.LESS,
        SteinPipeline.Depth.EQUAL,
        SteinPipeline.Depth.LEQUAL,
        SteinPipeline.Depth.GREATER,
        SteinPipeline.Depth.NOTEQUAL,
        SteinPipeline.Depth.GEQUAL,
        SteinPipeline.Depth.ALWAYS
    };

    static int stateKey() {
        boolean blend = SteinGameState.blend();
        int s = 0;
        int d = 0;
        int sa = 0;
        int da = 0;
        if (blend) {
            s = factor(SteinGameState.blendSrc());
            d = factor(SteinGameState.blendDst());
            sa = factor(SteinGameState.blendSrcAlpha());
            da = factor(SteinGameState.blendDstAlpha());
        }
        int func = SteinGameState.depthFunc() - GL11.GL_NEVER;
        boolean cull = SteinGameState.cull();
        return (alphaTest() ? ALPHA_BIT : 0)
                | (blend ? BLEND_BIT : 0)
                | s << SRC_SHIFT
                | d << DST_SHIFT
                | sa << SRC_ALPHA_SHIFT
                | da << DST_ALPHA_SHIFT
                | (SteinGameState.depthTest() ? DEPTH_TEST_BIT : 0)
                | (func & 7) << FUNC_SHIFT
                | (SteinGameState.depthMask() ? MASK_BIT : 0)
                | (cull ? CULL_BIT : 0)
                | (SteinGameState.cullMode() != GL11.GL_FRONT ? BACK_BIT : 0);
    }

    /** The textures the LENS variants read besides the game's (slots 2 to 6, SteinTerrain). */
    private static void lensTextures(SteinPipeline.Builder b) {
        b.texture("shadowMap", 2)
                .texture("casterMap", 3)
                .texture("glowMask", 4)
                .texture("shadowPrevMap", 5)
                .texture("cloudMap", 6);
    }

    /** The pipeline for {@code key}, in the LENS variant when {@code lens}. */
    static SteinPipeline pipeline(int key, boolean lens) {
        Map<Integer, SteinPipeline> cache = lens ? lensByKey : byKey;
        SteinPipeline p = cache.get(key);
        if (p != null) {
            return p;
        }
        boolean alpha = (key & ALPHA_BIT) != 0;
        SteinPipeline.Builder b =
                SteinPipeline.builder(
                                (lens ? "terrain lens " : "terrain ") + Integer.toHexString(key))
                        .vertex(lens ? vertexLens : vertex)
                        .fragment(
                                lens
                                        ? (alpha ? fragmentAlphaLens : fragmentLens)
                                        : (alpha ? fragmentAlpha : fragment))
                        .layout(layout)
                        .texture("tex", 0)
                        .texture("lightmap", 1)
                        .uniforms("Frame", 0);
        if (lens) {
            lensTextures(b);
        }
        if ((key & BLEND_BIT) != 0) {
            SteinPipeline.Factor[] f = SteinPipeline.Factor.values();
            b.blend(
                    f[key >>> SRC_SHIFT & 15],
                    f[key >>> DST_SHIFT & 15],
                    f[key >>> SRC_ALPHA_SHIFT & 15],
                    f[key >>> DST_ALPHA_SHIFT & 15]);
        }
        b.depth(
                (key & DEPTH_TEST_BIT) != 0 ? DEPTHS[key >>> FUNC_SHIFT & 7] : null,
                (key & MASK_BIT) != 0);
        b.cull(
                (key & CULL_BIT) == 0
                        ? SteinPipeline.Cull.NONE
                        : (key & BACK_BIT) != 0
                                ? SteinPipeline.Cull.BACK
                                : SteinPipeline.Cull.FRONT);
        p = pipelines.get(b.build());
        cache.put(key, p);
        return p;
    }

    /**
     * {@code key} without the alpha test: the state of the cutout faces whose texture is opaque
     * ({@link SteinOpaqueCutout}); the same shader without its discard.
     */
    static int withoutAlpha(int key) {
        return key & ~ALPHA_BIT;
    }

    /**
     * Whether a layer drawn with {@code key} may go out as a depth pre-pass plus a colour pass
     * ({@code terrainCutoutPrepass}): alpha test, no blending, depth test LEQUAL with writes. With
     * LEQUAL the pre-pass leaves the same depth the layer would; the colour pass with EQUAL and the
     * same discard then keeps, per pixel, the same last fragment at the nearest depth (two coplanar
     * quads, like the grass side and its overlay, still overwrite in draw order). LESS would keep
     * the first one instead, so it is left out.
     */
    static boolean prepassEligible(int key) {
        return fragmentDepth != null
                && (key & ALPHA_BIT) != 0
                && (key & BLEND_BIT) == 0
                && (key & DEPTH_TEST_BIT) != 0
                && (key & MASK_BIT) != 0
                && (key >>> FUNC_SHIFT & 7) == 3;
    }

    /** The pre-pass of {@code key}: depth only, with the alpha test (and its invariant vertex). */
    static SteinPipeline prepass(int key) {
        SteinPipeline p = prepassByKey.get(key);
        if (p == null) {
            p =
                    variant(
                            key,
                            "terrain depth ",
                            fragmentDepth,
                            false,
                            false,
                            true,
                            SteinPipeline.Depth.LEQUAL);
            prepassByKey.put(key, p);
        }
        return p;
    }

    /** The colour pass after {@link #prepass}: same discard, depth EQUAL without writes. */
    static SteinPipeline afterPrepass(int key, boolean lens) {
        Map<Integer, SteinPipeline> cache = lens ? lensAfterPrepassByKey : afterPrepassByKey;
        SteinPipeline p = cache.get(key);
        if (p == null) {
            p =
                    variant(
                            key,
                            lens ? "terrain lens colour " : "terrain colour ",
                            lens ? fragmentAlphaLens : fragmentAlpha,
                            lens,
                            true,
                            false,
                            SteinPipeline.Depth.EQUAL);
            cache.put(key, p);
        }
        return p;
    }

    /**
     * The faces without the alpha test ({@link SteinOpaqueCutout}) of a layer drawn with {@code
     * key}: the state without the alpha test; on a layer with the pre-pass, with the pre-pass's
     * invariant vertex, so that a face and the cutout face in the same plane (the grass side and
     * its overlay) keep exactly the same depth.
     */
    static SteinPipeline opaqueFaces(int key, boolean lens) {
        if (!prepassEligible(key)) {
            return pipeline(withoutAlpha(key), lens);
        }
        Map<Integer, SteinPipeline> cache = lens ? lensOpaqueByKey : opaqueByKey;
        SteinPipeline p = cache.get(key);
        if (p == null) {
            p =
                    variant(
                            key,
                            lens ? "terrain lens opaque " : "terrain opaque ",
                            lens ? fragmentLens : fragment,
                            lens,
                            true,
                            true,
                            SteinPipeline.Depth.LEQUAL);
            cache.put(key, p);
        }
        return p;
    }

    private static SteinPipeline variant(
            int key,
            String name,
            SteinShader frag,
            boolean lens,
            boolean color,
            boolean write,
            SteinPipeline.Depth depth) {
        SteinPipeline.Builder b =
                SteinPipeline.builder(name + Integer.toHexString(key))
                        .vertex(lens ? vertexInvariantLens : vertexInvariant)
                        .fragment(frag)
                        .layout(layout)
                        .texture("tex", 0)
                        .texture("lightmap", 1)
                        .uniforms("Frame", 0)
                        .depth(depth, write)
                        .cull(
                                (key & CULL_BIT) == 0
                                        ? SteinPipeline.Cull.NONE
                                        : (key & BACK_BIT) != 0
                                                ? SteinPipeline.Cull.BACK
                                                : SteinPipeline.Cull.FRONT);
        if (!color) {
            b.colorWrite(false);
        }
        if (lens) {
            lensTextures(b);
        }
        return pipelines.get(b.build());
    }

    /** OpenGL blend factor for the index in {@link SteinPipeline.Factor}. */
    private static int factor(int gl) {
        SteinPipeline.Factor f =
                switch (gl) {
                    case GL11.GL_ZERO -> SteinPipeline.Factor.ZERO;
                    case GL11.GL_SRC_COLOR -> SteinPipeline.Factor.SRC_COLOR;
                    case GL11.GL_ONE_MINUS_SRC_COLOR -> SteinPipeline.Factor.ONE_MINUS_SRC_COLOR;
                    case GL11.GL_DST_COLOR -> SteinPipeline.Factor.DST_COLOR;
                    case GL11.GL_ONE_MINUS_DST_COLOR -> SteinPipeline.Factor.ONE_MINUS_DST_COLOR;
                    case GL11.GL_SRC_ALPHA -> SteinPipeline.Factor.SRC_ALPHA;
                    case GL11.GL_ONE_MINUS_SRC_ALPHA -> SteinPipeline.Factor.ONE_MINUS_SRC_ALPHA;
                    case GL11.GL_DST_ALPHA -> SteinPipeline.Factor.DST_ALPHA;
                    case GL11.GL_ONE_MINUS_DST_ALPHA -> SteinPipeline.Factor.ONE_MINUS_DST_ALPHA;
                    case GL11.GL_SRC_ALPHA_SATURATE -> SteinPipeline.Factor.SRC_ALPHA_SATURATE;
                    default -> SteinPipeline.Factor.ONE;
                };
        return f.ordinal();
    }
}
