/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import dev.xavier.stein.bridge.core.Log;
import dev.xavier.stein.bridge.engine.GLMatrix;
import dev.xavier.stein.bridge.engine.ModelBatch;
import dev.xavier.stein.gfx.game.SteinGameFramebuffers;
import dev.xavier.stein.gfx.game.SteinGameTextures;
import dev.xavier.stein.gfx.gl.SteinCoreGl;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;

/**
 * OpenGL state deferred during the recorded entity pass ({@link SteinEntityDeferred}): the state
 * changes the game's GlStateManager makes (enable and disable, blend, depth, colour, bound texture,
 * active unit...) only update its cache and the record kept here; the driver receives the state
 * only when something still draws immediately — before any other OpenGL call by the game ({@link
 * ModelBatch#flush()}) and at the end of the pass.
 *
 * <p>The recorded parts do not need the state in the driver (their state travels with them, as
 * data): with almost everything recorded, the ~thousands of state changes per frame from the 400
 * players no longer reach the driver.
 *
 * <p>The diversions are the OpenGL call sites inside GlStateManager (bfl, bfl$c) and OpenGlHelper
 * (bqs.c, bqs.k), in the patcher's "solo" mode: the flush before the call does not go in there,
 * which these methods do themselves when they need to. Outside the pass (or without {@code
 * -Dsteinbridge.gfx.lazyState=true}) each method here is the original call with the flush before
 * it, as before.
 *
 * <p>Pipeline: both — the fixed pipeline always makes the original call; deferral only exists in
 * Stein GFX.
 */
public final class SteinLazyGl {

    public static final boolean REQUESTED = SteinGfxOptions.feature("lazyState", true);

    /**
     * No OpenGL context (the Vulkan backend): nothing is deferred and nothing reaches a driver; the
     * texture bindings and the depth mask go to the game's objects on the API, the rest lives in
     * the GlStateManager cache, which is where the renderer reads it.
     */
    private static final boolean VULKAN = SteinGfxOptions.VULKAN;

    private static boolean active;
    private static boolean dirty;
    private static boolean syncing;
    private static boolean warnedList;

    private SteinLazyGl() {}

    // ---- on and off (SteinEntityDeferred) ----

    static void activate() {
        if (VULKAN) {
            return;
        }
        if (!REQUESTED || active) {
            return;
        }
        active = true;
        realActive = GL13.GL_TEXTURE0 + SteinGameState.activeUnit();
        lazyActive = realActive;
    }

    static void deactivate() {
        if (!active) {
            return;
        }
        sync();
        active = false;
    }

    /** Is there state waiting to go to the driver? */
    public static boolean dirty() {
        return dirty;
    }

    /**
     * The state is being deferred right now (and a display list, which would record the call, is
     * not being built): whoever records state outside the diversions here (the texture filter of
     * {@link SteinTessCapture}) can just record it.
     */
    static boolean deferring() {
        return active && !GLMatrix.compiling();
    }

    // ---- the record ----

    private static final int MAX_CAPS = 64;

    /** Capability (with the unit on top, for GL_TEXTURE_2D) and whether it stays on. */
    private static final int[] capKey = new int[MAX_CAPS];

    private static final boolean[] capOn = new boolean[MAX_CAPS];
    private static int caps;

    private static final int MAX_UNITS = 8;

    /** A capability key packs the texture unit (+1) above this shift and the capability below. */
    private static final int CAP_UNIT_SHIFT = 16;

    private static final int CAP_MASK = 0xFFFF;

    private static final int[] bound = new int[MAX_UNITS];
    private static int boundDirty;

    private static int realActive;
    private static int lazyActive;

    private static final int BLEND = 1;
    private static final int BLEND_SEPARATE = 1 << 1;
    private static final int ALPHA = 1 << 2;
    private static final int DEPTH_FUNC = 1 << 3;
    private static final int DEPTH_MASK = 1 << 4;
    private static final int CULL_FACE = 1 << 5;
    private static final int COLOR = 1 << 6;
    private static final int COLOR_MASK = 1 << 7;
    private static final int SHADE = 1 << 8;
    private static final int COLOR_MATERIAL = 1 << 9;
    private static final int POLYGON_OFFSET = 1 << 10;
    private static final int LOGIC_OP = 1 << 11;
    private static int bits;

    private static int blendSrc;
    private static int blendDst;
    private static int blendSrcA;
    private static int blendDstA;
    private static int alphaFunc;
    private static float alphaRef;
    private static int depthFunc;
    private static boolean depthMask;
    private static int cullFace;
    private static float colorR;
    private static float colorG;
    private static float colorB;
    private static float colorA;
    private static boolean maskR;
    private static boolean maskG;
    private static boolean maskB;
    private static boolean maskA;
    private static int shade;
    private static int cmFace;
    private static int cmMode;
    private static float offsetFactor;
    private static float offsetUnits;
    private static int logicOp;

    /**
     * Before recording a change: the pending batch of parts (drawn immediately) goes out with the
     * previous state.
     */
    static void before() {
        if (ModelBatch.hasPending()) {
            ModelBatch.flush();
        }
        dirty = true;
    }

    private static void cap(int cap, boolean on) {
        before();
        int key =
                cap == GL11.GL_TEXTURE_2D
                        ? cap | (lazyActive - GL13.GL_TEXTURE0 + 1) << CAP_UNIT_SHIFT
                        : cap;
        for (int i = 0; i < caps; i++) {
            if (capKey[i] == key) {
                capOn[i] = on;
                return;
            }
        }
        if (caps == MAX_CAPS) {
            sync();
            before();
        }
        capKey[caps] = key;
        capOn[caps] = on;
        caps++;
    }

    /** Sends what is recorded to the driver. */
    public static void sync() {
        if (VULKAN) {
            return;
        }
        if (!dirty || syncing) {
            return;
        }
        if (GLMatrix.compiling()) {
            // Inside a display list (GL_COMPILE) the state would be recorded into it, not applied:
            // it stays pending.
            if (!warnedList) {
                warnedList = true;
                Log.info("gfx", "deferred state requested inside a display list (left pending)");
            }
            return;
        }
        syncing = true;
        dirty = false;
        try {
            for (int i = 0; i < caps; i++) {
                int key = capKey[i];
                int unit = key >>> CAP_UNIT_SHIFT;
                if (unit != 0) {
                    useUnit(GL13.GL_TEXTURE0 + unit - 1);
                }
                if (capOn[i]) {
                    SteinCoreGl.glEnable(key & CAP_MASK);
                } else {
                    SteinCoreGl.glDisable(key & CAP_MASK);
                }
            }
            caps = 0;
            for (int u = 0; boundDirty != 0 && u < MAX_UNITS; u++) {
                if ((boundDirty & 1 << u) != 0) {
                    useUnit(GL13.GL_TEXTURE0 + u);
                    GL11.glBindTexture(GL11.GL_TEXTURE_2D, bound[u]);
                    boundDirty &= ~(1 << u);
                }
            }
            useUnit(lazyActive);
            int b = bits;
            bits = 0;
            if ((b & BLEND) != 0) {
                GL11.glBlendFunc(blendSrc, blendDst);
            }
            if ((b & BLEND_SEPARATE) != 0) {
                GL14.glBlendFuncSeparate(blendSrc, blendDst, blendSrcA, blendDstA);
            }
            if ((b & ALPHA) != 0) {
                SteinCoreGl.glAlphaFunc(alphaFunc, alphaRef);
            }
            if ((b & DEPTH_FUNC) != 0) {
                GL11.glDepthFunc(depthFunc);
            }
            if ((b & DEPTH_MASK) != 0) {
                GL11.glDepthMask(depthMask);
            }
            if ((b & CULL_FACE) != 0) {
                GL11.glCullFace(cullFace);
            }
            if ((b & COLOR) != 0) {
                SteinCoreGl.glColor4f(colorR, colorG, colorB, colorA);
            }
            if ((b & COLOR_MASK) != 0) {
                GL11.glColorMask(maskR, maskG, maskB, maskA);
            }
            if ((b & SHADE) != 0) {
                SteinCoreGl.glShadeModel(shade);
            }
            if ((b & COLOR_MATERIAL) != 0) {
                SteinCoreGl.glColorMaterial(cmFace, cmMode);
            }
            if ((b & POLYGON_OFFSET) != 0) {
                GL11.glPolygonOffset(offsetFactor, offsetUnits);
            }
            if ((b & LOGIC_OP) != 0) {
                GL11.glLogicOp(logicOp);
            }
            if (lmDirty) {
                lmDirty = false;
                SteinCoreGl.glMultiTexCoord2f(lmUnit, lmU, lmV);
            }
            SteinTessCapture.syncFilters();
        } finally {
            syncing = false;
        }
    }

    private static void useUnit(int unit) {
        if (realActive != unit) {
            GL13.glActiveTexture(unit);
            realActive = unit;
        }
    }

    // ---- the diversions (call sites inside GlStateManager and OpenGlHelper) ----

    public static void glEnable(int cap) {
        if (!active) {
            ModelBatch.flush();
            SteinCoreGl.glEnable(cap);
            return;
        }
        cap(cap, true);
    }

    public static void glDisable(int cap) {
        if (!active) {
            ModelBatch.flush();
            SteinCoreGl.glDisable(cap);
            return;
        }
        cap(cap, false);
    }

    public static void glActiveTexture(int unit) {
        if (VULKAN) {
            SteinGameTextures.activeTexture(unit);
            return;
        }
        if (!active) {
            ModelBatch.flush();
            GL13.glActiveTexture(unit);
            return;
        }
        before();
        lazyActive = unit;
    }

    public static void glActiveTextureARB(int unit) {
        glActiveTexture(unit);
    }

    public static void glBindTexture(int target, int texture) {
        if (VULKAN) {
            SteinGameTextures.bind(target, texture);
            return;
        }
        int u = (active ? lazyActive : 0) - GL13.GL_TEXTURE0;
        if (!active || target != GL11.GL_TEXTURE_2D || u < 0 || u >= MAX_UNITS) {
            if (active) {
                sync();
            }
            ModelBatch.flush();
            GL11.glBindTexture(target, texture);
            return;
        }
        before();
        bound[u] = texture;
        boundDirty |= 1 << u;
    }

    public static void glBlendFunc(int src, int dst) {
        if (VULKAN) {
            return;
        }
        if (!active) {
            ModelBatch.flush();
            GL11.glBlendFunc(src, dst);
            return;
        }
        before();
        blendSrc = src;
        blendDst = dst;
        bits = bits & ~BLEND_SEPARATE | BLEND;
    }

    public static void glBlendFuncSeparate(int src, int dst, int srcA, int dstA) {
        if (VULKAN) {
            return;
        }
        if (!active) {
            ModelBatch.flush();
            GL14.glBlendFuncSeparate(src, dst, srcA, dstA);
            return;
        }
        before();
        blendSrc = src;
        blendDst = dst;
        blendSrcA = srcA;
        blendDstA = dstA;
        bits = bits & ~BLEND | BLEND_SEPARATE;
    }

    public static void glBlendFuncSeparateEXT(int src, int dst, int srcA, int dstA) {
        glBlendFuncSeparate(src, dst, srcA, dstA);
    }

    public static void glAlphaFunc(int func, float ref) {
        if (!active) {
            ModelBatch.flush();
            SteinCoreGl.glAlphaFunc(func, ref);
            return;
        }
        before();
        alphaFunc = func;
        alphaRef = ref;
        bits |= ALPHA;
    }

    public static void glDepthFunc(int func) {
        if (VULKAN) {
            return;
        }
        if (!active) {
            ModelBatch.flush();
            GL11.glDepthFunc(func);
            return;
        }
        before();
        depthFunc = func;
        bits |= DEPTH_FUNC;
    }

    public static void glDepthMask(boolean mask) {
        if (VULKAN) {
            SteinGameFramebuffers.depthMask(mask);
            return;
        }
        if (!active) {
            ModelBatch.flush();
            GL11.glDepthMask(mask);
            return;
        }
        before();
        depthMask = mask;
        bits |= DEPTH_MASK;
    }

    public static void glCullFace(int face) {
        if (VULKAN) {
            return;
        }
        if (!active) {
            ModelBatch.flush();
            GL11.glCullFace(face);
            return;
        }
        before();
        cullFace = face;
        bits |= CULL_FACE;
    }

    public static void glColor4f(float r, float g, float b, float a) {
        if (!active) {
            ModelBatch.flush();
            SteinCoreGl.glColor4f(r, g, b, a);
            return;
        }
        SteinTessCapture.noteColor(r, g, b, a);
        before();
        colorR = r;
        colorG = g;
        colorB = b;
        colorA = a;
        bits |= COLOR;
    }

    public static void glColorMask(boolean r, boolean g, boolean b, boolean a) {
        if (VULKAN) {
            SteinGameFramebuffers.colorMask(r, g, b, a);
            return;
        }
        if (!active) {
            ModelBatch.flush();
            GL11.glColorMask(r, g, b, a);
            return;
        }
        before();
        maskR = r;
        maskG = g;
        maskB = b;
        maskA = a;
        bits |= COLOR_MASK;
    }

    public static void glShadeModel(int mode) {
        if (!active) {
            ModelBatch.flush();
            SteinCoreGl.glShadeModel(mode);
            return;
        }
        before();
        shade = mode;
        bits |= SHADE;
    }

    public static void glColorMaterial(int face, int mode) {
        if (!active) {
            ModelBatch.flush();
            SteinCoreGl.glColorMaterial(face, mode);
            return;
        }
        before();
        cmFace = face;
        cmMode = mode;
        bits |= COLOR_MATERIAL;
    }

    public static void glPolygonOffset(float factor, float units) {
        if (VULKAN) {
            return;
        }
        if (!active) {
            ModelBatch.flush();
            GL11.glPolygonOffset(factor, units);
            return;
        }
        before();
        offsetFactor = factor;
        offsetUnits = units;
        bits |= POLYGON_OFFSET;
    }

    private static int lmUnit;
    private static float lmU;
    private static float lmV;
    private static boolean lmDirty;

    /**
     * OpenGlHelper.setLightmapTextureCoords (through SteinGameState): recorded if the state is
     * deferred (true); otherwise false and the caller makes the game's call.
     */
    static boolean lightmap(int unit, float u, float v) {
        if (!active) {
            return false;
        }
        before();
        lmUnit = unit;
        lmU = u;
        lmV = v;
        lmDirty = true;
        return true;
    }

    /**
     * GL11.glNormal3f on the sign, the arrow and the names (patcher rules, solo mode). The current
     * normal does not depend on the rest of the state: in the deferred pass only today's pending
     * batch parts (drawn immediately, with the previous normal) go out first; the deferred state
     * waits until something draws. Outside it, the usual flush.
     */
    public static void glNormal3f(float x, float y, float z) {
        if (!active || ModelBatch.hasPending()) {
            ModelBatch.flush();
        }
        SteinGlTrack.glNormal3f(x, y, z);
    }

    public static void glLogicOp(int op) {
        if (VULKAN) {
            return;
        }
        if (!active) {
            ModelBatch.flush();
            GL11.glLogicOp(op);
            return;
        }
        before();
        logicOp = op;
        bits |= LOGIC_OP;
    }
}
