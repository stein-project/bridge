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
 * The font swapped for another, character by character, <b>in the text that Stein draws</b> (the
 * mods' HUD). The game's own text — chat, scoreboard, boss bar, names, menus — does not go through
 * here: the registered font only answers while a Loader API drawing is in progress ({@link
 * #hudDepth}).
 *
 * <p>The 1.8.9 FontRenderer draws a text like this: it walks the characters, handles the format
 * codes (color, bold, italic, underline, strikethrough, scrambled), and for each letter calls
 * {@code renderChar} — which draws the glyph at (posX, posY) and returns how far to advance. The
 * shadow is the same text drawn earlier, darker and offset; bold is the letter drawn twice; the
 * measurements (a text's width, where to wrap the line, where to cut) come from {@code
 * getCharWidth}.
 *
 * <p>This hook swaps <b>only those two points</b> — drawing a character and its width — and leaves
 * everything else to the game. This way color, bold, shadow, underline and line wrapping keep
 * working as always, everywhere text is drawn (chat, scoreboard, nametags, signs, menus), without
 * rewriting any of it.
 *
 * <p>The one that supplies the glyphs is Stein Loader ({@link #source}): the bridge knows no font
 * at all. With no registered font, or for a character it does not have (a symbol, an ideogram), the
 * game's path applies — the two can be mixed in the same text. The glyphs go into the same batch of
 * the {@link FontBatch} (one draw per text).
 *
 * <p>{@code -Dsteinbridge.opt.vanillaText=true}: always the game's font.
 */
public final class TextHook {

    /** Whoever supplies the glyphs of the other font. */
    public interface Source {
        /**
         * {@link #advance} returns this for a character the font does not cover (or when it is
         * turned off).
         */
        int NONE = Integer.MIN_VALUE;

        /**
         * How far the character advances, in screen units (integer, as the game measures); {@link
         * #NONE} if it is not the font's.
         */
        int advance(char c);

        /**
         * The character's glyph, to draw with the origin of the game's cell at (0, 0): {@code out}
         * receives the texture, u0, v0, u1, v1, and the rectangle x0, y0, x1, y1 in screen units.
         * Returns false for a character with no drawing (the space). Only called for what {@link
         * #advance} did not answer with {@link #NONE}.
         */
        boolean glyph(char c, float[] out);

        /**
         * Changes whenever {@link #advance} or {@link #glyph} may answer differently for the same
         * character (another interface scale, a rebuilt atlas, the font option switched). The new
         * renderer's cached text ({@code SteinTextCache}) keeps this font's glyphs under this
         * generation: equal, the assembled text is valid; different, it is assembled again. Reading
         * this must be cheap (it is requested for every HUD text).
         */
        default int generation() {
            return 0;
        }
    }

    private static final boolean VANILLA = EngineOptions.VANILLA_TEXT;

    /** The font registered by the Loader, or null. */
    public static volatile Source source;

    /**
     * How many mod HUD drawings are in progress (the Loader's drawing API adds and subtracts):
     * outside them the text is the game's own, and the registered font does not answer. Render
     * thread only.
     */
    public static int hudDepth;

    /**
     * How many drawings in progress asked for the game's own font (a grid element with the "Font:
     * game" option): while it is above zero, the registered font does not answer, even inside the
     * HUD. Render thread only.
     */
    public static int gameFontDepth;

    private static final float[] OUT = new float[9];

    private TextHook() {}

    /**
     * FontRenderer.renderChar(char, italic), called by renderStringAtPos: draws the glyph and
     * returns the advance.
     */
    public static float renderChar(Object font, char c, boolean italic) {
        FontRenderer fr = (FontRenderer) font;
        Source s = VANILLA ? null : source;
        if (s == null) {
            return fr.renderChar(c, italic);
        }
        int advance = s.advance(c);
        if (advance == Source.NONE) {
            return fr.renderChar(c, italic);
        }
        FontBatch.beforeOwn();
        if (s.glyph(c, OUT)) {
            float x = fr.posX;
            float y = fr.posY;
            // The game's italic: the top of the glyph goes one unit to the right and the foot one
            // to the left.
            float skew = italic ? 1.0F : 0.0F;
            FontBatch.quad(
                    (int) OUT[0],
                    x + OUT[5],
                    y + OUT[6],
                    x + OUT[7],
                    y + OUT[8],
                    OUT[1],
                    OUT[2],
                    OUT[3],
                    OUT[4],
                    skew);
        }
        return advance;
    }

    /** FontRenderer.getCharWidth(char): the width the game uses to measure, wrap and cut text. */
    public static int charWidth(Object font, char c) {
        Source s = VANILLA ? null : source;
        if (s != null) {
            int advance = s.advance(c);
            if (advance != Source.NONE) {
                return advance;
            }
        }
        return ((FontRenderer) font).getCharWidth(c);
    }
}
