/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx.gl;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;

/**
 * The fixed-function state around an OpenGL pass ({@link SteinGlCommands}): reading the game's at
 * opening (in core, from the GlStateManager cache), emitting what a pipeline changes, and giving
 * the game's back at {@link SteinGlCommands#end()}. The state itself lives in the pass; this is the
 * code that moves it.
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
     * Whatever the game had enabled and the pass does not use goes off for the pass: the scissor
     * (queried), polygon offset and logic op (cached). The core context has no glPushAttrib: the
     * state the pass changes is given back by {@link #restore}.
     */
    static void disableForPass(SteinGlCommands cmd) {
        cmd.scissorWas = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        if (cmd.scissorWas) {
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
        }
        if (cmd.gOffsetFill) {
            GL11.glDisable(GL11.GL_POLYGON_OFFSET_FILL);
            cmd.offsetFill = false;
        }
        if (cmd.gLogicOp) {
            GL11.glDisable(GL11.GL_COLOR_LOGIC_OP);
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
     * the flush touched it; scissor and logic op only if the game had them enabled.
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
        if (cmd.gLogicOp) {
            GL11.glEnable(GL11.GL_COLOR_LOGIC_OP);
        }
        if (cmd.scissorOn != cmd.scissorWas) {
            enable(GL11.GL_SCISSOR_TEST, cmd.scissorWas);
        }
        if (cmd.scissorBoxSaved) {
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
