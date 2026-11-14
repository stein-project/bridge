/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.ui;

import dev.xavier.stein.bridge.engine.TextHook;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.client.renderer.texture.TextureUtil;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.Potion;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The API's 2D drawing (api.Render) against the current renderer. It is the only place where mods
 * touch GL: when the renderer changes, it changes here, and the mods stay the same.
 */
public final class Draw2D {

    private static final Map<String, ResourceLocation> textures = new HashMap<>();
    private static IntBuffer upload = IntBuffer.allocate(0);

    private Draw2D() {}

    /** Sets the current color from an ARGB int. */
    static void color(int argb) {
        GlStateManager.color(
                (argb >> 16 & 255) / 255.0F,
                (argb >> 8 & 255) / 255.0F,
                (argb & 255) / 255.0F,
                (argb >>> 24) / 255.0F);
    }

    private static void beginFlat(int argb) {
        GlStateManager.enableBlend();
        GlStateManager.disableTexture2D();
        GlStateManager.tryBlendFuncSeparate(
                GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        // The game's alpha test cuts below 0.1: a very translucent color (an 8% background)
        // vanished, and the rounded corners (textures, without the cut) showed up on their own.
        GlStateManager.alphaFunc(GL11.GL_GREATER, 0.0F);
        color(argb);
    }

    private static void endFlat() {
        GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1F);
        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    public static void rect(float x, float y, float w, float h, int argb) {
        Tessellator t = Tessellator.getInstance();
        WorldRenderer wr = t.getWorldRenderer();
        beginFlat(argb);
        wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION);
        wr.pos(x, y + h, 0).endVertex();
        wr.pos(x + w, y + h, 0).endVertex();
        wr.pos(x + w, y, 0).endVertex();
        wr.pos(x, y, 0).endVertex();
        t.draw();
        endFlat();
    }

    public static void roundRect(float x, float y, float w, float h, float r, int argb) {
        if (r <= 0) {
            rect(x, y, w, h, argb);
        } else {
            Ui.roundRect(x, y, w, h, Math.min(r, Math.min(w, h) / 2), argb);
        }
    }

    public static void ring(float cx, float cy, float r, float t, int argb) {
        Ui.ring(cx, cy, r, t, argb);
    }

    public static void triangle(
            float x1, float y1, float x2, float y2, float x3, float y3, int argb) {
        Tessellator t = Tessellator.getInstance();
        WorldRenderer wr = t.getWorldRenderer();
        // Both sides: the caller does not need to think about the vertex order.
        GlStateManager.disableCull();
        beginFlat(argb);
        wr.begin(GL11.GL_TRIANGLES, DefaultVertexFormats.POSITION);
        wr.pos(x1, y1, 0).endVertex();
        wr.pos(x2, y2, 0).endVertex();
        wr.pos(x3, y3, 0).endVertex();
        t.draw();
        endFlat();
        GlStateManager.enableCull();
    }

    public static int text(String s, float x, float y, int argb, boolean shadow) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        TextHook.hudDepth++;
        try {
            Ui.font().drawString(s, x, y, argb, shadow);
            return Ui.font().getStringWidth(s);
        } finally {
            TextHook.hudDepth--;
        }
    }

    /** The text width as {@link #text} draws it. */
    public static int textWidth(String s) {
        TextHook.hudDepth++;
        try {
            return Ui.font().getStringWidth(s);
        } finally {
            TextHook.hudDepth--;
        }
    }

    /** The text cut to fit in width, measured as {@link #text} draws it. */
    public static String trim(String s, int width) {
        TextHook.hudDepth++;
        try {
            return Ui.font().trimStringToWidth(s, width);
        } finally {
            TextHook.hudDepth--;
        }
    }

    public static int fontHeight() {
        return Ui.font().FONT_HEIGHT;
    }

    private static ResourceLocation location(String texture) {
        return textures.computeIfAbsent(texture, ResourceLocation::new);
    }

    public static void texQuad(
            float x, float y, float w, float h, float u0, float v0, float u1, float v1, int argb) {
        Tessellator t = Tessellator.getInstance();
        WorldRenderer wr = t.getWorldRenderer();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(
                GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        color(argb);
        wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
        wr.pos(x, y + h, 0).tex(u0, v1).endVertex();
        wr.pos(x + w, y + h, 0).tex(u1, v1).endVertex();
        wr.pos(x + w, y, 0).tex(u1, v0).endVertex();
        wr.pos(x, y, 0).tex(u0, v0).endVertex();
        t.draw();
        GlStateManager.disableBlend();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    public static void image(
            String texture,
            float x,
            float y,
            float w,
            float h,
            float u0,
            float v0,
            float u1,
            float v1,
            int argb) {
        Ui.mc().getTextureManager().bindTexture(location(texture));
        texQuad(x, y, w, h, u0, v0, u1, v1, argb);
    }

    public static void item(Object stack, int x, int y, boolean overlay) {
        itemCount(stack, x, y, overlay, null);
    }

    public static void push() {
        GlStateManager.pushMatrix();
    }

    public static void pop() {
        GlStateManager.popMatrix();
    }

    public static void translate(float x, float y) {
        GlStateManager.translate(x, y, 0.0F);
    }

    public static void scale(float s) {
        GlStateManager.scale(s, s, 1.0);
    }

    public static void rotate(float degrees) {
        GlStateManager.rotate(degrees, 0.0F, 0.0F, 1.0F);
    }

    public static void clip(float x, float y, float w, float h) {
        Ui.clip((int) Math.floor(x), (int) Math.floor(y), (int) Math.ceil(w), (int) Math.ceil(h));
    }

    public static void unclip() {
        Ui.unclip();
    }

    // --- mod textures ---

    public static int textureCreate(int w, int h) {
        int id = GL11.glGenTextures();
        TextureUtil.allocateTexture(id, w, h);
        GlStateManager.bindTexture(id);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        return id;
    }

    public static void textureSet(int id, int x, int y, int w, int h, int[] argb, int offset) {
        int n = w * h;
        if (upload.capacity() < n) {
            upload = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asIntBuffer();
        }
        upload.clear();
        upload.put(argb, offset, n).flip();
        GlStateManager.bindTexture(id);
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GL11.glTexSubImage2D(
                GL11.GL_TEXTURE_2D,
                0,
                x,
                y,
                w,
                h,
                GL12.GL_BGRA,
                GL12.GL_UNSIGNED_INT_8_8_8_8_REV,
                upload);
    }

    public static void textureDraw(
            int id,
            float x,
            float y,
            float w,
            float h,
            float u0,
            float v0,
            float u1,
            float v1,
            int argb) {
        GlStateManager.bindTexture(id);
        texQuad(x, y, w, h, u0, v0, u1, v1, argb);
    }

    public static void textureRelease(int id) {
        TextureUtil.deleteTexture(id);
    }

    /**
     * With repeat, the texture repeats beyond the edges (GL_REPEAT): a map that does not need to
     * move anything while walking.
     */
    public static int textureCreate(int w, int h, boolean repeat) {
        int id = textureCreate(w, h);
        if (repeat) {
            GlStateManager.bindTexture(id);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
        }
        return id;
    }

    // --- what the HUD used to draw directly in the game ---

    /**
     * Fan: the first point connects to all the others. With a texture (id != 0), each point with
     * its own u, v coordinate.
     */
    public static void fan(
            float[] xs, float[] ys, float[] us, float[] vs, int count, int argb, int texture) {
        Tessellator t = Tessellator.getInstance();
        WorldRenderer wr = t.getWorldRenderer();
        if (texture == 0) {
            beginFlat(argb);
            wr.begin(GL11.GL_TRIANGLE_FAN, DefaultVertexFormats.POSITION);
            for (int i = 0; i < count; i++) {
                wr.pos(xs[i], ys[i], 0).endVertex();
            }
            t.draw();
            endFlat();
            return;
        }
        GlStateManager.enableTexture2D();
        GlStateManager.bindTexture(texture);
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(
                GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        color(argb);
        wr.begin(GL11.GL_TRIANGLE_FAN, DefaultVertexFormats.POSITION_TEX);
        for (int i = 0; i < count; i++) {
            wr.pos(xs[i], ys[i], 0).tex(us[i], vs[i]).endVertex();
        }
        t.draw();
        GlStateManager.disableBlend();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    @SuppressWarnings("unchecked")
    public static List<String> wrap(String text, int width) {
        if (text == null) {
            return List.of();
        }
        TextHook.hudDepth++;
        try {
            return (List<String>) (List<?>) Ui.font().listFormattedStringToWidth(text, width);
        } finally {
            TextHook.hudDepth--;
        }
    }

    public static int guiSize(boolean width) {
        ScaledResolution r = new ScaledResolution(Ui.mc());
        return width ? r.getScaledWidth() : r.getScaledHeight();
    }

    /**
     * Item as in the hotbar: icon and, with overlay, the inventory overlay (durability bar and
     * count). count null is the stack's count, "" none, other text in its place.
     */
    public static void itemCount(Object stack, int x, int y, boolean overlay, String count) {
        if (!(stack instanceof ItemStack s)) {
            return;
        }
        Minecraft mc = Ui.mc();
        RenderItem ri = mc.getRenderItem();
        FontRenderer fr = Ui.font();
        GlStateManager.enableRescaleNormal();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(
                GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        RenderHelper.enableGUIStandardItemLighting();
        ri.renderItemAndEffectIntoGUI(s, x, y);
        if (overlay) {
            ri.renderItemOverlayIntoGUI(fr, s, x, y, count);
        }
        RenderHelper.disableStandardItemLighting();
        GlStateManager.disableRescaleNormal();
        GlStateManager.disableBlend();
        if (!overlay && count != null && !count.isEmpty()) {
            GlStateManager.disableDepth();
            fr.drawStringWithShadow(count, x + 17 - fr.getStringWidth(count), y + 9, 0xFFFFFF);
            GlStateManager.enableDepth();
        }
    }

    private static final ResourceLocation INVENTORY =
            new ResourceLocation("textures/gui/container/inventory.png");
    private static final ResourceLocation ICONS = new ResourceLocation("textures/gui/icons.png");
    private static final float PX = 1.0F / 256;

    /**
     * The effect icon in the inventory (textures/gui/container/inventory.png, 18x18 starting at 0,
     * 198).
     */
    public static void effectIcon(int id, float x, float y) {
        Potion potion = id >= 0 && id < Potion.potionTypes.length ? Potion.potionTypes[id] : null;
        if (potion == null || !potion.hasStatusIcon()) {
            return;
        }
        int index = potion.getStatusIconIndex();
        int u = index % 8 * 18;
        int v = 198 + index / 8 * 18;
        Ui.mc().getTextureManager().bindTexture(INVENTORY);
        texQuad(x, y, 18, 18, u * PX, v * PX, (u + 18) * PX, (v + 18) * PX, 0xFFFFFFFF);
    }

    /** The game's Tab ping bars (icons.png, 10x8 starting at 0, 176): the same cuts it uses. */
    public static void pingIcon(int ms, float x, float y) {
        int j = ms < 0 ? 5 : ms < 150 ? 0 : ms < 300 ? 1 : ms < 600 ? 2 : ms < 1000 ? 3 : 4;
        Ui.mc().getTextureManager().bindTexture(ICONS);
        texQuad(x, y, 10, 8, 0, (176 + j * 8) * PX, 10 * PX, (184 + j * 8) * PX, 0xFFFFFFFF);
    }
}
