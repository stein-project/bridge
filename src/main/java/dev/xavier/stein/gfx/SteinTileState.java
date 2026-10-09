/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

package dev.xavier.stein.gfx;

import dev.xavier.stein.bridge.engine.GLMatrix;

import net.minecraft.client.renderer.GlStateManager;

/**
 * The GlStateManager state around a tile entity's renderer: the snapshot taken on entering and
 * leaving, the fields that can be brought back to the exit value, and the reapplication of that
 * exit state.
 *
 * <p>Pipeline: Stein GFX only.
 */
final class SteinTileState {
    private SteinTileState() {}

    static final int TEX0_ON = 0;
    static final int TEX0 = 1;
    static final int TEX1_ON = 2;
    static final int TEX1 = 3;
    static final int TEX2_ON = 4;
    static final int TEX2 = 5;
    static final int UNIT = 6;
    static final int LIGHTING = 7;
    static final int LIGHT0 = 8;
    static final int LIGHT1 = 9;
    static final int CMAT = 10;
    static final int NORMALIZE = 11;
    static final int RESCALE = 12;
    static final int BLEND = 13;
    static final int BSRC = 14;
    static final int BDST = 15;
    static final int BSRCA = 16;
    static final int BDSTA = 17;
    static final int BSEP = 18;
    static final int ALPHA = 19;
    static final int AFUNC = 20;
    static final int AREF = 21;
    static final int DEPTH = 22;
    static final int DMASK = 23;
    static final int DFUNC = 24;
    static final int FOG = 25;
    static final int FOG_MODE = 26;
    static final int FOG_DENSITY = 27;
    static final int FOG_START = 28;
    static final int FOG_END = 29;
    static final int CULL = 30;
    static final int CULL_MODE = 31;
    static final int OFFSET = 32;
    static final int OFFSET_F = 33;
    static final int OFFSET_U = 34;
    static final int TEXGEN = 35;
    static final int LOGIC = 36;
    static final int MASK = 37;
    static final int OVERLAY = 38;
    static final int CR = 39;
    static final int CG = 40;
    static final int CB = 41;
    static final int CA = 42;
    static final int LMU = 43;
    static final int LMV = 44;
    static final int NKNOWN = 45;
    static final int NX = 46;
    static final int NY = 47;
    static final int NZ = 48;
    static final int LCKNOWN = 49;
    static final int LR = 50;
    static final int LG = 51;
    static final int LB = 52;
    static final int LA = 53;
    static final int TEXMAT = 54;
    static final int STATE_FIELDS = 55;

    /** The game packs both lightmap coordinates in one int, 16 bits each. */
    static final int LIGHT_SPAN = 65536;

    /** Packed lightmap coordinate of a light value: block light in the low 16 bits. */
    static float lightmapU(int light) {
        return (float) (light % LIGHT_SPAN);
    }

    /** Packed lightmap coordinate of a light value: sky light in the high bits. */
    static float lightmapV(int light) {
        return (float) (light / LIGHT_SPAN);
    }

    static int bit(boolean v) {
        return v ? 1 : 0;
    }

    static int bits(float v) {
        return Float.floatToRawIntBits(v);
    }

    static float fromBits(int bits) {
        return Float.intBitsToFloat(bits);
    }

    /**
     * Everything the recorded drawing of a block, or the state it leaves behind, may depend on: the
     * GlStateManager cache (as SteinEntityDeferred reads it), the lightmap coordinates, the tracked
     * current normal, the last vertex colour (the "forgotten" colour) and whether the texture
     * matrix is the identity.
     */
    static void snapshot(int[] s) {
        s[TEX0_ON] = bit(SteinGameState.texture2D(0));
        s[TEX0] = SteinGameState.textureName(0);
        s[TEX1_ON] = bit(SteinGameState.texture2D(1));
        s[TEX1] = SteinGameState.textureName(1);
        s[TEX2_ON] = bit(SteinGameState.texture2D(2));
        s[TEX2] = SteinGameState.textureName(2);
        s[UNIT] = SteinGameState.activeUnit();
        s[LIGHTING] = bit(SteinGameState.lighting());
        s[LIGHT0] = bit(SteinGameState.light(0));
        s[LIGHT1] = bit(SteinGameState.light(1));
        s[CMAT] = bit(SteinGameState.colorMaterial());
        s[NORMALIZE] = bit(SteinGameState.normalize());
        s[RESCALE] = bit(SteinGameState.rescaleNormal());
        s[BLEND] = bit(SteinGameState.blend());
        s[BSRC] = SteinGameState.blendSrc();
        s[BDST] = SteinGameState.blendDst();
        s[BSRCA] = SteinGameState.blendSrcAlpha();
        s[BDSTA] = SteinGameState.blendDstAlpha();
        s[BSEP] = bit(SteinGlTrack.blendSeparate());
        s[ALPHA] = bit(SteinGameState.alphaTest());
        s[AFUNC] = SteinGameState.alphaFunc();
        s[AREF] = bits(SteinGameState.alphaRef());
        s[DEPTH] = bit(SteinGameState.depthTest());
        s[DMASK] = bit(SteinGameState.depthMask());
        s[DFUNC] = SteinGameState.depthFunc();
        s[FOG] = bit(SteinGameState.fog());
        s[FOG_MODE] = SteinGameState.fogMode();
        s[FOG_DENSITY] = bits(SteinGameState.fogDensity());
        s[FOG_START] = bits(SteinGameState.fogStart());
        s[FOG_END] = bits(SteinGameState.fogEnd());
        s[CULL] = bit(SteinGameState.cull());
        s[CULL_MODE] = SteinGameState.cullMode();
        s[OFFSET] = bit(SteinGameState.polygonOffsetFill());
        s[OFFSET_F] = bits(SteinGameState.polygonOffsetFactor());
        s[OFFSET_U] = bits(SteinGameState.polygonOffsetUnits());
        s[TEXGEN] =
                bit(SteinGameState.texGen(0))
                        | bit(SteinGameState.texGen(1)) << 1
                        | bit(SteinGameState.texGen(2)) << 2
                        | bit(SteinGameState.texGen(3)) << 3;
        s[LOGIC] = bit(SteinGameState.logicOp());
        s[MASK] =
                SteinGameState.colorMaskAll()
                        ? 0
                        : SteinGameState.colorMaskRgb()
                                ? 1
                                : SteinGameState.colorMaskNone() ? 2 : 3;
        s[OVERLAY] = bit(SteinGameState.overlay());
        s[CR] = bits(SteinGameState.colorR());
        s[CG] = bits(SteinGameState.colorG());
        s[CB] = bits(SteinGameState.colorB());
        s[CA] = bits(SteinGameState.colorA());
        s[LMU] = bits(SteinGameState.lightmapU());
        s[LMV] = bits(SteinGameState.lightmapV());
        boolean nk = SteinGlTrack.normalKnown();
        s[NKNOWN] = bit(nk);
        s[NX] = nk ? bits(SteinGlTrack.normalX()) : 0;
        s[NY] = nk ? bits(SteinGlTrack.normalY()) : 0;
        s[NZ] = nk ? bits(SteinGlTrack.normalZ()) : 0;
        boolean ck = SteinTessCapture.lastColorKnown;
        s[LCKNOWN] = bit(ck);
        s[LR] = ck ? bits(SteinTessCapture.lastR) : 0;
        s[LG] = ck ? bits(SteinTessCapture.lastG) : 0;
        s[LB] = ck ? bits(SteinTessCapture.lastB) : 0;
        s[LA] = ck ? bits(SteinTessCapture.lastA) : 0;
        s[TEXMAT] = bit(GLMatrix.textureOrNull() != null);
    }

    /**
     * Can field {@code i} be brought to the exit value by the GlStateManager's methods (or by the
     * tracking)?
     */
    static boolean settable(int i, int[] exit) {
        return switch (i) {
            case TEX0, TEX0_ON ->
                    exit[UNIT] == 0; // enabling the texture applies to the active unit
            case LIGHTING,
                    CMAT,
                    NORMALIZE,
                    RESCALE,
                    BLEND,
                    ALPHA,
                    AFUNC,
                    AREF,
                    DEPTH,
                    DMASK,
                    DFUNC,
                    FOG,
                    CULL,
                    CULL_MODE,
                    OFFSET,
                    CR,
                    CG,
                    CB,
                    CA,
                    LMU,
                    LMV,
                    NKNOWN,
                    NX,
                    NY,
                    NZ,
                    LCKNOWN,
                    LR,
                    LG,
                    LB,
                    LA ->
                    true;
            default -> false;
        };
    }

    /**
     * Takes the current state to the recorded exit state, only in the fields the renderer changed.
     */
    static void applyExit(int[] x, int[] diff) {
        boolean color = false;
        boolean alphaFunc = false;
        boolean lightmap = false;
        boolean normal = false;
        boolean lastColor = false;
        for (int i : diff) {
            switch (i) {
                case TEX0 -> GlStateManager.bindTexture(x[TEX0]);
                case TEX0_ON -> {
                    if (x[TEX0_ON] != 0) {
                        GlStateManager.enableTexture2D();
                    } else {
                        GlStateManager.disableTexture2D();
                    }
                }
                case LIGHTING -> {
                    if (x[i] != 0) {
                        GlStateManager.enableLighting();
                    } else {
                        GlStateManager.disableLighting();
                    }
                }
                case CMAT -> {
                    if (x[i] != 0) {
                        GlStateManager.enableColorMaterial();
                    } else {
                        GlStateManager.disableColorMaterial();
                    }
                }
                case NORMALIZE -> {
                    if (x[i] != 0) {
                        GlStateManager.enableNormalize();
                    } else {
                        GlStateManager.disableNormalize();
                    }
                }
                case RESCALE -> {
                    if (x[i] != 0) {
                        GlStateManager.enableRescaleNormal();
                    } else {
                        GlStateManager.disableRescaleNormal();
                    }
                }
                case BLEND -> {
                    if (x[i] != 0) {
                        GlStateManager.enableBlend();
                    } else {
                        GlStateManager.disableBlend();
                    }
                }
                case ALPHA -> {
                    if (x[i] != 0) {
                        GlStateManager.enableAlpha();
                    } else {
                        GlStateManager.disableAlpha();
                    }
                }
                case DEPTH -> {
                    if (x[i] != 0) {
                        GlStateManager.enableDepth();
                    } else {
                        GlStateManager.disableDepth();
                    }
                }
                case FOG -> {
                    if (x[i] != 0) {
                        GlStateManager.enableFog();
                    } else {
                        GlStateManager.disableFog();
                    }
                }
                case CULL -> {
                    if (x[i] != 0) {
                        GlStateManager.enableCull();
                    } else {
                        GlStateManager.disableCull();
                    }
                }
                case OFFSET -> {
                    if (x[i] != 0) {
                        GlStateManager.enablePolygonOffset();
                    } else {
                        GlStateManager.disablePolygonOffset();
                    }
                }
                case DMASK -> GlStateManager.depthMask(x[DMASK] != 0);
                case DFUNC -> GlStateManager.depthFunc(x[DFUNC]);
                case CULL_MODE -> GlStateManager.cullFace(x[CULL_MODE]);
                case AFUNC, AREF -> alphaFunc = true;
                case CR, CG, CB, CA -> color = true;
                case LMU, LMV -> lightmap = true;
                case NKNOWN, NX, NY, NZ -> normal = true;
                case LCKNOWN, LR, LG, LB, LA -> lastColor = true;
                default -> throw new IllegalStateException("field without a setter: " + i);
            }
        }
        if (alphaFunc) {
            GlStateManager.alphaFunc(x[AFUNC], fromBits(x[AREF]));
        }
        if (color) {
            GlStateManager.color(
                    fromBits(x[CR]), fromBits(x[CG]), fromBits(x[CB]), fromBits(x[CA]));
        }
        if (lightmap) {
            SteinGameState.setLightmap(fromBits(x[LMU]), fromBits(x[LMV]));
        }
        if (normal) {
            if (x[NKNOWN] != 0) {
                SteinLazyGl.glNormal3f(fromBits(x[NX]), fromBits(x[NY]), fromBits(x[NZ]));
            } else {
                SteinGlTrack.forgetNormal();
            }
        }
        // Last: the colour above records the last colour too.
        if (lastColor || color) {
            SteinTessCapture.lastColorKnown = x[LCKNOWN] != 0;
            if (x[LCKNOWN] != 0) {
                SteinTessCapture.lastR = fromBits(x[LR]);
                SteinTessCapture.lastG = fromBits(x[LG]);
                SteinTessCapture.lastB = fromBits(x[LB]);
                SteinTessCapture.lastA = fromBits(x[LA]);
            }
        }
    }
}
