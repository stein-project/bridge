/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
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
    private static SteinVertexLayout layout;
    private static SteinPipelineCache pipelines;
    private static final Map<Integer, SteinPipeline> byKey = new HashMap<>();

    /** Loads the terrain shaders and builds the vertex layout and the pipeline cache. */
    static void create(SteinDevice dev) {
        // src/main/shaders/terrain.vert and terrain.frag (ALPHA_TEST: the variant with discard),
        // compiled in the build
        vertex = SteinShader.load(SteinShader.Stage.VERTEX, "terrain.vert");
        fragment = SteinShader.load(SteinShader.Stage.FRAGMENT, "terrain.frag");
        fragmentAlpha = SteinShader.load(SteinShader.Stage.FRAGMENT, "terrain_alpha.frag");
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

    static SteinPipeline pipeline(int key) {
        SteinPipeline p = byKey.get(key);
        if (p != null) {
            return p;
        }
        SteinPipeline.Builder b =
                SteinPipeline.builder("terrain " + Integer.toHexString(key))
                        .vertex(vertex)
                        .fragment((key & ALPHA_BIT) != 0 ? fragmentAlpha : fragment)
                        .layout(layout)
                        .texture("tex", 0)
                        .texture("lightmap", 1)
                        .uniforms("Frame", 0);
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
        byKey.put(key, p);
        return p;
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
