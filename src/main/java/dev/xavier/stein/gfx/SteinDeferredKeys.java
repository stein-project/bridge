/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import org.lwjgl.opengl.GL11;

/**
 * The group key of the deferred entities ({@link SteinEntityDeferred}): the per-instance option
 * flags, the bit layout of the 64-bit key and the functions that build and decode it.
 *
 * <p>Pipeline: Stein GFX only.
 */
final class SteinDeferredKeys {

    static final int FLAG_LIGHTING = 1;
    static final int FLAG_LIGHTMAP = 2;
    static final int FLAG_FOG = 4;

    /** Dynamic geometry: vertex colour, vertex lightmap, no texture (texture2D disabled). */
    static final int FLAG_VCOLOR = 8;

    static final int FLAG_VLIGHT = 16;
    static final int FLAG_NOTEX = 32;

    /**
     * The damage red / creeper white (setBrightness) is on. It also counts as on when the overlay
     * weight is greater than zero (SteinPlayerPose writes only the colour and the weight).
     */
    static final int FLAG_OVERLAY = 64;

    /**
     * The eye-space normal as the fixed-function pipeline would make it: no bit, normalised
     * (GL_NORMALIZE, or GL_RESCALE_NORMAL with a unit normal); NRESCALE only removes the matrix
     * scale and keeps the normal's length (GL_RESCALE_NORMAL without GL_NORMALIZE: the sign text
     * and the arrow deliberately send a short normal); NRAW neither (the matrix scale changes the
     * strength of the light).
     */
    static final int FLAG_NRESCALE = 128;

    static final int FLAG_NRAW = 256;

    /**
     * Dynamic without a per-vertex normal: the current normal (glNormal3f), in the instance in
     * place of the overlay.
     */
    static final int FLAG_CNORMAL = 512;

    /** Dynamic geometry in the group key (its format and shader). */
    static final long KEY_DYNAMIC = 1L << 28;

    /** Dynamic from the fixed item buffer (otherwise, from the frame ring). */
    static final long KEY_STATIC = 1L << 29;

    /** Lines (fishing rod, F3+B boxes): line pipeline, dynamic geometry as a segment list. */
    static final long KEY_LINES = 1L << 30;

    /**
     * A model part with the game's texture bound directly (outside the array: larger than 512, a
     * format that does not fit, full classes): shader variant with sampler2D, the texture in the
     * upper bits of the key as in the dynamic case.
     */
    static final long KEY_GAMETEX = 1L << 31;

    /** No colour write (mask fully off): the pipeline writes only depth. */
    static final long KEY_NOCOLOR = 1L << 60;

    /** RGB only (alpha mask off): the menu panorama. */
    static final long KEY_NOALPHA = 1L << 61;

    /**
     * Name text and background resident on the GPU ({@link SteinTextGpu}): another buffer, format
     * and vertex shader.
     */
    static final long KEY_TEXT = 1L << 58;

    /**
     * Polygon offset (the cracks of a block being broken, TNT flashing): index + 1 in a small
     * table.
     */
    static final int KEY_OFFSET_SHIFT = 56;

    /** The game's texture in the key: bits 36..55. */
    static final int MAX_KEY_TEXTURE = 1 << 20;

    // ---- layout of the key ----
    // Low 32 bits (an int, see key()): bits 0..3 texture class; 4 blend; 5..20 the four blend
    // factors (4 bits each); 21 depth test; 22..24 depth function; 25 depth write; 26 cull;
    // 27 culled face (0 = front, 1 = back). Bits 28..31: KEY_DYNAMIC, KEY_STATIC, KEY_LINES,
    // KEY_GAMETEX. Upper bits (a long): 32..35 the texture filter; 36..55 the game's texture;
    // 56..57 polygon offset; 58 KEY_TEXT; 60 KEY_NOCOLOR; 61 KEY_NOALPHA.

    static final int KEY_CLASS_MASK = 15;
    static final int KEY_BLEND_BIT = 1 << 4;
    static final int KEY_SRC_SHIFT = 5;
    static final int KEY_DST_SHIFT = 9;
    static final int KEY_SRC_ALPHA_SHIFT = 13;
    static final int KEY_DST_ALPHA_SHIFT = 17;
    static final int KEY_DEPTH_TEST_BIT = 1 << 21;
    static final int KEY_DEPTH_FUNC_SHIFT = 22;
    static final int KEY_DEPTH_MASK_BIT = 1 << 25;
    static final int KEY_CULL_BIT = 1 << 26;
    static final int KEY_FACE_SHIFT = 27;
    static final int KEY_FILTER_SHIFT = 32;
    static final int KEY_TEXTURE_SHIFT = 36;

    /** Indices into {@link #FACTORS} of the blend factors used by name. */
    static final int FACTOR_ZERO = 0;

    static final int FACTOR_ONE = 1;
    static final int FACTOR_SRC_ALPHA = 6;
    static final int FACTOR_ONE_MINUS_SRC_ALPHA = 7;

    /** Index into {@link #DEPTHS} of LEQUAL (the game's usual depth function). */
    static final int DEPTH_LEQUAL = 3;

    /** The blend indices of {@link #OWN_BLEND_KEY} (see {@link #blendIndices}). */
    static final int OWN_BLEND_INDICES =
            FACTOR_SRC_ALPHA
                    | FACTOR_ONE_MINUS_SRC_ALPHA << 4
                    | FACTOR_ONE << 8
                    | FACTOR_ZERO << 12;

    // ---- group key: texture class, blend, depth, face ----

    static int factorIndex(int gl) {
        return switch (gl) {
            case GL11.GL_ZERO -> 0;
            case GL11.GL_ONE -> 1;
            case GL11.GL_SRC_COLOR -> 2;
            case GL11.GL_ONE_MINUS_SRC_COLOR -> 3;
            case GL11.GL_DST_COLOR -> 4;
            case GL11.GL_ONE_MINUS_DST_COLOR -> 5;
            case GL11.GL_SRC_ALPHA -> 6;
            case GL11.GL_ONE_MINUS_SRC_ALPHA -> 7;
            case GL11.GL_DST_ALPHA -> 8;
            case GL11.GL_ONE_MINUS_DST_ALPHA -> 9;
            case GL11.GL_SRC_ALPHA_SATURATE -> 14;
            default -> -1;
        };
    }

    static final SteinPipeline.Factor[] FACTORS = {
        SteinPipeline.Factor.ZERO,
        SteinPipeline.Factor.ONE,
        SteinPipeline.Factor.SRC_COLOR,
        SteinPipeline.Factor.ONE_MINUS_SRC_COLOR,
        SteinPipeline.Factor.DST_COLOR,
        SteinPipeline.Factor.ONE_MINUS_DST_COLOR,
        SteinPipeline.Factor.SRC_ALPHA,
        SteinPipeline.Factor.ONE_MINUS_SRC_ALPHA,
        SteinPipeline.Factor.DST_ALPHA,
        SteinPipeline.Factor.ONE_MINUS_DST_ALPHA,
        null,
        null,
        null,
        null,
        SteinPipeline.Factor.SRC_ALPHA_SATURATE
    };

    static final SteinPipeline.Depth[] DEPTHS = {
        SteinPipeline.Depth.NEVER, SteinPipeline.Depth.LESS, SteinPipeline.Depth.EQUAL,
        SteinPipeline.Depth.LEQUAL, SteinPipeline.Depth.GREATER, SteinPipeline.Depth.NOTEQUAL,
        SteinPipeline.Depth.GEQUAL, SteinPipeline.Depth.ALWAYS
    };

    /** The key of the current state, or -1 if there is something the pipeline cannot represent. */
    static int stateKey(int klass) {
        return stateKey(klass, false);
    }

    /**
     * {@code ownBlend}: the blend that the Stein font turns on only for its own drawing (SRC_ALPHA,
     * ONE_MINUS_SRC_ALPHA, ONE, ZERO), in place of the game's.
     */
    static int stateKey(int klass, boolean ownBlend) {
        boolean blend = ownBlend || SteinGameState.blend();
        int blendIndices = 0;
        if (ownBlend) {
            blendIndices = OWN_BLEND_INDICES;
        } else if (blend) {
            blendIndices =
                    blendIndices(
                            SteinGameState.blendSrc(),
                            SteinGameState.blendDst(),
                            SteinGameState.blendSrcAlpha(),
                            SteinGameState.blendDstAlpha());
            if (blendIndices < 0) {
                return -1;
            }
        }
        boolean depthTest = SteinGameState.depthTest();
        int func = SteinGameState.depthFunc() - GL11.GL_NEVER;
        if (func < 0 || func > 7) {
            return -1;
        }
        boolean cull = SteinGameState.cull();
        int face = 1;
        if (cull) {
            int mode = SteinGameState.cullMode();
            if (mode == GL11.GL_FRONT) {
                face = 0;
            } else if (mode != GL11.GL_BACK) {
                return -1;
            }
        }
        return key(
                klass,
                blend,
                blendIndices,
                depthTest,
                func,
                SteinGameState.depthMask(),
                cull,
                face);
    }

    /**
     * The four blend factors (GL values: source and destination, colour and alpha) as indices into
     * {@link #FACTORS}, four bits each (source in the low bits); -1 if one cannot be represented.
     */
    static int blendIndices(int src, int dst, int srcA, int dstA) {
        int s = factorIndex(src);
        int d = factorIndex(dst);
        int sa = factorIndex(srcA);
        int da = factorIndex(dstA);
        if (s < 0 || d < 0 || sa < 0 || da < 0) {
            return -1;
        }
        return s | d << 4 | sa << 8 | da << 12;
    }

    /** Packs the state into the low 32 bits of the key ({@code blendIndices}: see above). */
    static int key(
            int klass,
            boolean blend,
            int blendIndices,
            boolean depthTest,
            int func,
            boolean mask,
            boolean cull,
            int face) {
        return klass
                | (blend ? KEY_BLEND_BIT : 0)
                | (blendIndices & 15) << KEY_SRC_SHIFT
                | (blendIndices >>> 4 & 15) << KEY_DST_SHIFT
                | (blendIndices >>> 8 & 15) << KEY_SRC_ALPHA_SHIFT
                | (blendIndices >>> 12 & 15) << KEY_DST_ALPHA_SHIFT
                | (depthTest ? KEY_DEPTH_TEST_BIT : 0)
                | func << KEY_DEPTH_FUNC_SHIFT
                | (mask ? KEY_DEPTH_MASK_BIT : 0)
                | (cull ? KEY_CULL_BIT : 0)
                | face << KEY_FACE_SHIFT;
    }

    /** The most common group: no blend, depth test LEQUAL with write, back faces culled. */
    static final int OPAQUE_KEY = key(0, false, 0, true, DEPTH_LEQUAL, true, true, 1);

    /**
     * The blend that the Stein font turns on for its own drawing (SRC_ALPHA, ONE_MINUS_SRC_ALPHA,
     * ONE, ZERO), with the depth test and cull of {@link #OPAQUE_KEY}.
     */
    static final int OWN_BLEND_KEY =
            key(0, true, OWN_BLEND_INDICES, true, DEPTH_LEQUAL, true, true, 1);

    // ---- reading the key ----

    static boolean keyBlend(int key) {
        return (key & KEY_BLEND_BIT) != 0;
    }

    /** The blend factor at {@code shift} (one of the {@code KEY_*_SHIFT}). */
    static SteinPipeline.Factor keyFactor(int key, int shift) {
        return FACTORS[key >>> shift & 15];
    }

    static boolean keyDepthTest(int key) {
        return (key & KEY_DEPTH_TEST_BIT) != 0;
    }

    static SteinPipeline.Depth keyDepthFunc(int key) {
        return DEPTHS[key >>> KEY_DEPTH_FUNC_SHIFT & 7];
    }

    static boolean keyDepthMask(int key) {
        return (key & KEY_DEPTH_MASK_BIT) != 0;
    }

    static boolean keyCull(int key) {
        return (key & KEY_CULL_BIT) != 0;
    }

    /** True if the culled face is the back one (otherwise the front). */
    static boolean keyCullBack(int key) {
        return (key >>> KEY_FACE_SHIFT & 1) != 0;
    }

    /** The texture class (the array of the texture, 0..15). */
    static int keyClass(long key) {
        return (int) (key & KEY_CLASS_MASK);
    }

    /** The game texture in the key (dynamic geometry, or a model part with its own texture). */
    static int keyTexture(long key) {
        return (int) (key >>> KEY_TEXTURE_SHIFT) & (MAX_KEY_TEXTURE - 1);
    }

    /** The texture filter code at the time of recording. */
    static int keyFilter(long key) {
        return (int) (key >>> KEY_FILTER_SHIFT & 15);
    }

    static long textureBits(int tex) {
        return (long) tex << KEY_TEXTURE_SHIFT;
    }

    static long filterBits(int filter) {
        return (long) filter << KEY_FILTER_SHIFT;
    }

    /** The bits of the key that hold the game texture and its filter. */
    static final long KEY_TEXTURE_BITS =
            (long) (MAX_KEY_TEXTURE - 1) << KEY_TEXTURE_SHIFT | 15L << KEY_FILTER_SHIFT;

    /**
     * The key of a group with the given state (blend factors and depth function as GL values),
     * always without cull (the game's doRender turns it off). -1 if something does not fit in a
     * pipeline.
     */
    static int poseKey(
            int klass,
            boolean blend,
            int src,
            int dst,
            int srcA,
            int dstA,
            boolean depthTest,
            int depthFunc,
            boolean depthMask) {
        int blendIndices = 0;
        if (blend) {
            blendIndices = blendIndices(src, dst, srcA, dstA);
            if (blendIndices < 0) {
                return -1;
            }
        }
        int func = depthFunc - GL11.GL_NEVER;
        if (func < 0 || func > 7 || klass < 0 || klass > 15) {
            return -1;
        }
        return key(klass, blend, blendIndices, depthTest, func, depthMask, false, 1);
    }

    /** The instance's options (the float in light.z): entity lighting, lightmap, fog. */
    static int poseFlags(boolean lighting, boolean lightmap, boolean fog) {
        return (lighting ? FLAG_LIGHTING : 0)
                | (lightmap ? FLAG_LIGHTMAP : 0)
                | (fog ? FLAG_FOG : 0);
    }
}
