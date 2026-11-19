/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.ui;

import dev.xavier.stein.bridge.core.Log;
import dev.xavier.stein.bridge.hooks.GameHooks;
import dev.xavier.stein.bridge.hooks.SteinSyncOffer;
import dev.xavier.stein.bridge.sync.SteinSync;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiMultiplayer;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.ResourceLocation;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

/**
 * The Stein Sync mark in the multiplayer server list: the "S" of the Stein wordmark next to the
 * player count of a server whose status offers Stein Sync, and a tooltip over it (through the
 * screen's hovering text, as the ping bars' tooltip).
 *
 * <p>Drawn on every frame for every visible entry, so nothing is allocated there: the mark is a
 * texture made once per interface scale, already reduced to the screen's pixels, and the tooltip
 * text is kept until the offer or the language changes.
 */
final class SteinSyncBadge {

    /** The mark's size in interface units: the height of a text line. */
    private static final int W = 6;

    private static final int H = 8;

    /** Space between the mark and the player count on its right. */
    private static final int GAP = 3;

    /**
     * The Stein "S" mark, cut to its shape and padded to the 3:4 box above. A geometric mark rather
     * than the wordmark's serif "S", which at 8 units tall read like a "$".
     */
    private static final String MARK = "/assets/steinui/textures/gui/stein_sync.png";

    private static final ResourceLocation TEXTURE =
            new ResourceLocation("steinui", "dynamic/steinsync_mark");

    /** The vanilla client's protocol: a server on another one shows its version, not its count. */
    private static final int PROTOCOL = 47;

    private static BufferedImage mark;
    private static boolean markFailed;

    /** The interface scale the texture was made for; 0 before the first. */
    private static int textureScale;

    private static SteinSyncOffer tooltipOffer;
    private static String tooltipLanguage;
    private static String tooltip;

    private SteinSyncBadge() {}

    /** After a server entry drew itself (SteinSyncScreens.serverListEntry). */
    static void draw(Object serverData, int x, int y, int listWidth, int mouseX, int mouseY) {
        if (!SteinSync.ENABLED) {
            return;
        }
        SteinSyncOffer offer = GameHooks.steinSyncOffer(serverData);
        if (offer == null || !texture()) {
            return;
        }
        ServerData server = (ServerData) serverData;
        Minecraft mc = Minecraft.getMinecraft();
        FontRenderer font = mc.fontRendererObj;
        // The same text the entry put left of the ping bars (colour codes take no width).
        String right = server.version != PROTOCOL ? server.gameVersion : server.populationInfo;
        int textW = font.getStringWidth(right);
        int bx = x + listWidth - 15 - 2 - textW - (textW > 0 ? GAP : 1) - W;
        Ui.image(TEXTURE, bx, y, W, H, 1.0F);
        if (mouseX >= bx && mouseX < bx + W && mouseY >= y && mouseY < y + H) {
            GuiScreen screen = mc.currentScreen;
            if (screen instanceof GuiMultiplayer) {
                ((GuiMultiplayer) screen).setHoveringText(tooltip(offer, mc));
            }
        }
    }

    /** The texture for the current interface scale, made when the scale changes. */
    private static boolean texture() {
        Minecraft mc = Minecraft.getMinecraft();
        GuiScreen screen = mc.currentScreen;
        int scale =
                screen == null || screen.width <= 0
                        ? 1
                        : Math.max(1, Math.round((float) mc.displayWidth / screen.width));
        if (scale == textureScale) {
            return true;
        }
        BufferedImage s = mark();
        if (s == null) {
            return false;
        }
        mc.getTextureManager().deleteTexture(TEXTURE);
        mc.getTextureManager()
                .loadTexture(TEXTURE, new DynamicTexture(reduce(s, W * scale, H * scale)));
        textureScale = scale;
        return true;
    }

    /** The "S" mark; null (logged once) if it cannot be read. */
    private static BufferedImage mark() {
        if (mark != null || markFailed) {
            return mark;
        }
        try (InputStream in = SteinSyncBadge.class.getResourceAsStream(MARK)) {
            BufferedImage image = in == null ? null : ImageIO.read(in);
            if (image == null) {
                throw new IOException("Stein Sync mark missing: " + MARK);
            }
            mark = image;
        } catch (IOException | RuntimeException e) {
            markFailed = true;
            Log.info("steinsync", "Stein Sync mark unavailable, the server list shows none: " + e);
        }
        return mark;
    }

    /**
     * The image reduced to w x h pixels by averaging every source pixel under each target pixel,
     * weighted by alpha (the edges of a white shape on a transparent background stay white).
     */
    static BufferedImage reduce(BufferedImage src, int w, int h) {
        int sw = src.getWidth();
        int sh = src.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int ty = 0; ty < h; ty++) {
            int y0 = ty * sh / h;
            int y1 = Math.max(y0 + 1, (ty + 1) * sh / h);
            for (int tx = 0; tx < w; tx++) {
                int x0 = tx * sw / w;
                int x1 = Math.max(x0 + 1, (tx + 1) * sw / w);
                long a = 0;
                long r = 0;
                long g = 0;
                long b = 0;
                for (int sy = y0; sy < y1; sy++) {
                    for (int sx = x0; sx < x1; sx++) {
                        int p = src.getRGB(sx, sy);
                        int pa = p >>> 24;
                        a += pa;
                        r += (long) (p >> 16 & 0xFF) * pa;
                        g += (long) (p >> 8 & 0xFF) * pa;
                        b += (long) (p & 0xFF) * pa;
                    }
                }
                if (a > 0) {
                    int n = (x1 - x0) * (y1 - y0);
                    int alpha = (int) (a / n);
                    out.setRGB(
                            tx,
                            ty,
                            alpha << 24 | (int) (r / a) << 16 | (int) (g / a) << 8 | (int) (b / a));
                }
            }
        }
        return out;
    }

    /** The tooltip lines, joined with line breaks as GuiMultiplayer's hovering text splits them. */
    private static String tooltip(SteinSyncOffer offer, Minecraft mc) {
        String language = mc.gameSettings.language;
        if (offer != tooltipOffer || language != tooltipLanguage) {
            List<String> lines = new ArrayList<>(3);
            lines.add(I18n.format("steinui.sync.compatible"));
            if (!offer.features.isEmpty()) {
                // The names are the server's text: shown plain, never with its formatting.
                lines.add(
                        I18n.format(
                                "steinui.sync.features",
                                SteinSync.plainText(String.join(", ", offer.features))));
            }
            if (offer.version > SteinSync.clientVersion()) {
                lines.add(I18n.format("steinui.sync.update"));
            }
            tooltip = String.join("\n", lines);
            tooltipOffer = offer;
            tooltipLanguage = language;
        }
        return tooltip;
    }
}
