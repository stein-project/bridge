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
 * open, so those reads stay reflective.
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
            LOGIC =
                    getter(l, logic, "a", bool)
                            .invoke(g.findStaticGetter(gsm, "j", logic).invoke());

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
    public static void lightmapCoords(int unit, float u, float v) throws Throwable {
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
    public static void setLightmap(float u, float v) throws Throwable {
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
            Object renderer, Object entity, float partialTicks, boolean combine) throws Throwable {
        boolean on =
                ((RendererLivingEntity) renderer)
                        .setBrightness((EntityLivingBase) entity, partialTicks, combine);
        brightnessSet(renderer, on);
        return on;
    }

    /** RendererLivingEntity.unsetBrightness (patcher rule). */
    public static void unsetBrightness(Object renderer) throws Throwable {
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

    private static Object readObject(MethodHandle h, Object o) {
        try {
            return (Object) h.invokeExact(o);
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
    }

    private static int readInt(MethodHandle h, Object o) {
        try {
            return (int) h.invokeExact(o);
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
    }

    private static float readFloat(MethodHandle h, Object o) {
        try {
            return (float) h.invokeExact(o);
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean readBoolean(MethodHandle h, Object o) {
        try {
            return (boolean) h.invokeExact(o);
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean alphaTest() {
        return readBoolean(BOOL, readObject(ALPHA_TEST, ALPHA));
    }

    public static int alphaFunc() {
        return readInt(ALPHA_FUNC, ALPHA);
    }

    public static float alphaRef() {
        return readFloat(ALPHA_REF, ALPHA);
    }

    public static boolean lighting() {
        return readBoolean(BOOL, LIGHTING);
    }

    public static boolean colorMaterial() {
        return readBoolean(BOOL, COLOR_MATERIAL);
    }

    /**
     * GL_LIGHT0 + {@code i} enabled (from the cache: with deferred state the driver may be behind).
     */
    public static boolean light(int i) {
        return i < LIGHTS.length && readBoolean(BOOL, LIGHTS[i]);
    }

    /** GL_NORMALIZE enabled. */
    public static boolean normalize() {
        return readBoolean(BOOL, NORMALIZE);
    }

    /** GL_RESCALE_NORMAL enabled. */
    public static boolean rescaleNormal() {
        return readBoolean(BOOL, RESCALE);
    }

    public static boolean blend() {
        return readBoolean(BOOL, readObject(BLEND_ON, BLEND));
    }

    public static int blendSrc() {
        return readInt(BLEND_SRC, BLEND);
    }

    public static int blendDst() {
        return readInt(BLEND_DST, BLEND);
    }

    /**
     * The alpha factor the driver uses: the cache's only if the last emitted blend was the separate
     * one; after a blendFunc(src, dst) alpha uses the color factors (the game's cache does not
     * update the alpha ones in that case).
     */
    public static int blendSrcAlpha() {
        return SteinGlTrack.blendSeparate() ? readInt(BLEND_SRC_A, BLEND) : blendSrc();
    }

    public static int blendDstAlpha() {
        return SteinGlTrack.blendSeparate() ? readInt(BLEND_DST_A, BLEND) : blendDst();
    }

    public static boolean depthTest() {
        return readBoolean(BOOL, readObject(DEPTH_TEST, DEPTH));
    }

    public static boolean depthMask() {
        return readBoolean(DEPTH_MASK, DEPTH);
    }

    public static int depthFunc() {
        return readInt(DEPTH_FUNC, DEPTH);
    }

    public static boolean fog() {
        return readBoolean(BOOL, readObject(FOG_ON, FOG));
    }

    public static int fogMode() {
        return readInt(FOG_MODE, FOG);
    }

    public static float fogDensity() {
        return readFloat(FOG_DENSITY, FOG);
    }

    public static float fogStart() {
        return readFloat(FOG_START, FOG);
    }

    public static float fogEnd() {
        return readFloat(FOG_END, FOG);
    }

    public static boolean cull() {
        return readBoolean(BOOL, readObject(CULL_ON, CULL));
    }

    public static int cullMode() {
        return readInt(CULL_MODE, CULL);
    }

    public static boolean polygonOffsetFill() {
        return readBoolean(BOOL, readObject(OFFSET_FILL, POLYGON_OFFSET));
    }

    /** glPolygonOffset: factor and units (the GlStateManager cache). */
    public static float polygonOffsetFactor() {
        return readFloat(OFFSET_FACTOR, POLYGON_OFFSET);
    }

    public static float polygonOffsetUnits() {
        return readFloat(OFFSET_UNITS, POLYGON_OFFSET);
    }

    /**
     * Texture coordinate generation enabled on coordinate {@code c} (0 = S, 1 = T, 2 = R, 3 = Q).
     */
    public static boolean texGen(int c) {
        return readBoolean(BOOL, TEXGEN_ON[c]);
    }

    /**
     * The generation mode (GL_OBJECT_LINEAR, GL_EYE_LINEAR...) of coordinate {@code c}, as the
     * cache stores it.
     */
    public static int texGenMode(int c) {
        return readInt(TEXGEN_MODE, TEXGEN[c]);
    }

    public static boolean logicOp() {
        return readBoolean(BOOL, LOGIC);
    }

    /**
     * 2D texture enabled on unit {@code unit} (0 = the textures one, 1 = lightmap, 2 = the glint).
     */
    public static boolean texture2D(int unit) {
        return unit < TEXTURES.length && readBoolean(BOOL, readObject(TEX_ON, TEXTURES[unit]));
    }

    /**
     * The texture bound on unit {@code unit} (-1: the game deleted the one that was bound and does
     * not know which one is left).
     */
    public static int textureName(int unit) {
        return unit < TEXTURES.length ? readInt(TEX_NAME, TEXTURES[unit]) : 0;
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
        return switch (channel) {
            case 0 -> readBoolean(MASK_R, MASK);
            case 1 -> readBoolean(MASK_G, MASK);
            case 2 -> readBoolean(MASK_B, MASK);
            default -> readBoolean(MASK_A, MASK);
        };
    }

    public static boolean colorMaskAll() {
        return readBoolean(MASK_R, MASK)
                && readBoolean(MASK_G, MASK)
                && readBoolean(MASK_B, MASK)
                && readBoolean(MASK_A, MASK);
    }

    /** Only alpha off in the mask (the menu panorama and its blur). */
    public static boolean colorMaskRgb() {
        return readBoolean(MASK_R, MASK)
                && readBoolean(MASK_G, MASK)
                && readBoolean(MASK_B, MASK)
                && !readBoolean(MASK_A, MASK);
    }

    /** Color mask fully off (depth only: the first cloud pass). */
    public static boolean colorMaskNone() {
        return !readBoolean(MASK_R, MASK)
                && !readBoolean(MASK_G, MASK)
                && !readBoolean(MASK_B, MASK)
                && !readBoolean(MASK_A, MASK);
    }

    public static float colorR() {
        return readFloat(COLOR_R, COLOR);
    }

    public static float colorG() {
        return readFloat(COLOR_G, COLOR);
    }

    public static float colorB() {
        return readFloat(COLOR_B, COLOR);
    }

    public static float colorA() {
        return readFloat(COLOR_A, COLOR);
    }
}
