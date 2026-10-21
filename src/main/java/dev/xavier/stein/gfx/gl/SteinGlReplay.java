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
 * driver in the order they were recorded, each pass through an {@link SteinGlCommands}. The list is
 * walked with three cursors (ints, longs and objects, see {@link SteinGlOpList}); each command
 * reads its own arguments, in the order {@link SteinGlFrame} wrote them.
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
     * The pass being replayed (it stays open between two replays if the sync fell in the middle of
     * it).
     */
    private static SteinGlCommands cur;

    /** The line width to give back at the end of the replayed pass (NaN = untouched). */
    private static float lineRestore = Float.NaN;

    private static final int[] SCRATCH4 = new int[4];

    // the cursors in the three arrays: int, long and object arguments of the next command
    private static int ip;
    private static int lp;
    private static int op;

    /**
     * Closes the replayed pass, if one is open, and gives back the line width: the end of the pass,
     * or the sync that falls in the middle of it.
     */
    static void closePass() {
        if (cur != null) {
            cur.end();
            cur = null;
        }
        if (!Float.isNaN(lineRestore)) {
            GL11.glLineWidth(lineRestore);
            lineRestore = Float.NaN;
        }
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
        float nowLw = SteinGlBindings.lineWidth();
        if (lw != nowLw) {
            GL11.glLineWidth(lw);
            lineRestore = nowLw;
        }
        if (checking) {
            chkRep(PASS_BEGIN, pass.name.hashCode(), (int) pass.target.handle());
            if (fbo != 0 && !GL30.glIsFramebuffer(fbo)) {
                invalid("framebuffer " + fbo + " of pass '" + pass.name + "'");
            }
        }
        cur = new SteinGlCommands(device, pass, true);
    }

    private static void playPassEnd() {
        if (checking) {
            chkRep(PASS_END);
        }
        closePass();
    }

    private static void playPipeline() {
        SteinPipeline p = (SteinPipeline) objs[op++];
        if (checking) {
            chkRep(PIPELINE, System.identityHashCode(p));
        }
        cur.bindPipeline(p);
    }

    private static void playVertex() {
        int index = ints[ip++];
        int h = ints[ip++];
        long off = longs[lp++];
        if (checking) {
            chkRep(VERTEX, index, h, off);
            checkBuffer(h);
        }
        cur.vertexBuffer(index, h, off);
    }

    private static void playIndex() {
        int h = ints[ip++];
        int u16 = ints[ip++];
        if (checking) {
            chkRep(INDEX, h, u16);
            checkBuffer(h);
        }
        cur.indexBuffer(h, u16 != 0);
    }

    private static void playTexture() {
        int slot = ints[ip++];
        int id = ints[ip++];
        int array = ints[ip++];
        int s = ints[ip++];
        if (checking) {
            SteinGlFrameCheck.replayTexture(slot, id, array, s);
        }
        cur.texture(slot, id, array != 0, s);
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
            cur.uniforms(slot, h, off, size);
        } else {
            cur.storage(slot, h, off, size);
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
        cur.setViewport(x, y, w, h);
    }

    private static void playScissor() {
        int has = ints[ip++];
        int[] r = new int[] {ints[ip], ints[ip + 1], ints[ip + 2], ints[ip + 3]};
        ip += 4;
        if (checking) {
            chkRep(SCISSOR, has, r[0], r[1], r[2], r[3]);
        }
        cur.setScissor(has != 0 ? r : null);
    }

    private static void playDraw() {
        int a = ints[ip++];
        int b = ints[ip++];
        int c = ints[ip++];
        int d = ints[ip++];
        if (checking) {
            chkRep(DRAW, a, b, c, d);
        }
        cur.draw(a, b, c, d);
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
        cur.drawIndexed(a, b, c, d, e);
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
            cur.drawIndirect(h, off, count);
        } else {
            cur.drawIndexedIndirect(h, off, count);
        }
    }

    private static void playDispatch() {
        int x = ints[ip++];
        int y = ints[ip++];
        int z = ints[ip++];
        if (checking) {
            chkRep(DISPATCH, x, y, z);
        }
        cur.dispatch(x, y, z);
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
     * The game's glClear at replay: target, masks, clear values and the scissor of recording time;
     * afterwards everything goes back to what the game believes now (the same state the tracked
     * pass reads).
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
        int nowFbo = SteinGlBindings.drawFbo();
        if (fbo != nowFbo) {
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, fbo);
        }
        boolean[] want = {(cm & 1) != 0, (cm & 2) != 0, (cm & 4) != 0, (cm & 8) != 0};
        boolean[] game = {
            SteinGlGame.colorMask(0),
            SteinGlGame.colorMask(1),
            SteinGlGame.colorMask(2),
            SteinGlGame.colorMask(3)
        };
        boolean maskDiff =
                want[0] != game[0]
                        || want[1] != game[1]
                        || want[2] != game[2]
                        || want[3] != game[3];
        if (maskDiff) {
            GL11.glColorMask(want[0], want[1], want[2], want[3]);
        }
        boolean gameDm = SteinGlGame.depthMask();
        if (dm != gameDm) {
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
        boolean scNow = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        int[] box = SCRATCH4;
        if (sc) {
            GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, box);
            GL11.glScissor(sx, sy, sw, sh);
        }
        if (sc != scNow) {
            if (sc) {
                GL11.glEnable(GL11.GL_SCISSOR_TEST);
            } else {
                GL11.glDisable(GL11.GL_SCISSOR_TEST);
            }
        }
        GL11.glClear(mask);
        if (sc != scNow) {
            if (scNow) {
                GL11.glEnable(GL11.GL_SCISSOR_TEST);
            } else {
                GL11.glDisable(GL11.GL_SCISSOR_TEST);
            }
        }
        if (sc) {
            GL11.glScissor(box[0], box[1], box[2], box[3]);
        }
        if (cd != depth) {
            GL11.glClearDepth(cd);
        }
        if (colorDiff) {
            GL11.glClearColor(cc[0], cc[1], cc[2], cc[3]);
        }
        if (dm != gameDm) {
            GL11.glDepthMask(gameDm);
        }
        if (maskDiff) {
            GL11.glColorMask(game[0], game[1], game[2], game[3]);
        }
        if (fbo != nowFbo) {
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, nowFbo);
        }
    }
}
