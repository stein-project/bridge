/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.ui;

import java.util.HashMap;
import java.util.Map;

/**
 * The Stein design system pieces (the same as the Stein Client's): the card, the title, the slider,
 * the toggle switch, the pill buttons. Colors and sizes come from {@link Theme}.
 *
 * <p>They live together here so that the Bridge's screens and the Stein Loader's look like the same
 * thing to the player. The Loader's option rows (a mod's {@code Option}) are drawn with these
 * pieces.
 */
public final class Widgets {

    // Opaque: the card sits in front of the game and the grid elements, and nothing may show
    // through it.
    public static final int CARD_BG = Theme.BG;
    public static final int SURFACE = 0x0DE8E1F5;
    public static final int SURFACE_HOVER = Theme.ACCENT_SOFT;
    public static final int TEXT = Theme.INK;
    public static final int TEXT_DIM = Theme.MUTED;
    public static final int ON = Theme.ACCENT;

    /** The toggle switch: a 16 by 8 pill, the same on every screen. */
    public static final int KNOB_W = 16;

    public static final int KNOB_H = 8;

    /** Width of a slider track at the right of an option row. */
    public static final int TRACK_W = 70;

    /** Height of an option row. */
    public static final int ROW = 22;

    /**
     * Where each option's switch is (0 to 1), by name; this is what makes it slide instead of jump.
     */
    private static final Map<String, Float> knobs = new HashMap<>();

    private Widgets() {}

    /** The background card: the design system's field, with a thin outline around it. */
    public static void card(float x, float y, float w, float h, float alpha) {
        Draw2D.rect(x, y, w, h, Ui.alpha(CARD_BG, alpha));
        Ui.frame(x, y, w, h, Ui.alpha(Theme.RULE_STRONG, alpha));
    }

    /**
     * A surface inside the card (a section, a list item): just one tone; under the cursor, the
     * faint accent.
     */
    public static void surface(float x, float y, float w, float h, boolean hover, float alpha) {
        Draw2D.rect(x, y, w, h, Ui.alpha(hover ? SURFACE_HOVER : SURFACE, alpha));
    }

    public static void title(String text, int x, int y, float alpha) {
        Ui.title(text, x, y, Theme.TITLE, Ui.alpha(Theme.INK, alpha));
    }

    /** Is the mouse over the close X of a card, centered at (cx, cy)? */
    public static boolean closeHit(int mx, int my, int cx, int cy) {
        return Math.abs(mx - cx) <= 8 && Math.abs(my - cy) <= 8;
    }

    /** The place of an option row: left, top, width and height. */
    public record Row(int x, int y, int w, int h) {}

    /** The mouse over a list: its position and whether it is inside the visible part. */
    public record Pointer(int mx, int my, boolean inside) {}

    /**
     * The design system slider, the same on every screen: a thin pill track, the part up to the
     * value in the accent and the round knob on top. Under the cursor the knob grows and, while
     * dragging, gets the accent halo. The knob slides to the new value (a click on the track does
     * not make it jump).
     *
     * @param x start of the track; {@code cy} its middle line; {@code w} the width
     * @param fraction where the value is, from 0 to 1
     */
    public static void slider(
            String key,
            float x,
            float cy,
            float w,
            float fraction,
            boolean hover,
            boolean active,
            float alpha,
            long delta) {
        float f = Ui.clamp01(fraction);
        float at = knobs.getOrDefault(key, f);
        // While dragging, the knob follows the mouse right away; on a click, it slides.
        at = active ? f : Ui.towards(at, f, 14.0F, delta);
        knobs.put(key, at);
        String hotKey = key + "|hot";
        float hot = knobs.getOrDefault(hotKey, 0.0F);
        hot = Ui.towards(hot, active ? 1.0F : hover ? 0.6F : 0.0F, 10.0F, delta);
        knobs.put(hotKey, hot);

        float th = 3.0F;
        float ty = cy - th / 2;
        Shapes.fill(x, ty, w, th, th / 2, Ui.alpha(Theme.RULE_STRONG, alpha));
        float kx = x + w * at;
        if (kx - x >= th) {
            Shapes.fill(x, ty, kx - x, th, th / 2, Ui.alpha(Theme.ACCENT, alpha));
        }
        if (hot > 0.01F) {
            Ui.circle(kx, cy, 5.0F + 3.0F * hot, Ui.alpha(Theme.ACCENT_SOFT, alpha * hot * 2.0F));
        }
        float r = 3.5F + 1.0F * hot;
        Ui.circle(kx, cy, r, Ui.alpha(Theme.INK, alpha));
        Ui.ring(
                kx,
                cy,
                r,
                1.0F / Ui.scale(),
                Ui.alpha(Theme.ACCENT, alpha * (0.35F + 0.65F * hot)));
    }

    /**
     * The on/off switch, the same on every screen: the design system pill, with the dot moving from
     * one side to the other. Off, just the outline; on, the accent. The dot slides instead of
     * jumping — the movement is what shows that this click changed that.
     */
    public static void knob(String key, float x, float y, boolean on, float alpha, long delta) {
        knob(key, x, y, on, ON, alpha, delta);
    }

    /**
     * The same switch, with another "on" color: the Loader menu paints a mod that is enabled but
     * did not load in red, so the row does not say "active" in the same color as one that is fine.
     */
    public static void knob(
            String key, float x, float y, boolean on, int onColor, float alpha, long delta) {
        float at = knobs.getOrDefault(key, on ? 1.0F : 0.0F);
        at = Ui.towards(at, on ? 1.0F : 0.0F, 8.0F, delta);
        knobs.put(key, at);
        float r = KNOB_H / 2.0F;
        Shapes.fill(x, y, KNOB_W, KNOB_H, r, Ui.alpha(onColor, alpha * 0.18F * at));
        int border = Ui.mix(Theme.RULE_STRONG | 0xFF000000, onColor, at);
        Shapes.stroke(x, y, KNOB_W, KNOB_H, r, 1, Ui.alpha(border, alpha * (0.4F + 0.6F * at)));
        int dot = Ui.mix(Theme.MUTED | 0xFF000000, onColor, at);
        Ui.circle(x + r + at * (KNOB_W - KNOB_H), y + r, r - 2.0F, Ui.alpha(dot, alpha));
    }

    /** The design system button: a thin-outlined pill, with the label in capitals. */
    public static void button(
            String label, int x, int y, int w, int h, boolean hover, boolean off, float alpha) {
        pill(label, x, y, w, h, hover, off, false, alpha);
    }

    /** The screen's main action button: the filled pill, in the accent. */
    public static void primary(
            String label, int x, int y, int w, int h, boolean hover, boolean off, float alpha) {
        pill(label, x, y, w, h, hover, off, true, alpha);
    }

    private static void pill(
            String label,
            int x,
            int y,
            int w,
            int h,
            boolean hover,
            boolean off,
            boolean primary,
            float alpha) {
        float r = h / 2.0F;
        int ink;
        if (primary && !off) {
            Ui.roundRect(x, y, w, h, r, Ui.alpha(hover ? 0xFF8D63FF : Theme.ACCENT, alpha));
            ink = Theme.ON_ACCENT;
        } else {
            int border = off ? Theme.RULE : hover ? Theme.GOLD : Theme.RULE_STRONG;
            if (hover && !off) {
                Shapes.fill(x, y, w, h, r, Ui.alpha(Theme.GOLD_FAINT, alpha));
            }
            Shapes.stroke(x, y, w, h, r, 1, Ui.alpha(border, alpha));
            ink = off ? Theme.FAINT : hover ? Theme.GOLD : Theme.INK;
        }
        String s = trimLabel(label, w - 12);
        Ui.label(s, x + (w - Ui.labelWidth(s)) / 2, y + (h - 8) / 2, Ui.alpha(ink, alpha));
    }

    private static String trimLabel(String s, int w) {
        while (s.length() > 1 && Ui.labelWidth(s) > w) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }
}
