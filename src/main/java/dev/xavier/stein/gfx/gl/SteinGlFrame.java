/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

package dev.xavier.stein.gfx.gl;

import static dev.xavier.stein.gfx.gl.SteinGlFrameCheck.checking;
import static dev.xavier.stein.gfx.gl.SteinGlFrameCheck.chkFilter;
import static dev.xavier.stein.gfx.gl.SteinGlFrameCheck.chkRec;
import static dev.xavier.stein.gfx.gl.SteinGlOpList.i;
import static dev.xavier.stein.gfx.gl.SteinGlOpList.keep;
import static dev.xavier.stein.gfx.gl.SteinGlOpList.l;
import static dev.xavier.stein.gfx.gl.SteinGlOpList.o;
import static dev.xavier.stein.gfx.gl.SteinGlOpList.op;
import static dev.xavier.stein.gfx.gl.SteinGlOpList.opN;
import static dev.xavier.stein.gfx.gl.SteinGlOps.*;

import dev.xavier.stein.bridge.core.Log;
import dev.xavier.stein.gfx.SteinGfxOptions;
import dev.xavier.stein.gfx.SteinPipeline;
import dev.xavier.stein.gfx.SteinRenderPass;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL45;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/**
 * The recorded frame ({@code -Dsteinbridge.gfx.recordFrame}, enabled with the renderer in core):
 * the OpenGL passes become a single command list ({@link SteinGlRecorder}), and the list goes to
 * the driver in one go — at the end of the frame ({@link #endFrame}, at the start of
 * Display.update) or earlier, at a sync point ({@link #sync}), when something outside Stein GFX
 * needs the order. Measured on the terrain: the first half of the frame (sky, solid terrain)
 * reached the GPU early, which finished and sat idle for ~130 us waiting for the CPU to get through
 * the entities up to the translucent pass; a glFlush per pass made it worse (submission on AMD is
 * expensive).
 *
 * <p><b>What becomes a command, in the order it happened</b> (whatever changes a resource or the
 * screen, read by the passes):
 *
 * <ul>
 *   <li>everything inside the passes (pipeline, buffers, textures and samplers, uniforms, storage,
 *       viewport, scissor, draws, indirect draws, compute, the polygon offset of the entity flush);
 *   <li>API buffer upload ({@code upload}: the data is copied into our own memory and goes in the
 *       replay — with memcpy on persistent mapping, with glNamedBufferSubData otherwise), buffer
 *       and texture copy, deleted buffer (the name is only released at replay), fence (inserted at
 *       replay: until then it has not passed, and waiting for it forces the submission);
 *   <li>texture filter and parameter (the game switches the atlas filter between the terrain layers
 *       and the RenderItem on every item: a draw recorded earlier comes out with the earlier
 *       filter), by name (GL 4.5);
 *   <li>the game's glClear (the depth before the hand), with the target, the masks, the clear color
 *       and depth and the scissor of the moment: without it the frame would split in two.
 * </ul>
 *
 * <b>Sync points</b> (the list goes first, and the call proceeds right away): game texture uploaded
 * (glTexImage2D, glTexSubImage2D) or deleted, glCopyTexSubImage2D, glReadPixels, framebuffer
 * deleted, API read (readPixels, readBuffer), API texture or pipeline or target uploaded/deleted,
 * wait on a fence that has not been inserted yet, direct drawing by the engine outside Stein GFX
 * (the old batches). A game state change (enable/disable, blend, binding, viewport, bound
 * framebuffer) is not a sync: the pass stores the target and the viewport at recording time, binds
 * what it uses and, at replay, gives back what the game believes at that moment.
 *
 * <p>The persistent rings ({@code SteinBuffer.ring}) still apply: the CPU writes to the region, the
 * region's fence enters the list after the draws that read it, and the region is only rewritten
 * after the fence has passed (a fence not inserted has not passed).
 *
 * <p>Needs the core with GL 4.5 (parameter and upload by name), a persistent buffer and GL 4.3
 * (texture copy); without that, or without the new detours in the game jar (the game's glClear has
 * not gone through here yet), the passes go right away. On the context thread.
 *
 * <p>This class is the recording side and the lifecycle (decide, sync, end of frame). The rest is
 * in its own class: the command codes ({@link SteinGlOps}), the list ({@link SteinGlOpList}), the
 * replay ({@link SteinGlReplay}), the fences ({@link SteinGlFenceBatch}), the check ({@link
 * SteinGlFrameCheck}), the counters ({@link SteinGlFrameStats}) and the dump ({@link
 * SteinGlFrameDump}).
 *
 * <p>Pipeline: Stein GFX only.
 */
public final class SteinGlFrame {

    private static final boolean REQUESTED = SteinGfxOptions.RECORD_FRAME;

    private static final boolean STATS = SteinGlFrameStats.ON;

    /** Recording is possible in this context (decided when the device is created). */
    private static boolean on;

    /** The pass being recorded right now (its commands go into the list). */
    private static SteinGlRecorder openPass;

    private SteinGlFrame() {}

    /** When the GL device is created: can the context record? */
    static void decide(SteinGlDevice dev) {
        SteinGlReplay.device = dev;
        if (!REQUESTED) {
            if (SteinGfxOptions.ENABLED) {
                Log.info(
                        "gfx",
                        "recorded frame: off (-Dsteinbridge.gfx.recordFrame=false),"
                                + " immediate submission");
            }
            return;
        }
        String why = null;
        if (!GL.getCapabilities().OpenGL45) {
            why = "no GL 4.5";
        } else if (!dev.caps().persistentMapping
                || !dev.caps().multiDrawIndirect
                || !dev.caps().copyImage) {
            why = "no persistent buffer/GL 4.3 (4.1 path)";
        }
        on = why == null;
        SteinGlFrameCheck.start(on); // the first frame is already checked
        Log.info(
                "gfx",
                "recorded frame: "
                        + (on
                                ? "on (submitted at the end of the frame and at syncs)"
                                : "off, immediate submission: " + why));
    }

    /** For the out-of-game test: submissions, commands and the check result so far. */
    static String summary() {
        return SteinGlFrameStats.submits
                + " submissions ("
                + SteinGlFrameStats.endSubmits
                + " at the end of the frame), "
                + SteinGlFrameStats.opsTotal
                + " commands, "
                + SteinGlFrameCheck.checkedFrames
                + " frames checked, "
                + SteinGlFrameCheck.mismatches
                + " differing commands, "
                + SteinGlFrameCheck.invalids
                + " nonexistent resources";
    }

    /** The check found a difference or a nonexistent resource. */
    static boolean checkFailed() {
        return SteinGlFrameCheck.failed();
    }

    /** Recording applies in this context (the out-of-game test says which mode it ran in). */
    static boolean recordingAvailable() {
        return on;
    }

    /** New passes are recorded (otherwise they go to the driver right away). */
    static boolean recording() {
        return on && !SteinGlReplay.active;
    }

    /**
     * There is a recorded command waiting: whatever changes a resource or the screen enters the
     * list (otherwise it goes right away).
     */
    public static boolean pending() {
        return opN > 0 && !SteinGlReplay.active;
    }

    /** A pass is being recorded (a direct polygon offset becomes a command). */
    static boolean passOpen() {
        return openPass != null && !SteinGlReplay.active;
    }

    // ---- recording: pass (SteinGlRecorder) ----

    static void beginPass(SteinGlRecorder r, SteinRenderPass pass) {
        // the line width that the immediate pass would inherit from the driver (the outline of
        // the targeted block: 2)
        r.lineWidth = SteinGlBindings.lineWidth();
        resumePass(r, pass, r.lineWidth);
    }

    /**
     * The recorded opening (the first, or the continuation after a sync in the middle: {@link
     * SteinGlRecorder#resume}).
     */
    static void resumePass(SteinGlRecorder r, SteinRenderPass pass, float lineWidth) {
        openPass = r;
        op(PASS_BEGIN);
        o(pass);
        int fbo = (int) pass.target.handle();
        i(fbo);
        i(Float.floatToRawIntBits(lineWidth));
        if (checking) {
            chkRec(PASS_BEGIN, pass.name.hashCode(), fbo);
        }
    }

    static void endPass(SteinGlRecorder r) {
        if (openPass == r) {
            openPass = null;
        }
        op(PASS_END);
        if (checking) {
            chkRec(PASS_END);
        }
    }

    static void pipeline(SteinPipeline p) {
        op(PIPELINE);
        o(p);
        if (checking) {
            chkRec(PIPELINE, System.identityHashCode(p));
        }
    }

    static void vertex(int index, int handle, long offset) {
        op(VERTEX);
        i(index);
        i(handle);
        l(offset);
        if (checking) {
            chkRec(VERTEX, index, handle, offset);
        }
    }

    static void index(int handle, boolean uint16) {
        op(INDEX);
        i(handle);
        i(uint16 ? 1 : 0);
        if (checking) {
            chkRec(INDEX, handle, uint16 ? 1 : 0);
        }
    }

    static void texture(int slot, int id, boolean array, int sampler) {
        op(TEXTURE);
        i(slot);
        i(id);
        i(array ? 1 : 0);
        i(sampler);
        if (checking) {
            SteinGlFrameCheck.recordTexture(slot, id, array, sampler);
        }
    }

    static void uniforms(boolean storage, int slot, int h, long offset, long size) {
        op(storage ? STORAGE : UNIFORMS);
        i(slot);
        i(h);
        l(offset);
        l(size);
        if (checking) {
            chkRec(storage ? STORAGE : UNIFORMS, slot, h, offset, size);
        }
    }

    static void viewport(int x, int y, int w, int h) {
        op(VIEWPORT);
        i(x);
        i(y);
        i(w);
        i(h);
        if (checking) {
            chkRec(VIEWPORT, x, y, w, h);
        }
    }

    static void scissor(int[] rect) {
        op(SCISSOR);
        i(rect == null ? 0 : 1);
        for (int k = 0; k < 4; k++) {
            i(rect == null ? 0 : rect[k]);
        }
        if (checking) {
            chkRec(
                    SCISSOR,
                    rect == null ? 0 : 1,
                    rect == null ? 0 : rect[0],
                    rect == null ? 0 : rect[1],
                    rect == null ? 0 : rect[2],
                    rect == null ? 0 : rect[3]);
        }
    }

    static void draw(int a, int b, int c, int d) {
        op(DRAW);
        i(a);
        i(b);
        i(c);
        i(d);
        if (checking) {
            chkRec(DRAW, a, b, c, d);
        }
    }

    static void drawIndexed(int a, int b, int c, int d, int e) {
        op(DRAW_INDEXED);
        i(a);
        i(b);
        i(c);
        i(d);
        i(e);
        if (checking) {
            chkRec(DRAW_INDEXED, a, b, c, d, e);
        }
    }

    static void drawIndirect(boolean indexed, int h, long offset, int count) {
        op(indexed ? DRAW_INDEXED_INDIRECT : DRAW_INDIRECT);
        i(h);
        l(offset);
        i(count);
        if (checking) {
            chkRec(indexed ? DRAW_INDEXED_INDIRECT : DRAW_INDIRECT, h, offset, count);
        }
    }

    static void dispatch(int x, int y, int z) {
        op(DISPATCH);
        i(x);
        i(y);
        i(z);
        if (checking) {
            chkRec(DISPATCH, x, y, z);
        }
    }

    static void polygonOffset(boolean on, float factor, float units) {
        if (openPass != null) {
            openPass.noteOffset(on, factor, units);
        }
        op(POLY_OFFSET);
        i(on ? 1 : 0);
        i(Float.floatToRawIntBits(factor));
        i(Float.floatToRawIntBits(units));
        if (checking) {
            chkRec(
                    POLY_OFFSET,
                    on ? 1 : 0,
                    Float.floatToRawIntBits(factor),
                    Float.floatToRawIntBits(units));
        }
    }

    // ---- recording: what changes a resource outside the passes ----

    /** glTextureParameteri on texture {@code tex}: right away, or recorded if there is a list. */
    public static void textureParameteri(int tex, int pname, int value) {
        if (!pending()) {
            GL45.glTextureParameteri(tex, pname, value);
            return;
        }
        op(TEX_PARAMI);
        i(tex);
        i(pname);
        i(value);
        if (checking) {
            chkRec(TEX_PARAMI, tex, pname, value);
            chkFilter(tex, pname, value);
        }
    }

    public static void textureParameterf(int tex, int pname, float value) {
        if (!pending()) {
            GL45.glTextureParameterf(tex, pname, value);
            return;
        }
        op(TEX_PARAMF);
        i(tex);
        i(pname);
        i(Float.floatToRawIntBits(value));
        if (checking) {
            chkRec(TEX_PARAMF, tex, pname, Float.floatToRawIntBits(value));
        }
    }

    /**
     * API buffer upload with a pending list: the data goes into the arena and is uploaded at
     * replay.
     */
    static void upload(int handle, long offset, ByteBuffer data, long mapped) {
        long bytes = data.remaining();
        long at = keep(MemoryUtil.memAddress(data), bytes);
        op(UPLOAD);
        i(handle);
        l(offset);
        l(at);
        l(bytes);
        l(mapped);
        if (checking) {
            chkRec(UPLOAD, handle, offset, bytes, mapped);
        }
    }

    static void copyBuffer(int src, long srcOffset, int dst, long dstOffset, long bytes) {
        op(COPY_BUFFER);
        i(src);
        i(dst);
        l(srcOffset);
        l(dstOffset);
        l(bytes);
        if (checking) {
            chkRec(COPY_BUFFER, src, dst, srcOffset, dstOffset, bytes);
        }
    }

    static void copyTexture(
            int src,
            int srcTarget,
            int srcLayer,
            int dst,
            int dstTarget,
            int dstLayer,
            int w,
            int h) {
        op(COPY_TEXTURE);
        i(src);
        i(srcTarget);
        i(srcLayer);
        i(dst);
        i(dstTarget);
        i(dstLayer);
        i(w);
        i(h);
        if (checking) {
            chkRec(COPY_TEXTURE, src, srcTarget, srcLayer, dst, dstTarget, dstLayer, w, h);
        }
    }

    /**
     * Buffer deleted with a pending list: the name (and the mapping) is only released at replay,
     * after whoever reads it.
     */
    static void deleteBuffer(int handle, boolean mapped) {
        op(DELETE_BUFFER);
        i(handle);
        i(mapped ? 1 : 0);
        if (checking) {
            chkRec(DELETE_BUFFER, handle, mapped ? 1 : 0);
        }
    }

    // ---- pending fences (SteinGlFence): inserted together at the end of the replay, never in the
    // middle of the queue ----

    static boolean deferFences() {
        return SteinGlFenceBatch.DEFER_FENCES && !SteinGlReplay.active;
    }

    static void deferFence(SteinGlFence f) {
        SteinGlFenceBatch.add(f);
    }

    /**
     * Without deferring ({@code deferFences=false}) and with a pending list: the fence enters the
     * list, at the point where it was requested.
     */
    static void fence(SteinGlFence f) {
        op(FENCE);
        o(f);
        if (checking) {
            chkRec(FENCE);
        }
    }

    /**
     * Inserts the pending fences: a single glFenceSync for all of them, at the current point of the
     * queue (the end of the replay, or just before the frame swap). They all pass together, after
     * everything that came before them.
     */
    public static void flushFences() {
        SteinGlFenceBatch.flush();
    }

    /**
     * The game's glClear with a pending list: records the target, the game's color and depth mask,
     * the clear color and depth and the scissor of the moment (the same that the immediate glClear
     * would use).
     */
    /** The game's glClear with a pending list (through SteinGlTrack). */
    public static void gameClear(int mask) {
        int fbo = SteinGlBindings.drawFbo();
        int cm =
                (SteinGlGame.colorMask(0) ? 1 : 0)
                        | (SteinGlGame.colorMask(1) ? 2 : 0)
                        | (SteinGlGame.colorMask(2) ? 4 : 0)
                        | (SteinGlGame.colorMask(3) ? 8 : 0);
        boolean dm = SteinGlGame.depthMask();
        float[] cc = SteinGlBindings.clearColor();
        double cd = SteinGlBindings.clearDepth();
        boolean sc = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        int[] box = SCRATCH4;
        if (sc) {
            GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, box);
        }
        op(GAME_CLEAR);
        i(mask);
        i(fbo);
        i(cm);
        i(dm ? 1 : 0);
        for (int k = 0; k < 4; k++) {
            i(Float.floatToRawIntBits(cc[k]));
        }
        l(Double.doubleToRawLongBits(cd));
        i(sc ? 1 : 0);
        for (int k = 0; k < 4; k++) {
            i(sc ? box[k] : 0);
        }
        if (checking) {
            chkRec(GAME_CLEAR, mask, fbo, cm, dm ? 1 : 0, sc ? 1 : 0);
        }
    }

    private static final int[] SCRATCH4 = new int[4];

    // ---- submission ----

    /**
     * Sync point: something outside will depend on the order (uploading or deleting a texture,
     * reading the screen, waiting on a fence...). The game's deferred state goes first (the
     * deferred filters enter at the end of the list, as they would have right away), and the list
     * goes to the driver.
     */
    public static void sync(String reason) {
        if (opN == 0 || SteinGlReplay.active) {
            return;
        }
        if (SteinGlGame.lazyDirty()) {
            SteinGlGame.lazySync();
        }
        if (STATS) {
            SteinGlFrameStats.noteSync(reason);
        }
        SteinGlReplay.run();
        splitOpenPass();
        SteinGlFenceBatch.flush();
    }

    /**
     * The sync fell in the middle of a recorded pass (an API texture upload inside the flush, for
     * example): the replayed part closes now — giving back the game's state, because the game runs
     * before the rest and touches the state (the particles' face culling came out swapped at the
     * close) — and the continuation reopens in the list, with everything rebound ({@link
     * SteinGlRecorder#resume}).
     */
    private static void splitOpenPass() {
        if (openPass == null) {
            return;
        }
        SteinGlReplay.closePass();
        if (STATS) {
            SteinGlFrameStats.passSplit(openPass.passName());
        }
        openPass.resume();
    }

    /**
     * End of the frame (start of Display.update, before anything in it): the rest goes to the GPU.
     */
    public static void endFrame() {
        if (!on) {
            return;
        }
        boolean replayedNow = false;
        if (opN > 0 && !SteinGlReplay.active) {
            replayedNow = true;
            if (SteinGlGame.lazyDirty()) {
                SteinGlGame.lazySync();
            }
            SteinGlFrameStats.endSubmits++;
            SteinGlReplay.run();
            splitOpenPass();
        }
        boolean fences = !SteinGlFenceBatch.isEmpty();
        SteinGlFenceBatch.flush(); // the frame's fences, together, after all of its draws
        if (SteinGlOpTiming.ON && fences && replayedNow) {
            // only right after the end replay: the interval is that of glFenceSync, not CPU between
            // submissions
            SteinGlOpTiming.after("pending fences");
        }
        if (checking) {
            SteinGlFrameCheck.compareCheck();
        }
        if (SteinGlFrameDump.dumping) {
            SteinGlFrameDump.print();
        }
        if (SteinGlOpTiming.ON) {
            SteinGlOpTiming.frame();
        }
        SteinGlFrameStats.frames++;
        SteinGlFrameCheck.afterFrame(SteinGlFrameStats.frames);
        SteinGlFrameDump.afterFrame(SteinGlFrameStats.frames);
        if (STATS) {
            SteinGlFrameStats.report();
        }
    }
}
