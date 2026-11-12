/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.ui;

import dev.xavier.stein.bridge.showcase.VideoSettings;

import net.minecraft.util.ResourceLocation;

import java.util.List;

/**
 * The drawing of {@link VideoScreen}'s panel: the header, the row of topics, the list and the hint.
 * Every piece registers where it was drawn in the {@link VideoLayout}, for the clicks (the controls
 * over the picture are {@link VideoToolbar}'s).
 */
final class VideoDrawing {

    /**
     * The panel: the field, translucent, so the landscape shows through without losing the text.
     */
    private static final int PANEL = 0xC80A0614;

    /** A slider's value, right-aligned in a column this wide: the track does not move with it. */
    private static final int VALUE_W = 46;

    private VideoDrawing() {}

    static void panel(
            VideoScreen screen,
            VideoLayout l,
            List<VideoSettings.Section> sections,
            int topic,
            int mx,
            int my,
            VideoSettings.Entry dragging,
            double dragAt,
            float alpha,
            long delta) {
        if (alpha <= 0.01F) {
            return;
        }
        Shapes.fill(l.px, l.py, l.pw, l.ph, 8, Ui.alpha(PANEL, alpha));
        Shapes.stroke(l.px, l.py, l.pw, l.ph, 8, 1, Ui.alpha(Theme.RULE, alpha));
        header(l, alpha);
        int bottom = topics(l, sections, topic, mx, my, alpha);
        Ui.hair(l.px + 10, bottom + 5, l.pw - 20, Ui.alpha(Theme.RULE, alpha));
        l.listTop = bottom + 9;
        if (topic >= sections.size()) {
            return;
        }
        VideoSettings.Section s = sections.get(topic);
        // the hint's room: as many lines as the topic's longest hint, so the list does not move
        // when the mouse goes from one option to another
        int lines = hintLines(s, l.contentW);
        l.listBottom = l.py + l.ph - (10 + lines * HINT_LINE);
        String hint = list(l, s, mx, my, dragging, dragAt, alpha, delta);
        hint(l, hint, lines, alpha);
    }

    /** The Stein wordmark (the loading screen's), 337 by 160 pixels. */
    private static final ResourceLocation LOGO =
            new ResourceLocation("steinbridge", "logo/stein.png");

    private static final int LOGO_H = 14;
    private static final int LOGO_W = Math.round(LOGO_H * 337 / 160.0F);

    /** The top of the panel: the Stein wordmark, and the screen's name beside it. */
    private static void header(VideoLayout l, float alpha) {
        int y = l.py + 6;
        Ui.image(LOGO, l.contentX, y, LOGO_W, LOGO_H, alpha);
        Ui.label(
                VideoScreen.tr("steinui.video.title"),
                l.contentX + LOGO_W + 7,
                y + (LOGO_H - 8) / 2 + 1,
                Ui.alpha(Theme.MUTED, alpha));
    }

    /** Height of a line of the hint. */
    private static final int HINT_LINE = 11;

    /** The most lines a hint takes; a longer one ends in "...". */
    private static final int HINT_MAX = 4;

    /** {@link #hintLines}' last answer, for the same topic and width. */
    private static VideoSettings.Section linesOf;

    private static int linesWidth;
    private static int lines;

    private static int hintLines(VideoSettings.Section s, int w) {
        if (s != linesOf || w != linesWidth) {
            int n = Ui.wrap(VideoScreen.tr("steinui.video.hintIdle"), w).size();
            for (VideoSettings.Entry e : s.entries) {
                n = Math.max(n, Ui.wrap(e.hint.get(), w).size());
            }
            linesOf = s;
            linesWidth = w;
            lines = Math.min(HINT_MAX, Math.max(1, n));
        }
        return lines;
    }

    /**
     * The topics in one row of icons, under it the name of the chosen one (or of the one under the
     * mouse). Returns where the name ends.
     */
    private static int topics(
            VideoLayout l,
            List<VideoSettings.Section> sections,
            int topic,
            int mx,
            int my,
            float alpha) {
        int n = Math.max(1, sections.size());
        int step = Math.min(22, l.contentW / n);
        int y = l.py + VideoLayout.HEADER;
        int h = VideoLayout.CHIP_H + 5;
        int shown = topic;
        for (int i = 0; i < sections.size(); i++) {
            int x = l.contentX + i * step;
            boolean hover = mx >= x && mx < x + step && my >= y && my < y + h;
            boolean on = i == topic;
            if (hover) {
                shown = i;
            }
            if (on || hover) {
                Shapes.fill(
                        x + 1,
                        y,
                        step - 2,
                        h,
                        5,
                        Ui.alpha(on ? Theme.ACCENT_SOFT : Theme.FIELD, alpha));
            }
            int color = on ? Theme.ACCENT : hover ? Theme.INK : Theme.MUTED;
            Icons.draw(
                    icon(sections.get(i).id),
                    x + step / 2.0F,
                    y + h / 2.0F,
                    11.0F,
                    Ui.alpha(color, alpha));
            l.add(VideoLayout.Kind.TOPIC, x, y, step, h).index = i;
        }
        if (shown < sections.size()) {
            Ui.label(
                    sections.get(shown).title.get(),
                    l.contentX,
                    y + h + 6,
                    Ui.alpha(shown == topic ? Theme.GOLD : Theme.INK_SOFT, alpha));
        }
        return y + h + 14;
    }

    /** The topic's options, one line each; returns the hint of the one under the mouse. */
    private static String list(
            VideoLayout l,
            VideoSettings.Section s,
            int mx,
            int my,
            VideoSettings.Entry dragging,
            double dragAt,
            float alpha,
            long delta) {
        int top = l.listTop;
        int fit = Math.max(1, (l.listBottom - top) / VideoLayout.ROW);
        l.rows(s.entries.size(), fit);
        String hint = "";
        int y = top;
        int end = Math.min(s.entries.size(), l.scroll() + fit);
        for (int i = l.scroll(); i < end; i++) {
            VideoSettings.Entry e = s.entries.get(i);
            int h = VideoLayout.ROW;
            boolean hover =
                    mx >= l.contentX - 4
                            && mx < l.contentX + l.contentW + 4
                            && my >= y
                            && my < y + h;
            if (hover || e == dragging) {
                hint = e.hint.get();
            }
            row(l, e, y, h, hover, e == dragging, dragAt, alpha, delta);
            y += h;
        }
        // more above or below: a thin bar on the panel's edge says how much and where
        if (s.entries.size() > fit) {
            int track = l.listBottom - top;
            int bar = Math.max(8, track * fit / s.entries.size());
            int at = (track - bar) * l.scroll() / Math.max(1, s.entries.size() - fit);
            Shapes.fill(l.px + l.pw - 5, top, 2, track, 1, Ui.alpha(Theme.RULE, alpha));
            Shapes.fill(l.px + l.pw - 5, top + at, 2, bar, 1, Ui.alpha(Theme.MUTED, alpha));
        }
        return hint;
    }

    /** One option in one line: its name on the left, its control on the right. */
    private static void row(
            VideoLayout l,
            VideoSettings.Entry e,
            int y,
            int h,
            boolean hover,
            boolean active,
            double dragAt,
            float alpha,
            long delta) {
        boolean enabled = e.enabled.getAsBoolean();
        if (hover && enabled && e.kind != VideoSettings.Kind.INFO) {
            Shapes.fill(l.contentX - 4, y, l.contentW + 8, h, 4, Ui.alpha(Theme.FIELD, alpha));
        }
        int ink = enabled || e.kind == VideoSettings.Kind.INFO ? Theme.INK_SOFT : Theme.FAINT;
        int right = l.contentX + l.contentW;
        int ty = y + 4;
        String key = "video|" + System.identityHashCode(e);
        VideoLayout.Hit hit = l.add(VideoLayout.Kind.ENTRY, l.contentX - 4, y, l.contentW + 8, h);
        hit.entry = e;
        String label = e.label.get();
        if (e.kind == VideoSettings.Kind.SLIDER) {
            double f = active ? dragAt : e.fraction.getAsDouble();
            String value = Ui.trim(e.value.get(), VALUE_W);
            int tw = Math.min(56, l.contentW / 4);
            int tx = right - VALUE_W - 6 - tw;
            Ui.text(Ui.trim(label, tx - 8 - l.contentX), l.contentX, ty, Ui.alpha(ink, alpha));
            Widgets.slider(
                    key,
                    tx,
                    y + h / 2.0F,
                    tw,
                    (float) f,
                    hover,
                    active,
                    alpha * (enabled ? 1.0F : 0.4F),
                    delta);
            Ui.text(
                    value,
                    right - Ui.width(value),
                    ty,
                    Ui.alpha(enabled ? Theme.GOLD : Theme.FAINT, alpha));
            hit.trackX = tx;
            hit.trackW = tw;
            return;
        }
        if (e.state != null) {
            Ui.text(
                    Ui.trim(label, l.contentW - Widgets.KNOB_W - 8),
                    l.contentX,
                    ty,
                    Ui.alpha(ink, alpha));
            Widgets.knob(
                    key,
                    right - Widgets.KNOB_W,
                    y + (h - Widgets.KNOB_H) / 2.0F,
                    e.state.getAsBoolean(),
                    alpha * (enabled ? 1.0F : 0.4F),
                    delta);
            return;
        }
        String value = e.value.get();
        if (e.kind == VideoSettings.Kind.ACTION) {
            // a button at the end of the line: the whole line clicks it
            int bw = Ui.labelWidth(value) + 16;
            int bx = right - bw;
            int color = !enabled ? Theme.FAINT : hover ? Theme.GOLD : Theme.INK;
            Ui.text(Ui.trim(label, bx - l.contentX - 6), l.contentX, ty, Ui.alpha(ink, alpha));
            Shapes.fill(bx, y + 2, bw, h - 4, (h - 4) / 2.0F, Ui.alpha(Theme.ACCENT_SOFT, alpha));
            Shapes.stroke(
                    bx,
                    y + 2,
                    bw,
                    h - 4,
                    (h - 4) / 2.0F,
                    1,
                    Ui.alpha(enabled ? Theme.ACCENT : Theme.RULE, alpha));
            Ui.label(value, bx + 8, y + 4, Ui.alpha(color, alpha));
            return;
        }
        if (e.kind == VideoSettings.Kind.INFO) {
            Ui.text(
                    Ui.trim(label, l.contentW - Ui.width(value) - 8),
                    l.contentX,
                    ty,
                    Ui.alpha(ink, alpha));
            Ui.text(value, right - Ui.width(value), ty, Ui.alpha(Theme.MUTED, alpha));
            return;
        }
        // a choice: "< value >", the arrows step back and forward
        int vw = Ui.width(value);
        int valueX = right - 10 - vw;
        int arrowL = valueX - 9;
        Ui.text(Ui.trim(label, arrowL - l.contentX - 6), l.contentX, ty, Ui.alpha(ink, alpha));
        int color = !enabled ? Theme.FAINT : hover ? Theme.GOLD : Theme.INK;
        Icons.chevron(
                arrowL + 3,
                y + h / 2.0F,
                7.0F,
                90.0F,
                Ui.alpha(enabled ? Theme.MUTED : Theme.FAINT, alpha));
        Ui.text(value, valueX, ty, Ui.alpha(color, alpha));
        Icons.chevron(
                right - 4,
                y + h / 2.0F,
                7.0F,
                -90.0F,
                Ui.alpha(enabled ? Theme.MUTED : Theme.FAINT, alpha));
        hit.backEdge = valueX - 2;
    }

    /** What the option under the mouse does, under the list. */
    private static void hint(VideoLayout l, String hint, int room, float alpha) {
        int top = l.listBottom + 4;
        Ui.hair(l.px + 10, top, l.pw - 20, Ui.alpha(Theme.RULE, alpha));
        boolean idle = hint == null || hint.isEmpty();
        // the hint in the reading font: a sentence, not a label
        List<String> lines =
                Ui.wrap(idle ? VideoScreen.tr("steinui.video.hintIdle") : hint, l.contentW);
        int color = idle ? Theme.FAINT : Theme.MUTED;
        int n = Math.min(room, lines.size());
        for (int i = 0; i < n; i++) {
            String line = lines.get(i);
            if (i == n - 1 && n < lines.size()) {
                line = Ui.trim(line + " ...", l.contentW);
            }
            Ui.text(line, l.contentX, top + 5 + i * HINT_LINE, Ui.alpha(color, alpha));
        }
    }

    /** The icon of each topic. */
    private static String icon(String id) {
        return switch (id) {
            case "general" -> "sliders";
            case "display" -> "screen";
            case "world" -> "mountain";
            case "camera" -> "camera";
            case "light" -> "bulb";
            case "image" -> "sparkle";
            case "sky" -> "cloud";
            case "textures" -> "grid";
            case "players" -> "users";
            case "animations" -> "sword";
            case "performance" -> "gauge";
            case "credits" -> "heart";
            default -> "gear";
        };
    }
}
