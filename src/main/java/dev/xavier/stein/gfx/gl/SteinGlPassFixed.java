/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx.gl;

import dev.xavier.stein.gfx.SteinRenderPass;
import dev.xavier.stein.gfx.SteinRenderTarget;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;

import java.util.Arrays;

/**
 * The fixed-function state around an OpenGL pass ({@link SteinGlCommands}): reading the game's at
 * opening (in core, from the GlStateManager cache), the pass's viewport, clears and scissor,
 * emitting what a pipeline changes, and giving the game's back at {@link SteinGlCommands#end()}.
 * The state itself lives in the pass; this is the code that moves it.
 *
 * <p>Pipeline: Stein GFX only.
 */
final class SteinGlPassFixed {

    private SteinGlPassFixed() {}

    /**
     * The game's fixed state read from the cache and set as the "already emitted" (the first
     * pipeline only changes what differs).
     */
    static void capture(SteinGlCommands cmd) {
        SteinGlBindings.viewport(cmd.prevViewport);
        System.arraycopy(cmd.prevViewport, 0, cmd.viewport, 0, 4);
        cmd.gBlend = SteinGlGame.blend();
        cmd.gBlendSrc = SteinGlGame.blendSrc();
        cmd.gBlendDst = SteinGlGame.blendDst();
        cmd.gBlendSrcAlpha = SteinGlGame.blendSrcAlpha();
        cmd.gBlendDstAlpha = SteinGlGame.blendDstAlpha();
        cmd.gDepthTest = SteinGlGame.depthTest();
        cmd.gDepthFunc = SteinGlGame.depthFunc();
        cmd.gDepthMask = SteinGlGame.depthMask();
        cmd.gCull = SteinGlGame.cull();
        cmd.gCullFace = SteinGlGame.cullMode();
        for (int i = 0; i < 4; i++) {
            cmd.gMask[i] = SteinGlGame.colorMask(i);
        }
        cmd.gOffsetFill = SteinGlGame.polygonOffsetFill();
        cmd.gOffsetFactor = SteinGlGame.polygonOffsetFactor();
        cmd.gOffsetUnits = SteinGlGame.polygonOffsetUnits();
        cmd.gLogicOp = SteinGlGame.logicOp();
        cmd.gLogicOpMode = SteinGlGame.logicOpMode();
        cmd.scissorWas = SteinGlBindings.scissorTest();
        cmd.scissorOn = cmd.scissorWas;
        cmd.scissorBoxSaved = false;
        // the emitted state starts as the game's; disableForPass turns off what a pass does not use
        cmd.logicOp = cmd.gLogicOp ? cmd.gLogicOpMode : 0;
        cmd.logicOpTouched = false;
        cmd.offsetTouched = false;
        cmd.fixedKnown = true;
        cmd.blend = cmd.gBlend;
        cmd.blendSrc = cmd.gBlendSrc;
        cmd.blendDst = cmd.gBlendDst;
        cmd.blendSrcAlpha = cmd.gBlendSrcAlpha;
        cmd.blendDstAlpha = cmd.gBlendDstAlpha;
        cmd.depthTest = cmd.gDepthTest;
        cmd.depthFunc = cmd.gDepthFunc;
        cmd.depthWrite = cmd.gDepthMask;
        cmd.cull = cmd.gCull;
        cmd.cullFace = cmd.gCullFace;
        System.arraycopy(cmd.gMask, 0, cmd.mask, 0, 4);
        cmd.offsetFill = cmd.gOffsetFill;
    }

    /**
     * Whatever is enabled and the pass does not use goes off for the pass: the scissor, polygon
     * offset and logic op. Compared with the emitted state, not the game's: on the first pass it is
     * the game's, and in the replay of the recorded frame the next pass starts from what the
     * previous one left. The core context has no glPushAttrib: the state the passes change is given
     * back by {@link #restore}.
     */
    static void disableForPass(SteinGlCommands cmd) {
        if (cmd.scissorOn) {
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            cmd.scissorOn = false;
        }
        if (cmd.offsetFill) {
            GL11.glDisable(GL11.GL_POLYGON_OFFSET_FILL);
            cmd.offsetFill = false;
        }
        if (cmd.logicOp != 0) {
            GL11.glDisable(GL11.GL_COLOR_LOGIC_OP);
            cmd.logicOp = 0;
        }
    }

    /** Color attachments a render target can have. */
    private static final int MAX_COLOR_ATTACHMENTS = 8;

    private static final float[] CLEAR_ZERO = new float[4];

    /** The depth clear value of {@link #clear} (on the context thread only). */
    private static final float[] CLEAR_DEPTH = new float[1];

    /**
     * The pass's viewport (its own or the whole target), sent only when it differs from the last
     * one.
     */
    static void passViewport(SteinGlCommands cmd, SteinRenderPass pass, SteinRenderTarget target) {
        if (pass.viewport != null) {
            viewport(cmd, pass.viewport[0], pass.viewport[1], pass.viewport[2], pass.viewport[3]);
        } else {
            viewport(cmd, 0, 0, target.width(), target.height());
        }
    }

    static void viewport(SteinGlCommands cmd, int x, int y, int w, int h) {
        int[] v = cmd.viewport;
        if (v[0] != x || v[1] != y || v[2] != w || v[3] != h) {
            GL11.glViewport(x, y, w, h);
            v[0] = x;
            v[1] = y;
            v[2] = w;
            v[3] = h;
        }
    }

    /**
     * The pass's clears (with the masks open and no scissor, which also apply to the clear). The
     * masks are only sent when the emitted ones are not open already.
     */
    static void clear(SteinGlCommands cmd, SteinRenderPass pass, SteinRenderTarget target) {
        boolean[] mask = cmd.mask;
        if (pass.colorLoad == SteinRenderPass.Load.CLEAR) {
            if (!mask[0] || !mask[1] || !mask[2] || !mask[3]) {
                GL11.glColorMask(true, true, true, true);
                mask[0] = mask[1] = mask[2] = mask[3] = true;
            }
            float[] color = pass.clearColor != null ? pass.clearColor : CLEAR_ZERO;
            int n = Math.max(1, colorCount(target));
            for (int i = 0; i < n; i++) {
                GL30.glClearBufferfv(GL11.GL_COLOR, i, color);
            }
        }
        if (pass.depthLoad == SteinRenderPass.Load.CLEAR) {
            if (!cmd.depthWrite) {
                GL11.glDepthMask(true);
                cmd.depthWrite = true;
            }
            CLEAR_DEPTH[0] = pass.clearDepth;
            GL30.glClearBufferfv(GL11.GL_DEPTH, 0, CLEAR_DEPTH);
        }
    }

    private static int colorCount(SteinRenderTarget target) {
        int n = 0;
        while (n < MAX_COLOR_ATTACHMENTS && target.color(n) != null) {
            n++;
        }
        return n;
    }

    /**
     * The scissor test of the pass (the driver's, not noted in {@link SteinGlBindings}: {@link
     * #restore} gives the game's back).
     */
    static void scissorTest(SteinGlCommands cmd, boolean on) {
        if (cmd.scissorOn != on) {
            if (on) {
                GL11.glEnable(GL11.GL_SCISSOR_TEST);
            } else {
                GL11.glDisable(GL11.GL_SCISSOR_TEST);
            }
            cmd.scissorOn = on;
        }
    }

    /**
     * The scissor box of the pass, sent only when it differs from the last one. The game's box is
     * kept the first time (tracked, not asked) for {@link #restore}.
     */
    static void scissorBox(SteinGlCommands cmd, int x, int y, int w, int h) {
        int[] box = cmd.scissorBox;
        if (!cmd.scissorBoxSaved) {
            System.arraycopy(SteinGlBindings.scissorBox(), 0, cmd.prevScissorBox, 0, 4);
            System.arraycopy(cmd.prevScissorBox, 0, box, 0, 4);
            cmd.scissorBoxSaved = true;
        }
        if (box[0] != x || box[1] != y || box[2] != w || box[3] != h) {
            GL11.glScissor(x, y, w, h);
            box[0] = x;
            box[1] = y;
            box[2] = w;
            box[3] = h;
        }
    }

    /** Only what differs from the last emitted state (from the game, the first time). */
    static void apply(SteinGlCommands cmd, SteinGlPipeline p) {
        boolean all = !cmd.fixedKnown;
        cmd.fixedKnown = true;
        if (all || cmd.blend != p.blend) {
            enable(GL11.GL_BLEND, p.blend);
            cmd.blend = p.blend;
        }
        if (p.blend
                && (all
                        || cmd.blendSrc != p.blendSrc
                        || cmd.blendDst != p.blendDst
                        || cmd.blendSrcAlpha != p.blendSrcAlpha
                        || cmd.blendDstAlpha != p.blendDstAlpha)) {
            GL14.glBlendFuncSeparate(p.blendSrc, p.blendDst, p.blendSrcAlpha, p.blendDstAlpha);
            cmd.blendSrc = p.blendSrc;
            cmd.blendDst = p.blendDst;
            cmd.blendSrcAlpha = p.blendSrcAlpha;
            cmd.blendDstAlpha = p.blendDstAlpha;
        }
        if (all || cmd.depthTest != p.depthTest) {
            enable(GL11.GL_DEPTH_TEST, p.depthTest);
            cmd.depthTest = p.depthTest;
        }
        if (p.depthTest && (all || cmd.depthFunc != p.depthFunc)) {
            GL11.glDepthFunc(p.depthFunc);
            cmd.depthFunc = p.depthFunc;
        }
        if (all || cmd.depthWrite != p.depthWrite) {
            GL11.glDepthMask(p.depthWrite);
            cmd.depthWrite = p.depthWrite;
        }
        if (all || cmd.cull != p.cull) {
            enable(GL11.GL_CULL_FACE, p.cull);
            cmd.cull = p.cull;
        }
        if (p.cull && (all || cmd.cullFace != p.cullFace)) {
            GL11.glCullFace(p.cullFace);
            cmd.cullFace = p.cullFace;
        }
        boolean c = p.colorWrite;
        boolean a = p.colorWrite && p.alphaWrite;
        if (all || cmd.mask[0] != c || cmd.mask[1] != c || cmd.mask[2] != c || cmd.mask[3] != a) {
            GL11.glColorMask(c, c, c, a);
            cmd.mask[0] = c;
            cmd.mask[1] = c;
            cmd.mask[2] = c;
            cmd.mask[3] = a;
        }
        if (all || cmd.logicOp != p.logicOp) {
            enable(GL11.GL_COLOR_LOGIC_OP, p.logicOp != 0);
            if (p.logicOp != 0) {
                GL11.glLogicOp(p.logicOp);
                cmd.logicOpTouched = true;
            }
            cmd.logicOp = p.logicOp;
        }
    }

    static void enable(int cap, boolean on) {
        if (on) {
            SteinCoreGl.glEnable(cap);
        } else {
            SteinCoreGl.glDisable(cap);
        }
    }

    /**
     * Tracked in core: only what the pass left different from the game (the already-emitted against
     * what was read at opening). The polygon offset goes back whole (enable, factor and units) if
     * the flush touched it; the logic op only if the game had it enabled; the scissor box only if a
     * pass changed it.
     */
    static void restore(SteinGlCommands cmd) {
        if (cmd.blend != cmd.gBlend) {
            enable(GL11.GL_BLEND, cmd.gBlend);
        }
        if (cmd.blendSrc != cmd.gBlendSrc
                || cmd.blendDst != cmd.gBlendDst
                || cmd.blendSrcAlpha != cmd.gBlendSrcAlpha
                || cmd.blendDstAlpha != cmd.gBlendDstAlpha) {
            GL14.glBlendFuncSeparate(
                    cmd.gBlendSrc, cmd.gBlendDst, cmd.gBlendSrcAlpha, cmd.gBlendDstAlpha);
        }
        if (cmd.depthTest != cmd.gDepthTest) {
            enable(GL11.GL_DEPTH_TEST, cmd.gDepthTest);
        }
        if (cmd.depthFunc != cmd.gDepthFunc) {
            GL11.glDepthFunc(cmd.gDepthFunc);
        }
        if (cmd.depthWrite != cmd.gDepthMask) {
            GL11.glDepthMask(cmd.gDepthMask);
        }
        if (cmd.cull != cmd.gCull) {
            enable(GL11.GL_CULL_FACE, cmd.gCull);
        }
        if (cmd.cullFace != cmd.gCullFace) {
            GL11.glCullFace(cmd.gCullFace);
        }
        if (cmd.mask[0] != cmd.gMask[0]
                || cmd.mask[1] != cmd.gMask[1]
                || cmd.mask[2] != cmd.gMask[2]
                || cmd.mask[3] != cmd.gMask[3]) {
            GL11.glColorMask(cmd.gMask[0], cmd.gMask[1], cmd.gMask[2], cmd.gMask[3]);
        }
        if (cmd.offsetFill != cmd.gOffsetFill) {
            enable(GL11.GL_POLYGON_OFFSET_FILL, cmd.gOffsetFill);
        }
        if (cmd.offsetTouched) {
            GL11.glPolygonOffset(cmd.gOffsetFactor, cmd.gOffsetUnits);
        }
        if (cmd.logicOp != 0 && !cmd.gLogicOp) {
            GL11.glDisable(GL11.GL_COLOR_LOGIC_OP);
        }
        if (cmd.logicOpTouched) {
            GL11.glLogicOp(cmd.gLogicOpMode);
        }
        if (cmd.gLogicOp) {
            GL11.glEnable(GL11.GL_COLOR_LOGIC_OP);
        }
        if (cmd.scissorOn != cmd.scissorWas) {
            enable(GL11.GL_SCISSOR_TEST, cmd.scissorWas);
        }
        if (cmd.scissorBoxSaved && !Arrays.equals(cmd.scissorBox, cmd.prevScissorBox)) {
            GL11.glScissor(
                    cmd.prevScissorBox[0],
                    cmd.prevScissorBox[1],
                    cmd.prevScissorBox[2],
                    cmd.prevScissorBox[3]);
        }
        if (cmd.viewport[0] != cmd.prevViewport[0]
                || cmd.viewport[1] != cmd.prevViewport[1]
                || cmd.viewport[2] != cmd.prevViewport[2]
                || cmd.viewport[3] != cmd.prevViewport[3]) {
            GL11.glViewport(
                    cmd.prevViewport[0],
                    cmd.prevViewport[1],
                    cmd.prevViewport[2],
                    cmd.prevViewport[3]);
        }
    }
}
