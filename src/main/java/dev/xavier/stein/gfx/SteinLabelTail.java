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
 * <p>Pipeline: Stein GFX only.
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

    private static void vertex(WorldRenderer wr, int x, double y) {
        WorldRenderer w = wr.pos((double) x, y, 0.0);
        w = w.color(0.0F, 0.0F, 0.0F, 0.25F);
        w.endVertex();
    }
}
