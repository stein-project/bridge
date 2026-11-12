/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.ui;

import dev.xavier.stein.bridge.core.Log;
import dev.xavier.stein.gfx.SteinGlTrack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Drawing shared by the tab and the menu. */
public final class Ui {

    public static final ResourceLocation ICON =
            new ResourceLocation("steinui", "textures/gui/icon.png");

    /** Stein Loader logo, 270 x 192 (aspect ratio 1.41). */
    public static final ResourceLocation LOGO =
            new ResourceLocation("steinui", "textures/gui/logo.png");

    private static final ResourceLocation CLICK = new ResourceLocation("gui.button.press");

    private Ui() {}

    public static Minecraft mc() {
        return Minecraft.getMinecraft();
    }

    public static FontRenderer font() {
        return mc().fontRendererObj;
    }

    public static long now() {
        return Minecraft.getSystemTime();
    }

    public static void rect(int left, int top, int right, int bottom, int argb) {
        Gui.drawRect(left, top, right, bottom, argb);
    }

    /** The whole texture at w x h (the Loader's textures have linear filtering: .mcmeta). */
    public static void image(ResourceLocation tex, int x, int y, int w, int h, float alpha) {
        mc().getTextureManager().bindTexture(tex);
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(
                GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        GlStateManager.color(1.0F, 1.0F, 1.0F, alpha);
        Gui.drawModalRectWithCustomSizedTexture(x, y, 0.0F, 0.0F, w, h, w, h);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.disableBlend();
    }

    /**
     * Same at a fractional position: slides smoothly, without jumping pixel by pixel of the GUI.
     */
    public static void image(ResourceLocation tex, float x, float y, int w, int h, float alpha) {
        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, 0.0F);
        image(tex, 0, 0, w, h, alpha);
        GlStateManager.popMatrix();
    }

    /**
     * The whole texture at w x h, painted in a single color (with its alpha): this is how an icon
     * drawn by code behaves — the caller picks the color and opacity —, but with the shape coming
     * from an image, for what is too curved to be made of rectangles and circles.
     */
    public static void tinted(ResourceLocation tex, float x, float y, int w, int h, int argb) {
        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, 0.0F);
        mc().getTextureManager().bindTexture(tex);
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(
                GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        Draw2D.color(argb);
        Gui.drawModalRectWithCustomSizedTexture(0, 0, 0.0F, 0.0F, w, h, w, h);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.disableBlend();
        GlStateManager.popMatrix();
    }

    /**
     * Rounded-corner rectangle, in fractional coordinates: the arc is smooth at any GUI scale. Same
     * vertex orientation as Gui.drawRect.
     */
    public static void roundRect(float x, float y, float w, float h, float r, int argb) {
        Shapes.fill(x, y, w, h, r, argb);
    }

    /** Filled circle: the rounded rectangle with a radius equal to half the side. */
    public static void circle(float cx, float cy, float r, int argb) {
        Shapes.fill(cx - r, cy - r, r * 2, r * 2, r, argb);
    }

    /** Ring (hollow circle) of thickness t. */
    public static void ring(float cx, float cy, float r, float t, int argb) {
        int px = Math.max(1, Math.round(t * scale()));
        Shapes.stroke(cx - r, cy - r, r * 2, r * 2, r, px, argb);
    }

    /** Limits a value to the range 0 to 1. */
    public static float clamp01(float v) {
        return Math.max(0.0F, Math.min(1.0F, v));
    }

    /** Color with another opacity (0 to 1), to fade in and out. */
    public static int alpha(int argb, float a) {
        int v = Math.round(clamp01(a) * (argb >>> 24));
        return (v << 24) | (argb & 0xFFFFFF);
    }

    /** Between two colors, channel by channel. */
    public static int mix(int from, int to, float t) {
        float u = clamp01(t);
        int out = 0;
        for (int shift = 0; shift <= 24; shift += 8) {
            int a = from >>> shift & 255;
            int b = to >>> shift & 255;
            out |= Math.round(a + (b - a) * u) << shift;
        }
        return out;
    }

    /**
     * Moves "from" toward "to" at a rate of speed per second, with the frame time; to animate
     * without accumulating error.
     */
    public static float towards(float from, float to, float speed, long deltaMs) {
        float step = speed * Math.min(100L, deltaMs) / 1000.0F;
        return from < to ? Math.min(to, from + step) : Math.max(to, from - step);
    }

    /**
     * The design system's typography on the Loader screens: three roles, as in the Stein Client.
     *
     * <ul>
     *   <li><b>text</b> ({@link #text}): EB Garamond — names, descriptions, running text;
     *   <li><b>mono</b> ({@link #mono}, {@link #label}): JetBrains Mono — versions, numbers, ids,
     *       and the uppercase labels with spaced letters;
     *   <li><b>title</b> ({@link #title}): Cinzel.
     * </ul>
     *
     * <p>{@code -Dsteinloader.vanillaFont=true} goes back to the game's font; likewise without the
     * font files. The caller passes the y of an 8-high text (the game font's): each role centers
     * itself in that strip.
     */
    private static final boolean STEIN_FONT = !UiOptions.VANILLA_FONT;

    private static boolean steinFont() {
        return STEIN_FONT
                && SteinFont.ready(SteinFont.Face.BODY)
                && SteinFont.ready(SteinFont.Face.MONO)
                && SteinFont.ready(SteinFont.Face.DISPLAY);
    }

    /**
     * The y at which a face of size {@code size} is centered in the 8-high strip that starts at y.
     */
    private static float centerY(SteinFont.Face face, float size, int y) {
        return y + (8.0F - SteinFont.height(face, size)) / 2.0F;
    }

    /**
     * The game's style codes (bold, italic...) do not exist in these fonts: they are removed from
     * the text.
     */
    private static String plain(String s) {
        return s.indexOf('\u00a7') < 0 ? s : s.replaceAll("\u00a7[k-oK-O]", "");
    }

    /** Running text. */
    public static void text(String s, int x, int y, int rgb) {
        if (steinFont()) {
            SteinFont.draw(
                    SteinFont.Face.BODY,
                    plain(s),
                    x,
                    centerY(SteinFont.Face.BODY, Theme.TEXT, y),
                    Theme.TEXT,
                    rgb,
                    0.0F,
                    Theme.CODES);
        } else {
            font().drawStringWithShadow(s, x, y, rgb);
        }
    }

    public static void centered(String s, int cx, int y, int rgb) {
        text(s, cx - width(s) / 2, y, rgb);
    }

    public static int width(String s) {
        if (steinFont()) {
            return (int) Math.ceil(SteinFont.width(SteinFont.Face.BODY, plain(s), Theme.TEXT));
        }
        return font().getStringWidth(s);
    }

    /** Numbers, versions, ids: mono, smaller. */
    public static void mono(String s, int x, int y, int rgb) {
        if (steinFont()) {
            SteinFont.draw(
                    SteinFont.Face.MONO,
                    plain(s),
                    x,
                    centerY(SteinFont.Face.MONO, Theme.MONO, y),
                    Theme.MONO,
                    rgb,
                    0.0F,
                    Theme.CODES);
        } else {
            font().drawStringWithShadow(s, x, y, rgb);
        }
    }

    /** Mono with the center of the drawing (the letters' ink, not the line box) at (cx, cy). */
    public static void monoCentered(String s, float cx, float cy, int rgb) {
        if (!steinFont()) {
            font().drawStringWithShadow(
                            s,
                            Math.round(cx - font().getStringWidth(s) / 2.0F),
                            Math.round(cy - 4),
                            rgb);
            return;
        }
        float[] ink = SteinFont.ink(SteinFont.Face.MONO, plain(s), Theme.MONO);
        SteinFont.draw(
                SteinFont.Face.MONO,
                plain(s),
                cx - (ink[0] + ink[2]) / 2.0F,
                cy - (ink[1] + ink[3]) / 2.0F,
                Theme.MONO,
                rgb,
                0.0F,
                Theme.CODES);
    }

    public static int monoWidth(String s) {
        if (steinFont()) {
            return (int) Math.ceil(SteinFont.width(SteinFont.Face.MONO, plain(s), Theme.MONO));
        }
        return font().getStringWidth(s);
    }

    /** Label: mono, uppercase, spaced letters (the design system's "eyebrows" and buttons). */
    public static void label(String s, int x, int y, int rgb) {
        if (steinFont()) {
            SteinFont.draw(
                    SteinFont.Face.MONO,
                    plain(s).toUpperCase(Locale.ROOT),
                    x,
                    centerY(SteinFont.Face.MONO, Theme.LABEL, y),
                    Theme.LABEL,
                    rgb,
                    Theme.TRACKING,
                    Theme.CODES);
        } else {
            font().drawStringWithShadow(s, x, y, rgb);
        }
    }

    public static int labelWidth(String s) {
        if (steinFont()) {
            return (int)
                    Math.ceil(
                            SteinFont.width(
                                    SteinFont.Face.MONO,
                                    plain(s).toUpperCase(Locale.ROOT),
                                    Theme.LABEL,
                                    Theme.TRACKING));
        }
        return font().getStringWidth(s);
    }

    /** Title, at the requested size (Theme.TITLE, Theme.HEADING). */
    public static void title(String s, int x, int y, float size, int rgb) {
        if (steinFont()) {
            SteinFont.draw(
                    SteinFont.Face.DISPLAY,
                    plain(s),
                    x,
                    centerY(SteinFont.Face.DISPLAY, size, y),
                    size,
                    rgb,
                    0.0F,
                    Theme.CODES);
        } else {
            font().drawStringWithShadow("\u00a7l" + s, x, y, rgb);
        }
    }

    public static int titleWidth(String s, float size) {
        if (steinFont()) {
            return (int) Math.ceil(SteinFont.width(SteinFont.Face.DISPLAY, plain(s), size));
        }
        return font().getStringWidth("\u00a7l" + s);
    }

    /** Cuts with an ellipsis to fit in w. */
    public static String trim(String s, int w) {
        if (width(s) <= w) {
            return s;
        }
        if (steinFont()) {
            String cut = s;
            while (!cut.isEmpty() && width(cut + "...") > w) {
                cut = cut.substring(0, cut.length() - 1);
            }
            return cut + "...";
        }
        return font().trimStringToWidth(s, Math.max(0, w - width("..."))) + "...";
    }

    /** Breaks running text into lines of up to w width, by word. */
    public static List<String> wrap(String s, int w) {
        List<String> out = new ArrayList<>();
        if (!steinFont()) {
            for (Object o : font().listFormattedStringToWidth(s, w)) {
                out.add((String) o);
            }
            return out;
        }
        for (String par : s.split("\n")) {
            StringBuilder line = new StringBuilder();
            for (String word : par.split(" ")) {
                String next = line.length() == 0 ? word : line + " " + word;
                if (line.length() > 0 && width(next) > w) {
                    out.add(line.toString());
                    line = new StringBuilder(word);
                } else {
                    line = new StringBuilder(next);
                }
            }
            out.add(line.toString());
        }
        return out;
    }

    /** A real one-pixel line (not one screen unit): horizontal at (x, y) with w width. */
    public static void hair(float x, float y, float w, int argb) {
        float t = 1.0F / scale();
        Draw2D.rect(x, y, w, t, argb);
    }

    /** The same, vertical, with h height. */
    public static void hairV(float x, float y, float h, int argb) {
        float t = 1.0F / scale();
        Draw2D.rect(x, y, t, h, argb);
    }

    /** A rectangle's outline, in one-pixel lines. */
    public static void frame(float x, float y, float w, float h, int argb) {
        float t = 1.0F / scale();
        Draw2D.rect(x, y, w, t, argb);
        Draw2D.rect(x, y + h - t, w, t, argb);
        Draw2D.rect(x, y, t, h, argb);
        Draw2D.rect(x + w - t, y, t, h, argb);
    }

    /** How many real pixels there are in one interface unit (the GUI scale). */
    public static int scale() {
        return new ScaledResolution(mc()).getScaleFactor();
    }

    /** The mouse x of the current event, in the screen's GUI coordinates. */
    public static int mouseX(GuiScreen gui) {
        return Mouse.getEventX() * gui.width / mc().displayWidth;
    }

    /** The mouse y of the current event, in the screen's GUI coordinates. */
    public static int mouseY(GuiScreen gui) {
        return gui.height - Mouse.getEventY() * gui.height / mc().displayHeight - 1;
    }

    /**
     * The label bubble to the left of a control: its right edge 10 pixels before {@code edgeX}, the
     * box at {@code y} (16 high).
     */
    public static void tooltipLeftOf(String text, float edgeX, int y) {
        int w = width(text);
        float tx = edgeX - w - 10.0F;
        Draw2D.rect(tx - 5.0F, y, w + 10.0F, 16.0F, Theme.BG_TOOLTIP);
        frame(tx - 5.0F, y, w + 10.0F, 16.0F, Theme.RULE_STRONG);
        text(text, Math.round(tx), y + 4, Theme.INK);
    }

    /** A task that opens something in the operating system (browser, file manager). */
    public interface DesktopTask {
        void run() throws Exception;
    }

    /**
     * Runs the task on a daemon thread: Desktop may take a while to respond, and a second standing
     * still with the game window frozen looks like a freeze. {@code target} is only for the log.
     */
    public static void openDesktop(String threadName, Object target, DesktopTask task) {
        Thread t =
                new Thread(
                        () -> {
                            try {
                                task.run();
                            } catch (Throwable e) {
                                Log.info("loader", "could not open " + target + ": " + e);
                            }
                        },
                        threadName);
        t.setDaemon(true);
        t.start();
    }

    public static void click() {
        mc().getSoundHandler().playSound(PositionedSoundRecord.create(CLICK, 1.0F));
    }

    /** Clips the drawing to the rectangle (screen coordinates). */
    public static void clip(int x, int y, int w, int h) {
        Minecraft mc = mc();
        int f = scale();
        // Through the Bridge: with Stein GFX recording, the clip goes along with what was recorded
        // (otherwise it leaked).
        SteinGlTrack.scissor(true, x * f, mc.displayHeight - (y + h) * f, w * f, h * f);
    }

    public static void unclip() {
        SteinGlTrack.scissor(false, 0, 0, 0, 0);
    }

    /** Decelerates at the end (cubic). */
    public static float ease(float t) {
        float u = 1.0F - clamp01(t);
        return 1.0F - u * u * u;
    }
}
