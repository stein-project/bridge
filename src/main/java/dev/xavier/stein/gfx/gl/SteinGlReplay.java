/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx.gl;

import static dev.xavier.stein.gfx.gl.SteinGlFrameCheck.checkBuffer;
import static dev.xavier.stein.gfx.gl.SteinGlFrameCheck.checkTexture;
import static dev.xavier.stein.gfx.gl.SteinGlFrameCheck.checking;
import static dev.xavier.stein.gfx.gl.SteinGlFrameCheck.chkRep;
import static dev.xavier.stein.gfx.gl.SteinGlFrameCheck.invalid;
import static dev.xavier.stein.gfx.gl.SteinGlFrameDump.opLabel;
import static dev.xavier.stein.gfx.gl.SteinGlOpList.arena;
import static dev.xavier.stein.gfx.gl.SteinGlOpList.ints;
import static dev.xavier.stein.gfx.gl.SteinGlOpList.longs;
import static dev.xavier.stein.gfx.gl.SteinGlOpList.objs;
import static dev.xavier.stein.gfx.gl.SteinGlOps.*;

import dev.xavier.stein.gfx.SteinPipeline;
import dev.xavier.stein.gfx.SteinRenderPass;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GL45;
import org.lwjgl.system.MemoryUtil;

/**
 * The replay of the recorded frame's command list ({@link SteinGlFrame}): the commands go to the
 * driver in the order they were recorded, the passes through one {@link SteinGlCommands} kept for
 * the whole run. The list is walked with three cursors (ints, longs and objects, see {@link
 * SteinGlOpList}); each command reads its own arguments, in the order {@link SteinGlFrame} wrote
 * them.
 *
 * <p>No game code runs during a run, so the game's state is read once, at the first pass (or game
 * clear), and given back once, at the end of the run ({@link #closePass()}): a pass starts from the
 * driver state the previous one left, and only what differs is sent. Saving and giving back the
 * game's state around every pass cost a few dozen calls per pass, which on some drivers (Intel's)
 * was most of the submission.
 *
 * <p>Pipeline: Stein GFX only.
 */
final class SteinGlReplay {

    private SteinGlReplay() {}

    /** The device that opens the replayed passes (set when the device is created). */
    static SteinGlDevice device;

    /** A replay is running: whatever is called meanwhile goes right away, not into the list. */
    static boolean active;

    /**
     * The state of the replay (created with the first one, kept: no allocation per frame). Between
     * the first pass of a run and {@link #closePass()} the driver holds the replay's state, not the
     * game's.
     */
    private static SteinGlCommands state;

    /** A replayed pass is open on {@link #state} (it ends at its PASS_END or at the run's end). */
    private static boolean inPass;

    /** The line width to give back at the end of the run (NaN = untouched). */
    private static float lineRestore = Float.NaN;

    /** The line width on the driver while {@link #lineRestore} is set. */
    private static float lineNow;

    private static final int[] SCRATCH4 = new int[4];

    /** The rectangle of the scissor op being replayed. */
    private static final int[] SCISSOR_RECT = new int[4];

    // the cursors in the three arrays: int, long and object arguments of the next command
    private static int ip;
    private static int lp;
    private static int op;

    /**
     * The end of a run: closes the replayed pass, if one is open (the sync that falls in the middle
     * of it), and gives the game's state back, line width included, before the game runs again.
     * Nothing to do when called again (the sync's split of the open pass).
     */
    static void closePass() {
        if (state != null) {
            if (inPass) {
                state.endPass();
                inPass = false;
            }
            state.finish();
        }
        if (!Float.isNaN(lineRestore)) {
            if (lineNow != lineRestore) {
                GL11.glLineWidth(lineRestore);
            }
            lineRestore = Float.NaN;
        }
    }

    /** The replay's state, with the game's state read (on the first use in the run). */
    private static SteinGlCommands tracked() {
        if (state == null) {
            state = new SteinGlCommands(device);
        }
        state.track();
        return state;
    }

    /**
     * Sends the whole list to the driver and empties it. The hot loop of the renderer: the dispatch
     * stays flat, one switch with a static method per command.
     */
    static void run() {
        active = true;
        SteinGlFrameStats.submits++;
        SteinGlFrameStats.opsTotal += SteinGlOpList.opN;
        SteinGlFrameStats.bytesTotal += SteinGlOpList.arenaN;
        ip = 0;
        lp = 0;
        op = 0;
        boolean opTiming = SteinGlOpTiming.ON;
        boolean dump = SteinGlFrameDump.dumping;
        if (opTiming) {
            SteinGlOpTiming.replayStart();
        }
        if (dump) {
            SteinGlFrameDump.begin(SteinGlFrameStats.submits);
        }
        try {
            int end = SteinGlOpList.intN;
            while (ip < end) {
                int code = ints[ip++];
                if (dump) {
                    SteinGlFrameDump.dumpOp(code, ip, lp, op);
                }
                switch (code) {
                    case PASS_BEGIN -> playPassBegin();
                    case PASS_END -> playPassEnd();
                    case PIPELINE -> playPipeline();
                    case VERTEX -> playVertex();
                    case INDEX -> playIndex();
                    case TEXTURE -> playTexture();
                    case UNIFORMS, STORAGE -> playBuffer(code);
                    case VIEWPORT -> playViewport();
                    case SCISSOR -> playScissor();
                    case DRAW -> playDraw();
                    case DRAW_INDEXED -> playDrawIndexed();
                    case DRAW_INDIRECT, DRAW_INDEXED_INDIRECT -> playDrawIndirect(code);
                    case DISPATCH -> playDispatch();
                    case POLY_OFFSET -> playPolygonOffset();
                    case TEX_PARAMI, TEX_PARAMF -> playTextureParameter(code);
                    case UPLOAD -> playUpload();
                    case COPY_BUFFER -> playCopyBuffer();
                    case COPY_TEXTURE -> playCopyTexture();
                    case DELETE_BUFFER -> playDeleteBuffer();
                    case FENCE -> playFence();
                    case GAME_CLEAR -> playGameClear();
                    default ->
                            throw new IllegalStateException(
                                    "recorded frame: unknown command " + code);
                }
                if (opTiming) {
                    SteinGlOpTiming.after(opLabel(code, ip, lp));
                    if (code == PASS_END) {
                        SteinGlOpTiming.passEnd();
                    }
                }
            }
        } finally {
            // the game runs next (or the sync's caller does): it must find its own state
            closePass();
            SteinGlOpList.reset();
            SteinGlFrameCheck.clearFilters();
            active = false;
        }
    }

    // ---- the commands ----

    private static void playPassBegin() {
        SteinRenderPass pass = (SteinRenderPass) objs[op++];
        if (SteinGlOpTiming.ON) {
            SteinGlOpTiming.passBegin(pass.name);
        }
        int fbo = ints[ip++];
        float lw = Float.intBitsToFloat(ints[ip++]);
        float nowLw = Float.isNaN(lineRestore) ? SteinGlBindings.lineWidth() : lineNow;
        if (lw != nowLw) {
            GL11.glLineWidth(lw);
            if (Float.isNaN(lineRestore)) {
                lineRestore = nowLw;
            }
            lineNow = lw;
        }
        if (checking) {
            chkRep(PASS_BEGIN, pass.name.hashCode(), (int) pass.target.handle());
            if (fbo != 0 && !GL30.glIsFramebuffer(fbo)) {
                invalid("framebuffer " + fbo + " of pass '" + pass.name + "'");
            }
        }
        if (inPass) {
            // a pass left open without its end (not recorded that way): close it as its end would
            state.endPass();
        }
        if (state == null) {
            state = new SteinGlCommands(device);
        }
        state.beginPass(pass);
        inPass = true;
    }

    private static void playPassEnd() {
        if (checking) {
            chkRep(PASS_END);
        }
        if (inPass) {
            state.endPass();
            inPass = false;
        }
    }

    private static void playPipeline() {
        SteinPipeline p = (SteinPipeline) objs[op++];
        if (checking) {
            chkRep(PIPELINE, System.identityHashCode(p));
        }
        state.bindPipeline(p);
    }

    private static void playVertex() {
        int index = ints[ip++];
        int h = ints[ip++];
        long off = longs[lp++];
        if (checking) {
            chkRep(VERTEX, index, h, off);
            checkBuffer(h);
        }
        state.vertexBuffer(index, h, off);
    }

    private static void playIndex() {
        int h = ints[ip++];
        int u16 = ints[ip++];
        if (checking) {
            chkRep(INDEX, h, u16);
            checkBuffer(h);
        }
        state.indexBuffer(h, u16 != 0);
    }

    private static void playTexture() {
        int slot = ints[ip++];
        int id = ints[ip++];
        int array = ints[ip++];
        int s = ints[ip++];
        if (checking) {
            SteinGlFrameCheck.replayTexture(slot, id, array, s);
        }
        state.texture(slot, id, array != 0, s);
    }

    /** Uniform or storage buffer range ({@code code}: which one). */
    private static void playBuffer(int code) {
        int slot = ints[ip++];
        int h = ints[ip++];
        long off = longs[lp++];
        long size = longs[lp++];
        if (checking) {
            chkRep(code, slot, h, off, size);
            checkBuffer(h);
        }
        if (code == UNIFORMS) {
            state.uniforms(slot, h, off, size);
        } else {
            state.storage(slot, h, off, size);
        }
    }

    private static void playViewport() {
        int x = ints[ip++];
        int y = ints[ip++];
        int w = ints[ip++];
        int h = ints[ip++];
        if (checking) {
            chkRep(VIEWPORT, x, y, w, h);
        }
        state.setViewport(x, y, w, h);
    }

    private static void playScissor() {
        int has = ints[ip++];
        // the pass copies the rectangle (SteinGlCommands.setScissor): one array serves every op
        int[] r = SCISSOR_RECT;
        r[0] = ints[ip];
        r[1] = ints[ip + 1];
        r[2] = ints[ip + 2];
        r[3] = ints[ip + 3];
        ip += 4;
        if (checking) {
            chkRep(SCISSOR, has, r[0], r[1], r[2], r[3]);
        }
        state.setScissor(has != 0 ? r : null);
    }

    private static void playDraw() {
        int a = ints[ip++];
        int b = ints[ip++];
        int c = ints[ip++];
        int d = ints[ip++];
        if (checking) {
            chkRep(DRAW, a, b, c, d);
        }
        state.draw(a, b, c, d);
    }

    private static void playDrawIndexed() {
        int a = ints[ip++];
        int b = ints[ip++];
        int c = ints[ip++];
        int d = ints[ip++];
        int e = ints[ip++];
        if (checking) {
            chkRep(DRAW_INDEXED, a, b, c, d, e);
        }
        state.drawIndexed(a, b, c, d, e);
    }

    /** Indirect draw ({@code code}: indexed or not). */
    private static void playDrawIndirect(int code) {
        int h = ints[ip++];
        long off = longs[lp++];
        int count = ints[ip++];
        if (checking) {
            chkRep(code, h, off, count);
            checkBuffer(h);
        }
        if (code == DRAW_INDIRECT) {
            state.drawIndirect(h, off, count);
        } else {
            state.drawIndexedIndirect(h, off, count);
        }
    }

    private static void playDispatch() {
        int x = ints[ip++];
        int y = ints[ip++];
        int z = ints[ip++];
        if (checking) {
            chkRep(DISPATCH, x, y, z);
        }
        state.dispatch(x, y, z);
    }

    private static void playPolygonOffset() {
        int onBit = ints[ip++];
        int f = ints[ip++];
        int u = ints[ip++];
        if (checking) {
            chkRep(POLY_OFFSET, onBit, f, u);
        }
        SteinGlCommands.polygonOffsetNow(
                onBit != 0, Float.intBitsToFloat(f), Float.intBitsToFloat(u));
    }

    /** Texture parameter ({@code code}: integer or float). */
    private static void playTextureParameter(int code) {
        int tex = ints[ip++];
        int pname = ints[ip++];
        int v = ints[ip++];
        if (checking) {
            chkRep(code, tex, pname, v);
            checkTexture(tex);
        }
        if (code == TEX_PARAMI) {
            GL45.glTextureParameteri(tex, pname, v);
        } else {
            GL45.glTextureParameterf(tex, pname, Float.intBitsToFloat(v));
        }
    }

    private static void playUpload() {
        int h = ints[ip++];
        long off = longs[lp++];
        long at = longs[lp++];
        long bytes = longs[lp++];
        long mapped = longs[lp++];
        if (checking) {
            chkRep(UPLOAD, h, off, bytes, mapped);
            checkBuffer(h);
        }
        if (mapped != 0) {
            MemoryUtil.memCopy(arena + at, mapped, bytes);
        } else {
            GL45.nglNamedBufferSubData(h, off, bytes, arena + at);
        }
    }

    private static void playCopyBuffer() {
        int src = ints[ip++];
        int dst = ints[ip++];
        long so = longs[lp++];
        long dOff = longs[lp++];
        long bytes = longs[lp++];
        if (checking) {
            chkRep(COPY_BUFFER, src, dst, so, dOff, bytes);
            checkBuffer(src);
            checkBuffer(dst);
        }
        GL45.glCopyNamedBufferSubData(src, dst, so, dOff, bytes);
    }

    private static void playCopyTexture() {
        int src = ints[ip++];
        int st = ints[ip++];
        int sl = ints[ip++];
        int dst = ints[ip++];
        int dt = ints[ip++];
        int dl = ints[ip++];
        int w = ints[ip++];
        int h = ints[ip++];
        boolean blit = ints[ip++] != 0;
        if (checking) {
            chkRep(COPY_TEXTURE, src, st, sl, dst, dt, dl, w, h, blit ? 1 : 0);
            checkTexture(src);
            checkTexture(dst);
        }
        if (blit) {
            SteinGlDevice.blit(src, st, sl, dst, dt, dl, w, h);
        } else {
            GL43.glCopyImageSubData(src, st, 0, 0, 0, sl, dst, dt, 0, 0, 0, dl, w, h, 1);
        }
    }

    private static void playDeleteBuffer() {
        int h = ints[ip++];
        int mapped = ints[ip++];
        if (checking) {
            chkRep(DELETE_BUFFER, h, mapped);
        }
        SteinGlBuffer.deleteNow(h, mapped != 0);
        if (state != null) {
            SteinGlPassBindings.bufferDeleted(state, h);
        }
    }

    private static void playFence() {
        SteinGlFence f = (SteinGlFence) objs[op++];
        if (checking) {
            chkRep(FENCE);
        }
        f.insertNow();
    }

    private static void playGameClear() {
        int mask = ints[ip++];
        int fbo = ints[ip++];
        int cm = ints[ip++];
        int dm = ints[ip++];
        float r = Float.intBitsToFloat(ints[ip++]);
        float g = Float.intBitsToFloat(ints[ip++]);
        float b = Float.intBitsToFloat(ints[ip++]);
        float a = Float.intBitsToFloat(ints[ip++]);
        double depth = Double.longBitsToDouble(longs[lp++]);
        int sc = ints[ip++];
        int sx = ints[ip++];
        int sy = ints[ip++];
        int sw = ints[ip++];
        int sh = ints[ip++];
        if (checking) {
            chkRep(GAME_CLEAR, mask, fbo, cm, dm, sc);
            if (fbo != 0 && !GL30.glIsFramebuffer(fbo)) {
                invalid("framebuffer " + fbo + " of the game clear");
            }
        }
        replayClear(mask, fbo, cm, dm != 0, r, g, b, a, depth, sc != 0, sx, sy, sw, sh);
    }

    /**
     * The game's glClear at replay: target, masks, clear values and the scissor of recording time.
     * The target, masks and scissor are set from the replay's state (what is on the driver now) and
     * go back to it; the clear values are the game's ({@link SteinGlBindings}), which only the game
     * and this clear change.
     */
    private static void replayClear(
            int mask,
            int fbo,
            int cm,
            boolean dm,
            float r,
            float g,
            float b,
            float a,
            double depth,
            boolean sc,
            int sx,
            int sy,
            int sw,
            int sh) {
        SteinGlCommands t = tracked();
        int nowFbo = t.drawFbo;
        if (fbo != nowFbo) {
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, fbo);
        }
        boolean[] now = t.mask;
        boolean w0 = (cm & 1) != 0;
        boolean w1 = (cm & 2) != 0;
        boolean w2 = (cm & 4) != 0;
        boolean w3 = (cm & 8) != 0;
        boolean maskDiff = w0 != now[0] || w1 != now[1] || w2 != now[2] || w3 != now[3];
        if (maskDiff) {
            GL11.glColorMask(w0, w1, w2, w3);
        }
        boolean nowDm = t.depthWrite;
        if (dm != nowDm) {
            GL11.glDepthMask(dm);
        }
        float[] cc = SteinGlBindings.clearColor();
        boolean colorDiff = cc[0] != r || cc[1] != g || cc[2] != b || cc[3] != a;
        if (colorDiff) {
            GL11.glClearColor(r, g, b, a);
        }
        double cd = SteinGlBindings.clearDepth();
        if (cd != depth) {
            GL11.glClearDepth(depth);
        }
        // the scissor goes through the replay's state, and back to it (a clear inside a pass
        // must not change the pass's scissor)
        boolean scWas = t.scissorOn;
        int[] box = SCRATCH4;
        if (sc) {
            System.arraycopy(
                    t.scissorBoxSaved ? t.scissorBox : SteinGlBindings.scissorBox(), 0, box, 0, 4);
            SteinGlPassFixed.scissorBox(t, sx, sy, sw, sh);
        }
        SteinGlPassFixed.scissorTest(t, sc);
        GL11.glClear(mask);
        SteinGlPassFixed.scissorTest(t, scWas);
        if (sc) {
            SteinGlPassFixed.scissorBox(t, box[0], box[1], box[2], box[3]);
        }
        if (cd != depth) {
            GL11.glClearDepth(cd);
        }
        if (colorDiff) {
            GL11.glClearColor(cc[0], cc[1], cc[2], cc[3]);
        }
        if (dm != nowDm) {
            GL11.glDepthMask(nowDm);
        }
        if (maskDiff) {
            GL11.glColorMask(now[0], now[1], now[2], now[3]);
        }
        if (fbo != nowFbo) {
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, nowFbo);
        }
    }
}
