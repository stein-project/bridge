/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

package dev.xavier.stein.bridge.engine;

import java.util.Arrays;

/**
 * FontRenderer's String.indexOf(char) replaced by a table lookup.
 *
 * <p>For every character drawn or measured, FontRenderer looks up the glyph index with indexOf on a
 * fixed 256-character string (and the format codes in "0123456789abcdefklmnor") — a linear search,
 * over UTF-16. With signs and nametags, this shows up at the top of the stack.
 *
 * <p>The table is built once from the string it receives, back to front, so the first occurrence
 * wins — which is what indexOf returns. It keeps up to two strings, recognised by identity (the
 * FontRenderer constants); any other string, and any character outside the BMP, goes to the normal
 * indexOf.
 *
 * <p>{@code -Dsteinbridge.opt.vanillaFontIndex=true}: always String.indexOf.
 */
public final class FontIndex {

    private static final boolean VANILLA = EngineOptions.VANILLA_FONT_INDEX;

    /** Short strings are not worth a 128 KB table. */
    private static final int MIN_LENGTH = 16;

    /** Size of the char range the tables cover: the Basic Multilingual Plane. */
    private static final int BMP_SIZE = 0x10000;

    private static String first;
    private static short[] firstTable;
    private static String second;
    private static short[] secondTable;

    private FontIndex() {}

    /** String.indexOf(int) in the FontRenderer methods (receiver as the first argument). */
    public static int indexOf(Object receiver, int ch) {
        String s = (String) receiver;
        if (VANILLA
                || ch < 0
                || ch >= BMP_SIZE
                || s.length() < MIN_LENGTH
                || s.length() > Short.MAX_VALUE) {
            return s.indexOf(ch);
        }
        if (s == first) {
            return firstTable[ch];
        }
        if (s == second) {
            return secondTable[ch];
        }
        if (first == null) {
            firstTable = table(s);
            first = s;
            return firstTable[ch];
        }
        if (second == null) {
            secondTable = table(s);
            second = s;
            return secondTable[ch];
        }
        return s.indexOf(ch);
    }

    private static short[] table(String s) {
        short[] t = new short[BMP_SIZE];
        Arrays.fill(t, (short) -1);
        for (int i = s.length() - 1; i >= 0; i--) {
            t[s.charAt(i)] = (short) i;
        }
        return t;
    }
}
