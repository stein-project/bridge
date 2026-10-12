/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import dev.xavier.stein.bridge.engine.GLMatrix;
import dev.xavier.stein.gfx.gl.SteinCoreGl;
import dev.xavier.stein.gfx.gl.SteinGlBindings;
import dev.xavier.stein.gfx.gl.SteinGlFrame;

import org.lwjgl.opengl.ARBFramebufferObject;
import org.lwjgl.opengl.ARBShaderObjects;
import org.lwjgl.opengl.EXTFramebufferObject;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.nio.FloatBuffer;

/**
 * OpenGL state that the game's GlStateManager does not keep, or keeps differently from what the
 * driver uses, tracked at the game's call sites (patcher rules) so that the deferred renderer
 * ({@link SteinEntityDeferred}) knows in which state the draw would have come out. A deliberately
 * light class: the detours run from the start of the game, before {@link SteinGameState} can read
 * the GlStateManager.
 *
 * <ul>
 *   <li><b>Blend alpha factors.</b> {@code GlStateManager.blendFunc(src, dst)} (bfl.b(II)) only
 *       updates the cache's colour fields (b, c), but the driver's glBlendFunc applies to colour
 *       and alpha; {@code tryBlendFuncSeparate} (bfl.a(IIII)) updates all four. The cache's alpha
 *       fields (d, e) are only valid if the last blend that reached the driver was the separate
 *       one.
 *   <li><b>Current normal</b> ({@code glNormal3f}): used by lighting when the draw carries no
 *       per-vertex normal (the sign text, the arrow). After a draw with per-vertex normals the
 *       current value is undefined in OpenGL; from then on, until the next glNormal3f, it counts as
 *       unknown.
 * </ul>
 *
 * <p>Pipeline: both — the game's call still reaches the driver in the fixed pipeline; the tracked
 * state is read only by Stein GFX.
 */
public final class SteinGlTrack {

    /** Sync reason: the game deleted a framebuffer. */
    private static final String GAME_FRAMEBUFFER_DELETED = "game framebuffer deleted";

    private SteinGlTrack() {}

    // ---- blend: the last call that reached the driver ----

    /**
     * The last blend issued was the separate one (initially, OpenGL's: ONE, ZERO for both, equal to
     * the cache).
     */
    private static boolean blendSeparate = true;

    /**
     * GL11.glBlendFunc inside bfl.b(II) and bqs.c(IIII) (patcher rules, in place of SteinLazyGl's
     * detour).
     */
    public static void glBlendFunc(int src, int dst) {
        blendSeparate = false;
        SteinLazyGl.glBlendFunc(src, dst);
    }

    /** GL14.glBlendFuncSeparate inside bqs.c(IIII). */
    public static void glBlendFuncSeparate(int src, int dst, int srcA, int dstA) {
        blendSeparate = true;
        SteinLazyGl.glBlendFuncSeparate(src, dst, srcA, dstA);
    }

    /** EXTBlendFuncSeparate.glBlendFuncSeparateEXT inside bqs.c(IIII). */
    public static void glBlendFuncSeparateEXT(int src, int dst, int srcA, int dstA) {
        blendSeparate = true;
        SteinLazyGl.glBlendFuncSeparateEXT(src, dst, srcA, dstA);
    }

    /** The cache's alpha fields are valid (otherwise alpha uses the same factors as colour). */
    static boolean blendSeparate() {
        return blendSeparate;
    }

    // ---- framebuffer, viewport and program, restored by the pass (gl.SteinGlBindings) ----
    //
    // Patcher rules (redirects-opt.tsv) at the call sites of the game and of mods: the same OpenGL
    // call, and the value is noted so the pass does not need to ask the driver. They apply with the
    // renderer off too (they only note).

    /** GL30.glBindFramebuffer (OpenGlHelper.glBindFramebuffer, the GL 3.0 path). */
    public static void glBindFramebuffer(int target, int fb) {
        GL30.glBindFramebuffer(target, fb);
        SteinGlBindings.noteFramebuffer(target, fb);
    }

    /** ARBFramebufferObject.glBindFramebuffer (OpenGlHelper's ARB path). */
    public static void glBindFramebufferARB(int target, int fb) {
        ARBFramebufferObject.glBindFramebuffer(target, fb);
        SteinGlBindings.noteFramebuffer(target, fb);
    }

    /**
     * EXTFramebufferObject.glBindFramebufferEXT (the EXT path: GL_FRAMEBUFFER_EXT has the value of
     * GL_FRAMEBUFFER).
     */
    public static void glBindFramebufferEXT(int target, int fb) {
        EXTFramebufferObject.glBindFramebufferEXT(target, fb);
        SteinGlBindings.noteFramebuffer(target, fb);
    }

    /**
     * GL30.glDeleteFramebuffers: deleting the bound framebuffer returns the binding to 0. In the
     * recorded frame it is a synchronisation point: a recorded pass may have this framebuffer as
     * its target (the game's, rebuilt when the window changes).
     */
    public static void glDeleteFramebuffers(int fb) {
        SteinGlFrame.sync(GAME_FRAMEBUFFER_DELETED);
        GL30.glDeleteFramebuffers(fb);
        SteinGlBindings.noteDeleteFramebuffer(fb);
    }

    public static void glDeleteFramebuffersARB(int fb) {
        SteinGlFrame.sync(GAME_FRAMEBUFFER_DELETED);
        ARBFramebufferObject.glDeleteFramebuffers(fb);
        SteinGlBindings.noteDeleteFramebuffer(fb);
    }

    public static void glDeleteFramebuffersEXT(int fb) {
        SteinGlFrame.sync(GAME_FRAMEBUFFER_DELETED);
        EXTFramebufferObject.glDeleteFramebuffersEXT(fb);
        SteinGlBindings.noteDeleteFramebuffer(fb);
    }

    // ---- recorded frame (gl.SteinGlFrame): what the game does that depends on the order of the
    // recorded passes ----

    /**
     * GL11.glClear throughout the game (and in mods): with a recorded pass waiting, the clear goes
     * into the list with the state of the moment (the depth cleared before the hand sits between
     * the world and the hand, without splitting the submission); otherwise, immediately.
     */
    public static void glClear(int mask) {
        if (SteinGlFrame.pending()) {
            SteinGlFrame.gameClear(mask);
            return;
        }
        GL11.glClear(mask);
    }

    /** GL11.glClearColor (GlStateManager.clearColor): noted for the recorded clear. */
    public static void glClearColor(float r, float g, float b, float a) {
        GL11.glClearColor(r, g, b, a);
        SteinGlBindings.noteClearColor(r, g, b, a);
    }

    /** GL11.glClearDepth (GlStateManager.clearDepth). */
    public static void glClearDepth(double d) {
        GL11.glClearDepth(d);
        SteinGlBindings.noteClearDepth(d);
    }

    /**
     * GL11.glLineWidth of the game and of mods (the outline of the targeted block sets 2): noted,
     * because the recorded pass draws the lines with the width at recording time, not the one at
     * the end of the frame.
     */
    public static void glLineWidth(float w) {
        GL11.glLineWidth(w);
        SteinGlBindings.noteLineWidth(w);
    }

    /** Before the game's glTexImage2D / glTexSubImage2D: the texture may be in a recorded pass. */
    public static void beforeTexImage() {
        SteinGlFrame.sync("game texture uploaded");
    }

    /**
     * Before the game's glCopyTexSubImage2D: it reads the screen, which the recorded passes have
     * not drawn yet.
     */
    public static void beforeCopyScreen() {
        SteinGlFrame.sync("game screen copy");
    }

    /** Before the game's glReadPixels (screenshot). */
    public static void beforeReadPixels() {
        SteinGlFrame.sync("game screen read");
    }

    /**
     * Before the game's glGetTexImage: it reads the texture, and a copy or parameter recorded
     * earlier has not reached it yet.
     */
    public static void beforeGetTexImage() {
        SteinGlFrame.sync("game texture read");
    }

    /**
     * Before the game's glDrawArrays: in core nothing from the game should reach the driver (Stein
     * GFX draws everything); if it does, it goes out after what was recorded before, in the game's
     * order.
     */
    public static void beforeGameDraw() {
        SteinGlFrame.sync("game direct draw");
    }

    /** Before the game's glDeleteTextures: the name may return to another texture before replay. */
    public static void beforeDeleteTexture() {
        SteinGlFrame.sync("game texture deleted");
    }

    /** GL20.glUseProgram (OpenGlHelper.glUseProgram). */
    public static void glUseProgram(int p) {
        GL20.glUseProgram(p);
        SteinGlBindings.noteProgram(p);
    }

    /** ARBShaderObjects.glUseProgramObjectARB (OpenGlHelper's ARB path). */
    public static void glUseProgramObjectARB(int p) {
        ARBShaderObjects.glUseProgramObjectARB(p);
        SteinGlBindings.noteProgram(p);
    }

    /**
     * GL11.glViewport throughout the game (in place of the rule that went straight to {@link
     * SteinEntityDeferred#glViewport}): notes it and moves on to there, which flushes what was
     * recorded under the previous viewport and calls OpenGL.
     */
    public static void glViewport(int x, int y, int w, int h) {
        SteinEntityDeferred.glViewport(x, y, w, h);
        SteinGlBindings.noteViewport(x, y, w, h);
    }

    // ---- scissor (glScissor) of the Loader's and mods' screens ----

    /** The scissor enabled now (x, y, width, height, in window pixels), or null. */
    private static int[] scissor;

    /**
     * Turns the screens' scissor on ({@code on}) or off (the Loader's Ui.clip). With the renderer
     * recording, what was recorded before goes out before the change, and the following passes go
     * out with the current scissor ({@link #scissor()}); without it, just the OpenGL call, as
     * always.
     */
    public static void scissor(boolean on, int x, int y, int w, int h) {
        SteinEntityDeferred.barrier();
        if (on) {
            scissor = new int[] {x, y, Math.max(0, w), Math.max(0, h)};
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            GL11.glScissor(x, y, Math.max(0, w), Math.max(0, h));
        } else {
            scissor = null;
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
        }
    }

    /** The scissor enabled now, or null. */
    static int[] scissor() {
        return scissor;
    }

    // ---- planes of texture coordinate generation (glTexGen), which the GlStateManager does not
    // keep ----

    /** Per coordinate (S, T, R, Q), 4 floats: the object plane and the eye plane. */
    private static final float[] objectPlane = new float[16];

    private static final float[] eyePlane = new float[16];
    private static final boolean[] objectKnown = new boolean[4];
    private static final boolean[] eyeKnown = new boolean[4];
    private static final float[] scratchInverse = new float[16];

    /**
     * GL11.glTexGenfv inside GlStateManager.texGen (patcher rule): stores and moves on. The eye
     * plane is stored as OpenGL stores it: multiplied by the inverse of the current modelview (the
     * GLMatrix already sent it to the driver before this call, and the Java one is the same).
     */
    public static void glTexGenfv(int coord, int pname, FloatBuffer params) {
        int c = coord - GL11.GL_S;
        if (c >= 0 && c < 4 && params.remaining() >= 4) {
            int at = params.position();
            float p0 = params.get(at);
            float p1 = params.get(at + 1);
            float p2 = params.get(at + 2);
            float p3 = params.get(at + 3);
            if (pname == GL11.GL_OBJECT_PLANE) {
                objectPlane[c * 4] = p0;
                objectPlane[c * 4 + 1] = p1;
                objectPlane[c * 4 + 2] = p2;
                objectPlane[c * 4 + 3] = p3;
                objectKnown[c] = true;
            } else if (pname == GL11.GL_EYE_PLANE) {
                eyeKnown[c] = invert(GLMatrix.current(), scratchInverse);
                for (int j = 0; j < 4; j++) {
                    // row * inverse: p_eye[j] = sum_i p[i] * inverse(i, j); the inverse is in
                    // columns, so inverse(i, j) = scratchInverse[j * 4 + i]
                    eyePlane[c * 4 + j] =
                            p0 * scratchInverse[j * 4]
                                    + p1 * scratchInverse[j * 4 + 1]
                                    + p2 * scratchInverse[j * 4 + 2]
                                    + p3 * scratchInverse[j * 4 + 3];
                }
            }
        }
        SteinCoreGl.glTexGenfv(coord, pname, params);
    }

    /**
     * The plane of coordinate {@code c} (object or eye) in {@code out[o..o+3]}; false = it was
     * never given.
     */
    static boolean plane(int c, boolean eye, float[] out, int o) {
        float[] src = eye ? eyePlane : objectPlane;
        System.arraycopy(src, c * 4, out, o, 4);
        return eye ? eyeKnown[c] : objectKnown[c];
    }

    /** 4x4 inverse (in columns, like OpenGL) of {@code m} in {@code out}; false = singular. */
    static boolean invert(float[] m, float[] out) {
        float a00 = m[0], a01 = m[4], a02 = m[8], a03 = m[12];
        float a10 = m[1], a11 = m[5], a12 = m[9], a13 = m[13];
        float a20 = m[2], a21 = m[6], a22 = m[10], a23 = m[14];
        float a30 = m[3], a31 = m[7], a32 = m[11], a33 = m[15];
        float b00 = a00 * a11 - a01 * a10;
        float b01 = a00 * a12 - a02 * a10;
        float b02 = a00 * a13 - a03 * a10;
        float b03 = a01 * a12 - a02 * a11;
        float b04 = a01 * a13 - a03 * a11;
        float b05 = a02 * a13 - a03 * a12;
        float b06 = a20 * a31 - a21 * a30;
        float b07 = a20 * a32 - a22 * a30;
        float b08 = a20 * a33 - a23 * a30;
        float b09 = a21 * a32 - a22 * a31;
        float b10 = a21 * a33 - a23 * a31;
        float b11 = a22 * a33 - a23 * a32;
        float det = b00 * b11 - b01 * b10 + b02 * b09 + b03 * b08 - b04 * b07 + b05 * b06;
        if (det == 0f) {
            return false;
        }
        float k = 1f / det;
        // (row, column) -> out[column * 4 + row]
        out[0] = (a11 * b11 - a12 * b10 + a13 * b09) * k;
        out[4] = (-a01 * b11 + a02 * b10 - a03 * b09) * k;
        out[8] = (a31 * b05 - a32 * b04 + a33 * b03) * k;
        out[12] = (-a21 * b05 + a22 * b04 - a23 * b03) * k;
        out[1] = (-a10 * b11 + a12 * b08 - a13 * b07) * k;
        out[5] = (a00 * b11 - a02 * b08 + a03 * b07) * k;
        out[9] = (-a30 * b05 + a32 * b02 - a33 * b01) * k;
        out[13] = (a20 * b05 - a22 * b02 + a23 * b01) * k;
        out[2] = (a10 * b10 - a11 * b08 + a13 * b06) * k;
        out[6] = (-a00 * b10 + a01 * b08 - a03 * b06) * k;
        out[10] = (a30 * b04 - a31 * b02 + a33 * b00) * k;
        out[14] = (-a20 * b04 + a21 * b02 - a23 * b00) * k;
        out[3] = (-a10 * b09 + a11 * b07 - a12 * b06) * k;
        out[7] = (a00 * b09 - a01 * b07 + a02 * b06) * k;
        out[11] = (-a30 * b03 + a31 * b01 - a32 * b00) * k;
        out[15] = (a20 * b03 - a21 * b01 + a22 * b00) * k;
        return true;
    }

    // ---- current normal ----

    private static boolean normalKnown;
    private static float normalX;
    private static float normalY;
    private static float normalZ = 1f;

    /** GL11.glNormal3f throughout the game (patcher rule): stores and moves on. */
    public static void glNormal3f(float x, float y, float z) {
        normalX = x;
        normalY = y;
        normalZ = z;
        normalKnown = true;
        SteinCoreGl.glNormal3f(x, y, z);
    }

    /**
     * A draw with per-vertex normals (or the start of the pass): the current one becomes undefined.
     */
    static void forgetNormal() {
        normalKnown = false;
    }

    static boolean normalKnown() {
        return normalKnown;
    }

    static float normalX() {
        return normalX;
    }

    static float normalY() {
        return normalY;
    }

    static float normalZ() {
        return normalZ;
    }
}
