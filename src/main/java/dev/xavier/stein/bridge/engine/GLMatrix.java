/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import dev.xavier.stein.bridge.core.Log;
import dev.xavier.stein.gfx.SteinEntityDeferred;
import dev.xavier.stein.gfx.SteinGfxOptions;
import dev.xavier.stein.gfx.gl.SteinCoreGl;

import net.minecraft.client.renderer.GlStateManager;

import org.lwjgl.opengl.GL13;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Arrays;

/**
 * MODELVIEW and TEXTURE (unit 0) matrix stacks kept in Java: the driver only receives the finished
 * matrix, in a glLoadMatrixf, when something is actually going to use it.
 *
 * <p>With 400 players in view, each part of each model is drawn with push, translate, rotate,
 * callList, pop — per frame, ~25 thousand pushes and well over 100 thousand matrix commands. Each
 * one is a JNI call and a command in the driver's queue. Here those operations become arithmetic in
 * Java, and each draw costs at most one glLoadMatrixf. The image does not change: the matrix that
 * reaches the driver is the same (up to float rounding), it just arrives finished.
 *
 * <p>TEXTURE: the enchantment glint rebuilds the texture matrix on every pass (loadIdentity, scale,
 * rotate, translate) — with the mode switch, ~6 calls per pass, 8 passes per player with enchanted
 * armor. On unit 0 this also stays in Java. Draws outside the batch receive the matrix at flush;
 * the batched parts ({@link ModelBatch}) receive the already transformed texture coordinate, and
 * the driver keeps the identity. Other units (lightmap on 1) go straight through.
 *
 * <p>The driver uses the matrices when drawing (glCallList, glDrawArrays, glBegin), when receiving
 * a light position (glLight) and a glTexGen plane: before each of those call sites the patcher
 * inserts {@link #flush} ("before" mode). glGetFloat of the two matrices is answered from here.
 * While a display list is being compiled nothing is deferred — whatever is called there is recorded
 * in the list, as in the original.
 *
 * <p>PROJECTION goes straight to the driver. The mode switch (glMatrixMode) waits until an
 * operation needs it.
 *
 * <p>{@code -Dsteinbridge.opt.vanillaMatrix=true}: everything straight to the driver, as in 1.8.9.
 * {@code -Dsteinbridge.opt.checkMatrix=true}: everything straight to the driver and the Java stacks
 * in parallel; every 64 draws it compares with the matrices read from the driver.
 *
 * <p>Pipeline: both — the fixed pipeline sends the finished matrix to the driver when something
 * uses it; Stein GFX only reads the stacks and sends nothing.
 */
public final class GLMatrix {

    private static final int GL_MODELVIEW = 0x1700;
    private static final int GL_PROJECTION = 0x1701;
    private static final int GL_TEXTURE = 0x1702;
    private static final int GL_MODELVIEW_MATRIX = 0x0BA6;
    private static final int GL_TEXTURE_MATRIX = 0x0BA8;

    /**
     * Stein GFX (core profile): the driver has no matrix stack, so the Java stacks are always the
     * emulated ones and nothing is sent to it.
     */
    private static final boolean CORE = SteinGfxOptions.CORE;

    private static final boolean VANILLA = !CORE && EngineOptions.VANILLA_MATRIX;
    private static final boolean CHECK = !CORE && !VANILLA && EngineOptions.CHECK_MATRIX;

    /** Java stacks updated — emulating or checking. */
    private static final boolean SHADOW = !VANILLA;

    /** Emulated operations do not go to the driver; only the finished matrix, at flush. */
    private static final boolean EMULATE = SHADOW && !CHECK;

    /** TEXTURE kept in Java too (not with {@code vanillaTextureMatrix}). */
    private static final boolean SHADOW_TEXTURE = CORE || !EngineOptions.VANILLA_TEXTURE_MATRIX;

    /** Tolerance of the check, relative to the matrix's largest element. */
    private static final float TOLERANCE = 1e-4f;

    /** Destination of a matrix operation. */
    private static final int PASS = 0;

    private static final int MV = 1;
    private static final int TEX = 2;

    private static final float[] IDENTITY = new float[16];

    // ---- MODELVIEW ----
    private static final float[] M = new float[16];

    /** Last MODELVIEW sent to the driver. */
    private static final float[] LOADED = new float[16];

    private static float[][] stack = new float[64][16];
    private static int depth;
    private static boolean dirty;

    /** Is LOADED the identity (loaded by the batch)? Starts that way, like GL. */
    private static boolean loadedIdentity = true;

    // ---- TEXTURE of unit 0 ----
    private static final float[] T = new float[16];

    /** Last texture matrix (unit 0) sent to the driver. */
    private static final float[] TLOADED = new float[16];

    private static float[][] texStack = new float[8][16];

    /**
     * Per level of the TEXTURE stack: the saved matrix was the identity (the texIdentity from when
     * it was pushed).
     */
    private static boolean[] texStackIdentity = new boolean[8];

    private static int texDepth;
    private static boolean texDirty;
    private static boolean texIdentity = true;
    private static boolean texLoadedIdentity = true;

    private static final float[] SCRATCH = new float[16];

    /** Mode requested by the game and mode the driver is actually in. */
    private static int mode = GL_MODELVIEW;

    private static int driverMode = GL_MODELVIEW;
    private static boolean compiling;
    private static boolean warnedList;

    private static final FloatBuffer BUF =
            ByteBuffer.allocateDirect(16 * Float.BYTES)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer();

    private static long draws;
    private static final CheckReport REPORT = new CheckReport(10_000_000_000L);
    private static float maxError;

    static {
        Mat4.identity(IDENTITY);
        Mat4.identity(M);
        Mat4.identity(LOADED);
        Mat4.identity(T);
        Mat4.identity(TLOADED);
        if (CHECK) {
            Log.info("matrix", "check: Java stacks compared with the driver ones every 64 draws");
        } else if (VANILLA) {
            Log.info("matrix", "driver matrix stack (vanilla)");
        }
    }

    private GLMatrix() {}

    // ---- GlStateManager: diverted from all of the game's call sites ----

    public static void matrixMode(int m) {
        if (compiling) {
            warnList();
            GlStateManager.matrixMode(m);
            return;
        }
        mode = m;
        if (!EMULATE) {
            GlStateManager.matrixMode(m);
            driverMode = m;
        }
    }

    public static void pushMatrix() {
        int t = target();
        if (t == MV) {
            if (depth == stack.length) {
                stack = grow(stack);
            }
            System.arraycopy(M, 0, stack[depth++], 0, 16);
        } else if (t == TEX) {
            if (texDepth == texStack.length) {
                texStack = grow(texStack);
                texStackIdentity = Arrays.copyOf(texStackIdentity, texStack.length);
            }
            texStackIdentity[texDepth] = texIdentity;
            System.arraycopy(T, 0, texStack[texDepth++], 0, 16);
        } else {
            syncMode();
        }
        if (t == PASS || !EMULATE) {
            GlStateManager.pushMatrix();
        }
    }

    public static void popMatrix() {
        int t = target();
        // Empty stack: GL ignores it (GL_STACK_UNDERFLOW); so does this.
        if (t == MV) {
            if (depth > 0) {
                System.arraycopy(stack[--depth], 0, M, 0, 16);
                dirty = true;
            }
        } else if (t == TEX) {
            if (texDepth > 0) {
                System.arraycopy(texStack[--texDepth], 0, T, 0, 16);
                // The matrix goes back to what it was when pushed: if it was the identity, that is
                // already known (no need to compare again).
                texDirty = true;
                texIdentity = texStackIdentity[texDepth];
            }
        } else {
            syncMode();
        }
        if (t == PASS || !EMULATE) {
            GlStateManager.popMatrix();
        }
    }

    public static void loadIdentity() {
        int t = target();
        if (t != PASS) {
            Mat4.identity(shadow(t));
            if (t == TEX) {
                texDirty = true;
                texIdentity = true; // it just became the identity
            } else {
                touch(t);
            }
            if (EMULATE) {
                return;
            }
        } else {
            syncMode();
        }
        GlStateManager.loadIdentity();
    }

    public static void translate(float x, float y, float z) {
        int t = target();
        if (t != PASS) {
            Mat4.translate(shadow(t), x, y, z);
            touch(t);
            if (EMULATE) {
                return;
            }
        } else {
            syncMode();
        }
        GlStateManager.translate(x, y, z);
    }

    /** Like the driver, converts to float before the arithmetic. */
    public static void translated(double x, double y, double z) {
        int t = target();
        if (t != PASS) {
            Mat4.translate(shadow(t), (float) x, (float) y, (float) z);
            touch(t);
            if (EMULATE) {
                return;
            }
        } else {
            syncMode();
        }
        GlStateManager.translate(x, y, z);
    }

    public static void rotate(float angle, float x, float y, float z) {
        int t = target();
        if (t != PASS) {
            Mat4.rotate(shadow(t), angle, x, y, z);
            touch(t);
            if (EMULATE) {
                return;
            }
        } else {
            syncMode();
        }
        GlStateManager.rotate(angle, x, y, z);
    }

    public static void scale(float x, float y, float z) {
        int t = target();
        if (t != PASS) {
            Mat4.scale(shadow(t), x, y, z);
            touch(t);
            if (EMULATE) {
                return;
            }
        } else {
            syncMode();
        }
        GlStateManager.scale(x, y, z);
    }

    public static void scaled(double x, double y, double z) {
        int t = target();
        if (t != PASS) {
            Mat4.scale(shadow(t), (float) x, (float) y, (float) z);
            touch(t);
            if (EMULATE) {
                return;
            }
        } else {
            syncMode();
        }
        GlStateManager.scale(x, y, z);
    }

    /** Reads 16 floats from the buffer's position, like glMultMatrixf. */
    public static void multMatrix(FloatBuffer b) {
        int t = target();
        if (t != PASS) {
            b.get(b.position(), SCRATCH);
            Mat4.mul(shadow(t), SCRATCH);
            touch(t);
            if (EMULATE) {
                return;
            }
        } else {
            syncMode();
        }
        GlStateManager.multMatrix(b);
    }

    public static void ortho(double l, double r, double b, double t0, double n, double f) {
        int t = target();
        if (t != PASS) {
            Mat4.ortho(
                    shadow(t), (float) l, (float) r, (float) b, (float) t0, (float) n, (float) f);
            touch(t);
            if (EMULATE) {
                return;
            }
        } else {
            syncMode();
        }
        GlStateManager.ortho(l, r, b, t0, n, f);
    }

    /**
     * The emulated matrices come from here; the rest, from the driver. Writes from the position,
     * like glGetFloatv.
     */
    public static void getFloat(int pname, FloatBuffer out) {
        if (EMULATE && pname == GL_MODELVIEW_MATRIX) {
            out.put(out.position(), M);
            return;
        }
        if (EMULATE && pname == GL_TEXTURE_MATRIX && unit0()) {
            out.put(out.position(), T);
            return;
        }
        GlStateManager.getFloat(pname, out);
    }

    // ---- hooks inserted before GL11 call sites ----

    /** Before each use of the matrices by the driver: draw, light, texgen. */
    public static void flush() {
        if (CORE || compiling) {
            return; // the core profile has no matrix to load: Stein GFX reads them from here
        }
        // Pending batched parts go out first, with their matrices (identity).
        ModelBatch.flush();
        if (CHECK) {
            check();
            return;
        }
        if (!EMULATE) {
            return;
        }
        if (texDirty) {
            texDirty = false;
            loadTexture(T, texIdentity);
        }
        // Even without a pending matrix: chunk display lists carry recorded push/mult/pop,
        // which the driver applies to the current mode when the list is called.
        if (driverMode != GL_MODELVIEW) {
            SteinCoreGl.glMatrixMode(GL_MODELVIEW);
            driverMode = GL_MODELVIEW;
        }
        if (!dirty) {
            return;
        }
        dirty = false;
        if (Arrays.equals(M, LOADED)) {
            return; // a push/pop that returned the matrix already sent
        }
        BUF.put(0, M);
        SteinCoreGl.glLoadMatrixf(BUF);
        System.arraycopy(M, 0, LOADED, 0, 16);
        loadedIdentity = false;
    }

    /** Before glNewList: the pending matrices go out first, outside the list. */
    public static void beginList() {
        if (CORE) {
            // What is pending goes out first, outside the list, and the tracked mode goes back to
            // MODELVIEW (chunk lists carry recorded push/mult/pop that apply to the current mode).
            ModelBatch.flush();
            if (driverMode != GL_MODELVIEW) {
                SteinCoreGl.glMatrixMode(GL_MODELVIEW);
                driverMode = GL_MODELVIEW;
            }
        } else {
            flush();
        }
        compiling = true;
    }

    /** Before glEndList. */
    public static void endList() {
        compiling = false;
    }

    /**
     * Recording a display list (between glNewList and glEndList): nothing can go to the driver
     * outside it.
     */
    public static boolean compiling() {
        return compiling;
    }

    // ---- for the ModelBatch ----

    public static boolean emulating() {
        return EMULATE;
    }

    /** Current MODELVIEW (read-only). */
    public static float[] current() {
        return M;
    }

    /** Texture matrix of unit 0, or null if identity (read-only). */
    public static float[] textureOrNull() {
        return texIdentity ? null : T;
    }

    /**
     * Can the part join the batch? Affine MODELVIEW, and a texture applicable on the CPU: affine in
     * (s, t) with r = 0 and q = 1 — no projective division.
     */
    public static boolean batchable() {
        if (compiling || mode != GL_MODELVIEW && mode != GL_TEXTURE) {
            return false;
        }
        if (M[3] != 0f || M[7] != 0f || M[11] != 0f || M[15] != 1f) {
            return false;
        }
        return texIdentity || (T[3] == 0f && T[7] == 0f && T[15] == 1f);
    }

    /**
     * The batch draws with vertices already in eye space and texture coordinates already
     * transformed: MODELVIEW and TEXTURE (unit 0) are the identity on the driver.
     */
    static void loadIdentityForBatch() {
        if (CORE) {
            return;
        }
        if (!texLoadedIdentity) {
            loadTexture(IDENTITY, true);
            texDirty = !texIdentity; // the next draw outside the batch needs T back
        }
        if (driverMode != GL_MODELVIEW) {
            SteinCoreGl.glMatrixMode(GL_MODELVIEW);
            driverMode = GL_MODELVIEW;
        }
        // Boolean, not a comparison of 16 floats: runs on every batch (~5 thousand per frame).
        if (!loadedIdentity) {
            BUF.put(0, IDENTITY);
            SteinCoreGl.glLoadMatrixf(BUF);
            System.arraycopy(IDENTITY, 0, LOADED, 0, 16);
            loadedIdentity = true;
        }
        dirty = true; // the next flush compares M with the loaded identity
    }

    // ---- internal ----

    /** Where the operation goes: MODELVIEW stack, unit 0's TEXTURE stack, or the driver. */
    private static int target() {
        if (compiling) {
            warnList();
            return PASS;
        }
        if (!SHADOW) {
            return PASS;
        }
        if (mode == GL_MODELVIEW) {
            return MV;
        }
        if (mode == GL_TEXTURE && SHADOW_TEXTURE && unit0()) {
            return TEX;
        }
        return PASS;
    }

    private static float[] shadow(int t) {
        return t == MV ? M : T;
    }

    private static void touch(int t) {
        if (t == MV) {
            dirty = true;
        } else {
            touchTexture();
        }
    }

    private static void touchTexture() {
        texDirty = true;
        texIdentity = isIdentity(T);
    }

    private static final int ONE_BITS = Float.floatToRawIntBits(1f);

    /**
     * The same as {@code Arrays.equals(m, IDENTITY)}, without the general array comparison path
     * (the item glint touches the texture matrix ~8 times per item, and that path was the hottest
     * leaf in the crowd). Bit by bit: the identity only has 0 and 1, which no NaN nor -0 equals, so
     * the result is the same as Arrays.equals. Exits at the first difference (after scale or
     * rotate, right at the first element).
     */
    private static boolean isIdentity(float[] m) {
        for (int i = 0; i < 16; i++) {
            if (Float.floatToRawIntBits(m[i]) != ((i % 5 == 0) ? ONE_BITS : 0)) {
                return false;
            }
        }
        return true;
    }

    private static int activeUnit() {
        return GlStateManager.activeTextureUnit;
    }

    private static boolean unit0() {
        return activeUnit() == 0;
    }

    /** Loads a matrix into the driver's unit 0 TEXTURE, if it changed. */
    private static void loadTexture(float[] want, boolean identity) {
        if (identity ? texLoadedIdentity : Arrays.equals(want, TLOADED)) {
            return;
        }
        boolean other = !unit0();
        int unit = 0;
        if (other) {
            unit = activeUnit();
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
        }
        if (driverMode != GL_TEXTURE) {
            SteinCoreGl.glMatrixMode(GL_TEXTURE);
            driverMode = GL_TEXTURE;
        }
        BUF.put(0, want);
        SteinCoreGl.glLoadMatrixf(BUF);
        System.arraycopy(want, 0, TLOADED, 0, 16);
        texLoadedIdentity = identity;
        if (other) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
        }
    }

    /** Before an operation that goes to the driver, the driver must be in the game's mode. */
    private static void syncMode() {
        // The pending batch goes out before the mode switch: it loads the identity into MODELVIEW.
        ModelBatch.flush();
        if (mode == GL_PROJECTION) {
            // The projection is about to change (or be read): what was recorded so far goes out
            // with the current one (the flush reads the projection from
            // GL; the menu panorama draws in perspective in the middle of the screen's pass).
            SteinEntityDeferred.barrier();
        }
        if (EMULATE && !compiling && driverMode != mode) {
            SteinCoreGl.glMatrixMode(mode);
            driverMode = mode;
        }
    }

    private static float[][] grow(float[][] s) {
        float[][] bigger = Arrays.copyOf(s, s.length * 2);
        for (int i = s.length; i < bigger.length; i++) {
            bigger[i] = new float[16];
        }
        return bigger;
    }

    /**
     * A matrix operation during display list compilation goes to the list, not to the Java stack.
     * In 1.8.9 this happens in the chunk upload (ChunkRenderDispatcher: push, multMatrix, draw,
     * pop): the driver applies the segment when the list is called, on top of the matrix loaded at
     * flush, and the pop gives it back — which is why flush guarantees the driver is in MODELVIEW.
     * Reports the first origin, so that another, unbalanced one is noticed.
     */
    private static void warnList() {
        if (SHADOW && !warnedList) {
            warnedList = true;
            Log.trace(
                    "matrix",
                    "matrix operation recorded in a display list (goes to the driver);"
                            + " first origin:");
        }
    }

    private static void check() {
        if ((++draws & 63) != 0) {
            return;
        }
        compare(GL_MODELVIEW_MATRIX, M, "modelview");
        if (unit0()) {
            compare(GL_TEXTURE_MATRIX, T, "texture");
        }
        if (REPORT.due()) {
            Log.info(
                    "matrix",
                    "checked "
                            + REPORT.checks
                            + " | divergences "
                            + REPORT.divergences
                            + " | largest relative error "
                            + maxError);
        }
    }

    private static void compare(int pname, float[] mine, String what) {
        BUF.clear();
        SteinCoreGl.glGetFloatv(pname, BUF);
        float scale = 1f;
        for (float v : mine) {
            scale = Math.max(scale, Math.abs(v));
        }
        float err = 0f;
        for (int i = 0; i < 16; i++) {
            err = Math.max(err, Math.abs(BUF.get(i) - mine[i]));
        }
        err /= scale;
        maxError = Math.max(maxError, err);
        if (REPORT.compared(!(err > TOLERANCE))) {
            float[] driver = new float[16];
            BUF.get(0, driver);
            Log.trace(
                    "matrix",
                    "DIVERGENCE ("
                            + what
                            + ") error "
                            + err
                            + "\n  driver "
                            + Arrays.toString(driver)
                            + "\n  java   "
                            + Arrays.toString(mine));
        }
    }
}
