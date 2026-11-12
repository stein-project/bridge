/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.ui;

import dev.xavier.stein.bridge.showcase.Showcase;
import dev.xavier.stein.bridge.showcase.VideoSettings;
import dev.xavier.stein.gfx.SteinHdr;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;

import java.util.List;

/**
 * The HDR calibration, started from its line in the video settings: the white, the black and the
 * game's brightness, in plain words — a card at the bottom of the screen and a large test above it,
 * on black, the reference the eye judges a screen's light against.
 *
 * <ol>
 *   <li><b>The brightest white</b>: a large square at the chosen peak with a symbol far brighter
 *       than any screen on it ({@link SteinHdr#peakTest}); the player drags until the symbol almost
 *       disappears — past the screen's real limit both look the same.
 *   <li><b>The black</b>: a black square with a symbol barely above black, drawn by the game; the
 *       player drags the shadows until it just shows — swallowed, the dark places of the game would
 *       be a black blot.
 *   <li><b>The game's brightness</b>: a card half white (the paper white) and half black; the
 *       player drags until the white is comfortable, like a sheet of paper in a bright room.
 *   <li><b>Done</b>: the black goes away and the landscape goes to night, to see the lights against
 *       the menu.
 * </ol>
 */
final class VideoCalibration {

    static final int STEPS = 4;

    /**
     * The black step's symbol: barely above black (3 of 255), where a screen that crushes the
     * shadows shows nothing.
     */
    private static final int DARK_SYMBOL = 0xFF030303;

    private static final int CARD_W = 300;
    private static final int CARD_H = 124;

    private VideoCalibration() {}

    /** The option a step adjusts, or null. */
    static VideoSettings.Entry entry(int step) {
        return switch (step) {
            case 0 -> VideoSettings.find("hdrPeak");
            case 1 -> VideoSettings.find("hdrBlack");
            case 2 -> VideoSettings.find("hdrWhite");
            default -> null;
        };
    }

    /** The values the calibration found, put back if the player leaves it halfway. */
    private static final double[] SAVED = new double[STEPS - 1];

    /**
     * Starting: HDR on (it is what is being calibrated), the landscape by day, and every value of
     * the calibration back at its default — each step is judged on a clean picture, not through the
     * values being replaced.
     */
    static void begin() {
        VideoSettings.Entry hdr = VideoSettings.find("hdr");
        if (hdr != null && hdr.state != null && !hdr.state.getAsBoolean()) {
            hdr.next.run();
        }
        for (int i = 0; i < SAVED.length; i++) {
            VideoSettings.Entry e = entry(i);
            SAVED[i] = e == null ? -1 : e.fraction.getAsDouble();
            if (e != null && e.byDefault >= 0) {
                e.setFraction.accept(e.byDefault);
            }
        }
        changed();
        Showcase.setNight(false);
        SteinHdr.calibrating(true);
    }

    /** Left halfway (Leave, Esc): the values from before the calibration come back. */
    static void cancel() {
        for (int i = 0; i < SAVED.length; i++) {
            VideoSettings.Entry e = entry(i);
            if (e != null && SAVED[i] >= 0) {
                e.setFraction.accept(SAVED[i]);
            }
        }
        changed();
    }

    private static void changed() {
        VideoSettings.Source s = VideoSettings.source();
        if (s != null) {
            s.changed();
        }
    }

    /**
     * Each step's arrival: the adjusting steps judge the plain image (the HDR style off); the last
     * one shows the night, in the style chosen.
     */
    static void arrive(int step) {
        Showcase.setNight(step == STEPS - 1);
        SteinHdr.calibrating(step < STEPS - 1);
    }

    /** Leaving: the test off, the style back. */
    static void end() {
        SteinHdr.peakTest(false, 0, 0, 0, 0, 0);
        SteinHdr.calibrating(false);
    }

    /**
     * Draws step {@code step}; {@code live} is false while the card fades out after the end (then
     * nothing turns the peak test back on).
     */
    static void draw(
            VideoLayout l,
            int width,
            int height,
            int step,
            boolean live,
            int mx,
            int my,
            VideoSettings.Entry dragging,
            double dragAt,
            float alpha,
            long delta) {
        int x = (width - CARD_W) / 2;
        int y = height - VideoLayout.MARGIN - CARD_H;
        if (step < STEPS - 1) {
            // black around the test: the reference the screen's light is judged against
            Ui.rect(0, 0, width, height, Ui.alpha(0xFF000000, 0.97F * alpha));
        }
        int top = VideoLayout.MARGIN + 6;
        int room = y - 14 - top;
        peak(live && step == 0, width, top, room, alpha);
        if (step == 1) {
            dark(width, top, room, alpha);
        } else if (step == 2) {
            whiteAndBlack(width, top, room, alpha);
        }
        Shapes.fill(x, y, CARD_W, CARD_H, 8, Ui.alpha(0xF00A0614, alpha));
        Shapes.stroke(x, y, CARD_W, CARD_H, 8, 1, Ui.alpha(Theme.RULE_STRONG, alpha));
        String key = step == 0 ? "peak" : step == 1 ? "dark" : step == 2 ? "white" : "done";
        Ui.label(
                VideoScreen.tr("steinui.hdr.step", step + 1, STEPS),
                x + 12,
                y + 9,
                Ui.alpha(Theme.GOLD, alpha));
        Ui.text(
                VideoScreen.tr("steinui.hdr." + key + ".title"),
                x + 12,
                y + 19,
                Ui.alpha(Theme.INK, alpha));
        // the explanation in the reading font: a sentence, not a label
        List<String> lines = Ui.wrap(VideoScreen.tr("steinui.hdr." + key + ".text"), CARD_W - 24);
        for (int i = 0; i < Math.min(3, lines.size()); i++) {
            Ui.text(lines.get(i), x + 12, y + 35 + i * 11, Ui.alpha(Theme.MUTED, alpha));
        }
        VideoSettings.Entry e = entry(step);
        if (e != null) {
            int tx = x + 12;
            // no number: the eye on the test decides, not a value in nits
            int tw = CARD_W - 24;
            int ty = y + CARD_H - 34;
            boolean hover = my >= ty - 6 && my < ty + 6 && mx >= tx - 4 && mx < tx + tw + 4;
            double f = e == dragging ? dragAt : e.fraction.getAsDouble();
            Widgets.slider(
                    "hdrcal|" + step, tx, ty, tw, (float) f, hover, e == dragging, alpha, delta);
            VideoLayout.Hit hit = l.add(VideoLayout.Kind.CAL_SLIDER, tx - 4, ty - 6, tw + 8, 12);
            hit.entry = e;
            hit.trackX = tx;
            hit.trackW = tw;
        }
        int bw = 70;
        int by = y + CARD_H - 22;
        String next = VideoScreen.tr(step == STEPS - 1 ? "steinui.hdr.finish" : "steinui.hdr.next");
        String back = VideoScreen.tr(step == 0 ? "steinui.hdr.cancel" : "steinui.hdr.back");
        int nx = x + CARD_W - 12 - bw;
        int bx = x + 12;
        Widgets.primary(next, nx, by, bw, 16, inside(mx, my, nx, by, bw, 16), false, alpha);
        Widgets.button(back, bx, by, bw, 16, inside(mx, my, bx, by, bw, 16), false, alpha);
        l.add(VideoLayout.Kind.CAL_NEXT, nx, by, bw, 16);
        l.add(VideoLayout.Kind.CAL_BACK, bx, by, bw, 16);
    }

    /**
     * The peak test's square, as large as the room above the card allows; the encode draws inside
     * it. Off ({@code on} false), the test is turned off.
     */
    private static void peak(boolean on, int width, int top, int room, float alpha) {
        if (!on) {
            SteinHdr.peakTest(false, 0, 0, 0, 0, 0);
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        int size = Math.max(48, Math.min(room, width * 2 / 5));
        int px = (width - size) / 2;
        int py = top + (room - size) / 2;
        Shapes.stroke(px - 4, py - 4, size + 8, size + 8, 6, 1, Ui.alpha(Theme.RULE_STRONG, alpha));
        int s = new ScaledResolution(mc).getScaleFactor();
        SteinHdr.peakTest(true, px * s, py * s, size * s, size * s, mc.displayHeight);
    }

    /** The black step's square: black, with a block barely above it (as the peak test's). */
    private static void dark(int width, int top, int room, float alpha) {
        int size = Math.max(48, Math.min(room, width * 2 / 5));
        int x = (width - size) / 2;
        int y = top + (room - size) / 2;
        Shapes.stroke(x - 4, y - 4, size + 8, size + 8, 6, 1, Ui.alpha(Theme.RULE_STRONG, alpha));
        Shapes.fill(x, y, size, size, 0, Ui.alpha(0xFF000000, alpha));
        float cx = x + size / 2.0F;
        float cy = y + size / 2.0F;
        Icons.draw("cube", cx, cy, size * 0.62F, Ui.alpha(DARK_SYMBOL, alpha));
    }

    /** The card of the third step: the game's white (the paper white) beside black. */
    private static void whiteAndBlack(int width, int top, int room, float alpha) {
        int h = Math.max(48, Math.min(room, width / 4));
        int w = h * 2;
        int x = (width - w) / 2;
        int y = top + (room - h) / 2;
        Shapes.stroke(x - 4, y - 4, w + 8, h + 8, 6, 1, Ui.alpha(Theme.RULE_STRONG, alpha));
        Shapes.fill(x, y, w / 2, h, 0, Ui.alpha(0xFFFFFFFF, alpha));
        Shapes.fill(x + w / 2, y, w - w / 2, h, 0, Ui.alpha(0xFF000000, alpha));
        String white = VideoScreen.tr("steinui.hdr.sample");
        Ui.label(
                white,
                x + (w / 2 - Ui.labelWidth(white)) / 2,
                y + h / 2 - 4,
                Ui.alpha(0xFF0A0614, alpha));
        String black = VideoScreen.tr("steinui.hdr.black");
        Ui.label(
                black,
                x + w / 2 + (w / 2 - Ui.labelWidth(black)) / 2,
                y + h / 2 - 4,
                Ui.alpha(Theme.MUTED, alpha));
    }

    private static boolean inside(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }
}
