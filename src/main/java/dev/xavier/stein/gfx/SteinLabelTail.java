/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import static dev.xavier.stein.gfx.SteinNameLabels.COLOR_THROUGH;
import static dev.xavier.stein.gfx.SteinNameLabels.SNEAK_OFFSET_Y;
import static dev.xavier.stein.gfx.SteinNameLabels.SNEAK_SCALE;

import dev.xavier.stein.bridge.engine.GLMatrix;
import dev.xavier.stein.bridge.engine.NameStyle;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;

import org.lwjgl.opengl.GL11;

/**
 * The name drawn by the game's own path from the computed name onward (what the direct label
 * replaces, and what teaches the templates).
 *
 * <p>With a {@link NameStyle}, the same game calls with the style's values: the shape of {@link
 * SteinLabelShape} as the background (or none), the text color, the shadow through drawString's
 * own, the size and the height ({@link SteinLabelGeometry}). RenderPlayer's scoreboard line and
 * Render.renderLivingLabel are followed call by call, so the GL state left is the game's.
 *
 * <p>Pipeline: Stein GFX; with a style, also the fixed-function pipeline.
 */
final class SteinLabelTail {

    private SteinLabelTail() {}

    /**
     * The game from the computed name onward: renderName's alphaFunc and, standing,
     * renderOffsetLivingLabel (virtual: RenderPlayer's adds the scoreboard line); sneaking, the
     * renderName section, with the same calls in the same order.
     */
    static void tail() {
        Render render = (Render) SteinLabelContext.render;
        Entity entity = (Entity) SteinLabelContext.entity;
        RenderManager rm = (RenderManager) SteinLabelContext.manager;
        FontRenderer font = (FontRenderer) SteinLabelContext.font;
        boolean sneak = SteinLabelContext.sneak;
        String name = SteinLabelContext.name;
        double x = SteinLabelContext.x;
        double y = SteinLabelContext.y;
        double z = SteinLabelContext.z;
        double d = SteinLabelContext.d;
        GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1F);
        float h = entity.height;
        boolean child = ((EntityLivingBase) entity).isChild();
        if (NameStyle.custom()) {
            if (sneak) {
                styledSneak(rm, font, name, x, y, z, h, child);
                return;
            }
            double yy = y - (child ? (double) (h / 2.0F) : 0.0);
            if (SteinLabelContext.kind == SteinLabelKinds.PLAYER) {
                String s = SteinLabelGeometry.scoreLine(entity, d, SteinLabelContext.slot);
                if (s != null) {
                    styledLabel(rm, font, s, x, yy, z, h);
                    yy += SteinLabelGeometry.scoreStep(font);
                }
            }
            styledLabel(rm, font, name, x, yy, z, h);
            return;
        }
        if (!sneak) {
            render.renderOffsetLivingLabel(
                    entity, x, y - (child ? (double) (h / 2.0F) : 0.0), z, name, SNEAK_SCALE, d);
            return;
        }
        GLMatrix.pushMatrix();
        GLMatrix.translate((float) x, (float) y + h + 0.5F - (child ? h / 2.0F : 0.0F), (float) z);
        SteinLazyGl.glNormal3f(0.0F, 1.0F, 0.0F);
        GLMatrix.rotate(-rm.playerViewY, 0.0F, 1.0F, 0.0F);
        GLMatrix.rotate(rm.playerViewX, 1.0F, 0.0F, 0.0F);
        GLMatrix.scale(-SNEAK_SCALE, -SNEAK_SCALE, SNEAK_SCALE);
        GLMatrix.translate(0.0F, SNEAK_OFFSET_Y, 0.0F);
        GlStateManager.disableLighting();
        GlStateManager.depthMask(false);
        GlStateManager.enableBlend();
        GlStateManager.disableTexture2D();
        GlStateManager.tryBlendFuncSeparate(
                GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        int i = SteinTextCache.stringWidth(font, name) / 2;
        Tessellator tess = Tessellator.getInstance();
        WorldRenderer wr = tess.getWorldRenderer();
        wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        vertex(wr, -i - 1, -1.0);
        vertex(wr, -i - 1, 8.0);
        vertex(wr, i + 1, 8.0);
        vertex(wr, i + 1, -1.0);
        SteinTessCapture.draw(tess);
        GlStateManager.enableTexture2D();
        GlStateManager.depthMask(true);
        int w = SteinTextCache.stringWidth(font, name);
        int ignoredWidth = font.drawString(name, -w / 2, 0, COLOR_THROUGH);
        GlStateManager.enableLighting();
        GlStateManager.disableBlend();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GLMatrix.popMatrix();
    }

    /** Render.renderLivingLabel with the style (the distance was checked by renderName). */
    private static void styledLabel(
            RenderManager rm,
            FontRenderer font,
            String str,
            double x,
            double y,
            double z,
            float h) {
        float s = SteinLabelGeometry.labelScale();
        GLMatrix.pushMatrix();
        GLMatrix.translate((float) x + 0.0F, SteinLabelGeometry.labelTop(y, h), (float) z);
        SteinLazyGl.glNormal3f(0.0F, 1.0F, 0.0F);
        GLMatrix.rotate(-rm.playerViewY, 0.0F, 1.0F, 0.0F);
        GLMatrix.rotate(rm.playerViewX, 1.0F, 0.0F, 0.0F);
        GLMatrix.scale(-s, -s, s);
        GlStateManager.disableLighting();
        GlStateManager.depthMask(false);
        GlStateManager.disableDepth();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(
                GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        int i = str.equals("deadmau5") ? -10 : 0;
        int w = SteinTextCache.stringWidth(font, str);
        GlStateManager.disableTexture2D();
        background(w / 2, -1 + i, 8 + i);
        GlStateManager.enableTexture2D();
        float tx = (float) (-w / 2);
        int ignoredThrough = font.drawString(str, tx, (float) i, NameStyle.textColor(0x20), false);
        GlStateManager.enableDepth();
        GlStateManager.depthMask(true);
        int ignored =
                font.drawString(str, tx, (float) i, NameStyle.textColor(0xFF), NameStyle.shadow());
        GlStateManager.enableLighting();
        GlStateManager.disableBlend();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GLMatrix.popMatrix();
    }

    /** RendererLivingEntity.renderName's sneaking label with the style (no shadow: see-through). */
    private static void styledSneak(
            RenderManager rm,
            FontRenderer font,
            String name,
            double x,
            double y,
            double z,
            float h,
            boolean child) {
        float s = SteinLabelGeometry.sneakScale();
        GLMatrix.pushMatrix();
        GLMatrix.translate((float) x, SteinLabelGeometry.sneakTop(y, h, child), (float) z);
        SteinLazyGl.glNormal3f(0.0F, 1.0F, 0.0F);
        GLMatrix.rotate(-rm.playerViewY, 0.0F, 1.0F, 0.0F);
        GLMatrix.rotate(rm.playerViewX, 1.0F, 0.0F, 0.0F);
        GLMatrix.scale(-s, -s, s);
        GLMatrix.translate(0.0F, SNEAK_OFFSET_Y, 0.0F);
        GlStateManager.disableLighting();
        GlStateManager.depthMask(false);
        GlStateManager.enableBlend();
        GlStateManager.disableTexture2D();
        GlStateManager.tryBlendFuncSeparate(
                GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        int w = SteinTextCache.stringWidth(font, name);
        background(w / 2, -1, 8);
        GlStateManager.enableTexture2D();
        GlStateManager.depthMask(true);
        int ignored = font.drawString(name, -w / 2, 0, NameStyle.textColor(0x20));
        GlStateManager.enableLighting();
        GlStateManager.disableBlend();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GLMatrix.popMatrix();
    }

    /** The style's background through the Tessellator, as the game draws its quad; or none. */
    private static void background(int j, int y0, int y1) {
        if (!NameStyle.background()) {
            return;
        }
        int quads = SteinLabelShape.build(j, y0, y1);
        Tessellator tess = Tessellator.getInstance();
        WorldRenderer wr = tess.getWorldRenderer();
        wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        SteinLabelShape.writeQuads(wr, quads);
        SteinTessCapture.draw(tess);
    }

    private static void vertex(WorldRenderer wr, int x, double y) {
        WorldRenderer w = wr.pos((double) x, y, 0.0);
        w = w.color(0.0F, 0.0F, 0.0F, 0.25F);
        w.endVertex();
    }
}
