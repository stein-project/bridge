/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import static dev.xavier.stein.gfx.SteinDeferredFrame.*;
import static dev.xavier.stein.gfx.SteinDeferredGroups.*;
import static dev.xavier.stein.gfx.SteinDeferredKeys.*;
import static dev.xavier.stein.gfx.SteinDeferredLights.*;
import static dev.xavier.stein.gfx.SteinDeferredModel.*;
import static dev.xavier.stein.gfx.SteinDeferredResources.*;
import static dev.xavier.stein.gfx.SteinDeferredRetained.*;
import static dev.xavier.stein.gfx.SteinDeferredSessions.*;
import static dev.xavier.stein.gfx.SteinDeferredStats.*;
import static dev.xavier.stein.gfx.SteinEntityDeferred.*;
import static dev.xavier.stein.gfx.SteinInstanceWriter.*;
import static dev.xavier.stein.gfx.SteinPassTiming.*;

import dev.xavier.stein.bridge.engine.GLMatrix;

/**
 * Recording of dynamic geometry (Tessellator, font, item lists) for {@link SteinEntityDeferred}:
 * reads the game state at {@code beginDynamic}, reserves the vertices in the frame ring and writes
 * the instance.
 *
 * <p>Pipeline: Stein GFX only.
 */
final class SteinDeferredDynamic {

    // ---- dynamic geometry (SteinTessCapture): Tessellator, font, items ----

    static long dynKey;
    static float dynAlphaRef;
    static float dynR;
    static float dynG;
    static float dynB;
    static float dynA;
    static float dynLmU;
    static float dynLmV;
    static int dynFlags;

    /** The current normal ({@link SteinDeferredKeys#FLAG_CNORMAL}). */
    static float dynNx;

    static float dynNy;
    static float dynNz;

    /**
     * With {@link SteinDeferredKeys#FLAG_OVERLAY}: dynN* carry the damage colour and this the
     * weight.
     */
    static float dynOvA;

    /**
     * The texture coordinate already came out transformed (CPU generation): the instance carries
     * the identity.
     */
    static boolean dynTexBaked;

    static long dynInst;
    static int dynFirst;

    /**
     * Reads the game's state for a dynamic draw with the elements {@code bits} ({@link
     * SteinTessCapture#HAS_UV}...); false = cannot be recorded (draws immediately). {@code
     * ownBlend}: the Stein font's blend.
     */
    static boolean beginDynamic(int bits, boolean ownBlend) {
        return beginDynamic(bits, ownBlend, false);
    }

    /** {@code lines}: the draw is lines (as a segment list), in a line pipeline. */
    static boolean beginDynamic(int bits, boolean ownBlend, boolean lines) {
        if (!dynamicReady || !capturing() || !GLMatrix.batchable()) {
            return rejectDynamic(
                    !dynamicReady
                            ? "dynamic unavailable"
                            : !capturing() ? "outside a pass" : "matrix cannot be grouped");
        }
        // A fully off mask (depth only) becomes a pipeline without colour write; RGB only (the
        // menu panorama), without alpha write; any other partial one (only anaglyph, deliberately
        // off) stays immediate.
        boolean noColor = SteinGameState.colorMaskNone();
        boolean noAlpha = !noColor && SteinGameState.colorMaskRgb();
        if (!noColor && !noAlpha && !SteinGameState.colorMaskAll() || SteinGameState.logicOp()) {
            return rejectDynamic("partial color mask or logicOp");
        }
        long offset = offsetBits();
        if (offset < 0) {
            return rejectDynamic("polygon offset");
        }
        // The damage red on a layer that draws dynamic geometry (block head, cow's mushroom):
        // the setBrightness combiner, as in the model parts. Unit 2 on for another reason:
        // immediate.
        boolean overlay = SteinGameState.texture2D(2);
        if (overlay && !SteinGameState.overlay()) {
            return rejectDynamic("unit 2 without overlay");
        }
        boolean textured = unit0Textured(bits);
        int tex = textured ? SteinGameState.textureName(0) : 0;
        if (textured
                && (tex <= 0 || tex >= MAX_KEY_TEXTURE || (bits & SteinTessCapture.HAS_UV) == 0)) {
            // texture without a coordinate on the vertex: GL would use the current one, which is
            // not known
            return rejectDynamic("texture without a coordinate or outside the key");
        }
        if (!alphaFuncSupported()) {
            return rejectDynamic("alphaFunc");
        }
        float ref = alphaRefValue();
        // The sky with a container screen open: the screen left lighting on (and the normal of
        // the last geometry); it goes out without light, like the sky without a screen open.
        boolean lighting = SteinGameState.lighting() && !skyNoUv;
        if (lighting) {
            needLights();
        }
        boolean currentNormal = lighting && (bits & SteinTessCapture.HAS_NORMAL) == 0;
        // The round shadow (Render.renderShadow) goes out with lighting on and the normal left
        // over from the last draw; its texture is black (RGB 0), so lighting changes nothing and
        // any normal gives the same pixel.
        boolean anyNormal = SteinTessCapture.inShadow();
        // Lighting without COLOR_MATERIAL (the game re-enables only the light after the map in
        // the frame): the material was left with the last tracked colour, the white of these
        // draws; it goes out as with the colour material.
        if (lighting && currentNormal && !SteinGlTrack.normalKnown() && !anyNormal) {
            return rejectDynamic("light with an undefined current normal");
        }
        boolean vColor = (bits & SteinTessCapture.HAS_COLOR) != 0;
        float r = 1f;
        float g = 1f;
        float b = 1f;
        float a = 1f;
        if (!vColor) {
            if (!currentColor()) {
                return rejectDynamic("unknown current color");
            }
            r = COLOR[0];
            g = COLOR[1];
            b = COLOR[2];
            a = COLOR[3];
        }
        int key = stateKey(0, ownBlend);
        if (key < 0) {
            return rejectDynamic("state without a key");
        }
        int flags = dynamicFlags(bits, lighting, currentNormal, overlay, vColor, textured);
        if (flags < 0) {
            return false;
        }
        int filter = textured ? SteinTessCapture.filterCode(tex) : 0;
        dynKey =
                (key & 0xFFFFFFFFL)
                        | KEY_DYNAMIC
                        | textureBits(tex)
                        | filterBits(filter)
                        | offset
                        | (lines ? KEY_LINES : 0L)
                        | (noColor ? KEY_NOCOLOR : 0L)
                        | (noAlpha ? KEY_NOALPHA : 0L);
        dynAlphaRef = ref;
        if (session) {
            kindCaptured[sessionKind]++;
        }
        dynR = r;
        dynG = g;
        dynB = b;
        dynA = a;
        dynLmU = SteinGameState.lightmapU();
        dynLmV = SteinGameState.lightmapV();
        dynFlags = flags;
        return true;
    }

    /** Does the draw sample unit 0's texture? (A position-only sky draw does not.) */
    private static boolean unit0Textured(int bits) {
        boolean textured = SteinGameState.texture2D(0);
        if (textured && skyNoUv && (bits & SteinTessCapture.HAS_UV) == 0) {
            // The sky (position only) with unit 0's texture still bound: with a screen open the
            // game turns the texture off with unit 1 active, and the fixed-function pipeline would
            // sample the screen's texture at a leftover coordinate. Without a texture, like the sky
            // without a screen open.
            textured = false;
        }
        return textured;
    }

    /**
     * The option flags of the dynamic draw; also sets the place of the instance that the overlay
     * (damage colour) or the current normal uses. -1 (already refused) if they cannot be recorded.
     */
    private static int dynamicFlags(
            int bits,
            boolean lighting,
            boolean currentNormal,
            boolean overlay,
            boolean vColor,
            boolean textured) {
        int flags = 0;
        if (lighting) {
            // The normal may not be unit length (the current one of the sign text and the arrow
            // is deliberately short, and the matrix scale compensates for it or not): the
            // fixed-function pipeline's handling, according to NORMALIZE and RESCALE.
            flags |= FLAG_LIGHTING;
            if (!SteinGameState.normalize()) {
                flags |= SteinGameState.rescaleNormal() ? FLAG_NRESCALE : FLAG_NRAW;
            }
            if (currentNormal) {
                if (overlay) {
                    // the current normal and the overlay use the same place in the instance
                    rejectDynamic("current normal with overlay");
                    return -1;
                }
                flags |= FLAG_CNORMAL;
                boolean known = SteinGlTrack.normalKnown();
                dynNx = known ? SteinGlTrack.normalX() : 0f;
                dynNy = known ? SteinGlTrack.normalY() : 1f;
                dynNz = known ? SteinGlTrack.normalZ() : 0f;
            }
        }
        if ((bits & SteinTessCapture.HAS_NORMAL) != 0) {
            SteinGlTrack.forgetNormal();
        }
        if (SteinGameState.texture2D(1)) {
            flags |= FLAG_LIGHTMAP;
            lightmapId = SteinGameState.textureName(1);
            if ((bits & SteinTessCapture.HAS_LIGHT) != 0) {
                flags |= FLAG_VLIGHT;
            }
        }
        if (SteinGameState.fog()) {
            flags |= FLAG_FOG;
        }
        if (vColor) {
            flags |= FLAG_VCOLOR;
        }
        if (!textured) {
            flags |= FLAG_NOTEX;
        }
        if (overlay) {
            flags |= FLAG_OVERLAY;
            dynNx = SteinGameState.overlay(0);
            dynNy = SteinGameState.overlay(1);
            dynNz = SteinGameState.overlay(2);
            dynOvA = SteinGameState.overlay(3);
        } else {
            dynOvA = 0f;
        }
        return flags;
    }

    static boolean rejectDynamic(String why) {
        dynRejected++;
        SteinHeldItem.recordBad();
        SteinTileRetain.taint();
        rejectWhy = why;
        return false;
    }

    /**
     * Reserves the instance and {@code verts} vertices in the frame ring; returns the address of
     * the vertices (the caller writes and calls {@link #commitDynamic}), or 0 if it does not fit
     * (draws immediately).
     */
    static long reserveDynamic(int verts) {
        long bytes = (long) verts * SteinTessCapture.VERTEX_BYTES;
        for (int tries = 0; tries < 2; tries++) {
            long inst = instances.reserve(INSTANCE_BYTES);
            long at = inst < 0 ? inst : dynVertices.reserve(bytes);
            if (inst == -1 || at == -1) {
                return 0L; // larger than a region
            }
            if (inst == -2 || at == -2) {
                flush();
                rotate();
                continue;
            }
            dynInst = inst;
            dynFirst = (int) (at / SteinTessCapture.VERTEX_BYTES);
            if (SteinTileRetain.recording) {
                // Block being recorded for retention: the vertices go first to memory of our own
                // (the ring is write-only) and commitDynamic copies them to the ring and to the
                // retention.
                dynRing = dynVertices.address(at);
                return SteinTileRetain.scratch(bytes);
            }
            return dynVertices.address(at);
        }
        return 0L;
    }

    /** The reserved dynamic draw, with {@code verts} vertices already written. */
    static void commitDynamic(int verts) {
        commitDynamic(verts, false);
    }

    /**
     * {@code texBaked}: the vertices' texture coordinate is already the final one ({@link
     * SteinTexGen}).
     */
    static void commitDynamic(int verts, boolean texBaked) {
        SteinHeldItem.recordBad(); // the held-item mould only accepts fixed geometry
        if (SteinTileRetain.recording) {
            commitDynamicRetained(verts, texBaked);
            return;
        }
        dynTexBaked = texBaked;
        putDynamic(instances.address(dynInst));
        dynTexBaked = false;
        command(group(dynKey), verts, dynFirst, (int) (dynInst / INSTANCE_BYTES));
        dynDraws++;
        dynVerts += verts;
    }

    /**
     * A fixed-buffer item model ({@link SteinTessCapture#staticGeometry()}); false = did not fit.
     */
    static boolean commitStatic(int first, int verts) {
        // Fixed geometry (item lists, owned ranges): retention does not cover it.
        SteinTileRetain.taint();
        long inst = reserveInstance();
        if (inst < 0) {
            return false;
        }
        putDynamic(instances.address(inst));
        command(group(dynKey | KEY_STATIC), verts, first, (int) (inst / INSTANCE_BYTES));
        dynDraws++;
        if (SteinHeldItem.recording) {
            SteinHeldItem.note(dynKey | KEY_STATIC, first, verts); // the pose's held-item mould
        }
        return true;
    }

    /**
     * The dynamic draw's instance: the current matrix and the state read in {@link #beginDynamic}.
     */
    static void putDynamic(long p) {
        putMatrixRows(p, GLMatrix.current(), 0);
        float[] t = dynTexBaked ? null : GLMatrix.textureOrNull();
        putTexture(p + 48, t, 0f, dynAlphaRef);
        // the overlay's place: the damage colour (FLAG_OVERLAY) or the current normal
        // (FLAG_CNORMAL)
        putMaterial(
                p + 80, dynR, dynG, dynB, dynA, dynLmU, dynLmV, dynFlags, dynNx, dynNy, dynNz,
                dynOvA);
    }

    /**
     * The "rest" (20 floats) of the current dynamic draw's instance, as {@link #putDynamic} writes
     * it (the held-item mould: {@link SteinHeldItem}).
     */
    static void dynRest(float[] out, int at) {
        float[] t = dynTexBaked ? null : GLMatrix.textureOrNull();
        putTexture(out, at, t, 0f, dynAlphaRef);
        putMaterial(
                out, at + 8, dynR, dynG, dynB, dynA, dynLmU, dynLmV, dynFlags, dynNx, dynNy, dynNz,
                dynOvA);
    }
}
