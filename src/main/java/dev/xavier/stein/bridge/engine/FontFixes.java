/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import net.minecraft.client.gui.FontRenderer;

/**
 * Fixes in the game's FontRenderer: styles leaking from a text's shadow into the text, and unicode
 * glyphs whose start was read as a negative number.
 *
 * <ul>
 *   <li>drawString clears the styles (bold, italic, underline, strikethrough, obfuscated) once,
 *       then draws the shadow and the text in two passes. The text pass started with whatever the
 *       shadow pass ended with: in a text that turns bold halfway, the shadow ends bold, so the
 *       text's first half came out bold (and wider) over a plain shadow. Each pass now starts
 *       plain, as the shadow always did. {@code -Dsteinbridge.opt.vanillaTextStyles=true} brings
 *       back 1.8.9's carry.
 *   <li>The unicode font's glyph sizes are bytes: the high four bits are the column where the glyph
 *       starts, the low four the last column. renderUnicodeChar shifts the signed byte, so a glyph
 *       starting in column 8 or later (50 of them in the game's table, among them Korean vowels
 *       such as U+314F and U+3163 and the half blocks U+2590 and U+2595 that servers use in bars)
 *       read a start near 2^28: its texture coordinates pointed nowhere and its advance was hugely
 *       negative, so the rest of the text went off the screen. Here those glyphs are drawn with the
 *       unsigned start; every other glyph goes to the game's method. The width the layout measures
 *       (getCharWidth) already clamps these glyphs, so text widths do not change. {@code
 *       -Dsteinbridge.opt.vanillaGlyphStart=true} brings back 1.8.9's reading.
 * </ul>
 */
public final class FontFixes {

    private FontFixes() {}

    private static final boolean VANILLA_STYLES = GuiFixOptions.VANILLA_TEXT_STYLES;

    private static final boolean VANILLA_GLYPHS = GuiFixOptions.VANILLA_GLYPH_START;

    /** FontRenderer.renderString(text, x, y, color, shadow) in drawString: each pass plain. */
    public static int renderString(
            Object font, Object text, float x, float y, int color, boolean shadow) {
        FontRenderer f = (FontRenderer) font;
        if (!VANILLA_STYLES) {
            f.resetStyles();
        }
        return f.renderString((String) text, x, y, color, shadow);
    }

    /** FontRenderer.renderUnicodeChar(c, italic) in renderChar. */
    public static float unicodeChar(Object font, char c, boolean italic) {
        FontRenderer f = (FontRenderer) font;
        int size = f.glyphWidth[c];
        if (size >= 0 || VANILLA_GLYPHS) {
            return f.renderUnicodeChar(c, italic);
        }
        // The game's drawing of one glyph, with the start column read unsigned. The GL calls go
        // to FontBatch exactly as the game's own (the patcher sends them there in FontRenderer).
        size &= 0xFF;
        f.loadGlyphTexture(c / 256);
        float start = size >>> 4;
        float end = (size & 15) + 1;
        float u = (c % 16 * 16) + start;
        float v = (c & 255) / 16 * 16;
        float width = end - start - 0.02F;
        float skew = italic ? 1.0F : 0.0F;
        float x = f.posX;
        float y = f.posY;
        FontBatch.glBegin(5);
        FontBatch.glTexCoord2f(u / 256.0F, v / 256.0F);
        FontBatch.glVertex3f(x + skew, y, 0.0F);
        FontBatch.glTexCoord2f(u / 256.0F, (v + 15.98F) / 256.0F);
        FontBatch.glVertex3f(x - skew, y + 7.99F, 0.0F);
        FontBatch.glTexCoord2f((u + width) / 256.0F, v / 256.0F);
        FontBatch.glVertex3f(x + width / 2.0F + skew, y, 0.0F);
        FontBatch.glTexCoord2f((u + width) / 256.0F, (v + 15.98F) / 256.0F);
        FontBatch.glVertex3f(x + width / 2.0F - skew, y + 7.99F, 0.0F);
        FontBatch.glEnd();
        return (end - start) / 2.0F + 1.0F;
    }
}
