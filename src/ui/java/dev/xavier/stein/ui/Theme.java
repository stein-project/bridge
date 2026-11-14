/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.ui;

/**
 * The tokens of the Stein design system, the same as in Stein Client (client/ui/src/ds/tokens,
 * "vesper" palette): a near-black violet field, lavender ink, a muted gold-violet for marks and
 * numbers, and an electric violet only for what is alive (enabled, selected, the main action).
 * Everything else comes from the translucent ink (the hairlines).
 *
 * <p>Everything the Loader draws takes its color from here: changing the design system means
 * changing this file.
 */
public final class Theme {

    /** The field: near-black violet. */
    public static final int BG = 0xFF0A0614;

    /** The same, a little deeper (a recessed area, a text field). */
    public static final int BG_SUNKEN = 0xFF080510;

    /** The field at 94% opacity: tooltips and labels over the game. */
    public static final int BG_TOOLTIP = 0xF00A0614;

    /** The field at 90% opacity: pop-ups and labels in the editor. */
    public static final int BG_POPUP = 0xE60A0614;

    /** The ink at 85% opacity: names at rest. */
    public static final int INK_SOFT = 0xD9E8E1F5;

    /** The ink at 8% opacity: the fill of a control at rest. */
    public static final int FIELD = 0x14E8E1F5;

    /** The gold at 8% opacity: the faint highlight of a selected control. */
    public static final int GOLD_FAINT = 0x14B89CD9;

    /** The ink: lavender white, the main text. */
    public static final int INK = 0xFFE8E1F5;

    /** Secondary text (55% of the ink) and muted (35%). */
    public static final int MUTED = 0x8CE8E1F5;

    public static final int FAINT = 0x59E8E1F5;

    /** Gold-violet: numbers, versions, marks. */
    public static final int GOLD = 0xFFB89CD9;

    /** Electric violet: enabled, selected, the main action. */
    public static final int ACCENT = 0xFF7C4DFF;

    /** The very faint accent, for the background of what is selected or under the cursor. */
    public static final int ACCENT_SOFT = 0x247C4DFF;

    public static final int ON_ACCENT = 0xFFFFFFFF;

    /** The hairlines: 10% and 22% of the ink. */
    public static final int RULE = 0x1AE8E1F5;

    public static final int RULE_STRONG = 0x38E8E1F5;

    /** Error, and what calls for care. */
    public static final int DANGER = 0xFFE0708A;

    public static final int WARN = 0xFFE0B870;

    /** The panel over the game: the field, almost opaque. */
    public static final int PANEL = 0xFA0A0614;

    /** Font sizes, in screen units (the font is rasterized at the real resolution). */
    public static final float TEXT = 10.0F;

    public static final float MONO = 6.5F;
    public static final float LABEL = 6.0F;
    public static final float TITLE = 12.0F;
    public static final float HEADING = 16.0F;

    /** The letter spacing of uppercase labels, as a fraction of the size. */
    public static final float TRACKING = 0.14F;

    /**
     * The game's color codes (0 to f) on Stein screens: the same meanings (a green "went well", c
     * red "error", e yellow "attention", 7 gray "muted"), with the theme's colors in place of
     * Minecraft's.
     */
    public static final int[] CODES = {
        0x000000, 0x6F5BC2, 0x6FB583, 0x7FB8C4, 0xC0566E, 0x9A6BE0, 0xE0B870, 0x9B93AD,
        0x6B6480, 0x8C7BFF, 0x9BD4A8, 0x9AD8E0, 0xE0708A, 0xB89CD9, 0xE8D08A, 0xE8E1F5,
    };

    private Theme() {}
}
