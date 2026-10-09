/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import dev.xavier.stein.bridge.core.Log;
import dev.xavier.stein.bridge.engine.FontBatch;
import dev.xavier.stein.bridge.engine.GLMatrix;
import dev.xavier.stein.gfx.gl.SteinCoreGl;
import dev.xavier.stein.gfx.gl.SteinGlAudit;

import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;

import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/**
 * Generic capture for the deferred entity renderer ({@link SteinEntityDeferred}, steps 3 and 4 of
 * the plan): whatever the game draws through the Tessellator (name backgrounds, round shadows,
 * fire, frames, arrows, leashes, pistons...), through the batched font ({@code FontBatch}) and
 * through the item model lists ({@code ItemModelCache}) during the entity pass becomes data —
 * vertices in a single format of our own, an instance with the matrix and the state — and joins the
 * same per-state groups as the model parts, instead of going to the driver right away.
 *
 * <p>Our format (32 bytes per vertex): position 3 floats, texture 2 floats, color 4 ubytes, normal
 * 3 bytes + 1 of padding, lightmap 2 ushorts. The game's WorldRenderer formats (position, texture,
 * color, normal, lightmap, padding) are converted here; quads and strips/fans become triangles in
 * the vertex order GL uses (the face does not change). What the format lacks (color, lightmap)
 * comes from the state, on the instance, as the fixed-function pipeline would do with the current
 * value.
 *
 * <p>Lines (GL_LINES, LINE_STRIP, LINE_LOOP: fishing rod, F3+B boxes) become a list of segments in
 * a line pipeline. Texture coordinate generation (the End portal) is done here per vertex ({@link
 * SteinTexGen}).
 *
 * <p>Draws immediately (today's path) when: outside the pass; while recording a display list;
 * points and the modes the game does not use on entities (QUAD_STRIP, POLYGON); a format with an
 * element we do not know; coordinate generation we do not reproduce (sphere, reflection; q varying
 * within the draw); and any state that {@link SteinEntityDeferred#beginDynamic} refuses.
 *
 * <p>Item models already come as display lists (ItemModelCache): when the list is recorded, the
 * vertices going into it are converted once into a fixed buffer on the GPU, and each call of the
 * list becomes just an instance pointing there — like the model parts.
 *
 * <p>Pipeline: both — the fixed pipeline draws the Tessellator as the game does; Stein GFX captures
 * it as data.
 */
public final class SteinTessCapture {

    static final int VERTEX_BYTES = 32;

    /** Format bits (what the draw carries per vertex). */
    static final int HAS_UV = 1;

    static final int HAS_COLOR = 2;
    static final int HAS_NORMAL = 4;
    static final int HAS_LIGHT = 8;

    private SteinTessCapture() {}

    // ---- Tessellator.draw (patcher rule: every call site, except those that already have their
    // own detour) ----

    /** Warnings about immediate draws in the core (nothing is output). */
    private static int coreWarned;

    /**
     * In the core, a draw into a display list (the game's or the item cache's): the list is just a
     * number (the item comes from the fixed buffer, the model parts from the instancer's geometry)
     * and the draw would go straight to the screen. Does only the rest of the original draw:
     * finishes and clears the WorldRenderer.
     */
    private static void discard(Object tess) {
        WorldRenderer wr = ((Tessellator) tess).getWorldRenderer();
        wr.finishDrawing();
        wr.reset();
    }

    /** In the core context the Tessellator only finishes and clears; otherwise the game draws. */
    private static void drawOrDiscard(Object tess) throws Throwable {
        if (SteinCoreGl.CORE) {
            discard(tess);
        } else {
            ((Tessellator) tess).draw();
        }
    }

    /** In place of Tessellator.draw. */
    public static void draw(Object tess) throws Throwable {
        if (SteinStaticGeometry.recordList == 0 && !SteinEntityDeferred.capturingDynamic()) {
            if (GLMatrix.compiling()) {
                // into a display list (does not go to the screen): in the core it only finishes and
                // clears; in the regular context it goes to the list
                drawOrDiscard(tess);
                return;
            }
            // Outside the pass (F3, loading screens, panorama): a one-draw pass for this draw only.
            if (SteinEntityDeferred.oneShotBegin()) {
                try {
                    draw(tess);
                } finally {
                    SteinEntityDeferred.oneShotEnd();
                }
                return;
            }
            if (SteinGlAudit.ON) {
                SteinGlAudit.why = "outside the pass";
            }
            ((Tessellator) tess).draw();
            return;
        }
        if (SteinStaticGeometry.recordList != 0) {
            SteinStaticGeometry.record(tess);
            drawOrDiscard(tess);
            return;
        }
        if (FontBatch.textRecording) {
            // Text being assembled with the Tessellator (underline, strikethrough): kept out of
            // the cache.
            SteinTextCache.markBad();
        }
        if (GLMatrix.compiling()) {
            // game display list (model part): goes to the list, as always
            drawOrDiscard(tess);
            return;
        }
        if (!capture(tess)) {
            if (SteinGlAudit.ON) {
                SteinGlAudit.why = "refused: " + refused;
            }
            if (SteinCoreGl.CORE && coreWarned++ < 8) {
                StackTraceElement[] st = new Throwable().getStackTrace();
                StringBuilder b = new StringBuilder("core: immediate draw (does not show up)");
                for (int i = 1; i < Math.min(8, st.length); i++) {
                    b.append("\n[gfx]     ").append(st[i]);
                }
                Log.info("gfx", b.toString());
            }
            SteinEntityDeferred.beforeImmediate();
            ((Tessellator) tess).draw();
        }
    }

    /**
     * The last color given explicitly (glColor). After a draw with per-vertex color, GlStateManager
     * forgets the color (resetColor), but the driver keeps this one: vertex colors do not change
     * the current color (verified on AMD; the creative inventory tabs came out black with the last
     * vertex's color).
     */
    static float lastR;

    static float lastG;
    static float lastB;
    static float lastA;
    static boolean lastColorKnown;

    /**
     * Every explicit color that goes to the driver (or would stay in it, in the deferred state and
     * in the core).
     */
    public static void noteColor(float r, float g, float b, float a) {
        lastR = r;
        lastG = g;
        lastB = b;
        lastA = a;
        lastColorKnown = true;
    }

    /** Why the last {@link #capture} refused (renderer audit). */
    private static String refused;

    /** Records the draw; false = nothing changed (the caller draws immediately). */
    private static boolean capture(Object tess) throws Throwable {
        WorldRenderer wr = ((Tessellator) tess).getWorldRenderer();
        int count = wr.getVertexCount();
        if (count == 0) {
            // the original only finishes and clears (nothing goes to the driver)
            wr.finishDrawing();
            wr.reset();
            return true;
        }
        int mode = wr.getDrawMode();
        boolean lines =
                mode == GL11.GL_LINES || mode == GL11.GL_LINE_STRIP || mode == GL11.GL_LINE_LOOP;
        int out =
                lines
                        ? SteinVertexConverter.lineVertices(mode, count)
                        : SteinVertexConverter.triangles(mode, count);
        if (out <= 0) {
            refused = "mode " + mode;
            return false;
        }
        SteinCaptureFormat f = SteinCaptureFormat.of(wr.getVertexFormat());
        if ((f.bits & HAS_NORMAL) != 0) {
            // Recorded or immediate: after it the current OpenGL normal is undefined.
            SteinGlTrack.forgetNormal();
        }
        if (!f.ok) {
            refused = "format";
            return false;
        }
        // Texture coordinate generation (the End portal): done here per vertex ({@link
        // SteinTexGen}).
        boolean gen = texGen();
        if (gen && !SteinTexGen.prepare((f.bits & HAS_UV) != 0)) {
            refused = "texgen";
            return false;
        }
        if (!SteinEntityDeferred.beginDynamic(gen ? f.bits | HAS_UV : f.bits, false, lines)) {
            refused = SteinEntityDeferred.rejectWhy;
            return false;
        }
        if (!lines
                && !gen
                && SteinTextGpu.REQUESTED
                && SteinTextGpu.wantsBackground(mode, count, f.bits)) {
            // A name's background (SteinTextGpu): a resident run and the record that the name's
            // text reuses.
            long tmp = SteinTextGpu.quadScratch();
            SteinVertexConverter.convert(
                    MemoryUtil.memAddress(wr.getByteBuffer(), 0),
                    f,
                    mode,
                    count,
                    tmp,
                    flat() && f.color >= 0);
            if (SteinTextGpu.background(tmp, out)) {
                wr.finishDrawing();
                GlStateManager.resetColor();
                wr.reset();
                return true;
            }
        }
        long dst = SteinEntityDeferred.reserveDynamic(out);
        if (dst == 0L) {
            refused = "ring full";
            return false;
        }
        ByteBuffer buf = wr.getByteBuffer();
        if (lines) {
            SteinVertexConverter.convertLines(
                    MemoryUtil.memAddress(buf, 0), f, mode, count, dst, flat() && f.color >= 0);
        } else if (FAST_QUADS && mode == GL11.GL_QUADS && f.quadFast && !(f.color >= 0 && flat())) {
            SteinVertexConverter.convertQuads(MemoryUtil.memAddress(buf, 0), f, count, dst);
            if (CAPTURE_CHECK) {
                SteinVertexConverter.checkQuads(MemoryUtil.memAddress(buf, 0), f, count, dst, out);
            }
        } else {
            SteinVertexConverter.convert(
                    MemoryUtil.memAddress(buf, 0), f, mode, count, dst, flat() && f.color >= 0);
        }
        if (gen && !SteinTexGen.bake(dst, out)) {
            refused = "texgen bake";
            return false; // the reserved space stays unused (nothing points to it)
        }
        SteinEntityDeferred.commitDynamic(out, gen);
        // What the original draw does besides drawing: finishes the WorldRenderer, forgets the
        // color (GL keeps the last vertex's) and clears the WorldRenderer.
        wr.finishDrawing();
        if (f.color >= 0) {
            GlStateManager.resetColor();
        }
        wr.reset();
        return true;
    }

    // ---- quads of the HUD and screen formats, without the generic converter ----

    /**
     * {@code -Dsteinbridge.gfx.fastQuads=false}: every quad goes through the generic {@link
     * #convert}. When on, quads of the position/texture/color-only formats (POSITION, POSITION_TEX,
     * POSITION_COLOR, POSITION_TEX_COLOR: the HUD, the screens, text through the Tessellator) go
     * through a dedicated loop with the offsets already known — the same bytes, in the same vertex
     * order.
     */
    private static final boolean FAST_QUADS = SteinGfxOptions.FAST_QUADS;

    /**
     * {@code -Dsteinbridge.gfx.captureCheck=true}: each quad of the dedicated loop is also
     * converted by the generic one, and the two are compared byte by byte on the fields the format
     * carries (every 5 s, in the log, equal and different).
     */
    private static final boolean CAPTURE_CHECK = SteinGfxOptions.CAPTURE_CHECK;

    static boolean texGen() throws Throwable {
        for (Object o : SteinTessGame.TEXGEN) {
            if ((boolean) SteinTessGame.BOOL.invokeExact(o)) {
                return true;
            }
        }
        return false;
    }

    private static boolean flat() {
        return GlStateManager.activeShadeModel == GL11.GL_FLAT;
    }

    // ---- fire and glowing items: immediate draw, after what has already been recorded ----

    /** Before the Tessellator.draw of Render.renderEntityOnFire (patcher rule). */
    private static boolean shadow;

    /** Before and after the round shadow's draw (Render.renderShadow, patcher rules). */
    public static void shadowBegin() {
        shadow = true;
    }

    public static void shadowEnd() {
        shadow = false;
    }

    static boolean inShadow() {
        return shadow;
    }

    // ---- batched font (FontBatch.flush) ----

    /** Refusal with the reason, for the renderer audit. */
    static boolean refuse(String why) {
        if (SteinGlAudit.ON) {
            SteinGlAudit.why = why;
        }
        return false;
    }

    /**
     * The FontBatch text batch: {@code count} vertices (x, y, z, u, v as floats, already in
     * triangles) at {@code addr}. {@code ownBlend}: Stein's own font, which draws with blending
     * (SRC_ALPHA, ONE_MINUS_SRC_ALPHA, ONE, ZERO). true = recorded (the batch can be emptied
     * without drawing).
     */
    public static boolean font(long addr, int count, boolean ownBlend) {
        if (!SteinEntityDeferred.capturingDynamic()
                && SteinStaticGeometry.recordList == 0
                && !GLMatrix.compiling()
                && SteinEntityDeferred.oneShotBegin()) {
            // text outside the pass (F3, the profiler graph): a one-draw pass for it alone
            try {
                return font(addr, count, ownBlend);
            } finally {
                SteinEntityDeferred.oneShotEnd();
            }
        }
        if (!SteinEntityDeferred.capturingDynamic()
                || SteinStaticGeometry.recordList != 0
                || GLMatrix.compiling()) {
            return refuse(!SteinEntityDeferred.capturingDynamic() ? "outside the pass" : "list");
        }
        try {
            if (texGen() || !SteinEntityDeferred.beginDynamic(HAS_UV, ownBlend)) {
                SteinEntityDeferred.beforeImmediate();
                return refuse(texGen() ? "texgen" : SteinEntityDeferred.rejectWhy);
            }
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
        long d = SteinEntityDeferred.reserveDynamic(count);
        if (d == 0L) {
            SteinEntityDeferred.beforeImmediate();
            return refuse("ring full");
        }
        for (int i = 0; i < count; i++, d += VERTEX_BYTES) {
            long p = addr + i * 20L;
            MemoryUtil.memPutFloat(d, MemoryUtil.memGetFloat(p));
            MemoryUtil.memPutFloat(d + 4, MemoryUtil.memGetFloat(p + 4));
            MemoryUtil.memPutFloat(d + 8, MemoryUtil.memGetFloat(p + 8));
            MemoryUtil.memPutFloat(d + 12, MemoryUtil.memGetFloat(p + 12));
            MemoryUtil.memPutFloat(d + 16, MemoryUtil.memGetFloat(p + 16));
        }
        SteinEntityDeferred.commitDynamic(count);
        return true;
    }

    /**
     * Already-assembled text ({@link SteinTextCache}): {@code count} vertices already in our format
     * (32 bytes, in triangles) at {@code addr}, copied in one go. Same state and same refusals as
     * {@link #font}; false = nothing recorded. {@code own}: a batch from the other font (TextHook),
     * with the blending it enables just for its own draw.
     */
    public static boolean fontPrebuilt(long addr, int count, boolean own) {
        if (!SteinEntityDeferred.capturingDynamic()
                || SteinStaticGeometry.recordList != 0
                || GLMatrix.compiling()) {
            return false;
        }
        try {
            if (texGen() || !SteinEntityDeferred.beginDynamic(HAS_UV, own)) {
                SteinEntityDeferred.beforeImmediate();
                return false;
            }
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
        long d = SteinEntityDeferred.reserveDynamic(count);
        if (d == 0L) {
            SteinEntityDeferred.beforeImmediate();
            return false;
        }
        MemoryUtil.memCopy(addr, d, (long) count * VERTEX_BYTES);
        SteinEntityDeferred.commitDynamic(count);
        return true;
    }

    /**
     * A run of glyphs resident on the GPU ({@link SteinTextGpu}): {@code count} vertices starting
     * at {@code first} in its buffer, with the run's texture already bound. Nothing is copied: only
     * the name's record and the command. The color comes from the vertex (hence HAS_COLOR: the
     * state's is not read). Same refusals as {@link #fontPrebuilt}; false = nothing recorded, and
     * the caller repeats the text through the copy path (which draws immediately if the state
     * refuses again).
     */
    static boolean fontResident(int first, int count, boolean own) {
        if (!SteinEntityDeferred.capturingDynamic()
                || SteinStaticGeometry.recordList != 0
                || GLMatrix.compiling()) {
            return false;
        }
        try {
            if (texGen() || !SteinEntityDeferred.beginDynamic(HAS_UV | HAS_COLOR, own)) {
                return false;
            }
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
        return SteinEntityDeferred.commitText(first, count, false);
    }

    // ---- fixed geometry (item lists, owned ranges): see SteinStaticGeometry ----

    /** Before recording the list {@code list} of an item model (inside glNewList). */
    public static void listBegin(int list) {
        SteinStaticGeometry.listBegin(list);
    }

    /** After recording the list: its vertices go to the fixed buffer. */
    public static void listEnd() {
        SteinStaticGeometry.listEnd();
    }

    /**
     * In place of an item model's glCallList, with the current matrix: true = recorded as an
     * instance of the fixed geometry (do not call the list).
     */
    public static boolean callList(int list) {
        return SteinStaticGeometry.callList(list);
    }

    /**
     * The owner's geometry converted into its own range of the fixed geometry; false = not
     * suitable.
     */
    public static boolean slot(Object owner, long src, Object format, int mode, int count)
            throws Throwable {
        return SteinStaticGeometry.slot(owner, src, format, mode, count);
    }

    /** In place of the owner's draw: true = recorded (do not draw). */
    public static boolean drawSlot(Object owner) {
        return SteinStaticGeometry.drawSlot(owner);
    }

    /** Resource reload: the item lists are deleted and rebuilt; the fixed buffer starts over. */
    public static void clearLists() {
        SteinStaticGeometry.clearLists();
    }

    static SteinBuffer staticGeometry() {
        return SteinStaticGeometry.staticGeometry();
    }

    // ---- game texture filter: see SteinTextureFilters ----

    /**
     * GL11.glTexParameteri inside AbstractTexture.setBlurMipmapDirect and
     * TextureUtil.setTextureBlurMipmap (patcher rules, solo mode): records the bound texture's
     * filter. See {@link SteinTextureFilters#texParameter}.
     */
    public static void texParameter(int target, int pname, int value) {
        SteinTextureFilters.texParameter(target, pname, value);
    }

    /** Is there a registered filter waiting for the driver? */
    static boolean filtersPending() {
        return SteinTextureFilters.filtersPending();
    }

    static void syncFilters() {
        SteinTextureFilters.syncFilters();
    }

    static void forgetFilter(int tex) {
        SteinTextureFilters.forgetFilter(tex);
    }

    /** The texture's known filter: 1 + (min index * 2 + mag index), or 0 if unknown. */
    static int filterCode(int tex) {
        return SteinTextureFilters.filterCode(tex);
    }

    static int applyFilter(int tex, int code) {
        return SteinTextureFilters.applyFilter(tex, code);
    }

    static void restore(int tex, int code) {
        SteinTextureFilters.restore(tex, code);
    }
}
