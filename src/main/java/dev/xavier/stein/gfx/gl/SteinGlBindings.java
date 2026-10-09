/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx.gl;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL40;
import org.lwjgl.opengl.GL43;

/**
 * The OpenGL bindings that the Stein pass saves and restores, tracked without asking the driver
 * (always on with the renderer). Asking (glGetInteger) on every pass was ~5% of the render thread
 * on terrain: the first query after a batch of draws pays for the driver's queue.
 *
 * <p>Each field starts unknown and is read from the driver once (on the first pass); from then on
 * whoever changes the binding reports it here:
 *
 * <ul>
 *   <li><b>Draw and read framebuffer, viewport, program</b>: the game (and the mods, through
 *       ModPatch) changes them through the OpenGlHelper and glViewport call sites, redirected to
 *       {@link dev.xavier.stein.gfx.SteinGlTrack} (rules in redirects-opt.tsv), which calls the
 *       same OpenGL and records it here (or through {@link #bindDrawFramebuffer}).
 *   <li><b>VAO, indirect buffer, generic uniform and storage, array texture and sampler per unit,
 *       copy target</b>: the game does not use them; only Stein code does, and it always restores
 *       what was there (or leaves 0, the default: the model batch of {@code SteinModelInstancer}
 *       reports through {@link #resetAfterOwnDraw}). They stay constant after the first read.
 * </ul>
 *
 * The pass only reads from here (and restores the driver to the value read at the end), so it
 * changes nothing here. The 2D texture and the active unit do not live here: what the game believes
 * is the GlStateManager cache ({@code SteinGameState}).
 *
 * <p>{@code -Dsteinbridge.gfx.passStateCheck=true} checks every value against the driver on every
 * pass ({@link SteinGlPassCheck}). On the context thread.
 *
 * <p>Pipeline: Stein GFX only.
 */
public final class SteinGlBindings {

    private static final int UNKNOWN = Integer.MIN_VALUE;

    private SteinGlBindings() {}

    private static int drawFbo = UNKNOWN;
    private static int readFbo = UNKNOWN;
    private static int program = UNKNOWN;
    private static int vao = UNKNOWN;
    private static int indirect = UNKNOWN;
    private static int uniform = UNKNOWN;
    private static int storage = UNKNOWN;
    private static int copyWrite = UNKNOWN;
    private static int copyRead = UNKNOWN;
    private static boolean viewportKnown;
    private static final int[] VIEWPORT = new int[4];
    private static final int UNITS = 64;
    private static final int[] TEX_ARRAY = new int[UNITS];
    private static final int[] SAMPLER = new int[UNITS];
    private static long arrayKnown;
    private static long samplerKnown;

    // ---- reading (the pass) ----

    /** The draw framebuffer bound right now. */
    public static int drawFbo() {
        if (drawFbo == UNKNOWN) {
            drawFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        }
        return drawFbo;
    }

    /** The read framebuffer (only the check uses it: the pass does not touch it). */
    static int readFbo() {
        if (readFbo == UNKNOWN) {
            readFbo = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        }
        return readFbo;
    }

    static int program() {
        if (program == UNKNOWN) {
            program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        }
        return program;
    }

    static int vao() {
        if (vao == UNKNOWN) {
            vao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        }
        return vao;
    }

    static int indirect() {
        if (indirect == UNKNOWN) {
            indirect = GL11.glGetInteger(GL40.GL_DRAW_INDIRECT_BUFFER_BINDING);
        }
        return indirect;
    }

    static int uniform() {
        if (uniform == UNKNOWN) {
            uniform = GL11.glGetInteger(GL31.GL_UNIFORM_BUFFER_BINDING);
        }
        return uniform;
    }

    /** Generic storage (only with compute: without GL 4.3 the target does not even exist). */
    static int storage() {
        if (storage == UNKNOWN) {
            storage = GL11.glGetInteger(GL43.GL_SHADER_STORAGE_BUFFER_BINDING);
        }
        return storage;
    }

    /** The viewport in {@code out[0..3]}. */
    static void viewport(int[] out) {
        if (!viewportKnown) {
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, VIEWPORT);
            viewportKnown = true;
        }
        System.arraycopy(VIEWPORT, 0, out, 0, 4);
    }

    /**
     * The array texture and the sampler of the unit have already been read (otherwise the read
     * needs the active unit).
     */
    static boolean unitKnown(int unit) {
        long bit = 1L << unit;
        return (arrayKnown & bit) != 0 && (samplerKnown & bit) != 0;
    }

    /** Array texture on unit {@code unit} (the already active unit, on its first read). */
    static int texArray(int unit) {
        if ((arrayKnown & 1L << unit) == 0) {
            TEX_ARRAY[unit] = GL11.glGetInteger(GL30.GL_TEXTURE_BINDING_2D_ARRAY);
            arrayKnown |= 1L << unit;
        }
        return TEX_ARRAY[unit];
    }

    /** Sampler on unit {@code unit} (the already active unit, on its first read). */
    static int sampler(int unit) {
        if ((samplerKnown & 1L << unit) == 0) {
            SAMPLER[unit] = GL11.glGetInteger(GL33.GL_SAMPLER_BINDING);
            samplerKnown |= 1L << unit;
        }
        return SAMPLER[unit];
    }

    /** Buffer on the copy-write target (only Stein uses it). */
    static int copyWrite() {
        if (copyWrite == UNKNOWN) {
            copyWrite = GL11.glGetInteger(GL31.GL_COPY_WRITE_BUFFER);
        }
        return copyWrite;
    }

    static int copyRead() {
        if (copyRead == UNKNOWN) {
            copyRead = GL11.glGetInteger(GL31.GL_COPY_READ_BUFFER);
        }
        return copyRead;
    }

    // ---- notices (whoever changes the binding) ----

    /** glBindFramebuffer(target, fb) has just gone to the driver. */
    public static void noteFramebuffer(int target, int fb) {
        if (target == GL30.GL_FRAMEBUFFER) {
            drawFbo = fb;
            readFbo = fb;
        } else if (target == GL30.GL_DRAW_FRAMEBUFFER) {
            drawFbo = fb;
        } else if (target == GL30.GL_READ_FRAMEBUFFER) {
            readFbo = fb;
        }
    }

    /**
     * glDeleteFramebuffers(fb): deleting the bound framebuffer returns the binding to 0 (as OpenGL
     * does).
     */
    public static void noteDeleteFramebuffer(int fb) {
        if (fb == 0) {
            return;
        }
        if (drawFbo == fb) {
            drawFbo = 0;
        }
        if (readFbo == fb) {
            readFbo = 0;
        }
    }

    /** glViewport has just gone to the driver. */
    public static void noteViewport(int x, int y, int w, int h) {
        VIEWPORT[0] = x;
        VIEWPORT[1] = y;
        VIEWPORT[2] = w;
        VIEWPORT[3] = h;
        viewportKnown = true;
    }

    /** glUseProgram has just gone to the driver. */
    public static void noteProgram(int p) {
        program = p;
    }

    // ---- clear color and depth (the game's glClear recorded in the frame: SteinGlFrame) ----

    private static boolean clearColorKnown;
    private static final float[] CLEAR_COLOR = new float[4];
    private static boolean clearDepthKnown;
    private static double clearDepth;

    /** glClearColor has just gone to the driver (the game's redirect in SteinGlTrack). */
    public static void noteClearColor(float r, float g, float b, float a) {
        CLEAR_COLOR[0] = r;
        CLEAR_COLOR[1] = g;
        CLEAR_COLOR[2] = b;
        CLEAR_COLOR[3] = a;
        clearColorKnown = true;
    }

    public static void noteClearDepth(double d) {
        clearDepth = d;
        clearDepthKnown = true;
    }

    /**
     * The driver's clear color (read from it the first time). The array is this class's own: do not
     * modify it.
     */
    static float[] clearColor() {
        if (!clearColorKnown) {
            GL11.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, CLEAR_COLOR);
            clearColorKnown = true;
        }
        return CLEAR_COLOR;
    }

    static double clearDepth() {
        if (!clearDepthKnown) {
            clearDepth = GL11.glGetDouble(GL11.GL_DEPTH_CLEAR_VALUE);
            clearDepthKnown = true;
        }
        return clearDepth;
    }

    private static boolean lineWidthKnown;
    private static float lineWidth;

    /** The game's glLineWidth (redirect in SteinGlTrack). */
    public static void noteLineWidth(float w) {
        lineWidth = w;
        lineWidthKnown = true;
    }

    /** The driver's line width (read from it the first time). */
    static float lineWidth() {
        if (!lineWidthKnown) {
            lineWidth = GL11.glGetFloat(GL11.GL_LINE_WIDTH);
            lineWidthKnown = true;
        }
        return lineWidth;
    }

    /**
     * glBindVertexArray outside the pass (Stein code that binds a VAO of its own and returns to 0).
     */
    public static void noteVao(int v) {
        vao = v;
    }

    static void noteCopyWrite(int b) {
        copyWrite = b;
    }

    static void noteCopyRead(int b) {
        copyRead = b;
    }

    /**
     * glDeleteBuffers(b) of a Stein buffer: the generic bindings that pointed to it go back to 0.
     */
    static void noteDeleteBuffer(int b) {
        if (b == 0) {
            return;
        }
        if (indirect == b) {
            indirect = 0;
        }
        if (uniform == b) {
            uniform = 0;
        }
        if (storage == b) {
            storage = 0;
        }
        if (copyWrite == b) {
            copyWrite = 0;
        }
        if (copyRead == b) {
            copyRead = 0;
        }
    }

    /** Binds the draw framebuffer and records it (drawing outside the game's loop). */
    public static void bindDrawFramebuffer(int fb) {
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, fb);
        noteFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, fb);
    }

    /**
     * The engine's model batch ({@code SteinModelInstancer.flush}) drew with its program, VAO and
     * indirect buffer and returned all three to 0.
     */
    public static void resetAfterOwnDraw() {
        program = 0;
        vao = 0;
        indirect = 0;
    }
}
