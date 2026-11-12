/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.ui;

import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;

/**
 * The rounded shapes of the design system (the pill of buttons and switches, the circles), smooth.
 *
 * <p>Drawing the corner as a triangle fan leaves the edge stepped: the game does not smooth
 * polygons. Here each corner's arc is a small texture, generated with smoothing <b>at the real
 * screen resolution</b> (the radius in actual pixels, not in interface units) and drawn pixel by
 * pixel — the same way as {@code SteinFont}. The middle and the sides are straight rectangles,
 * which need none of that.
 *
 * <p>One corner per radius (and per thickness, for the outline), stored: a screen uses half a dozen
 * of them.
 */
public final class Shapes {

    /**
     * Corners already generated: the key is the radius in pixels and the outline thickness (0 =
     * filled).
     */
    private static final Map<Integer, Integer> corners = new HashMap<>();

    /**
     * Vector drawings already generated (the icons): the key is the name, the size in pixels and
     * whatever else defines it.
     */
    private static final Map<String, int[]> vectors = new HashMap<>();

    /**
     * Recording a vector drawing ({@link #vector}): the shapes of this file and of {@link Ui} go to
     * this image, smoothed, instead of going to the screen.
     */
    private static Graphics2D rec;

    private Shapes() {}

    /**
     * A drawing made of shapes (an icon), smooth at any size and rotation: {@code painter} draws
     * with the usual shapes ({@link Ui#roundRect}, {@link Ui#ring}) inside a box of side {@code s}
     * centered at (cx, cy), and the result is rasterized once, with smoothing, at the real screen
     * resolution, and stored. After that it is just drawing the texture, in the requested color.
     *
     * <p>Rotating or tilting a polygon is what jags the most in direct drawing; here the rotation
     * happens at rasterization, and comes out smooth.
     */
    static void vector(String key, float cx, float cy, float s, int argb, Runnable painter) {
        if (rec != null) {
            painter.run(); // a drawing inside another: goes into the same image
            return;
        }
        if ((argb >>> 24) == 0) {
            return;
        }
        int sc = Ui.scale();
        int p = (int) Math.ceil(s * 1.8F * sc) + 4;
        p += p & 1;
        String k = key + '|' + Math.round(s * sc * 4.0F);
        int[] tex = vectors.get(k);
        if (tex == null) {
            BufferedImage img = new BufferedImage(p, p, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setColor(Color.WHITE);
            g.translate(p / 2.0, p / 2.0);
            g.scale(sc, sc);
            g.translate(-cx, -cy);
            rec = g;
            try {
                painter.run();
            } finally {
                rec = null;
                g.dispose();
            }
            tex = new int[] {uploadRgba(img, p, p, GL11.GL_LINEAR, true), p};
            vectors.put(k, tex);
        }
        float u = 1.0F / sc;
        float x = (Math.round(cx * sc) - tex[1] / 2) * u;
        float y = (Math.round(cy * sc) - tex[1] / 2) * u;
        float size = tex[1] * u;
        GlStateManager.bindTexture(tex[0]);
        blend();
        int cr = argb >> 16 & 0xFF, cg = argb >> 8 & 0xFF, cb = argb & 0xFF, ca = argb >>> 24;
        Tessellator tess = Tessellator.getInstance();
        WorldRenderer wr = tess.getWorldRenderer();
        wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR);
        quad(wr, x, y, size, 0, 0, 1, 1, cr, cg, cb, ca);
        tess.draw();
        unblend();
    }

    /**
     * Recording: a stroke icon, when building the Stein Client's icons — a 24 x 24 box, line only,
     * round caps and joins. {@code spec} is the list of shapes, each in the format of the SVG the
     * icons come from:
     *
     * <ul>
     *   <li>{@code "M6 9l6 6 6-6"}: a path (M L H V C S A Z, upper and lower case);
     *   <li>{@code "o cx cy r"}: circle; {@code "e cx cy rx ry"}: ellipse; {@code "r x y w h
     *       radius"}: rectangle;
     *   <li>{@code "d cx cy r"}: filled dot.
     * </ul>
     *
     * <p>The 24 box becomes a square of side {@code box} centered at (cx, cy), rotated by {@code
     * degrees}. The stroke is 1.4 in the 24 box (the Stein Client's), and never less than one real
     * pixel and a bit — in a small icon, anything thinner than that disappears.
     */
    static void strokes(float cx, float cy, float box, float degrees, String[] spec) {
        AffineTransform saved = rec.getTransform();
        float k = box / 24.0F;
        rec.translate(cx, cy);
        if (degrees != 0.0F) {
            rec.rotate(Math.toRadians(degrees));
        }
        rec.scale(k, k);
        rec.translate(-12.0, -12.0);
        float pixel = 24.0F / (box * Ui.scale()); // one real pixel, in the 24 box
        rec.setStroke(
                new BasicStroke(
                        Math.max(1.4F, pixel * 1.15F),
                        BasicStroke.CAP_ROUND,
                        BasicStroke.JOIN_ROUND));
        for (String s : spec) {
            char kind = s.charAt(0);
            if (kind == 'M' || kind == 'm') {
                rec.draw(SvgPath.parse(s));
                continue;
            }
            String[] n = s.substring(1).trim().split("[ ,]+");
            float a = Float.parseFloat(n[0]),
                    b = Float.parseFloat(n[1]),
                    c = Float.parseFloat(n[2]);
            switch (kind) {
                case 'o' -> rec.draw(new Ellipse2D.Float(a - c, b - c, c * 2, c * 2));
                case 'd' -> rec.fill(new Ellipse2D.Float(a - c, b - c, c * 2, c * 2));
                case 'e' -> {
                    float ry = Float.parseFloat(n[3]);
                    rec.draw(new Ellipse2D.Float(a - c, b - ry, c * 2, ry * 2));
                }
                case 'r' -> {
                    float h = Float.parseFloat(n[3]);
                    float r = n.length > 4 ? Float.parseFloat(n[4]) * 2 : 0;
                    rec.draw(new RoundRectangle2D.Float(a, b, c, h, r, r));
                }
                default -> throw new IllegalArgumentException("unknown icon shape: " + s);
            }
        }
        rec.setTransform(saved);
    }

    /**
     * Uploads the image as an RGBA texture (clamped, one-pixel rows) and returns its id. With
     * {@code whiteRgb} only the alpha is kept (the color comes from the vertex), otherwise the
     * image's own color.
     */
    static int uploadRgba(BufferedImage img, int w, int h, int filter, boolean whiteRgb) {
        ByteBuffer px = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder());
        for (int p : img.getRGB(0, 0, w, h, null, 0, w)) {
            if (whiteRgb) {
                px.put((byte) 0xFF).put((byte) 0xFF).put((byte) 0xFF);
            } else {
                px.put((byte) (p >> 16)).put((byte) (p >> 8)).put((byte) p);
            }
            px.put((byte) (p >>> 24));
        }
        px.flip();
        int id = GL11.glGenTextures();
        GlStateManager.bindTexture(id);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, filter);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, filter);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        GL11.glTexImage2D(
                GL11.GL_TEXTURE_2D,
                0,
                GL11.GL_RGBA,
                w,
                h,
                0,
                GL11.GL_RGBA,
                GL11.GL_UNSIGNED_BYTE,
                px);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
        return id;
    }

    private static void blend() {
        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.enableAlpha();
        GlStateManager.tryBlendFuncSeparate(
                GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        // The game's alpha test cuts below 0.1: the smoothed edge of an already translucent color
        // would vanish.
        GlStateManager.alphaFunc(GL11.GL_GREATER, 0.0F);
    }

    private static void unblend() {
        GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1F);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    /**
     * The top-left quarter of a circle of radius r (in pixels), filled or just the outline of t
     * pixels.
     */
    private static int corner(int r, int t) {
        int key = r << 8 | t;
        Integer tex = corners.get(key);
        if (tex != null) {
            return tex;
        }
        BufferedImage img = new BufferedImage(r, r, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setColor(Color.WHITE);
        if (t <= 0) {
            g.fill(new Ellipse2D.Float(0, 0, r * 2, r * 2));
        } else {
            g.setStroke(new BasicStroke(t));
            g.draw(new Ellipse2D.Float(t / 2.0F, t / 2.0F, r * 2 - t, r * 2 - t));
        }
        g.dispose();
        int id = uploadRgba(img, r, r, GL11.GL_LINEAR, true);
        corners.put(key, id);
        return id;
    }

    /**
     * Filled rectangle with rounded corners (radius r; with r equal to half the height, a pill).
     */
    public static void fill(float x, float y, float w, float h, float r, int argb) {
        draw(x, y, w, h, r, 0, argb);
    }

    /** Outline only, with {@code thickness} real pixels of thickness (1 = a hairline). */
    public static void stroke(
            float x, float y, float w, float h, float r, int thickness, int argb) {
        draw(x, y, w, h, r, Math.max(1, thickness), argb);
    }

    private static void draw(float x, float y, float w, float h, float r, int t, int argb) {
        if (rec != null) {
            // Recording a vector drawing: the shape goes into the image (in white; the color is
            // applied when drawing the texture).
            float rr = Math.min(r, Math.min(w, h) / 2.0F) * 2.0F;
            if (t <= 0) {
                rec.fill(new RoundRectangle2D.Float(x, y, w, h, rr, rr));
            } else {
                float st = t / (float) Ui.scale();
                rec.setStroke(new BasicStroke(st));
                rec.draw(
                        new RoundRectangle2D.Float(
                                x + st / 2, y + st / 2, w - st, h - st, rr - st, rr - st));
            }
            return;
        }
        int s = Ui.scale();
        int px = Math.round(x * s), py = Math.round(y * s);
        int pw = Math.round((x + w) * s) - px, ph = Math.round((y + h) * s) - py;
        if (pw <= 0 || ph <= 0 || (argb >>> 24) == 0) {
            return;
        }
        int pr = Math.max(0, Math.min(Math.round(r * s), Math.min(pw, ph) / 2));
        float u = 1.0F / s;
        if (pr < 2) {
            if (t <= 0) {
                Draw2D.rect(px * u, py * u, pw * u, ph * u, argb);
            } else {
                Draw2D.rect(px * u, py * u, pw * u, t * u, argb);
                Draw2D.rect(px * u, (py + ph - t) * u, pw * u, t * u, argb);
                Draw2D.rect(px * u, (py + t) * u, t * u, (ph - 2 * t) * u, argb);
                Draw2D.rect((px + pw - t) * u, (py + t) * u, t * u, (ph - 2 * t) * u, argb);
            }
            return;
        }
        int tt = Math.min(t, pr);
        // The straight parts.
        if (t <= 0) {
            Draw2D.rect((px + pr) * u, py * u, (pw - 2 * pr) * u, ph * u, argb);
            Draw2D.rect(px * u, (py + pr) * u, pr * u, (ph - 2 * pr) * u, argb);
            Draw2D.rect((px + pw - pr) * u, (py + pr) * u, pr * u, (ph - 2 * pr) * u, argb);
        } else {
            Draw2D.rect((px + pr) * u, py * u, (pw - 2 * pr) * u, tt * u, argb);
            Draw2D.rect((px + pr) * u, (py + ph - tt) * u, (pw - 2 * pr) * u, tt * u, argb);
            Draw2D.rect(px * u, (py + pr) * u, tt * u, (ph - 2 * pr) * u, argb);
            Draw2D.rect((px + pw - tt) * u, (py + pr) * u, tt * u, (ph - 2 * pr) * u, argb);
        }
        // The four corners: the same texture, mirrored.
        int tex = corner(pr, tt);
        GlStateManager.bindTexture(tex);
        blend();
        int cr = argb >> 16 & 0xFF, cg = argb >> 8 & 0xFF, cb = argb & 0xFF, ca = argb >>> 24;
        Tessellator tess = Tessellator.getInstance();
        WorldRenderer wr = tess.getWorldRenderer();
        wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR);
        quad(wr, px * u, py * u, pr * u, 0, 0, 1, 1, cr, cg, cb, ca); // top, left
        quad(wr, (px + pw - pr) * u, py * u, pr * u, 1, 0, 0, 1, cr, cg, cb, ca); // top, right
        quad(wr, px * u, (py + ph - pr) * u, pr * u, 0, 1, 1, 0, cr, cg, cb, ca); // bottom, left
        quad(
                wr,
                (px + pw - pr) * u,
                (py + ph - pr) * u,
                pr * u,
                1,
                1,
                0,
                0,
                cr,
                cg,
                cb,
                ca); // bottom, right
        tess.draw();
        unblend();
    }

    /** A square of side s at (x, y), with the texture from (u0, v0) to (u1, v1). */
    private static void quad(
            WorldRenderer wr,
            float x,
            float y,
            float s,
            float u0,
            float v0,
            float u1,
            float v1,
            int r,
            int g,
            int b,
            int a) {
        wr.pos(x, y + s, 0).tex(u0, v1).color(r, g, b, a).endVertex();
        wr.pos(x + s, y + s, 0).tex(u1, v1).color(r, g, b, a).endVertex();
        wr.pos(x + s, y, 0).tex(u1, v0).color(r, g, b, a).endVertex();
        wr.pos(x, y, 0).tex(u0, v0).color(r, g, b, a).endVertex();
    }
}
