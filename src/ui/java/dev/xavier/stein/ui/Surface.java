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

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;

/**
 * The standard Stein background: a single shape for everything that stays on screen during play
 * (the grid elements) and for anyone who wants the same look on a screen of their own.
 *
 * <p>It is the design system's card with rounded corners: Stein's dark field and, around it, a
 * <b>true one-pixel</b> hairline. All the player chooses is the transparency; the hairline follows
 * it, so that an almost transparent background does not turn into just a frame.
 *
 * <p>Drawing the field and the hairline in parts (middle, sides and corners, each with its own
 * transparency) left the seam lighter at the corners: where two translucent parts touch, one adds
 * to the other. Here the whole background is drawn <b>once</b>, with smoothing, in a small image at
 * the screen's real resolution — the square with the four corners, field and hairline already
 * composited — and stretched over nine pieces that do not overlap: the corners as they are, the
 * sides and the middle by the middle column and row. Every screen pixel receives a single layer.
 */
public final class Surface {

    /** Gap between the background and the content, in interface units. */
    public static final int PAD = 3;

    /** Corner radius, in interface units. */
    private static final float RADIUS = 3.0F;

    private static final int FILL = Theme.BG & 0xFFFFFF;
    private static final int RULE = Theme.INK & 0xFFFFFF;

    /** Hairline opacity with the field at full. */
    private static final float RULE_ALPHA = 0x47 / 255.0F;

    /** Images already generated: the key is the radius in pixels and the opacity in hundredths. */
    private static final Map<Integer, int[]> textures = new HashMap<>();

    private Surface() {}

    /**
     * The background from (x, y) to (x + w, y + h), with opacity from 0 (nothing) to 1 (full
     * field).
     */
    public static void draw(float x, float y, float w, float h, float opacity) {
        int level = Math.round(Ui.clamp01(opacity) * 100.0F);
        if (level <= 0 || w <= 0.0F || h <= 0.0F) {
            return;
        }
        int sc = Ui.scale();
        int px = Math.round(x * sc), py = Math.round(y * sc);
        int pw = Math.round((x + w) * sc) - px, ph = Math.round((y + h) * sc) - py;
        if (pw < 2 || ph < 2) {
            return;
        }
        int r = Math.max(1, Math.min(Math.round(RADIUS * sc), Math.min(pw, ph) / 2 - 1));
        int[] tex = texture(r, level);
        int size = tex[1];
        float u = 1.0F / sc;
        float t = 1.0F / size;
        // The middle column and row of the image, sampled at the texel center: what gets stretched.
        float mid = (r + 0.5F) * t;
        float a = r * t, b = (r + 1) * t;
        float x0 = px * u, x1 = (px + r) * u, x2 = (px + pw - r) * u, x3 = (px + pw) * u;
        float y0 = py * u, y1 = (py + r) * u, y2 = (py + ph - r) * u, y3 = (py + ph) * u;

        GlStateManager.bindTexture(tex[0]);
        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(
                GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        GlStateManager.enableAlpha();
        GlStateManager.alphaFunc(GL11.GL_GREATER, 0.0F);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        Tessellator tess = Tessellator.getInstance();
        WorldRenderer wr = tess.getWorldRenderer();
        wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
        // Corners.
        quad(wr, x0, y0, x1, y1, 0, 0, a, a);
        quad(wr, x2, y0, x3, y1, b, 0, 1, a);
        quad(wr, x0, y2, x1, y3, 0, b, a, 1);
        quad(wr, x2, y2, x3, y3, b, b, 1, 1);
        // Sides: the middle row or column, stretched.
        quad(wr, x1, y0, x2, y1, mid, 0, mid, a);
        quad(wr, x1, y2, x2, y3, mid, b, mid, 1);
        quad(wr, x0, y1, x1, y2, 0, mid, a, mid);
        quad(wr, x2, y1, x3, y2, b, mid, 1, mid);
        // Middle.
        quad(wr, x1, y1, x2, y2, mid, mid, mid, mid);
        tess.draw();
        GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1F);
    }

    private static void quad(
            WorldRenderer wr,
            float x0,
            float y0,
            float x1,
            float y1,
            float u0,
            float v0,
            float u1,
            float v1) {
        if (x1 <= x0 || y1 <= y0) {
            return;
        }
        wr.pos(x0, y1, 0).tex(u0, v1).endVertex();
        wr.pos(x1, y1, 0).tex(u1, v1).endVertex();
        wr.pos(x1, y0, 0).tex(u1, v0).endVertex();
        wr.pos(x0, y0, 0).tex(u0, v0).endVertex();
    }

    /**
     * The square of side 2r + 1 with the four corners: field and hairline already composited,
     * smoothed.
     */
    private static int[] texture(int r, int level) {
        int key = r << 8 | level;
        int[] tex = textures.get(key);
        if (tex != null) {
            return tex;
        }
        float opacity = level / 100.0F;
        int s = r * 2 + 1;
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setColor(
                new Color(
                        FILL >> 16 & 0xFF,
                        FILL >> 8 & 0xFF,
                        FILL & 0xFF,
                        Math.round(opacity * 255)));
        g.fill(new RoundRectangle2D.Float(0, 0, s, s, r * 2, r * 2));
        float rule = RULE_ALPHA * Math.min(1.0F, opacity * 1.6F);
        g.setColor(
                new Color(
                        RULE >> 16 & 0xFF, RULE >> 8 & 0xFF, RULE & 0xFF, Math.round(rule * 255)));
        g.setStroke(new BasicStroke(1.0F));
        g.draw(new RoundRectangle2D.Float(0.5F, 0.5F, s - 1, s - 1, r * 2 - 1, r * 2 - 1));
        g.dispose();

        // Pixel by pixel: the image is already at the screen's resolution, and stretching the
        // middle row must not blend neighbors.
        int id = Shapes.uploadRgba(img, s, s, GL11.GL_NEAREST, false);
        tex = new int[] {id, s};
        textures.put(key, tex);
        return tex;
    }
}
