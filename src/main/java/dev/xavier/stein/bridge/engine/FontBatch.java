/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import dev.xavier.stein.bridge.core.Log;
import dev.xavier.stein.gfx.SteinGameState;
import dev.xavier.stein.gfx.SteinLazyGl;
import dev.xavier.stein.gfx.SteinTessCapture;
import dev.xavier.stein.gfx.SteinTextCache;
import dev.xavier.stein.gfx.SteinTextureArrays;
import dev.xavier.stein.gfx.gl.SteinCoreGl;
import dev.xavier.stein.gfx.gl.SteinGlAudit;
import dev.xavier.stein.gfx.gl.SteinGlFrame;

import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Batched text: a text's characters go into a buffer and are drawn with a single glDrawArrays,
 * instead of one glBegin/glEnd block per character.
 *
 * <p>1.8.9's FontRenderer draws each character in immediate mode: glBegin(TRIANGLE_STRIP), 4 x
 * (glTexCoord2f, glVertex3f), glEnd — ten calls per letter. With 400 nametags (each drawn twice),
 * that is ~5 thousand blocks and ~50 thousand calls per frame just for text.
 *
 * <p>The image is the same: the vertices and texture coordinates are the same floats, and each
 * 4-vertex strip becomes the same two triangles, in the same vertex order GL uses for a strip —
 * (v0,v1,v2) and (v2,v1,v3). Color, normal and lightmap coordinate keep coming from the current
 * state, as in immediate mode. The batch is drawn before any state change that the FontRenderer
 * makes between characters (color, texture, underline) and at the end of each text.
 *
 * <p>{@code -Dsteinbridge.opt.vanillaFont=true}: immediate mode, as in 1.8.9.
 *
 * <p>Pipeline: both — the fixed pipeline draws each batch with one glDrawArrays; Stein GFX feeds
 * the batches to its text cache and recorded passes.
 */
public final class FontBatch {

    private static final boolean VANILLA = EngineOptions.VANILLA_FONT;

    /** x, y, z, u, v as floats. */
    private static final int STRIDE = 20;

    private static final int CAPACITY = 6 * 1024;

    private static final ByteBuffer DATA =
            ByteBuffer.allocateDirect(CAPACITY * STRIDE).order(ByteOrder.nativeOrder());
    private static final long ADDR = MemoryUtil.memAddress(DATA);

    /** Vertices in the buffer, waiting to be drawn. */
    private static int count;

    /** glBegin block outside the FontRenderer's pattern: goes straight to GL. */
    private static boolean immediate;

    /** Vertices received in the current block, and the last two of the strip. */
    private static int inStrip;

    private static final float[] PREV = new float[10];
    private static float u;
    private static float v;
    private static Object boundLocation;

    /**
     * The marker for "the bound texture is that of {@link #quad}", in place of a ResourceLocation.
     */
    private static final Object OWN = new Object();

    private static int ownTexture = -1;

    static {
        if (VANILLA) {
            Log.info("font", "text in immediate mode (vanilla)");
        }
    }

    private FontBatch() {}

    // ---- GL11 inside the FontRenderer's character methods ----

    public static void glBegin(int mode) {
        if (VANILLA || mode != GL11.GL_TRIANGLE_STRIP) {
            if (textRecording) {
                SteinTextCache.markBad(); // outside the batch: the text does not go into the cache
            }
            flush();
            immediate = true;
            GLMatrix.flush();
            SteinCoreGl.glBegin(mode);
            return;
        }
        immediate = false;
        inStrip = 0;
    }

    public static void glTexCoord2f(float s, float t) {
        if (immediate) {
            SteinCoreGl.glTexCoord2f(s, t);
            return;
        }
        u = s;
        v = t;
    }

    public static void glVertex3f(float x, float y, float z) {
        if (immediate) {
            SteinCoreGl.glVertex3f(x, y, z);
            return;
        }
        if (inStrip >= 2) {
            if (count + 3 > CAPACITY) {
                flush();
            }
            // Triangle i of the strip: (i, i+1, i+2) if even, (i+1, i, i+2) if odd — like GL.
            int a = (inStrip & 1) == 0 ? 0 : 5;
            put(PREV, a);
            put(PREV, 5 - a);
            putVertex(x, y, z, u, v);
        }
        // Slides the window of the last two vertices.
        System.arraycopy(PREV, 5, PREV, 0, 5);
        PREV[5] = x;
        PREV[6] = y;
        PREV[7] = z;
        PREV[8] = u;
        PREV[9] = v;
        inStrip++;
    }

    public static void glEnd() {
        if (immediate) {
            SteinCoreGl.glEnd();
            immediate = false;
        }
    }

    /**
     * A glyph of a font that is not the game's ({@link TextHook}): the rectangle (x0, y0)-(x1, y1)
     * with the texture from (u0, v0) to (u1, v1), slanted by {@code skew} (the game's italic). It
     * goes into the same batch; a texture change closes the previous batch, like the game font's
     * page change. The color is the current one, as in the whole batch.
     */
    public static void quad(
            int texture,
            float x0,
            float y0,
            float x1,
            float y1,
            float u0,
            float v0,
            float u1,
            float v1,
            float skew) {
        if (boundLocation != OWN || ownTexture != texture) {
            flush();
            boundLocation = OWN;
            ownTexture = texture;
        }
        // Always, like the game's bindTexture: between one text and another, something else may
        // have bound another texture. Through GlStateManager, not straight to GL: it is the one
        // that keeps track of which one is bound (and skips the repeated call).
        GlStateManager.bindTexture(texture);
        if (count + 6 > CAPACITY) {
            flush();
        }
        putVertex(x0 + skew, y0, 0.0F, u0, v0);
        putVertex(x0 - skew, y1, 0.0F, u0, v1);
        putVertex(x1 + skew, y0, 0.0F, u1, v0);
        putVertex(x1 + skew, y0, 0.0F, u1, v0);
        putVertex(x0 - skew, y1, 0.0F, u0, v1);
        putVertex(x1 - skew, y1, 0.0F, u1, v1);
    }

    /**
     * The other font's texture ({@link TextHook}) bound the way {@link #quad} binds it: closes the
     * batch if the texture changes and goes through GlStateManager. So that the cached text can
     * repeat one of its batches ({@code SteinTextCache}).
     */
    public static void bindOwn(int texture) {
        if (boundLocation != OWN || ownTexture != texture) {
            flush();
            boundLocation = OWN;
            ownTexture = texture;
        }
        GlStateManager.bindTexture(texture);
    }

    /**
     * Before requesting a glyph from the other font ({@link TextHook}): if the batch has game
     * characters (a symbol from the unicode page, which Stein's font does not draw), they go out
     * now, with their texture still bound. Requesting the glyph may bind the other font's texture
     * (to record a new glyph in it) — and the pending batch used to be drawn afterwards with the
     * wrong texture: the symbol vanished.
     */
    public static void beforeOwn() {
        if (boundLocation != OWN) {
            flush();
        }
    }

    /** TextureManager.bindTexture called by the FontRenderer: a texture change closes the batch. */
    public static void bindTexture(Object textureManager, Object location) {
        if (location != boundLocation) {
            flush();
            boundLocation = location;
        }
        // The same text asks for the same texture on each name: if it is still the bound one (and
        // no texture was deleted, which could reuse the id), the game's bindTexture would do
        // nothing beyond the lookup in its HashMap.
        long deletes = SteinTextureArrays.deletes();
        if (location == memoLocation
                && deletes == memoDeletes
                && SteinGameState.textureName(SteinGameState.activeUnit()) == memoTexture) {
            return;
        }
        ((TextureManager) textureManager).bindTexture((ResourceLocation) location);
        memoLocation = location;
        memoDeletes = deletes;
        memoTexture = SteinGameState.textureName(SteinGameState.activeUnit());
    }

    private static Object memoLocation;
    private static long memoDeletes;
    private static int memoTexture;

    /** Draws what is in the batch. Before a state change and at the end of each text. */
    public static void flush() {
        if (count == 0) {
            return;
        }
        if (textRecording) {
            // Text being built for the cache (SteinTextCache): the batch is kept, and goes on as
            // usual.
            SteinTextCache.segment(ADDR, count, boundLocation, boundLocation == OWN, ownTexture);
            if (textDry) {
                count = 0; // cache check: recording only, no drawing
                return;
            }
        }
        // New renderer (deferred entities): the batch becomes recorded data, in the order of the
        // rest of the pass.
        if (SteinTessCapture.font(ADDR, count, boundLocation == OWN)) {
            count = 0;
            return;
        }
        // the recorded frame goes before binding arrays and state: the replay changes those
        // bindings
        SteinGlFrame.sync("direct draw of the font batch");
        // Client arrays here must not see the VBO/arrays of the parts batch.
        ModelBatch.restore();
        GLMatrix.flush();
        SteinCoreGl.glEnableClientState(GL11.GL_VERTEX_ARRAY);
        SteinCoreGl.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        GL11.nglVertexPointer(3, GL11.GL_FLOAT, STRIDE, ADDR);
        GL11.nglTexCoordPointer(2, GL11.GL_FLOAT, STRIDE, ADDR + 12);
        if (boundLocation == OWN) {
            // The other font is smoothed: the edge of each letter is the coverage in alpha, and
            // without blending it comes out hard (only the game's alpha test, all or nothing). The
            // HUD arrives here with blending off. Turn it on for this batch and restore it as it
            // was.
            // The driver must have the game's state before the read.
            SteinLazyGl.sync();
            boolean was = GL11.glIsEnabled(GL11.GL_BLEND);
            if (!was) {
                GlStateManager.enableBlend();
            }
            GlStateManager.tryBlendFuncSeparate(
                    GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
            SteinLazyGl.sync();
            if (SteinGlAudit.ON) {
                SteinGlAudit.draw("drawArrays");
            }
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, count);
            if (!was) {
                GlStateManager.disableBlend();
            }
        } else {
            SteinLazyGl.sync();
            if (SteinGlAudit.ON) {
                SteinGlAudit.draw("drawArrays");
            }
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, count);
        }
        SteinCoreGl.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        SteinCoreGl.glDisableClientState(GL11.GL_VERTEX_ARRAY);
        count = 0;
    }

    // ---- cached text (dev.xavier.stein.gfx.SteinTextCache) ----

    /** A text being built for the cache: each batch is handed to it in {@link #flush()}. */
    public static boolean textRecording;

    /**
     * With {@link #textRecording}: the batches only go to the recording, with no drawing (check of
     * the cached text).
     */
    public static boolean textDry;

    /**
     * A batch of a cached text, with the texture and color already set: {@code n} vertices in the
     * new renderer's format (32 bytes: x, y, z, u, v and the rest unused) at {@code addr}. Recorded
     * directly; if the state does not allow it, it goes back to this class's batch and goes out
     * like any batch (right away).
     */
    public static void replay(long addr, int n) {
        replay(addr, n, false);
    }

    /**
     * {@link #replay(long, int)} of a batch of the other font ({@code own}: the texture bound by
     * {@link #bindOwn}, with the blending it turns on only for its own drawing).
     */
    public static void replay(long addr, int n, boolean own) {
        flush();
        if (SteinTessCapture.fontPrebuilt(addr, n, own)) {
            return;
        }
        for (int i = 0; i < n; i++) {
            if (count == CAPACITY) {
                flush();
            }
            long p = addr + i * 32L;
            putVertex(
                    MemoryUtil.memGetFloat(p),
                    MemoryUtil.memGetFloat(p + 4),
                    MemoryUtil.memGetFloat(p + 8),
                    MemoryUtil.memGetFloat(p + 12),
                    MemoryUtil.memGetFloat(p + 16));
        }
        flush();
    }

    private static void put(float[] src, int off) {
        putVertex(src[off], src[off + 1], src[off + 2], src[off + 3], src[off + 4]);
    }

    private static void putVertex(float x, float y, float z, float s, float t) {
        int o = count * STRIDE;
        DATA.putFloat(o, x);
        DATA.putFloat(o + 4, y);
        DATA.putFloat(o + 8, z);
        DATA.putFloat(o + 12, s);
        DATA.putFloat(o + 16, t);
        count++;
    }
}
