/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import net.minecraft.client.renderer.GlStateManager;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL45;

import java.util.Arrays;

/**
 * The game's textures (skins, armor, glint, chests...) copied on the GPU into texture arrays, one
 * per size: the new renderer draws entities with different textures in a single draw, picking the
 * layer per instance, instead of switching texture for each entity.
 *
 * <p>The copy is GPU to GPU ({@code copyTexture}), once per texture. When the game rewrites a
 * texture (a skin that arrived from the network, a dynamic texture) or deletes it, the layer
 * becomes invalid and is copied again on the next use — the hooks are the game's upload and delete
 * calls (patcher rules).
 *
 * <p>Only RGBA8 textures up to 512x512 get in; the others (HD skins, full classes) the deferred
 * renderer records with the game's texture directly, in one group per texture ({@code
 * SteinEntityDeferred.KEY_GAMETEX}).
 *
 * <p>Pipeline: Stein GFX only.
 */
public final class SteinTextureArrays {

    private static final int MAX_SIZE = 512;
    private static final int CLASSES = 16;
    private static final int MIN_LAYERS = 16;
    private static final int MAX_LAYERS = 2048;

    /** Per game texture: the size class + 1 (0 = no layer) and the layer. */
    private static int[] klass = new int[4096];

    private static int[] layerOf = new int[4096];

    /** Textures already known not to fit (size or format). */
    private static boolean[] rejected = new boolean[4096];

    private static final int[] classW = new int[CLASSES];
    private static final int[] classH = new int[CLASSES];
    private static final SteinTexture[] arrays = new SteinTexture[CLASSES];
    private static final int[] used = new int[CLASSES];
    private static final int[] kindOf = new int[CLASSES];
    private static final int[] layersOf = new int[CLASSES];
    private static final SteinSampler[] samplers = new SteinSampler[CLASSES];
    private static final long BUDGET_BYTES = 32L << 20;

    /** Per class and layer: the game texture that is there (to release when the class fills up). */
    private static final int[][] owner = new int[CLASSES][];

    private static int classes;
    private static int capacity;

    private static long copies;

    private SteinTextureArrays() {}

    /** Layers per array (the GPU's maximum, up to 2048). */
    private static int capacity() {
        if (capacity == 0) {
            capacity =
                    Math.max(
                            MIN_LAYERS,
                            Math.min(MAX_LAYERS, SteinDevice.get().caps().maxArrayLayers));
        }
        return capacity;
    }

    /**
     * The layer of the game texture {@code tex}: {@code (class << 16) | layer}, or -1 if it does
     * not fit. Copies on the first time (and after each game upload to it).
     */
    public static int layer(int tex) {
        if (tex <= 0) {
            return -1;
        }
        if (tex >= klass.length) {
            grow(tex);
        }
        int k = klass[tex];
        if (k != 0) {
            return ((k - 1) << 16) | layerOf[tex];
        }
        if (rejected[tex]) {
            return -1;
        }
        return copy(tex);
    }

    private static void grow(int tex) {
        int n = Math.max(tex + 1, klass.length * 2);
        klass = Arrays.copyOf(klass, n);
        layerOf = Arrays.copyOf(layerOf, n);
        rejected = Arrays.copyOf(rejected, n);
    }

    private static int copy(int tex) {
        int w;
        int h;
        int format;
        if (GL.getCapabilities().OpenGL45) {
            w = GL45.glGetTextureLevelParameteri(tex, 0, GL11.GL_TEXTURE_WIDTH);
            h = GL45.glGetTextureLevelParameteri(tex, 0, GL11.GL_TEXTURE_HEIGHT);
            format = GL45.glGetTextureLevelParameteri(tex, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT);
        } else {
            // No direct access: binds on the active unit, queries, and restores the one that was
            // actually bound.
            int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
            w = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
            h = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
            format =
                    GL11.glGetTexLevelParameteri(
                            GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous);
        }
        if (w <= 0
                || h <= 0
                || w > MAX_SIZE
                || h > MAX_SIZE
                || format != GL30.GL_RGBA8 && format != GL11.GL_RGBA) {
            rejected[tex] = true;
            return -1;
        }
        int c = classFor(w, h, samplerKind(tex));
        if (c < 0) {
            rejected[tex] = true;
            return -1;
        }
        if (used[c] == layersOf[c]) {
            reset(c);
        }
        int layer = used[c]++;
        SteinDevice dev = SteinDevice.get();
        SteinTexture src =
                dev.importTexture(
                        SteinTexture.Desc.texture2D(
                                "game texture " + tex, SteinFormat.RGBA8, w, h, 1),
                        tex);
        dev.copyTexture(src, 0, arrays[c], layer, w, h);
        owner[c][layer] = tex;
        klass[tex] = c + 1;
        layerOf[tex] = layer;
        copies++;
        return (c << 16) | layer;
    }

    /**
     * How the game samples the texture: bit 0 = smooth (LINEAR, the .mcmeta "blur", e.g. the
     * enchantment glint), bit 1 = clamped edge (the "clamp"). Each combination becomes a class of
     * its own, with its own sampler.
     */
    private static int samplerKind(int tex) {
        int mag;
        int wrap;
        if (GL.getCapabilities().OpenGL45) {
            mag = GL45.glGetTextureParameteri(tex, GL11.GL_TEXTURE_MAG_FILTER);
            wrap = GL45.glGetTextureParameteri(tex, GL11.GL_TEXTURE_WRAP_S);
        } else {
            int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
            mag = GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER);
            wrap = GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous);
        }
        return (mag == GL11.GL_LINEAR ? 1 : 0) | (wrap != GL11.GL_REPEAT ? 2 : 0);
    }

    /** Layers of a class: by memory budget (32 MB per array), between 16 and the GPU's maximum. */
    private static int layersFor(int w, int h) {
        long perLayer = (long) w * h * 4;
        long n = BUDGET_BYTES / perLayer;
        return (int) Math.max(MIN_LAYERS, Math.min(capacity(), n));
    }

    private static int classFor(int w, int h, int kind) {
        for (int i = 0; i < classes; i++) {
            if (classW[i] == w && classH[i] == h && kindOf[i] == kind) {
                return i;
            }
        }
        if (classes == CLASSES) {
            return -1;
        }
        int c = classes++;
        classW[c] = w;
        classH[c] = h;
        kindOf[c] = kind;
        layersOf[c] = layersFor(w, h);
        SteinDevice dev = SteinDevice.get();
        arrays[c] =
                dev.createTexture(
                        SteinTexture.Desc.array(
                                "textures " + w + "x" + h + " " + kind,
                                SteinFormat.RGBA8,
                                w,
                                h,
                                layersOf[c],
                                1));
        SteinSampler.Filter f =
                (kind & 1) != 0 ? SteinSampler.Filter.LINEAR : SteinSampler.Filter.NEAREST;
        SteinSampler.Wrap wr = (kind & 2) != 0 ? SteinSampler.Wrap.CLAMP : SteinSampler.Wrap.REPEAT;
        samplers[c] =
                dev.createSampler(
                        new SteinSampler.Desc(f, f, SteinSampler.Mip.NONE, wr, wr, 1f, false));
        owner[c] = new int[layersOf[c]];
        return c;
    }

    /** The sampler of class {@code c} (the filter and edge the game uses on its textures). */
    public static SteinSampler sampler(int c) {
        return samplers[c];
    }

    /**
     * The class filled up: releases all its layers (the textures in use are copied again on the
     * next use).
     */
    private static void reset(int c) {
        for (int i = 0; i < used[c]; i++) {
            int tex = owner[c][i];
            if (tex < klass.length && klass[tex] == c + 1) {
                klass[tex] = 0;
            }
        }
        used[c] = 0;
    }

    /**
     * Any class that already exists (for a draw with no texture, which needs something bound for
     * the shader to sample and ignore), or -1 if none.
     */
    public static int anyClass() {
        return classes > 0 ? 0 : -1;
    }

    /** The array of class {@code c}. */
    public static SteinTexture array(int c) {
        return arrays[c];
    }

    // ---- hooks: the game touched a texture ----

    /**
     * Before a game upload (glTexImage2D / glTexSubImage2D / glCopyTexSubImage2D): the bound
     * texture changes.
     */
    public static void touchBound() {
        int tex = SteinGameState.textureName(SteinGameState.activeUnit());
        forget(tex);
    }

    /** GlStateManager.deleteTexture, throughout the game (patcher rule). */
    public static void deleteTexture(int tex) {
        forget(tex);
        SteinTessCapture.forgetFilter(tex);
        deletes++;
        GlStateManager.deleteTexture(tex);
    }

    private static long deletes;

    /** How many textures the game has deleted (a deleted id can come back for another texture). */
    public static long deletes() {
        return deletes;
    }

    private static void forget(int tex) {
        if (tex > 0 && tex < klass.length) {
            klass[tex] = 0;
            rejected[tex] = false;
        }
    }

    /** Copies since the last call (for the log). */
    public static long takeCopies() {
        long c = copies;
        copies = 0;
        return c;
    }
}
