/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import static java.lang.invoke.MethodType.methodType;

import dev.xavier.stein.bridge.engine.Obf;

import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.entity.RendererLivingEntity;
import net.minecraft.entity.EntityLivingBase;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Array;
import java.nio.FloatBuffer;

/**
 * The game's drawing state, read from the GlStateManager (bfl) cache without asking the driver: the
 * new renderer records the drawing for later, so it needs to know what state the game would have
 * drawn it in. Read-only: nothing here changes the game.
 *
 * <p>The GlStateManager state objects are fixed (created once), so they are kept at startup and
 * read field by field on every query. The state classes are nested and closed in the game
 * (GlStateManager$BooleanState and the like are package-private), which the compile jar does not
 * open, so those reads stay reflective. Each accessor invokes its own static final handle on its
 * own static final object: the JIT folds that into a plain field read even when the accessor is
 * compiled on its own (not inlined into a large caller such as the pose collection). A shared
 * helper taking the handle as a parameter only folds when inlined, and otherwise pays a generic
 * method-handle call per read, which the crowd profile showed in the pose's colour reads.
 *
 * <p>Pipeline: Stein GFX only.
 */
public final class SteinGameState {

    private SteinGameState() {}

    private static final MethodHandle BOOL; // bfl$c.b: enabled
    private static final Object ALPHA; // bfl$a: test (a), function (b), reference (c)
    private static final MethodHandle ALPHA_TEST;
    private static final MethodHandle ALPHA_FUNC;
    private static final MethodHandle ALPHA_REF;
    private static final Object LIGHTING; // bfl$c
    private static final Object[] LIGHTS; // bfl.c: bfl$c[] (GL_LIGHT0..7)
    private static final Object NORMALIZE; // bfl.n: bfl$c
    private static final Object RESCALE; // bfl.r: bfl$c
    private static final Object COLOR_MATERIAL; // bfl$h.a
    private static final Object BLEND; // bfl$b: enable (a), src (b), dst (c), srcA (d), dstA (e)
    private static final MethodHandle BLEND_ON;
    private static final MethodHandle BLEND_SRC;
    private static final MethodHandle BLEND_DST;
    private static final MethodHandle BLEND_SRC_A;
    private static final MethodHandle BLEND_DST_A;
    private static final Object DEPTH; // bfl$j: test (a), write (b), function (c)
    private static final MethodHandle DEPTH_TEST;
    private static final MethodHandle DEPTH_MASK;
    private static final MethodHandle DEPTH_FUNC;
    private static final Object FOG; // bfl$k: enable (a), mode (b), density (c), start (d), end (e)
    private static final MethodHandle FOG_ON;
    private static final MethodHandle FOG_MODE;
    private static final MethodHandle FOG_DENSITY;
    private static final MethodHandle FOG_START;
    private static final MethodHandle FOG_END;
    private static final Object CULL; // bfl$i: enable (a), face (b)
    private static final MethodHandle CULL_ON;
    private static final MethodHandle CULL_MODE;
    private static final Object POLYGON_OFFSET; // bfl$l: fill (a)
    private static final MethodHandle OFFSET_FILL;
    private static final MethodHandle OFFSET_FACTOR; // bfl$l.c
    private static final MethodHandle OFFSET_UNITS; // bfl$l.d
    private static final Object[] TEXGEN_ON; // bfl.k (bfl$q): s, t, r, q -> bfl$p.a (bfl$c)
    private static final Object[] TEXGEN; // the bfl$p (mode in c)
    private static final MethodHandle TEXGEN_MODE;
    private static final Object LOGIC; // bfl$f.a
    private static final Object LOGIC_STATE; // bfl$f: enable (a), operation (b)
    private static final MethodHandle LOGIC_MODE;
    private static final Object[] TEXTURES; // bfl$r[]: bound 2D texture (a), name (b)
    private static final MethodHandle TEX_ON;
    private static final MethodHandle TEX_NAME;
    private static final Object MASK; // bfl$g: r, g, b, a
    private static final MethodHandle MASK_R;
    private static final MethodHandle MASK_G;
    private static final MethodHandle MASK_B;
    private static final MethodHandle MASK_A;
    private static final Object COLOR; // bfl$e: r, g, b, a
    private static final MethodHandle COLOR_R;
    private static final MethodHandle COLOR_G;
    private static final MethodHandle COLOR_B;
    private static final MethodHandle COLOR_A;
    private static final int LIGHTMAP_UNIT = OpenGlHelper.lightmapTexUnit;

    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            Class<?> gsm = Obf.cls("bfl");
            MethodHandles.Lookup g = MethodHandles.privateLookupIn(gsm, l);
            Class<?> bool = Obf.cls("bfl$c");
            BOOL = getter(l, bool, "b", boolean.class);

            Class<?> alpha = Obf.cls("bfl$a");
            ALPHA = g.findStaticGetter(gsm, "a", alpha).invoke();
            ALPHA_TEST = getter(l, alpha, "a", bool);
            ALPHA_FUNC = getter(l, alpha, "b", int.class);
            ALPHA_REF = getter(l, alpha, "c", float.class);

            LIGHTING = g.findStaticGetter(gsm, "b", bool).invoke();
            Object lights = g.findStaticGetter(gsm, "c", bool.arrayType()).invoke();
            LIGHTS = toArray(lights);
            NORMALIZE = g.findStaticGetter(gsm, "n", bool).invoke();
            RESCALE = g.findStaticGetter(gsm, "r", bool).invoke();
            Class<?> colorMaterial = Obf.cls("bfl$h");
            COLOR_MATERIAL =
                    getter(l, colorMaterial, "a", bool)
                            .invoke(g.findStaticGetter(gsm, "d", colorMaterial).invoke());

            Class<?> blend = Obf.cls("bfl$b");
            BLEND = g.findStaticGetter(gsm, "e", blend).invoke();
            BLEND_ON = getter(l, blend, "a", bool);
            BLEND_SRC = getter(l, blend, "b", int.class);
            BLEND_DST = getter(l, blend, "c", int.class);
            BLEND_SRC_A = getter(l, blend, "d", int.class);
            BLEND_DST_A = getter(l, blend, "e", int.class);

            Class<?> depth = Obf.cls("bfl$j");
            DEPTH = g.findStaticGetter(gsm, "f", depth).invoke();
            DEPTH_TEST = getter(l, depth, "a", bool);
            DEPTH_MASK = getter(l, depth, "b", boolean.class);
            DEPTH_FUNC = getter(l, depth, "c", int.class);

            Class<?> fog = Obf.cls("bfl$k");
            FOG = g.findStaticGetter(gsm, "g", fog).invoke();
            FOG_ON = getter(l, fog, "a", bool);
            FOG_MODE = getter(l, fog, "b", int.class);
            FOG_DENSITY = getter(l, fog, "c", float.class);
            FOG_START = getter(l, fog, "d", float.class);
            FOG_END = getter(l, fog, "e", float.class);

            Class<?> cull = Obf.cls("bfl$i");
            CULL = g.findStaticGetter(gsm, "h", cull).invoke();
            CULL_ON = getter(l, cull, "a", bool);
            CULL_MODE = getter(l, cull, "b", int.class);

            Class<?> offset = Obf.cls("bfl$l");
            POLYGON_OFFSET = g.findStaticGetter(gsm, "i", offset).invoke();
            OFFSET_FILL = getter(l, offset, "a", bool);
            OFFSET_FACTOR = getter(l, offset, "c", float.class);
            OFFSET_UNITS = getter(l, offset, "d", float.class);

            Class<?> genState = Obf.cls("bfl$q");
            Class<?> gen = Obf.cls("bfl$p");
            Object genAll = g.findStaticGetter(gsm, "k", genState).invoke();
            MethodHandles.Lookup gl = MethodHandles.privateLookupIn(genState, l);
            MethodHandle genOn = MethodHandles.privateLookupIn(gen, l).findGetter(gen, "a", bool);
            TEXGEN = new Object[4];
            TEXGEN_ON = new Object[4];
            String[] coords = {"a", "b", "c", "d"};
            for (int i = 0; i < 4; i++) {
                TEXGEN[i] = gl.findGetter(genState, coords[i], gen).invoke(genAll);
                TEXGEN_ON[i] = genOn.invoke(TEXGEN[i]);
            }
            TEXGEN_MODE = getter(l, gen, "c", int.class);

            Class<?> logic = Obf.cls("bfl$f");
            LOGIC_STATE = g.findStaticGetter(gsm, "j", logic).invoke();
            LOGIC = getter(l, logic, "a", bool).invoke(LOGIC_STATE);
            LOGIC_MODE = getter(l, logic, "b", int.class);

            Class<?> tex = Obf.cls("bfl$r");
            Object arr = g.findStaticGetter(gsm, "p", tex.arrayType()).invoke();
            TEXTURES = toArray(arr);
            TEX_ON = getter(l, tex, "a", bool);
            TEX_NAME = getter(l, tex, "b", int.class);

            Class<?> mask = Obf.cls("bfl$g");
            MASK = g.findStaticGetter(gsm, "s", mask).invoke();
            MASK_R = getter(l, mask, "a", boolean.class);
            MASK_G = getter(l, mask, "b", boolean.class);
            MASK_B = getter(l, mask, "c", boolean.class);
            MASK_A = getter(l, mask, "d", boolean.class);

            Class<?> color = Obf.cls("bfl$e");
            COLOR = g.findStaticGetter(gsm, "t", color).invoke();
            COLOR_R = getter(l, color, "a", float.class);
            COLOR_G = getter(l, color, "b", float.class);
            COLOR_B = getter(l, color, "c", float.class);
            COLOR_A = getter(l, color, "d", float.class);

        } catch (Throwable e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static MethodHandle getter(
            MethodHandles.Lookup l, Class<?> owner, String name, Class<?> type)
            throws ReflectiveOperationException {
        Class<?> ret = type.isPrimitive() ? type : Object.class;
        return MethodHandles.privateLookupIn(owner, l)
                .findGetter(owner, name, type)
                .asType(methodType(ret, Object.class));
    }

    // ---- lightmap: the coordinates the game set last (the GlStateManager does not keep them) ----

    private static float lightmapU;
    private static float lightmapV;

    /**
     * OpenGlHelper.setLightmapTextureCoords, everywhere in the game (patcher rule): records and
     * moves on.
     */
    public static void lightmapCoords(int unit, float u, float v) {
        if (unit == LIGHTMAP_UNIT) {
            lightmapU = u;
            lightmapV = v;
            // In the recorded pass the coordinate goes along with each instance; it only goes to
            // the driver if something draws immediately.
            if (SteinLazyGl.lightmap(unit, u, v)) {
                return;
            }
        }
        OpenGlHelper.setLightmapTextureCoords(unit, u, v);
    }

    /** Restores the lightmap coordinates (the deferred draw of a name). */
    public static void setLightmap(float u, float v) {
        lightmapCoords(LIGHTMAP_UNIT, u, v);
    }

    public static float lightmapU() {
        return lightmapU;
    }

    public static float lightmapV() {
        return lightmapV;
    }

    // ---- the damage red: the color the game's setBrightness puts on unit 2 ----

    private static boolean overlay;
    private static final float[] OVERLAY = new float[4];

    /**
     * After the game's setBrightness / setDoRenderBrightness: if it turned on, saves the color (its
     * FloatBuffer).
     */
    public static void brightnessSet(Object renderer, boolean on) {
        overlay = on;
        if (on) {
            FloatBuffer b = ((RendererLivingEntity) renderer).brightnessBuffer;
            OVERLAY[0] = b.get(0);
            OVERLAY[1] = b.get(1);
            OVERLAY[2] = b.get(2);
            OVERLAY[3] = b.get(3);
        }
    }

    /** RendererLivingEntity.setBrightness inside renderLayers (patcher rule). */
    public static boolean setBrightness(
            Object renderer, Object entity, float partialTicks, boolean combine) {
        boolean on =
                ((RendererLivingEntity) renderer)
                        .setBrightness((EntityLivingBase) entity, partialTicks, combine);
        brightnessSet(renderer, on);
        return on;
    }

    /** RendererLivingEntity.unsetBrightness (patcher rule). */
    public static void unsetBrightness(Object renderer) {
        overlay = false;
        ((RendererLivingEntity) renderer).unsetBrightness();
    }

    /** Is the damage red on right now? The color comes from {@link #overlay(int)}. */
    public static boolean overlay() {
        return overlay;
    }

    public static float overlay(int i) {
        return OVERLAY[i];
    }

    // ---- reading ----

    private static Object[] toArray(Object array) {
        Object[] out = new Object[Array.getLength(array)];
        for (int i = 0; i < out.length; i++) {
            out[i] = Array.get(array, i);
        }
        return out;
    }

    /**
     * A read failed: the handles are exact getters of fields that exist, so this does not happen.
     */
    private static IllegalStateException readFailed(Throwable e) {
        return new IllegalStateException(e);
    }

    public static boolean alphaTest() {
        try {
            return (boolean) BOOL.invokeExact((Object) ALPHA_TEST.invokeExact(ALPHA));
        } catch (Throwable e) {
            throw readFailed(e);
        }
    }

    public static int alphaFunc() {
        try {
            return (int) ALPHA_FUNC.invokeExact(ALPHA);
        } catch (Throwable e) {
            throw readFailed(e);
        }
    }

    public static float alphaRef() {
        try {
            return (float) ALPHA_REF.invokeExact(ALPHA);
        } catch (Throwable e) {
            throw readFailed(e);
        }
    }

    public static boolean lighting() {
        try {
            return (boolean) BOOL.invokeExact(LIGHTING);
        } catch (Throwable e) {
            throw readFailed(e);
        }
    }

    public static boolean colorMaterial() {
        try {
            return (boolean) BOOL.invokeExact(COLOR_MATERIAL);
        } catch (Throwable e) {
            throw readFailed(e);
        }
    }

    /**
     * GL_LIGHT0 + {@code i} enabled (from the cache: with deferred state the driver may be behind).
     */
    public static boolean light(int i) {
        try {
            return i < LIGHTS.length && (boolean) BOOL.invokeExact(LIGHTS[i]);
        } catch (Throwable e) {
            throw readFailed(e);
        }
    }

    /** GL_NORMALIZE enabled. */
    public static boolean normalize() {
        try {
            return (boolean) BOOL.invokeExact(NORMALIZE);
        } catch (Throwable e) {
            throw readFailed(e);
        }
    }

    /** GL_RESCALE_NORMAL enabled. */
    public static boolean rescaleNormal() {
        try {
            return (boolean) BOOL.invokeExact(RESCALE);
        } catch (Throwable e) {
            throw readFailed(e);
        }
    }

    public static boolean blend() {
        try {
            return (boolean) BOOL.invokeExact((Object) BLEND_ON.invokeExact(BLEND));
        } catch (Throwable e) {
            throw readFailed(e);
        }
    }

    public static int blendSrc() {
        try {
            return (int) BLEND_SRC.invokeExact(BLEND);
        } catch (Throwable e) {
            throw readFailed(e);
        }
    }

    public static int blendDst() {
        try {
            return (int) BLEND_DST.invokeExact(BLEND);
        } catch (Throwable e) {
            throw readFailed(e);
        }
    }

    /**
     * The alpha factor the driver uses: the cache's only if the last emitted blend was the separate
     * one; after a blendFunc(src, dst) alpha uses the color factors (the game's cache does not
     * update the alpha ones in that case).
     */
    public static int blendSrcAlpha() {
        try {
            return SteinGlTrack.blendSeparate() ? (int) BLEND_SRC_A.invokeExact(BLEND) : blendSrc();
        } catch (Throwable e) {
            throw readFailed(e);
        }
    }

    public static int blendDstAlpha() {
        try {
            return SteinGlTrack.blendSeparate() ? (int) BLEND_DST_A.invokeExact(BLEND) : blendDst();
        } catch (Throwable e) {
            throw readFailed(e);
        }
    }

    public static boolean depthTest() {
        try {
            return (boolean) BOOL.invokeExact((Object) DEPTH_TEST.invokeExact(DEPTH));
        } catch (Throwable e) {
            throw readFailed(e);
        }
    }

    public static boolean depthMask() {
        try {
            return (boolean) DEPTH_MASK.invokeExact(DEPTH);
        } catch (Throwable e) {
            throw readFailed(e);
        }
    }

    public static int depthFunc() {
        try {
            return (int) DEPTH_FUNC.invokeExact(DEPTH);
        } catch (Throwable e) {
            throw readFailed(e);
        }
    }

    public static boolean fog() {
        try {
            return (boolean) BOOL.invokeExact((Object) FOG_ON.invokeExact(FOG));
        } catch (Throwable e) {
            throw readFailed(e);
        }
    }

    public static int fogMode() {
        try {
            return (int) FOG_MODE.invokeExact(FOG);
        } catch (Throwable e) {
            throw readFailed(e);
        }
    }

    public static float fogDensity() {
        try {
            return (float) FOG_DENSITY.invokeExact(FOG);
        } catch (Throwable e) {
            throw readFailed(e);
        }
    }

    public static float fogStart() {
        try {
            return (float) FOG_START.invokeExact(FOG);
        } catch (Throwable e) {
            throw readFailed(e);
        }
    }

    public static float fogEnd() {
        try {
            return (float) FOG_END.invokeExact(FOG);
        } catch (Throwable e) {
            throw readFailed(e);
        }
    }

    public static boolean cull() {
        try {
            return (boolean) BOOL.invokeExact((Object) CULL_ON.invokeExact(CULL));
        } catch (Throwable e) {
            throw readFailed(e);
        }
    }

    public static int cullMode() {
        try {
            return (int) CULL_MODE.invokeExact(CULL);
        } catch (Throwable e) {
            throw readFailed(e);
        }
    }

    public static boolean polygonOffsetFill() {
        try {
            return (boolean) BOOL.invokeExact((Object) OFFSET_FILL.invokeExact(POLYGON_OFFSET));
        } catch (Throwable e) {
            throw readFailed(e);
        }
    }

    /** glPolygonOffset: factor and units (the GlStateManager cache). */
    public static float polygonOffsetFactor() {
        try {
            return (float) OFFSET_FACTOR.invokeExact(POLYGON_OFFSET);
        } catch (Throwable e) {
            throw readFailed(e);
        }
    }

    public static float polygonOffsetUnits() {
        try {
            return (float) OFFSET_UNITS.invokeExact(POLYGON_OFFSET);
        } catch (Throwable e) {
            throw readFailed(e);
        }
    }

    /**
     * Texture coordinate generation enabled on coordinate {@code c} (0 = S, 1 = T, 2 = R, 3 = Q).
     */
    public static boolean texGen(int c) {
        try {
            return (boolean) BOOL.invokeExact(TEXGEN_ON[c]);
        } catch (Throwable e) {
            throw readFailed(e);
        }
    }

    /**
     * The generation mode (GL_OBJECT_LINEAR, GL_EYE_LINEAR...) of coordinate {@code c}, as the
     * cache stores it.
     */
    public static int texGenMode(int c) {
        try {
            return (int) TEXGEN_MODE.invokeExact(TEXGEN[c]);
        } catch (Throwable e) {
            throw readFailed(e);
        }
    }

    public static boolean logicOp() {
        try {
            return (boolean) BOOL.invokeExact(LOGIC);
        } catch (Throwable e) {
            throw readFailed(e);
        }
    }

    /** glLogicOp's operation as the cache stores it (GL_COPY until the game sets another). */
    public static int logicOpMode() {
        try {
            return (int) LOGIC_MODE.invokeExact(LOGIC_STATE);
        } catch (Throwable e) {
            throw readFailed(e);
        }
    }

    /**
     * 2D texture enabled on unit {@code unit} (0 = the textures one, 1 = lightmap, 2 = the glint).
     */
    public static boolean texture2D(int unit) {
        try {
            return unit < TEXTURES.length
                    && (boolean) BOOL.invokeExact((Object) TEX_ON.invokeExact(TEXTURES[unit]));
        } catch (Throwable e) {
            throw readFailed(e);
        }
    }

    /**
     * The texture bound on unit {@code unit} (-1: the game deleted the one that was bound and does
     * not know which one is left).
     */
    public static int textureName(int unit) {
        try {
            return unit < TEXTURES.length ? (int) TEX_NAME.invokeExact(TEXTURES[unit]) : 0;
        } catch (Throwable e) {
            throw readFailed(e);
        }
    }

    public static int activeUnit() {
        return GlStateManager.activeTextureUnit;
    }

    /** How many texture units the GlStateManager cache tracks (those of {@link #textureName}). */
    public static int textureUnits() {
        return TEXTURES.length;
    }

    /** Color mask from the cache, channel by channel (0 = R, 1 = G, 2 = B, 3 = A). */
    public static boolean colorMask(int channel) {
        try {
            return switch (channel) {
                case 0 -> (boolean) MASK_R.invokeExact(MASK);
                case 1 -> (boolean) MASK_G.invokeExact(MASK);
                case 2 -> (boolean) MASK_B.invokeExact(MASK);
                default -> (boolean) MASK_A.invokeExact(MASK);
            };
        } catch (Throwable e) {
            throw readFailed(e);
        }
    }

    public static boolean colorMaskAll() {
        try {
            return (boolean) MASK_R.invokeExact(MASK)
                    && (boolean) MASK_G.invokeExact(MASK)
                    && (boolean) MASK_B.invokeExact(MASK)
                    && (boolean) MASK_A.invokeExact(MASK);
        } catch (Throwable e) {
            throw readFailed(e);
        }
    }

    /** Only alpha off in the mask (the menu panorama and its blur). */
    public static boolean colorMaskRgb() {
        try {
            return (boolean) MASK_R.invokeExact(MASK)
                    && (boolean) MASK_G.invokeExact(MASK)
                    && (boolean) MASK_B.invokeExact(MASK)
                    && !(boolean) MASK_A.invokeExact(MASK);
        } catch (Throwable e) {
            throw readFailed(e);
        }
    }

    /** Color mask fully off (depth only: the first cloud pass). */
    public static boolean colorMaskNone() {
        try {
            return !(boolean) MASK_R.invokeExact(MASK)
                    && !(boolean) MASK_G.invokeExact(MASK)
                    && !(boolean) MASK_B.invokeExact(MASK)
                    && !(boolean) MASK_A.invokeExact(MASK);
        } catch (Throwable e) {
            throw readFailed(e);
        }
    }

    public static float colorR() {
        try {
            return (float) COLOR_R.invokeExact(COLOR);
        } catch (Throwable e) {
            throw readFailed(e);
        }
    }

    public static float colorG() {
        try {
            return (float) COLOR_G.invokeExact(COLOR);
        } catch (Throwable e) {
            throw readFailed(e);
        }
    }

    public static float colorB() {
        try {
            return (float) COLOR_B.invokeExact(COLOR);
        } catch (Throwable e) {
            throw readFailed(e);
        }
    }

    public static float colorA() {
        try {
            return (float) COLOR_A.invokeExact(COLOR);
        } catch (Throwable e) {
            throw readFailed(e);
        }
    }
}
