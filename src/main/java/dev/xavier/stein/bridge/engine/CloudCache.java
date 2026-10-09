/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import dev.xavier.stein.bridge.core.Log;
import dev.xavier.stein.gfx.SteinGfxOptions;
import dev.xavier.stein.gfx.SteinTessCapture;
import dev.xavier.stein.gfx.gl.SteinCoreGl;
import dev.xavier.stein.gfx.gl.SteinGlAudit;
import dev.xavier.stein.gfx.gl.SteinGlFrame;

import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * "Fancy" clouds kept between frames.
 *
 * <p>The 1.8.9 RenderGlobal.renderCloudsFancy rebuilds the cloud geometry every frame, vertex by
 * vertex (WorldRenderer.pos/tex/color/normal): 64 cells, two passes (depth, then color), 128 draws.
 * In the benchmark's terrain scene that was 58% of the render thread — more than the whole terrain.
 *
 * <p>The geometry only changes when the whole cell under the player changes (the texture
 * coordinates), the side it is viewed from (above or below the layer, which turns on the top and
 * bottom faces), the color (in bytes, as the WorldRenderer writes it) or the anaglyph mode. Between
 * one and another, only the fractional part of the offset and the eye height change, and they enter
 * the positions by addition. So:
 *
 * <ul>
 *   <li>when the key changes, the game's own renderCloudsFancy runs with Tessellator.draw diverted
 *       to here: instead of drawing, the vertices are copied — the geometry is the game's, not a
 *       rewrite of it;
 *   <li>every frame, the copy is handed to Stein GFX ({@link SteinTessCapture#drawSlot}) or,
 *       without it, drawn from a VBO, with the difference in offset and height since the capture
 *       applied as a translation: the same GL state, the same quad order, one draw per pass instead
 *       of 64.
 * </ul>
 *
 * <p>{@code -Dsteinbridge.opt.vanillaClouds=true}: clouds rebuilt every frame, as in 1.8.9. {@code
 * -Dsteinbridge.opt.checkClouds=true}: uses the cache and, every second, captures the game's
 * geometry again and compares it vertex by vertex with the translated copy.
 *
 * <p>Pipeline: both — the cached clouds are drawn straight from a buffer; Stein GFX draws a copy
 * kept in its fixed geometry.
 */
public final class CloudCache {

    private static final boolean VANILLA = EngineOptions.VANILLA_CLOUDS;
    private static final boolean CHECK = !VANILLA && EngineOptions.CHECK_CLOUDS;

    /**
     * POSITION_TEX_COLOR_NORMAL: position 3 floats, texture 2 floats, color 4 bytes, normal 3 bytes
     * and 1 of padding.
     */
    private static final int STRIDE = 28;

    /**
     * Bytes compared per vertex: everything except the padding, which the WorldRenderer does not
     * write.
     */
    private static final int SIGNIFICANT = 27;

    static {
        if (CHECK) {
            Log.info(
                    "clouds",
                    "check: the game geometry is recaptured every second and"
                            + " compared with the cache");
        } else if (VANILLA) {
            Log.info("clouds", "clouds rebuilt every frame (vanilla)");
        }
    }

    /** Target of the diverted Tessellator.draw; null = normal drawing. */
    private static ByteBuffer target;

    private static boolean captureBad;
    private static ByteBuffer captureBuf = direct(512 * 1024);
    private static ByteBuffer checkBuf;

    /** One pass of the captured geometry, the same one that Stein GFX or the VBO holds. */
    private static ByteBuffer cache;

    private static int vbo;
    private static int vertices;
    private static boolean valid;
    private static boolean broken;

    // Geometry key and offset at capture.
    private static int capturedCellX;
    private static int capturedCellZ;
    private static int capturedPass;
    private static boolean capturedTop;
    private static boolean capturedBottom;
    private static long capturedColorA;
    private static int capturedColorB;
    private static float capturedDx;
    private static float capturedDz;
    private static float capturedDy;

    // Current frame.
    private static int cellX;
    private static int cellZ;
    private static boolean top;
    private static boolean bottom;
    private static long colorA;
    private static int colorB;
    private static float dx;
    private static float dz;
    private static float dy;

    private static long captures;
    private static long checkedVertices;
    private static float maxError;
    private static long lastCheck;
    private static final CheckReport REPORT = new CheckReport(5_000_000_000L);

    private CloudCache() {}

    /** RenderGlobal.renderCloudsFancy, called by the game's renderClouds. */
    public static void renderFancy(Object rg, float partialTicks, int pass) throws Throwable {
        if (VANILLA || broken) {
            ((RenderGlobal) rg).renderCloudsFancy(partialTicks, pass);
            return;
        }
        frame(rg, partialTicks, pass);
        if (!valid
                || cellX != capturedCellX
                || cellZ != capturedCellZ
                || top != capturedTop
                || bottom != capturedBottom
                || pass != capturedPass
                || colorA != capturedColorA
                || colorB != capturedColorB) {
            if (!recapture(rg, partialTicks, pass)) {
                ((RenderGlobal) rg).renderCloudsFancy(partialTicks, pass);
                return;
            }
        } else if (CHECK && System.nanoTime() - lastCheck >= 1_000_000_000L) {
            verify(rg, partialTicks, pass);
        }
        render(rg, pass);
    }

    /**
     * Tessellator.draw inside renderCloudsFancy: draws, as in the game, or — during a capture —
     * copies the vertices and clears the WorldRenderer as the draw would.
     */
    public static void draw(Object tessellator) {
        if (target == null) {
            ((Tessellator) tessellator).draw();
            return;
        }
        WorldRenderer wr = ((Tessellator) tessellator).getWorldRenderer();
        wr.finishDrawing();
        int n = wr.getVertexCount();
        if (n > 0) {
            if (wr.getVertexFormat() != DefaultVertexFormats.POSITION_TEX_COLOR_NORMAL
                    || wr.getDrawMode() != GL11.GL_QUADS) {
                captureBad = true;
            } else {
                int len = n * STRIDE;
                if (target.remaining() < len) {
                    ByteBuffer bigger =
                            direct(Math.max(target.capacity() * 2, target.position() + len));
                    target.flip();
                    bigger.put(target);
                    target = bigger;
                }
                // Absolute copy, without depending on the position and limit finishDrawing left.
                ByteBuffer src = wr.getByteBuffer().duplicate();
                src.limit(src.capacity());
                target.put(target.position(), src, 0, len);
                target.position(target.position() + len);
            }
        }
        wr.reset();
    }

    /** The same computations as renderCloudsFancy, in the same order and with the same types. */
    private static void frame(Object rg, float pt, int pass) {
        RenderGlobal global = (RenderGlobal) rg;
        Entity e = global.mc.getRenderViewEntity();
        double lastY = e.lastTickPosY;
        float eye = (float) (lastY + (e.posY - lastY) * (double) pt);
        double ticks = (double) ((float) global.cloudTickCounter + pt);
        double prevX = e.prevPosX;
        double prevZ = e.prevPosZ;
        double d1 = (prevX + (e.posX - prevX) * (double) pt + ticks * (double) 0.03F) / 12.0;
        double d2 = (prevZ + (e.posZ - prevZ) * (double) pt) / 12.0 + (double) 0.33F;
        World world = global.theWorld;
        // The height that the game's renderCloudsFancy sees (diverted to Visuals: height option).
        dy = Visuals.cloudHeight(world.provider) - eye + 0.33F;
        d1 -= (double) (BlockMath.floor(d1 / 2048.0) * 2048);
        d2 -= (double) (BlockMath.floor(d2 / 2048.0) * 2048);
        cellX = BlockMath.floor(d1);
        cellZ = BlockMath.floor(d2);
        dx = (float) (d1 - (double) cellX);
        dz = (float) (d2 - (double) cellZ);
        top = dy <= 5.0F;
        bottom = dy > -5.0F;

        Vec3 c = world.getCloudColour(pt);
        float r = (float) c.xCoord;
        float g = (float) c.yCoord;
        float b = (float) c.zCoord;
        if (pass != 2) {
            float ar = (r * 30.0F + g * 59.0F + b * 11.0F) / 100.0F;
            float ag = (r * 30.0F + g * 70.0F) / 100.0F;
            float ab = (r * 30.0F + b * 70.0F) / 100.0F;
            r = ar;
            g = ag;
            b = ab;
        }
        // The bytes that WorldRenderer.color writes: top (color), x sides (0.9),
        // bottom (0.7) and z sides (0.8).
        long a = 0L;
        a = a << 8 | byteOf(r);
        a = a << 8 | byteOf(g);
        a = a << 8 | byteOf(b);
        a = a << 8 | byteOf(r * 0.9F);
        a = a << 8 | byteOf(g * 0.9F);
        a = a << 8 | byteOf(b * 0.9F);
        a = a << 8 | byteOf(r * 0.7F);
        a = a << 8 | byteOf(g * 0.7F);
        colorA = a;
        colorB =
                byteOf(b * 0.7F) << 24
                        | byteOf(r * 0.8F) << 16
                        | byteOf(g * 0.8F) << 8
                        | byteOf(b * 0.8F);
    }

    private static int byteOf(float c) {
        return (int) (c * 255.0F) & 0xFF;
    }

    /** Captures the current frame's geometry and hands it to Stein GFX or takes it to the VBO. */
    private static boolean recapture(Object rg, float pt, int pass) throws Throwable {
        int total = capture(rg, pt, pass, captureBuf);
        captureBuf = captureTarget;
        if (total < 0) {
            return false;
        }
        int half = total / 2;
        if (cache == null || cache.capacity() < half) {
            cache = direct(Math.max(half, 256 * 1024));
        }
        cache.clear();
        cache.put(0, captureBuf, 0, half);
        cache.limit(half);
        vertices = half / STRIDE;
        // The VBO is always filled: it is the fixed-function draw, and Stein GFX's fallback when
        // its copy cannot be drawn (see drawCopy).
        if (vbo == 0) {
            vbo = GL15.glGenBuffers();
        }
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, cache, GL15.GL_STATIC_DRAW);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        if (SteinGfxOptions.ENABLED) {
            // The same copy in Stein GFX's fixed geometry, drawn by the API.
            SteinTessCapture.slot(
                    SLOT,
                    org.lwjgl.system.MemoryUtil.memAddress(cache),
                    FORMAT,
                    GL11.GL_QUADS,
                    vertices);
        }
        capturedCellX = cellX;
        capturedCellZ = cellZ;
        capturedTop = top;
        capturedBottom = bottom;
        capturedPass = pass;
        capturedColorA = colorA;
        capturedColorB = colorB;
        capturedDx = dx;
        capturedDz = dz;
        capturedDy = dy;
        if (captures++ == 0) {
            Log.info("clouds", "cloud cache on: " + vertices + " vertices per pass");
        }
        valid = true;
        return true;
    }

    /**
     * Runs the game's renderCloudsFancy with Tessellator.draw copying into {@code into}. The matrix
     * goes back as it was (the method applies the cloud scale). Returns the bytes of one pass times
     * two, or -1 if the capture is not usable — then the cache turns itself off.
     */
    private static int capture(Object rg, float pt, int pass, ByteBuffer into) {
        into.clear();
        captureBad = false;
        target = into;
        GLMatrix.pushMatrix();
        try {
            ((RenderGlobal) rg).renderCloudsFancy(pt, pass);
        } finally {
            captureTarget = target;
            target = null;
            GLMatrix.popMatrix();
        }
        ByteBuffer got = captureTarget;
        int total = got.position();
        // Both passes draw the same geometry; only the color mask changes.
        if (captureBad || total == 0 || total % (2 * STRIDE) != 0 || !halvesEqual(got, total / 2)) {
            broken = true;
            Log.info(
                    "clouds",
                    "unexpected capture (bytes "
                            + total
                            + ", format "
                            + (captureBad ? "other" : "ok")
                            + "): clouds as in 1.8.9 from here on");
            return -1;
        }
        return total;
    }

    /**
     * The buffer that received the last capture: the one passed in, or a larger one if it grew
     * during it.
     */
    private static ByteBuffer captureTarget;

    private static boolean halvesEqual(ByteBuffer b, int half) {
        for (int v = 0; v < half; v += STRIDE) {
            for (int i = 0; i < SIGNIFICANT; i++) {
                if (b.get(v + i) != b.get(half + v + i)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** The GL state of renderCloudsFancy, with the copy in place of the 64 draws of each pass. */
    private static void render(Object rg, int pass) {
        GlStateManager.disableCull();
        ((RenderGlobal) rg).renderEngine.bindTexture(RenderGlobal.locationCloudsPng);
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(
                GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        GLMatrix.scale(12.0F, 1.0F, 12.0F);
        for (int p = 0; p < 2; p++) {
            if (p == 0) {
                GlStateManager.colorMask(false, false, false, false);
            } else {
                switch (pass) {
                    case 0:
                        GlStateManager.colorMask(false, true, true, true);
                        break;
                    case 1:
                        GlStateManager.colorMask(true, false, false, true);
                        break;
                    case 2:
                        GlStateManager.colorMask(true, true, true, true);
                        break;
                    default:
                        break; // unexpected pass: the mask stays as it was
                }
            }
            GLMatrix.pushMatrix();
            GLMatrix.translate(capturedDx - dx, dy - capturedDy, capturedDz - dz);
            if (SteinGfxOptions.ENABLED) {
                drawCopy();
            } else {
                GLMatrix.flush();
                drawVbo();
            }
            GLMatrix.popMatrix();
        }
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.disableBlend();
        GlStateManager.enableCull();
    }

    /** Owner of the copy in the Stein renderer's fixed geometry. */
    private static final Object SLOT = new Object();

    private static final Object FORMAT = DefaultVertexFormats.POSITION_TEX_COLOR_NORMAL;

    /**
     * The cached copy of the pass, recorded by Stein GFX with the current state and matrix. The GL
     * color ends as the last vertex's, like the game's draw. When Stein GFX cannot draw the copy
     * (outside the recorded pass, no copy yet, or the recording refused it), the pass is drawn from
     * the VBO instead.
     */
    private static void drawCopy() {
        if (SteinTessCapture.drawSlot(SLOT)) {
            GlStateManager.resetColor();
            return;
        }
        GLMatrix.flush();
        drawVbo();
    }

    /** The pointers and states of WorldVertexBufferUploader.draw, reading from the VBO. */
    private static void drawVbo() {
        // the recorded frame goes before binding buffer and arrays: replay swaps those bindings
        SteinGlFrame.sync("direct draw of the old clouds");
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        SteinCoreGl.glVertexPointer(3, GL11.GL_FLOAT, STRIDE, 0L);
        SteinCoreGl.glEnableClientState(GL11.GL_VERTEX_ARRAY);
        SteinCoreGl.glClientActiveTexture(GL13.GL_TEXTURE0);
        SteinCoreGl.glTexCoordPointer(2, GL11.GL_FLOAT, STRIDE, 12L);
        SteinCoreGl.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        SteinCoreGl.glColorPointer(4, GL11.GL_UNSIGNED_BYTE, STRIDE, 20L);
        SteinCoreGl.glEnableClientState(GL11.GL_COLOR_ARRAY);
        SteinCoreGl.glNormalPointer(GL11.GL_BYTE, STRIDE, 24L);
        SteinCoreGl.glEnableClientState(GL11.GL_NORMAL_ARRAY);
        if (SteinGlAudit.ON) {
            SteinGlAudit.draw("drawArrays");
        }
        GL11.glDrawArrays(GL11.GL_QUADS, 0, vertices);
        SteinCoreGl.glDisableClientState(GL11.GL_VERTEX_ARRAY);
        SteinCoreGl.glClientActiveTexture(GL13.GL_TEXTURE0);
        SteinCoreGl.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        SteinCoreGl.glDisableClientState(GL11.GL_COLOR_ARRAY);
        GlStateManager.resetColor();
        SteinCoreGl.glDisableClientState(GL11.GL_NORMAL_ARRAY);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
    }

    /** Check: the geometry the game would give now x the translated copy. */
    private static void verify(Object rg, float pt, int pass) {
        lastCheck = System.nanoTime();
        if (checkBuf == null) {
            checkBuf = direct(captureBuf.capacity());
        }
        int total = capture(rg, pt, pass, checkBuf);
        checkBuf = captureTarget;
        if (total < 0) {
            return;
        }
        int n = total / 2 / STRIDE;
        boolean diverged = n != vertices;
        float ox = capturedDx - dx;
        float oy = dy - capturedDy;
        float oz = capturedDz - dz;
        for (int v = 0; v < Math.min(n, vertices) && !diverged; v++) {
            int o = v * STRIDE;
            float ex = Math.abs(checkBuf.getFloat(o) - (cache.getFloat(o) + ox));
            float ey = Math.abs(checkBuf.getFloat(o + 4) - (cache.getFloat(o + 4) + oy));
            float ez = Math.abs(checkBuf.getFloat(o + 8) - (cache.getFloat(o + 8) + oz));
            maxError = Math.max(maxError, Math.max(ex, Math.max(ey, ez)));
            // Texture, color and normal: the same bytes.
            for (int i = 12; i < SIGNIFICANT && !diverged; i++) {
                diverged = checkBuf.get(o + i) != cache.get(o + i);
            }
            diverged |= ex > 1.0e-3F || ey > 1.0e-3F || ez > 1.0e-3F;
            checkedVertices++;
        }
        if (REPORT.compared(!diverged)) {
            Log.info(
                    "clouds",
                    "DIVERGENCE: "
                            + n
                            + " vertices captured x "
                            + vertices
                            + " in the cache (cell "
                            + cellX
                            + ","
                            + cellZ
                            + ")");
        }
        if (REPORT.due()) {
            Log.info(
                    "clouds",
                    "check: %d comparisons, %d vertices, largest position deviation"
                            + " %.1e, %d divergences, %d captures",
                    REPORT.checks,
                    checkedVertices,
                    maxError,
                    REPORT.divergences,
                    captures);
        }
    }

    private static ByteBuffer direct(int bytes) {
        return ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
    }
}
