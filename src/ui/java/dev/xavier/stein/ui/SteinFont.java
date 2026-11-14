/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.ui;

import dev.xavier.stein.bridge.core.Log;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.font.TextAttribute;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * The Stein design system fonts (the same as Stein Client), drawn on the game screen.
 *
 * <p>The game draws the interface at a scale (1x, 2x, 3x...): a text "10 tall" takes up 20 or 30
 * real pixels. Minecraft's font is an 8-pixel bitmap scaled up, which is why it looks the way it
 * does. Here each size is rasterized <b>at the screen's real resolution</b> (size x scale), with
 * smoothing, and drawn pixel by pixel — the same text stays sharp at any scale, instead of blurry
 * like a stretched texture.
 *
 * <p><b>Java's text engine is what positions the letters</b> ({@link Font#layoutGlyphVector}), not
 * a sum of widths: it applies the font's adjustment between pairs of letters ("kerning": an "A"
 * tucks against the "v", a "T" covers the "o"). Without that, a serif font ends up with crooked
 * spacing. The result of each text (the glyphs and where each one goes) is cached: the screen
 * repeats the same texts every frame.
 *
 * <p>Each glyph is rasterized once, <b>aligned to the pixel grid</b> — all on the same baseline, on
 * a whole pixel; aligning each one by its own top left one letter a pixel above another — and goes
 * into an atlas (one texture per font and real size). A text is a single batch (one draw), as the
 * FontBatch does with the game's font.
 *
 * <p>Without the font (a file that did not load), {@link #ready} is false and the caller falls back
 * to the game's font.
 */
public final class SteinFont {

    /** The design system faces: titles, text, italic text, labels and numbers. */
    public enum Face {
        DISPLAY("Cinzel.ttf"),
        BODY("EBGaramond.ttf"),
        ITALIC("EBGaramond-Italic.ttf"),
        MONO("JetBrainsMono.ttf"),
        /**
         * The one for the in-game HUD: the same mono, in a strong weight. Over the world, with no
         * background behind it, the normal weight is too thin (Minecraft's font is thick for
         * exactly that reason). {@code -Dsteinloader.hudWeight} changes the weight (SemiBold, Bold,
         * ExtraBold) for comparison.
         */
        HUD("JetBrainsMono-" + UiOptions.HUD_WEIGHT + ".ttf");

        final String file;
        private Font base;
        private boolean failed;

        Face(String file) {
            this.file = file;
        }

        /** The loaded font, or null if the file did not open. */
        synchronized Font base() {
            if (base == null && !failed) {
                try (InputStream in =
                        SteinFont.class.getResourceAsStream("/assets/steinui/fonts/" + file)) {
                    // With adjustment between pairs of letters; without ligatures (a fused "fi"
                    // cannot be painted in two colors).
                    Map<TextAttribute, Object> attrs = new HashMap<>();
                    attrs.put(TextAttribute.KERNING, TextAttribute.KERNING_ON);
                    base = Font.createFont(Font.TRUETYPE_FONT, in).deriveFont(attrs);
                } catch (Exception e) {
                    failed = true;
                    Log.info("loader", "font " + file + " failed to load: " + e);
                }
            }
            return base;
        }
    }

    private static final int ATLAS = 1024;

    /**
     * Padding around each glyph in the atlas, so the neighbor does not bleed with linear filtering.
     */
    private static final int PAD = 2;

    /** Texts kept per atlas: above this, whatever the screen no longer uses is dropped at once. */
    private static final int MAX_RUNS = 4096;

    private static final Map<Long, Atlas> ATLASES = new HashMap<>();

    /** The sixteen colors of the game's format codes (§0 to §f). */
    private static final int[] GAME_COLORS = {
        0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
        0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF,
    };

    private SteinFont() {}

    /**
     * A glyph in the atlas: the rectangle (in real pixels) and where it sits from the glyph origin.
     */
    private static final class Glyph {
        int x, y, w, h;
        int left, top;
    }

    /**
     * An already positioned run of text: the glyphs, the x of each one (in pixels) and how far the
     * run advances.
     */
    private static final class Run {
        Glyph[] glyphs;
        float[] xs;
        float advance;
    }

    /** A font at a real size (in screen pixels). */
    private static final class Atlas {
        final Font font;
        final FontRenderContext frc = new FontRenderContext(null, true, true);
        final Map<Integer, Glyph> glyphs = new HashMap<>();
        final Map<String, Run> runs = new HashMap<>();
        final int ascent;
        final float height;
        int texture = -1;
        int penX = PAD;
        int penY = PAD;
        int rowH;
        boolean full;

        Atlas(Font font) {
            this.font = font;
            BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = probe.createGraphics();
            FontMetrics fm = g.getFontMetrics(font);
            ascent = fm.getAscent();
            height = fm.getAscent() + fm.getDescent();
            g.dispose();
        }

        /**
         * The run positioned by the text engine (with adjustment between pairs of letters), cached.
         */
        Run run(String text) {
            Run run = runs.get(text);
            if (run != null) {
                return run;
            }
            if (runs.size() >= MAX_RUNS) {
                runs.clear();
            }
            char[] chars = text.toCharArray();
            for (int i = 0; i < chars.length; i++) {
                if (!Character.isSurrogate(chars[i]) && !font.canDisplay(chars[i])) {
                    chars[i] = '?';
                }
            }
            GlyphVector gv =
                    font.layoutGlyphVector(frc, chars, 0, chars.length, Font.LAYOUT_LEFT_TO_RIGHT);
            int n = gv.getNumGlyphs();
            run = new Run();
            run.glyphs = new Glyph[n];
            run.xs = new float[n];
            for (int i = 0; i < n; i++) {
                run.glyphs[i] = glyph(gv.getGlyphCode(i));
                run.xs[i] = (float) gv.getGlyphPosition(i).getX();
            }
            run.advance = (float) gv.getGlyphPosition(n).getX();
            runs.put(text, run);
            return run;
        }

        private Glyph glyph(int code) {
            Glyph gl = glyphs.get(code);
            if (gl == null) {
                gl = rasterize(code);
                glyphs.put(code, gl);
            }
            return gl;
        }

        private Glyph rasterize(int code) {
            Glyph gl = new Glyph();
            GlyphVector gv = font.createGlyphVector(frc, new int[] {code});
            Rectangle2D b = gv.getVisualBounds();
            if (b.isEmpty() || full) {
                return gl; // space (or atlas full): nothing to draw
            }
            // On the pixel grid: the glyph origin falls on a whole pixel of the image, so all
            // glyphs end up on the same baseline when drawn.
            int bx = (int) Math.floor(b.getX());
            int by = (int) Math.floor(b.getY());
            int w = (int) Math.ceil(b.getMaxX()) - bx + 2;
            int h = (int) Math.ceil(b.getMaxY()) - by + 2;
            if (penX + w + PAD > ATLAS) {
                penX = PAD;
                penY += rowH + PAD;
                rowH = 0;
            }
            if (penY + h + PAD > ATLAS) {
                full = true;
                return gl;
            }
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(
                    RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(
                    RenderingHints.KEY_FRACTIONALMETRICS,
                    RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            g.setColor(Color.WHITE);
            g.drawGlyphVector(gv, 1 - bx, 1 - by);
            g.dispose();

            // White with the coverage in alpha: the color comes from whoever draws.
            ByteBuffer px = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder());
            int[] argb = img.getRGB(0, 0, w, h, null, 0, w);
            for (int p : argb) {
                px.put((byte) 0xFF).put((byte) 0xFF).put((byte) 0xFF).put((byte) (p >>> 24));
            }
            px.flip();
            bind();
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
            GL11.glTexSubImage2D(
                    GL11.GL_TEXTURE_2D,
                    0,
                    penX,
                    penY,
                    w,
                    h,
                    GL11.GL_RGBA,
                    GL11.GL_UNSIGNED_BYTE,
                    px);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);

            gl.x = penX;
            gl.y = penY;
            gl.w = w;
            gl.h = h;
            gl.left = bx - 1;
            gl.top = by - 1;
            penX += w + PAD;
            rowH = Math.max(rowH, h);
            return gl;
        }

        void bind() {
            if (texture < 0) {
                texture = GL11.glGenTextures();
                GlStateManager.bindTexture(texture);
                GL11.glTexParameteri(
                        GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
                GL11.glTexParameteri(
                        GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
                GL11.glTexParameteri(
                        GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
                GL11.glTexParameteri(
                        GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
                ByteBuffer empty = ByteBuffer.allocateDirect(ATLAS * ATLAS * 4);
                GL11.glTexImage2D(
                        GL11.GL_TEXTURE_2D,
                        0,
                        GL11.GL_RGBA,
                        ATLAS,
                        ATLAS,
                        0,
                        GL11.GL_RGBA,
                        GL11.GL_UNSIGNED_BYTE,
                        empty);
            } else {
                GlStateManager.bindTexture(texture);
            }
        }
    }

    private static int scaleW, scaleH, scaleSetting = -1, scaleValue = 1;
    private static boolean scaleUnicode;

    /**
     * The interface scale right now (real pixels per screen unit): the game's ScaledResolution
     * computation, kept while the window and the option do not change — this is called for every
     * text and every character.
     */
    public static int scale() {
        Minecraft mc = Minecraft.getMinecraft();
        int w = mc.displayWidth, h = mc.displayHeight, setting = mc.gameSettings.guiScale;
        boolean unicode = mc.isUnicode();
        if (w != scaleW || h != scaleH || setting != scaleSetting || unicode != scaleUnicode) {
            scaleW = w;
            scaleH = h;
            scaleSetting = setting;
            scaleUnicode = unicode;
            scaleValue = new ScaledResolution(mc).getScaleFactor();
        }
        return scaleValue;
    }

    private static Atlas atlas(Face face, float size, int scale) {
        Font base = face.base();
        if (base == null) {
            return null;
        }
        int px = Math.max(6, Math.round(size * scale));
        long key = ((long) face.ordinal() << 32) | px;
        Atlas a = ATLASES.get(key);
        if (a == null) {
            a = new Atlas(base.deriveFont((float) px));
            ATLASES.put(key, a);
        }
        return a;
    }

    // ---- the game's text ----

    /**
     * The font for the HUD grid (the bridge's hook, {@code TextHook}; outside the grid the game's
     * text does not change): JetBrains Mono at <b>8.6 body size</b>, advancing <b>exactly 5
     * units</b> per letter (with 6, the width of most of the game's letters, the monospaced one
     * came out too spaced). With 9.6 the capitals had the height of the game's (7), but JetBrains
     * Mono has tall lowercase letters and the bold is heavy: in the Tab list and in the chat it
     * ended up larger than the game's. With 8.6 its on-screen weight stays its own ({@code
     * -Dsteinloader.hudFontSize} to compare). This way what the game and servers align by counting
     * widths (scoreboards, tables in chat, centered text) barely moves out of place, and the game's
     * measurements, which are in whole units, stay exact.
     */
    public static final float GAME_SIZE = UiOptions.HUD_FONT_SIZE;

    public static final int GAME_ADVANCE = 5;

    /**
     * Where the baseline sits within the game's 8-tall cell: half a unit above the game's (7), so
     * that what descends (g, j, p, y) fits in the line — the Tab strips are 8 tall. Rounded to the
     * screen pixel.
     */
    private static final float GAME_BASELINE = 6.5F;

    private static Atlas gameAtlas;
    private static int gameScale;
    private static Glyph[] gameGlyphs = new Glyph[0];

    /** 0 not queried, 1 the font has it, 2 it does not. */
    private static final byte[] GAME_COVERS = new byte[65536];

    /**
     * The characters the Stein font draws: letters (accented), numbers and text punctuation.
     * Everything else — symbols, arrows, drawings, ideograms, and especially what the server swaps
     * for an icon in its resource pack (ranks, emojis) — stays with the game's font. JetBrains Mono
     * has many of these symbols, but with its own drawing: the server's icon would vanish or turn
     * into something else.
     */
    private static boolean letter(char c) {
        return c > 32 && c < 127
                || c >= 0xA1 && c <= 0x17F && c != '\u00a7' && c != 0xAD
                || c >= 0x2010 && c <= 0x2026 // dashes, curly quotes, ellipsis
                || c == 0x20AC; // euro
    }

    /**
     * Does the game's font have this character? (Symbols and ideograms stay with Minecraft's font.)
     */
    public static boolean gameCovers(char c) {
        byte b = GAME_COVERS[c];
        if (b == 0) {
            Font base = Face.HUD.base();
            b = (byte) (base != null && letter(c) && base.canDisplay(c) ? 1 : 2);
            GAME_COVERS[c] = b;
        }
        return b == 1;
    }

    /**
     * The glyph of a character of the game's text: {@code out} receives the texture, u0, v0, u1, v1
     * and the rectangle x0, y0, x1, y1 in screen units, from the corner of the game's cell. Returns
     * false if there is nothing to draw.
     */
    public static boolean gameGlyph(char c, float[] out) {
        int scale = scale();
        if (gameAtlas == null || scale != gameScale) {
            gameAtlas = atlas(Face.HUD, GAME_SIZE, scale);
            gameScale = scale;
            gameGlyphs = new Glyph[65536];
        }
        Atlas a = gameAtlas;
        if (a == null) {
            return false;
        }
        Glyph gl = gameGlyphs[c];
        if (gl == null) {
            gl = a.glyph(a.font.createGlyphVector(a.frc, new char[] {c}).getGlyphCode(0));
            gameGlyphs[c] = gl;
        }
        if (gl.w == 0) {
            return false;
        }
        float inv = 1.0F / scale;
        float t = 1.0F / ATLAS;
        a.bind(); // the texture exists (and the glyph is in it) before the bridge binds it
        out[0] = a.texture;
        out[1] = gl.x * t;
        out[2] = gl.y * t;
        out[3] = (gl.x + gl.w) * t;
        out[4] = (gl.y + gl.h) * t;
        out[5] = gl.left * inv;
        float base = Math.round(GAME_BASELINE * scale) * inv;
        out[6] = base + gl.top * inv;
        out[7] = (gl.left + gl.w) * inv;
        out[8] = base + (gl.top + gl.h) * inv;
        return true;
    }

    /** Did the font load? Without it, the caller draws with the game's font. */
    public static boolean ready(Face face) {
        return face.base() != null;
    }

    /** The next run without a format code from {@code from}: returns where it ends. */
    private static int segmentEnd(String text, int from) {
        int i = text.indexOf('§', from);
        return i < 0 || i + 1 >= text.length() ? text.length() : i;
    }

    /** The text width, in screen units. */
    public static float width(Face face, String text, float size) {
        return width(face, text, size, 0.0F);
    }

    /** The width with letter spacing ({@code tracking}, as a fraction of the size). */
    public static float width(Face face, String text, float size, float tracking) {
        int scale = scale();
        Atlas a = atlas(face, size, scale);
        if (a == null || text == null) {
            return 0;
        }
        float track = tracking * size * scale;
        float w = 0;
        for (int i = 0; i < text.length(); ) {
            if (text.charAt(i) == '§' && i + 1 < text.length()) {
                i += 2; // the game's format code: takes no space
                continue;
            }
            int end = segmentEnd(text, i);
            Run run = a.run(text.substring(i, end));
            w += run.advance + track * run.glyphs.length;
            i = end;
        }
        return w / scale;
    }

    /**
     * The rectangle the text's ink occupies (x0, y0, x1, y1), in screen units, from the point where
     * {@link #draw} would put it (the top corner of the line). To center by the actual drawing: the
     * line box includes the space below descending letters, and centering by it leaves a lone
     * letter ("G", "+") off the middle.
     */
    public static float[] ink(Face face, String text, float size) {
        int scale = scale();
        Atlas a = atlas(face, size, scale);
        if (a == null || text == null || text.isEmpty()) {
            return new float[4];
        }
        java.awt.geom.Rectangle2D b = a.font.createGlyphVector(a.frc, text).getVisualBounds();
        float inv = 1.0F / scale;
        return new float[] {
            (float) b.getX() * inv,
            (float) (a.ascent + b.getY()) * inv,
            (float) b.getMaxX() * inv,
            (float) (a.ascent + b.getMaxY()) * inv
        };
    }

    /**
     * The height of a line (from the top of the tallest glyph to the end of the descenders), in
     * screen units.
     */
    public static float height(Face face, float size) {
        int scale = scale();
        Atlas a = atlas(face, size, scale);
        return a == null ? size : a.height / scale;
    }

    /**
     * Draws the text with its top left corner at (x, y), {@code size} being the letter height in
     * screen units. Returns the x where the text ended.
     */
    public static float draw(Face face, String text, float x, float y, float size, int argb) {
        return draw(face, text, x, y, size, argb, 0.0F);
    }

    /** The same, with letter spacing ({@code tracking}, as a fraction of the size). */
    public static float draw(
            Face face, String text, float x, float y, float size, int argb, float tracking) {
        return draw(face, text, x, y, size, argb, tracking, GAME_COLORS);
    }

    /**
     * The same, with another palette for the color codes (sixteen colors, 0 to f): the Stein
     * screens swap the game's vivid green and red for the theme's. The color codes (§0 to §f, §r)
     * change the color from there on; the style ones (§l, §o...) are ignored.
     */
    public static float draw(
            Face face,
            String text,
            float x,
            float y,
            float size,
            int argb,
            float tracking,
            int[] palette) {
        int scale = scale();
        Atlas a = atlas(face, size, scale);
        if (a == null || text == null || text.isEmpty()) {
            return x;
        }
        if ((argb & 0xFF000000) == 0) {
            argb |= 0xFF000000; // rgb without alpha, as the FontRenderer accepts it
        }
        int al = argb >>> 24;
        float track = tracking * size * scale;

        // First the runs (positioning and rasterizing touch the texture), the drawing afterwards,
        // in a single batch.
        int parts = 0;
        Run[] runs = new Run[4];
        int[] colors = new int[4];
        int color = argb & 0xFFFFFF;
        for (int i = 0; i < text.length(); ) {
            if (text.charAt(i) == '§' && i + 1 < text.length()) {
                int code = "0123456789abcdefr".indexOf(Character.toLowerCase(text.charAt(i + 1)));
                if (code == 16) {
                    color = argb & 0xFFFFFF;
                } else if (code >= 0) {
                    color = palette[code];
                }
                i += 2;
                continue;
            }
            int end = segmentEnd(text, i);
            if (parts == runs.length) {
                runs = Arrays.copyOf(runs, parts * 2);
                colors = Arrays.copyOf(colors, parts * 2);
            }
            runs[parts] = a.run(text.substring(i, end));
            colors[parts++] = color;
            i = end;
        }

        return emitQuads(a, runs, colors, parts, x, y, al, track, scale);
    }

    /**
     * Draws the positioned runs in a single batch, on the real pixel grid, and returns the x where
     * the text ended.
     */
    private static float emitQuads(
            Atlas a,
            Run[] runs,
            int[] colors,
            int parts,
            float x,
            float y,
            int al,
            float track,
            int scale) {
        // On the real pixel grid: whole origin, so each texel lands on a pixel and the text does
        // not blur; and the same (whole) baseline for all glyphs.
        float penX = Math.round(x * scale);
        int baseY = Math.round(y * scale) + a.ascent;
        float inv = 1.0F / scale;
        float t = 1.0F / ATLAS;

        a.bind();
        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.enableAlpha();
        GlStateManager.tryBlendFuncSeparate(
                GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        // The game's alpha test cuts below 0.1: the smoothed edge of an already translucent text
        // would vanish.
        GlStateManager.alphaFunc(GL11.GL_GREATER, 0.0F);
        Tessellator tess = Tessellator.getInstance();
        WorldRenderer wr = tess.getWorldRenderer();
        wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR);
        for (int p = 0; p < parts; p++) {
            Run run = runs[p];
            int r = colors[p] >> 16 & 0xFF, g = colors[p] >> 8 & 0xFF, b = colors[p] & 0xFF;
            for (int k = 0; k < run.glyphs.length; k++) {
                Glyph gl = run.glyphs[k];
                if (gl.w == 0) {
                    continue;
                }
                float gx = (Math.round(penX + run.xs[k] + track * k) + gl.left) * inv;
                float gy = (baseY + gl.top) * inv;
                float gw = gl.w * inv;
                float gh = gl.h * inv;
                float u0 = gl.x * t, v0 = gl.y * t, u1 = (gl.x + gl.w) * t, v1 = (gl.y + gl.h) * t;
                wr.pos(gx, gy + gh, 0).tex(u0, v1).color(r, g, b, al).endVertex();
                wr.pos(gx + gw, gy + gh, 0).tex(u1, v1).color(r, g, b, al).endVertex();
                wr.pos(gx + gw, gy, 0).tex(u1, v0).color(r, g, b, al).endVertex();
                wr.pos(gx, gy, 0).tex(u0, v0).color(r, g, b, al).endVertex();
            }
            penX += run.advance + track * run.glyphs.length;
        }
        tess.draw();
        GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1F);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        return penX * inv;
    }

    private static int gameGeneration;
    private static int generationScale = -1;

    /**
     * Changes whenever the glyph of a game-text character may come out different: another interface
     * scale (the {@link #gameGlyph} atlas is rebuilt on it) or the loose textures ({@link #reset}).
     * A glyph already placed in the atlas does not move while the atlas lives, so the text
     * assembled with it is valid until this changes.
     */
    public static int gameGeneration() {
        int s = scale();
        if (s != generationScale) {
            generationScale = s;
            gameGeneration++;
        }
        return gameGeneration;
    }
}
