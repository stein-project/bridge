/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import static dev.xavier.stein.gfx.SteinDeferredGroups.*;
import static dev.xavier.stein.gfx.SteinDeferredKeys.*;
import static dev.xavier.stein.gfx.SteinDeferredLights.*;
import static dev.xavier.stein.gfx.SteinDeferredResources.*;
import static dev.xavier.stein.gfx.SteinDeferredRetained.*;
import static dev.xavier.stein.gfx.SteinDeferredStats.*;
import static dev.xavier.stein.gfx.SteinEntityDeferred.*;
import static dev.xavier.stein.gfx.SteinInstanceWriter.*;

import dev.xavier.stein.bridge.engine.GLMatrix;

import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;

import java.util.Arrays;

/**
 * Recording of model parts for {@link SteinEntityDeferred}: reads the game state at {@code
 * beginModel}, writes one instance per part and keeps the parts recorded for the enchantment glint.
 *
 * <p>Pipeline: Stein GFX only.
 */
final class SteinDeferredModel {

    // ---- current model state (read once per ModelRenderer.render) ----

    static long curKey;
    static int curClass;
    static float curLayer;
    static float curAlphaRef;
    static float curR;
    static float curG;
    static float curB;
    static float curA;
    static float curLmU;
    static float curLmV;
    static int curFlags;
    static float curOvR;
    static float curOvG;
    static float curOvB;
    static float curOvA;
    static float[] curTex;

    /**
     * The game's texture of the current model (-1 = no texture): retention checks its layer
     * afterwards.
     */
    static int curTexName;

    /**
     * Reads the game's state for the model about to be drawn; false = cannot be recorded (draws
     * immediately).
     */
    public static boolean beginModel() {
        SteinGlTrack.forgetNormal(); // the parts carry per-vertex normals: the current one becomes
        // undefined in
        // OpenGL
        if (!SteinGameState.colorMaskAll() || SteinGameState.logicOp()) {
            return reject();
        }
        long offset = offsetBits();
        if (offset < 0) {
            return reject();
        }
        // Texture: the layer in the array; outside it (larger than 512, format, full classes) the
        // game's own directly, in a group per texture; off (the spectator outline, the dragon's
        // damage red) none, with any array bound only so the shader has something to sample.
        boolean textured = SteinGameState.texture2D(0);
        int klass;
        float layer = 0f;
        long texBits = 0L;
        curTexName = -1;
        if (textured) {
            int tex = SteinGameState.textureName(0);
            curTexName = tex;
            int packed = SteinTextureArrays.layer(tex);
            if (packed >= 0) {
                klass = packed >>> 16;
                layer = packed & 0xFFFF;
            } else {
                if (tex <= 0 || tex >= MAX_KEY_TEXTURE) {
                    return reject(); // texture 0 bound: OpenGL would sample an incomplete one
                }
                klass = 0;
                texBits = KEY_GAMETEX | textureBits(tex);
            }
        } else {
            klass = SteinTextureArrays.anyClass();
            if (klass < 0) {
                return reject();
            }
        }
        if (!alphaFuncSupported()) {
            return reject();
        }
        float ref = alphaRefValue();
        boolean lighting = SteinGameState.lighting();
        if (lighting) {
            needLights();
        }
        // Lighting without COLOR_MATERIAL: as with it (see beginDynamic).
        if (!currentColor()) {
            return reject();
        }
        float r = COLOR[0];
        float g = COLOR[1];
        float b = COLOR[2];
        float a = COLOR[3];
        int key = stateKey(klass);
        if (key < 0) {
            return reject();
        }
        int flags = textured ? 0 : FLAG_NOTEX;
        if (lighting) {
            // Parts with a unit normal: with GL_NORMALIZE or GL_RESCALE_NORMAL the normal comes out
            // unit length (the default); with neither, the matrix scale changes the strength of the
            // light (the End crystal, scale 2).
            flags |= FLAG_LIGHTING;
            if (!SteinGameState.normalize() && !SteinGameState.rescaleNormal()) {
                flags |= FLAG_NRAW;
            }
        }
        if (SteinGameState.texture2D(1)) {
            flags |= FLAG_LIGHTMAP;
            lightmapId = SteinGameState.textureName(1);
        }
        if (SteinGameState.fog()) {
            flags |= FLAG_FOG;
        }
        curOvA = 0f;
        if (SteinGameState.texture2D(2) && SteinGameState.overlay()) {
            readOverlay();
            flags |= FLAG_OVERLAY;
        }
        curKey = (key & 0xFFFFFFFFL) | texBits | offset;
        curClass = klass;
        curLayer = layer;
        curAlphaRef = ref;
        curR = r;
        curG = g;
        curB = b;
        curA = a;
        curLmU = SteinGameState.lightmapU();
        curLmV = SteinGameState.lightmapV();
        curFlags = flags;
        curTex = GLMatrix.textureOrNull();
        return true;
    }

    /**
     * The model is going to draw immediately (the call list is a number in the core context, so it
     * does not reach the screen): only counted.
     */
    static boolean reject() {
        rejected++;
        SteinTileRetain.taint();
        return false;
    }

    // ---- polygon offset: few values in the whole game (the crack and TNT use -3, -3) ----

    static final int OFFSETS = 3;
    static final float[] offsetFactor = new float[OFFSETS];
    static final float[] offsetUnits = new float[OFFSETS];
    static int offsets;

    /**
     * The key bits for the current polygon offset: 0 for no offset; (index + 1) << {@link
     * #KEY_OFFSET_SHIFT}; -1 if the table is full (a new value: immediate).
     */
    static long offsetBits() {
        if (!SteinGameState.polygonOffsetFill()) {
            return 0L;
        }
        float f = SteinGameState.polygonOffsetFactor();
        float u = SteinGameState.polygonOffsetUnits();
        for (int i = 0; i < offsets; i++) {
            if (offsetFactor[i] == f && offsetUnits[i] == u) {
                return (long) (i + 1) << KEY_OFFSET_SHIFT;
            }
        }
        if (offsets == OFFSETS) {
            return -1L;
        }
        offsetFactor[offsets] = f;
        offsetUnits[offsets] = u;
        offsets++;
        return (long) offsets << KEY_OFFSET_SHIFT;
    }

    /**
     * Can the alpha test in force be represented? Off, GREATER and ALWAYS can (the shader has a
     * GREATER reference); any other function draws immediately.
     */
    static boolean alphaFuncSupported() {
        if (!SteinGameState.alphaTest()) {
            return true;
        }
        int func = SteinGameState.alphaFunc();
        return func == GL11.GL_GREATER || func == GL11.GL_ALWAYS;
    }

    /** The alpha reference of the GREATER test in force; -1 for none (off, or ALWAYS). */
    static float alphaRefValue() {
        if (SteinGameState.alphaTest() && SteinGameState.alphaFunc() == GL11.GL_GREATER) {
            return SteinGameState.alphaRef();
        }
        return -1f;
    }

    /** The colour read by {@link #currentColor}. */
    static final float[] COLOR = new float[4];

    /**
     * The current colour into {@link #COLOR}. A colour "forgotten" by the GlStateManager
     * (resetColor) is that of the last vertex with colour, the one the driver was left with; false
     * if there is none.
     */
    static boolean currentColor() {
        float r = SteinGameState.colorR();
        float g = SteinGameState.colorG();
        float b = SteinGameState.colorB();
        float a = SteinGameState.colorA();
        if (r < 0f || g < 0f || b < 0f || a < 0f) {
            if (!SteinTessCapture.lastColorKnown) {
                return false;
            }
            r = SteinTessCapture.lastR;
            g = SteinTessCapture.lastG;
            b = SteinTessCapture.lastB;
            a = SteinTessCapture.lastA;
        }
        COLOR[0] = r;
        COLOR[1] = g;
        COLOR[2] = b;
        COLOR[3] = a;
        return true;
    }

    /**
     * The damage red (the game's setBrightness): the constant colour of unit 2, which it blends on
     * top. The colour is stored by {@link SteinGameState} when the game turns it on (asking the
     * driver for every model cost ~4% of the frame in a crowd).
     */
    static void readOverlay() {
        if (!SteinGameState.overlay()) {
            return;
        }
        curOvR = SteinGameState.overlay(0);
        curOvG = SteinGameState.overlay(1);
        curOvB = SteinGameState.overlay(2);
        curOvA = SteinGameState.overlay(3);
    }

    // ---- recording the parts ----

    /** A part with the matrix {@code m[o..o+15]}, in the state read by {@link #beginModel()}. */
    public static void add(int list, float[] m, int o) {
        int verts = SteinModelInstancer.vertexCount(list);
        int first = SteinModelInstancer.firstVertex(list);
        long inst = reserveInstance();
        if (inst < 0) {
            return;
        }
        if (SteinTileRetain.recording) {
            addRetained(list, verts, first, inst, m, o);
            return;
        }
        long p = instances.address(inst);
        putMatrixRows(p, m, o);
        putRest(p + 48, curTex);
        command(verts, first, (int) (inst / INSTANCE_BYTES));
        if (recording) {
            mirror(verts, first, m, o);
        }
    }

    /** Texture, colour, lightmap and damage of the instance, from byte 48 (after the 3 rows). */
    static void putRest(long p, float[] t) {
        putTexture(p, t, curLayer, curAlphaRef);
        putMaterial(
                p + 32, curR, curG, curB, curA, curLmU, curLmV, curFlags, curOvR, curOvG, curOvB,
                curOvA);
    }

    // ---- enchantment glint: the base parts recorded and re-recorded with the glint state ----

    static boolean recording;
    static int recStart;

    /**
     * Per recorded part: vertices, first vertex, and the matrix (12 floats) in {@link #mirrorM}.
     */
    static int[] mirrorL = new int[2 * 1024];

    static float[] mirrorM = new float[12 * 1024];
    static int mirrorN;

    static void mirror(int verts, int first, float[] m, int o) {
        if (mirrorN * 2 + 2 > mirrorL.length) {
            mirrorL = Arrays.copyOf(mirrorL, mirrorL.length * 2);
            mirrorM = Arrays.copyOf(mirrorM, mirrorM.length * 2);
        }
        mirrorL[mirrorN * 2] = verts;
        mirrorL[mirrorN * 2 + 1] = first;
        putMatrixRows(mirrorM, mirrorN * 12, m, o);
        mirrorN++;
    }

    static void startRecording() {
        recording = true;
        recStart = mirrorN;
    }

    /** {start, end} of the recorded parts, or null. */
    static int[] stopRecording() {
        boolean was = recording;
        recording = false;
        return was && mirrorN > recStart ? new int[] {recStart, mirrorN} : null;
    }

    /**
     * The parts recorded again, in the current state (the glint's) and with texture matrix {@code
     * tex}.
     */
    static void replay(int[][] recs, int n, float[] tex) {
        SteinTileRetain.taint();
        if (!beginModel()) {
            lostGlint++;
            return;
        }
        for (int i = 0; i < n; i++) {
            int[] r = recs[i];
            for (int e = r[0]; e < r[1]; e++) {
                long inst = reserveInstance();
                if (inst < 0) {
                    return;
                }
                long p = instances.address(inst);
                int at = e * 12;
                for (int k = 0; k < 12; k++) {
                    MemoryUtil.memPutFloat(p + k * 4L, mirrorM[at + k]);
                }
                putRest(p + 48, tex);
                command(mirrorL[e * 2], mirrorL[e * 2 + 1], (int) (inst / INSTANCE_BYTES));
            }
        }
    }
}
