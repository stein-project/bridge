/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import dev.xavier.stein.bridge.core.Log;
import dev.xavier.stein.gfx.SteinLazyGl;
import dev.xavier.stein.gfx.SteinModelInstancer;
import dev.xavier.stein.gfx.gl.SteinCoreGl;
import dev.xavier.stein.gfx.gl.SteinGlAudit;
import dev.xavier.stein.gfx.gl.SteinGlFrame;

import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL44;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Model parts drawn in a batch from a persistent mapped buffer, instead of a glLoadMatrixf +
 * glCallList per part.
 *
 * <p>Each ModelRenderer compiles its box into a display list and draws it every frame with its
 * matrix. With the 400, that is ~17 thousand pairs per frame, and the driver processes each one.
 * Here:
 *
 * <ul>
 *   <li>when the list is compiled, the vertices going into it are captured from the Tessellator
 *       (the same position and texture floats, the same normal);
 *   <li>when drawing, the part is transformed on the CPU by the current matrix (the Java stack,
 *       {@link GLMatrix}) and written to a buffer that the GPU reads directly (GL 4.4, persistent
 *       and coherent mapping: the driver copies nothing);
 *   <li>consecutive parts go out in a single glDrawArrays, with an identity MODELVIEW — the
 *       vertices are already in eye space.
 * </ul>
 *
 * <p>The batch is drawn before ANY OpenGL call from the game (the patcher inserts {@link #flush}
 * before each GL call site in the jar), so every state change — texture, color, light, depth,
 * texture matrix — happens after the parts that came before it, as in the original. Normal: M3x3 x
 * n, normalized — what GL_RESCALE_NORMAL does with uniform scale. Passes that depend on equal depth
 * (armor glint, depthFunc EQUAL) go through the same path as the base pass and produce the same
 * depths.
 *
 * <p>On by default ({@code -Dsteinbridge.opt.vanillaModelBatch=true} turns it off). Requires the
 * Java matrix stack on and GL 4.4 (or ARB_buffer_storage); without that, it turns itself off.
 * Checked by capture with and without: crowd, mobs with extra layers (eyes, wool), chests, signs,
 * skull, enchanted armor stand, first-person arm and player in the inventory.
 *
 * <p>Pipeline: both — the fixed pipeline draws the model parts from its own mapped buffer; Stein
 * GFX only captures their geometry for the instancer.
 */
public final class ModelBatch {

    /**
     * On by default; {@code -Dsteinbridge.opt.vanillaModelBatch=true} turns it off. With Stein GFX
     * the capture always runs (the geometry goes to {@link SteinModelInstancer}).
     */
    private static final boolean REQUESTED = SteinCoreGl.CORE || !EngineOptions.VANILLA_MODEL_BATCH;

    /** x y z u v nx ny nz, as floats. */
    private static final int FLOATS = 8;

    private static final int STRIDE = FLOATS * 4;

    /**
     * Vertices per ring region. The write stays in the same region from frame to frame until it
     * fills; only then does a fence close the region and the next one is used, waiting for the GPU
     * to release what it read from it two switches earlier. A fence and a wait per frame cost ~25%
     * of the frame when looking at the sky (1-2 parts per frame).
     */
    private static final int REGION_VERTS = 640 * 1024;

    private static final int REGIONS = 3;

    private static boolean active;
    private static boolean initDone;

    // ---- captured geometry: display list id -> vertices (FLOATS per vertex) ----
    private static float[][] geometry = new float[8192][];
    private static int capturing;
    private static float[] captureBuf = new float[24 * FLOATS * 8];
    private static int captureLen;
    private static boolean captureOk;

    // ---- buffer ring ----
    private static int vbo;
    private static long base;
    private static int region;
    private static int written;
    private static final long[] FENCES = new long[REGIONS];

    /** First vertex and count of the pending batch (absolute indices in the buffer). */
    private static int pendingFirst;

    private static int pendingCount;
    private static boolean flushing;

    private ModelBatch() {}

    // ---- ModelRenderer display list compilation (bct.d) ----

    public static void glNewList(int list, int mode) {
        if (REQUESTED && list > 0) {
            capturing = list;
            captureLen = 0;
            captureOk = true;
        }
        SteinCoreGl.glNewList(list, mode);
    }

    public static void glEndList() {
        if (capturing != 0) {
            if (captureOk && captureLen > 0) {
                float[] copy = Arrays.copyOf(captureBuf, captureLen);
                if (!SteinCoreGl.CORE) {
                    // The own batch (fixed-function fallback) keeps the geometry; in the core
                    // context only the instancer does.
                    if (capturing >= geometry.length) {
                        geometry =
                                Arrays.copyOf(
                                        geometry, Math.max(capturing + 1, geometry.length * 2));
                    }
                    geometry[capturing] = copy;
                }
                SteinModelInstancer.register(capturing, copy);
            }
            capturing = 0;
        }
        SteinCoreGl.glEndList();
    }

    /**
     * Before each face's Tessellator.draw (TexturedQuad.draw): copies the vertices going into the
     * list.
     */
    public static void captureQuad() {
        if (capturing == 0 || !captureOk) {
            return;
        }
        WorldRenderer wr = Tessellator.getInstance().getWorldRenderer();
        int count = wr.getVertexCount();
        int bytes = wr.getBufferSize() * 4;
        int mode = wr.getDrawMode();
        // Expected format: POSITION_TEX_NORMAL (3 floats, 2 floats, 3 bytes + 1), quads.
        if (mode != GL11.GL_QUADS || count == 0 || bytes != count * 24) {
            captureOk = false;
            return;
        }
        ByteBuffer b = wr.getByteBuffer();
        if (captureLen + count * FLOATS > captureBuf.length) {
            captureBuf =
                    Arrays.copyOf(
                            captureBuf,
                            Math.max(captureBuf.length * 2, captureLen + count * FLOATS));
        }
        for (int v = 0; v < count; v++) {
            int o = v * 24;
            float[] c = captureBuf;
            int k = captureLen;
            c[k] = b.getFloat(o);
            c[k + 1] = b.getFloat(o + 4);
            c[k + 2] = b.getFloat(o + 8);
            c[k + 3] = b.getFloat(o + 12);
            c[k + 4] = b.getFloat(o + 16);
            // Signed byte normal, normalized as GL does (c / 127).
            c[k + 5] = Math.max(b.get(o + 20) / 127f, -1f);
            c[k + 6] = Math.max(b.get(o + 21) / 127f, -1f);
            c[k + 7] = Math.max(b.get(o + 22) / 127f, -1f);
            captureLen += FLOATS;
        }
    }

    // ---- part draw (bfl.o inside ModelRenderer.render / renderWithRotation) ----

    public static void callList(int list) {
        if (SteinModelInstancer.REQUESTED) {
            // Per-instance parts (new renderer, experimental): what is pending here goes out first,
            // so the order stays the game's; a part that is not from there continues through here,
            // after the pending one from there.
            flushOwn();
            if (SteinModelInstancer.draw(list)) {
                return;
            }
            SteinModelInstancer.flush();
        }
        float[] g = list > 0 && list < geometry.length ? geometry[list] : null;
        if (g == null || !ready() || !GLMatrix.batchable()) {
            flush();
            GlStateManager.callList(list);
            return;
        }
        int verts = g.length / FLOATS;
        if (written + verts > REGION_VERTS) {
            if (verts > REGION_VERTS) {
                flush();
                GlStateManager.callList(list); // part larger than a region: original path
                return;
            }
            nextRegion();
        }
        float[] m = GLMatrix.current();
        // Texture matrix (glint): applied here to the coordinate, the driver keeps the identity.
        float[] tex = GLMatrix.textureOrNull();
        long p = base + ((long) region * REGION_VERTS + written) * STRIDE;
        // A face's 4 corners have the same normal: transform only when it changes.
        float pnx = Float.NaN;
        float pny = 0f;
        float pnz = 0f;
        float tx = 0f;
        float ty = 0f;
        float tz = 0f;
        for (int k = 0; k < g.length; k += FLOATS) {
            float x = g[k], y = g[k + 1], z = g[k + 2];
            MemoryUtil.memPutFloat(p, m[0] * x + m[4] * y + m[8] * z + m[12]);
            MemoryUtil.memPutFloat(p + 4, m[1] * x + m[5] * y + m[9] * z + m[13]);
            MemoryUtil.memPutFloat(p + 8, m[2] * x + m[6] * y + m[10] * z + m[14]);
            float s = g[k + 3], t = g[k + 4];
            if (tex == null) {
                MemoryUtil.memPutFloat(p + 12, s);
                MemoryUtil.memPutFloat(p + 16, t);
            } else {
                // Like GL with (s, t, 0, 1): affine, no division (checked in batchable()).
                MemoryUtil.memPutFloat(p + 12, tex[0] * s + tex[4] * t + tex[12]);
                MemoryUtil.memPutFloat(p + 16, tex[1] * s + tex[5] * t + tex[13]);
            }
            float nx = g[k + 5], ny = g[k + 6], nz = g[k + 7];
            if (nx != pnx || ny != pny || nz != pnz) {
                pnx = nx;
                pny = ny;
                pnz = nz;
                tx = m[0] * nx + m[4] * ny + m[8] * nz;
                ty = m[1] * nx + m[5] * ny + m[9] * nz;
                tz = m[2] * nx + m[6] * ny + m[10] * nz;
                float len = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
                if (len > 0f) {
                    tx /= len;
                    ty /= len;
                    tz /= len;
                }
            }
            MemoryUtil.memPutFloat(p + 20, tx);
            MemoryUtil.memPutFloat(p + 24, ty);
            MemoryUtil.memPutFloat(p + 28, tz);
            p += STRIDE;
        }
        if (pendingCount == 0) {
            pendingFirst = region * REGION_VERTS + written;
        }
        pendingCount += verts;
        written += verts;
        frameWritten += verts;
    }

    /**
     * Full region: the pending batch goes out, a fence closes the region and the next one is
     * released by the GPU.
     */
    private static void nextRegion() {
        flush();
        if (FENCES[region] != 0L) {
            GL32.glDeleteSync(FENCES[region]);
        }
        FENCES[region] = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        region = (region + 1) % REGIONS;
        written = 0;
        long f = FENCES[region];
        if (f != 0L) {
            // Two switches ago; normally it has already finished.
            GL32.glClientWaitSync(f, GL32.GL_SYNC_FLUSH_COMMANDS_BIT, 1_000_000_000L);
            GL32.glDeleteSync(f);
            FENCES[region] = 0L;
        }
    }

    /**
     * Batch draw state (VBO bound, position/texture/normal arrays enabled pointing to it) stays
     * active between batches and is only undone before calls that use arrays or buffers ({@link
     * #restore}). Changing texture, color, light etc. does not interact with it; display lists and
     * immediate mode do not read client arrays.
     */
    private static boolean stateActive;

    private static long frameVertices;
    private static long frameWritten;
    private static long frameDraws;
    private static int frames;
    private static long lastReport = System.nanoTime();

    /**
     * Draws the pending batch (and the per-instance parts'). Before any OpenGL call from the game.
     */
    public static void flush() {
        if (SteinLazyGl.dirty()) {
            // Stein GFX's deferred state: goes to the driver before any game GL.
            SteinLazyGl.sync();
        }
        SteinModelInstancer.flush();
        flushOwn();
    }

    /** Are there parts waiting to be drawn (here or in the per-instance parts)? */
    public static boolean hasPending() {
        return pendingCount != 0 || SteinModelInstancer.hasPending();
    }

    /**
     * Only today's batch (before the new renderer draws outside callList, so the order stays the
     * game's).
     */
    public static void flushBatch() {
        flushOwn();
    }

    private static void flushOwn() {
        if (pendingCount == 0 || flushing) {
            return;
        }
        SteinLazyGl.sync();
        flushing = true;
        int first = pendingFirst;
        int count = pendingCount;
        pendingCount = 0;
        try {
            GLMatrix.loadIdentityForBatch();
            if (!stateActive) {
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
                SteinCoreGl.glEnableClientState(GL11.GL_VERTEX_ARRAY);
                SteinCoreGl.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
                SteinCoreGl.glEnableClientState(GL11.GL_NORMAL_ARRAY);
                GL11.nglVertexPointer(3, GL11.GL_FLOAT, STRIDE, 0L);
                GL11.nglTexCoordPointer(2, GL11.GL_FLOAT, STRIDE, 12L);
                GL11.nglNormalPointer(GL11.GL_FLOAT, STRIDE, 20L);
                stateActive = true;
            }
            if (SteinGlAudit.ON) {
                SteinGlAudit.draw("drawArrays");
            }
            SteinGlFrame.sync("direct draw of the model batch");
            GL11.glDrawArrays(GL11.GL_QUADS, first, count);
            frameDraws++;
        } finally {
            flushing = false;
        }
    }

    /**
     * Draws what is pending and undoes the batch state. Before calls that use arrays or buffers.
     */
    public static void restore() {
        flush();
        if (stateActive) {
            stateActive = false;
            SteinCoreGl.glDisableClientState(GL11.GL_NORMAL_ARRAY);
            SteinCoreGl.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
            SteinCoreGl.glDisableClientState(GL11.GL_VERTEX_ARRAY);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        }
    }

    /**
     * End of frame (Display.update): undoes the batch state; the region continues in the next
     * frame.
     */
    public static void endFrame() {
        if (!active) {
            return;
        }
        restore();
        frameVertices += frameWritten;
        frameWritten = 0;
        frames++;
        long now = System.nanoTime();
        if (now - lastReport > 10_000_000_000L && frames > 0) {
            Log.info(
                    "batch",
                    "per frame: "
                            + (frameVertices / frames / 24)
                            + " parts (~24 vertices) in "
                            + (frameDraws / frames)
                            + " draws"
                            + (SteinModelInstancer.REQUESTED
                                    ? " | " + SteinModelInstancer.frameStats(frames)
                                    : ""));
            lastReport = now;
            frameVertices = 0;
            frameDraws = 0;
            frames = 0;
        }
    }

    private static boolean ready() {
        if (initDone) {
            return active;
        }
        initDone = true;
        if (!REQUESTED) {
            return false;
        }
        if (SteinCoreGl.CORE) {
            // Core context: the batch here draws through the fixed-function pipeline, which does
            // not exist. The part continues through the display list, recorded by Stein GFX (on
            // 4.4+ the SteinModelInstancer takes it first; on 4.1 there is no direct draw for it).
            return false;
        }
        GLCapabilities caps = GL.getCapabilities();
        if (!GLMatrix.emulating()) {
            Log.info("batch", "off: needs the Java matrix stack");
            return false;
        }
        if (!(caps.OpenGL44 || caps.GL_ARB_buffer_storage)) {
            Log.info("batch", "off: no GL 4.4 / ARB_buffer_storage");
            return false;
        }
        long size = (long) REGIONS * REGION_VERTS * STRIDE;
        int flags = GL30.GL_MAP_WRITE_BIT | GL44.GL_MAP_PERSISTENT_BIT | GL44.GL_MAP_COHERENT_BIT;
        vbo = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GL44.glBufferStorage(GL15.GL_ARRAY_BUFFER, size, flags);
        ByteBuffer mapped = GL30.glMapBufferRange(GL15.GL_ARRAY_BUFFER, 0, size, flags);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        if (mapped == null) {
            Log.info("batch", "off: persistent mapping failed");
            return false;
        }
        base = MemoryUtil.memAddress(mapped);
        active = true;
        Log.info("batch", "batched model parts: persistent buffer of " + (size >> 20) + " MB");
        return true;
    }
}
