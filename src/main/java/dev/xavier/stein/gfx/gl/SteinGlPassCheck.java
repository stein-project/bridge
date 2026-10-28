/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx.gl;

import dev.xavier.stein.bridge.core.Log;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL40;
import org.lwjgl.opengl.GL43;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Check of the tracked pass ({@code -Dsteinbridge.gfx.passStateCheck=true}, diagnostic): the
 * earlier driver queries, on every pass, compared with what the pass used without asking.
 *
 * <ul>
 *   <li><b>Opening</b>: the tracked state ({@link SteinGlBindings}: program, VAO, framebuffers,
 *       generic buffers, viewport), the active unit and the 2D texture per unit from the game's
 *       cache, and in core the cache's fixed state (blend, depth, face, mask, offset, logic op)
 *       against the driver. Plus what the tracked pass does not even touch because it is the OpenGL
 *       default that nobody changes: stencil off, FILL polygon mode, CCW front face, FUNC_ADD blend
 *       equation.
 *   <li><b>Next pass of the replay</b> ({@link SteinGlReplay} keeps the state from one pass to the
 *       next): before it touches anything, what the replay believes is on the driver (bindings,
 *       units already used, viewport, fixed state, scissor) against the driver.
 *   <li><b>Closing</b>: after the pass (or the replay) returns the state, the driver against what
 *       the game believes (the same values as the opening).
 *   <li><b>Game clear recorded</b>: the tracked scissor ({@link SteinGlBindings}) it records
 *       against the driver.
 * </ul>
 *
 * Counts divergences per field, prints the first 30 with the value on each side and, every 5 s, the
 * summary (with zero too, to show that it ran). The pass proceeds with the tracked state (the check
 * only looks).
 *
 * <p>Pipeline: Stein GFX only.
 */
final class SteinGlPassCheck {

    private SteinGlPassCheck() {}

    private static long passes;
    private static long total;

    /** Divergences since the start (the out-of-game test checks this). */
    private static long ever;

    static long ever() {
        return ever;
    }

    private static int printed;
    private static final Map<String, long[]> BY_FIELD = new LinkedHashMap<>();
    private static long reportAt = System.nanoTime();

    private static void diverge(
            SteinGlCommands c, String when, String field, String tracked, String driver) {
        diverge(c.name, when, field, tracked, driver);
    }

    private static void diverge(
            String pass, String when, String field, String tracked, String driver) {
        total++;
        ever++;
        BY_FIELD.computeIfAbsent(field, k -> new long[1])[0]++;
        if (printed < 30) {
            printed++;
            Log.info(
                    "gfx",
                    "check: pass '"
                            + pass
                            + "' "
                            + when
                            + " "
                            + field
                            + ": tracked "
                            + tracked
                            + ", driver "
                            + driver);
        }
    }

    private static void eq(SteinGlCommands c, String when, String field, int tracked, int driver) {
        if (tracked != driver) {
            diverge(c, when, field, hex(tracked), hex(driver));
        }
    }

    private static void eq(
            SteinGlCommands c, String when, String field, boolean tracked, boolean driver) {
        if (tracked != driver) {
            diverge(c, when, field, String.valueOf(tracked), String.valueOf(driver));
        }
    }

    private static void eq(
            SteinGlCommands c, String when, String field, float tracked, float driver) {
        if (Float.compare(tracked, driver) != 0) {
            diverge(c, when, field, String.valueOf(tracked), String.valueOf(driver));
        }
    }

    private static String hex(int v) {
        return v >= 0x100 ? v + " (0x" + Integer.toHexString(v) + ")" : String.valueOf(v);
    }

    private static int get(int pname) {
        return GL11.glGetInteger(pname);
    }

    private static void compareBindings(SteinGlCommands c, String w) {
        eq(c, w, "program", c.prevProgram, get(GL20.GL_CURRENT_PROGRAM));
        eq(c, w, "VAO", c.prevVao, get(GL30.GL_VERTEX_ARRAY_BINDING));
        eq(c, w, "draw framebuffer", c.prevDrawFbo, get(GL30.GL_DRAW_FRAMEBUFFER_BINDING));
        eq(
                c,
                w,
                "read framebuffer",
                SteinGlBindings.readFbo(),
                get(GL30.GL_READ_FRAMEBUFFER_BINDING));
    }

    private static void compareBufferBindings(SteinGlCommands c, String w) {
        eq(
                c,
                w,
                "indirect buffer",
                c.prevIndirectBuffer,
                get(GL40.GL_DRAW_INDIRECT_BUFFER_BINDING));
        eq(c, w, "generic uniform", c.prevUniformBuffer, get(GL31.GL_UNIFORM_BUFFER_BINDING));
        if (c.compute()) {
            eq(
                    c,
                    w,
                    "generic storage",
                    c.prevStorageBuffer,
                    get(GL43.GL_SHADER_STORAGE_BUFFER_BINDING));
        }
        eq(c, w, "active unit", c.prevActive, get(GL13.GL_ACTIVE_TEXTURE));
    }

    private static void compareViewport(SteinGlCommands c, String w) {
        int[] v = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, v);
        for (int i = 0; i < 4; i++) {
            eq(c, w, "viewport[" + i + "]", c.prevViewport[i], v[i]);
        }
    }

    /** At opening, before the pass touches anything. */
    static void atOpen(SteinGlCommands c) {
        passes++;
        String w = "open";
        compareBindings(c, w);
        compareBufferBindings(c, w);
        compareViewport(c, w);
        fixed(c, w);
        eq(c, w, "scissor", c.scissorWas, GL11.glIsEnabled(GL11.GL_SCISSOR_TEST));
        compareScissorBox(c.name, w, SteinGlBindings.scissorBox());
        eq(c, w, "stencil", false, GL11.glIsEnabled(GL11.GL_STENCIL_TEST));
        int[] mode = new int[2];
        GL11.glGetIntegerv(GL11.GL_POLYGON_MODE, mode);
        eq(c, w, "polygon mode", GL11.GL_FILL, mode[0]);
        eq(c, w, "front face", GL11.GL_CCW, get(GL11.GL_FRONT_FACE));
        eq(c, w, "blend equation (color)", GL14.GL_FUNC_ADD, get(GL20.GL_BLEND_EQUATION_RGB));
        eq(c, w, "blend equation (alpha)", GL14.GL_FUNC_ADD, get(GL20.GL_BLEND_EQUATION_ALPHA));
        report();
    }

    /** The game's fixed state (the cache, read at opening) against the driver. */
    private static void fixed(SteinGlCommands c, String w) {
        eq(c, w, "blend", c.gBlend, GL11.glIsEnabled(GL11.GL_BLEND));
        eq(c, w, "blend source", c.gBlendSrc, get(GL14.GL_BLEND_SRC_RGB));
        eq(c, w, "blend destination", c.gBlendDst, get(GL14.GL_BLEND_DST_RGB));
        eq(c, w, "blend source alpha", c.gBlendSrcAlpha, get(GL14.GL_BLEND_SRC_ALPHA));
        eq(c, w, "blend destination alpha", c.gBlendDstAlpha, get(GL14.GL_BLEND_DST_ALPHA));
        eq(c, w, "depth test", c.gDepthTest, GL11.glIsEnabled(GL11.GL_DEPTH_TEST));
        eq(c, w, "depth function", c.gDepthFunc, get(GL11.GL_DEPTH_FUNC));
        eq(c, w, "depth write", c.gDepthMask, GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK));
        eq(c, w, "face culling", c.gCull, GL11.glIsEnabled(GL11.GL_CULL_FACE));
        eq(c, w, "culled face", c.gCullFace, get(GL11.GL_CULL_FACE_MODE));
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer m = stack.malloc(16);
            GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, m);
            for (int i = 0; i < 4; i++) {
                eq(c, w, "color mask[" + i + "]", c.gMask[i], m.get(i) != 0);
            }
        }
        eq(c, w, "polygon offset", c.gOffsetFill, GL11.glIsEnabled(GL11.GL_POLYGON_OFFSET_FILL));
        eq(c, w, "offset factor", c.gOffsetFactor, GL11.glGetFloat(GL11.GL_POLYGON_OFFSET_FACTOR));
        eq(c, w, "offset units", c.gOffsetUnits, GL11.glGetFloat(GL11.GL_POLYGON_OFFSET_UNITS));
        eq(c, w, "logic op", c.gLogicOp, GL11.glIsEnabled(GL11.GL_COLOR_LOGIC_OP));
    }

    private static void compareScissorBox(String pass, String w, int[] tracked) {
        int[] box = new int[4];
        GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, box);
        for (int i = 0; i < 4; i++) {
            if (tracked[i] != box[i]) {
                diverge(pass, w, "scissor box[" + i + "]", hex(tracked[i]), hex(box[i]));
            }
        }
    }

    /**
     * A replayed pass that continues from the previous one, before it touches anything: what the
     * replay believes is on the driver (not the game's state: that one is only given back at the
     * end of the replay).
     */
    static void atContinue(SteinGlCommands c) {
        passes++;
        String w = "continue";
        eq(c, w, "program", c.program, get(GL20.GL_CURRENT_PROGRAM));
        eq(c, w, "VAO", c.vao, get(GL30.GL_VERTEX_ARRAY_BINDING));
        eq(c, w, "draw framebuffer", c.drawFbo, get(GL30.GL_DRAW_FRAMEBUFFER_BINDING));
        eq(
                c,
                w,
                "read framebuffer",
                SteinGlBindings.readFbo(),
                get(GL30.GL_READ_FRAMEBUFFER_BINDING));
        if (c.arrayBuffer != Integer.MIN_VALUE) {
            eq(c, w, "ARRAY_BUFFER", c.arrayBuffer, get(GL15.GL_ARRAY_BUFFER_BINDING));
        }
        eq(c, w, "indirect buffer", c.indirectBuffer, get(GL40.GL_DRAW_INDIRECT_BUFFER_BINDING));
        eq(c, w, "generic uniform", c.uniformGeneric, get(GL31.GL_UNIFORM_BUFFER_BINDING));
        if (c.compute()) {
            eq(
                    c,
                    w,
                    "generic storage",
                    c.storageGeneric,
                    get(GL43.GL_SHADER_STORAGE_BUFFER_BINDING));
        }
        int active = get(GL13.GL_ACTIVE_TEXTURE);
        if (c.active != Integer.MIN_VALUE) {
            eq(c, w, "active unit", c.active, active);
        }
        long units = c.savedUnits;
        while (units != 0) {
            int slot = Long.numberOfTrailingZeros(units);
            units &= units - 1;
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + slot);
            String wu = "continue (unit " + slot + ")";
            if (c.cur2D[slot] != Integer.MIN_VALUE) {
                eq(c, wu, "texture 2D", c.cur2D[slot], get(GL11.GL_TEXTURE_BINDING_2D));
            }
            eq(c, wu, "texture array", c.curArray[slot], get(GL30.GL_TEXTURE_BINDING_2D_ARRAY));
            eq(c, wu, "sampler", c.curSampler[slot], get(GL33.GL_SAMPLER_BINDING));
        }
        GL13.glActiveTexture(active);
        int[] v = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, v);
        for (int i = 0; i < 4; i++) {
            eq(c, w, "viewport[" + i + "]", c.viewport[i], v[i]);
        }
        emitted(c, w);
        eq(c, w, "scissor", c.scissorOn, GL11.glIsEnabled(GL11.GL_SCISSOR_TEST));
        compareScissorBox(
                c.name, w, c.scissorBoxSaved ? c.scissorBox : SteinGlBindings.scissorBox());
        report();
    }

    /** The fixed state the replay emitted last (blend, depth, face, mask, offset, logic op). */
    private static void emitted(SteinGlCommands c, String w) {
        eq(c, w, "blend", c.blend, GL11.glIsEnabled(GL11.GL_BLEND));
        eq(c, w, "blend source", c.blendSrc, get(GL14.GL_BLEND_SRC_RGB));
        eq(c, w, "blend destination", c.blendDst, get(GL14.GL_BLEND_DST_RGB));
        eq(c, w, "blend source alpha", c.blendSrcAlpha, get(GL14.GL_BLEND_SRC_ALPHA));
        eq(c, w, "blend destination alpha", c.blendDstAlpha, get(GL14.GL_BLEND_DST_ALPHA));
        eq(c, w, "depth test", c.depthTest, GL11.glIsEnabled(GL11.GL_DEPTH_TEST));
        eq(c, w, "depth function", c.depthFunc, get(GL11.GL_DEPTH_FUNC));
        eq(c, w, "depth write", c.depthWrite, GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK));
        eq(c, w, "face culling", c.cull, GL11.glIsEnabled(GL11.GL_CULL_FACE));
        eq(c, w, "culled face", c.cullFace, get(GL11.GL_CULL_FACE_MODE));
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer m = stack.malloc(16);
            GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, m);
            for (int i = 0; i < 4; i++) {
                eq(c, w, "color mask[" + i + "]", c.mask[i], m.get(i) != 0);
            }
        }
        eq(c, w, "polygon offset", c.offsetFill, GL11.glIsEnabled(GL11.GL_POLYGON_OFFSET_FILL));
        eq(c, w, "logic op", c.logicOp != 0, GL11.glIsEnabled(GL11.GL_COLOR_LOGIC_OP));
    }

    /** The game's glClear being recorded: the tracked scissor it records against the driver. */
    static void atGameClear(boolean scissor, int[] box) {
        String w = "record";
        boolean on = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        if (scissor != on) {
            diverge("game clear", w, "scissor", String.valueOf(scissor), String.valueOf(on));
        }
        if (scissor) {
            compareScissorBox("game clear", w, box);
        }
    }

    /** The first time the pass uses the unit: what it kept against the driver. */
    static void unit(SteinGlCommands c, int slot) {
        c.activeUnit(slot);
        String w = "open (unit " + slot + ")";
        eq(c, w, "texture 2D", c.prevTex2D[slot], get(GL11.GL_TEXTURE_BINDING_2D));
        eq(c, w, "texture array", c.prevTexArray[slot], get(GL30.GL_TEXTURE_BINDING_2D_ARRAY));
        eq(c, w, "sampler", c.prevSampler[slot], get(GL33.GL_SAMPLER_BINDING));
    }

    /** At closing, after the pass returns everything: the driver must be as the game believes. */
    static void atClose(SteinGlCommands c) {
        String w = "close";
        long units = c.savedUnits;
        while (units != 0) {
            int slot = Long.numberOfTrailingZeros(units);
            units &= units - 1;
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + slot);
            String wu = "close (unit " + slot + ")";
            eq(c, wu, "texture 2D", c.prevTex2D[slot], get(GL11.GL_TEXTURE_BINDING_2D));
            eq(c, wu, "texture array", c.prevTexArray[slot], get(GL30.GL_TEXTURE_BINDING_2D_ARRAY));
            eq(c, wu, "sampler", c.prevSampler[slot], get(GL33.GL_SAMPLER_BINDING));
        }
        GL13.glActiveTexture(c.prevActive);
        compareBindings(c, w);
        if (c.prevArrayBuffer != Integer.MIN_VALUE) {
            eq(c, w, "ARRAY_BUFFER", c.prevArrayBuffer, get(GL15.GL_ARRAY_BUFFER_BINDING));
        }
        compareBufferBindings(c, w);
        compareViewport(c, w);
        fixed(c, w);
        eq(c, w, "scissor", c.scissorWas, GL11.glIsEnabled(GL11.GL_SCISSOR_TEST));
        compareScissorBox(c.name, w, SteinGlBindings.scissorBox());
    }

    private static void report() {
        long now = System.nanoTime();
        if (now - reportAt < 5_000_000_000L) {
            return;
        }
        StringBuilder b =
                new StringBuilder("tracked-pass check: ")
                        .append(passes)
                        .append(" passes, ")
                        .append(total)
                        .append(" divergences");
        if (!BY_FIELD.isEmpty()) {
            b.append(" (");
            boolean first = true;
            for (Map.Entry<String, long[]> e : BY_FIELD.entrySet()) {
                if (!first) {
                    b.append(", ");
                }
                first = false;
                b.append(e.getKey()).append(' ').append(e.getValue()[0]);
            }
            b.append(')');
        }
        Log.info("gfx", b.toString());
        passes = 0;
        total = 0;
        BY_FIELD.clear();
        reportAt = now;
    }
}
