/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.ui;

import net.minecraft.client.Minecraft;

/**
 * The controls over the picture of {@link VideoScreen} (the panel is on the right): back in the top
 * left corner, and in the bottom left corner hide the menu, day or night, and the landscape or the
 * player's own view. The two-way ones are a small switch with the highlight sliding to the side
 * that is on. Where the row does not fit next to the panel, the buttons keep only their icons and
 * the name shows above the one under the mouse.
 *
 * <p>Inside a server, a note next to back says the player is still there, and turns red while they
 * take damage.
 */
final class VideoToolbar {

    private static final int H = 20;
    private static final int GAP = 5;

    /** The fields: the panel's, translucent. */
    private static final int FIELD = 0xC80A0614;

    /** Where each switch's highlight is, from 0 (left) to 1 (right). */
    private float dayAt;

    private float viewAt;

    /** The name of the button under the mouse, when only icons fit; null for none. */
    private String tip;

    private int tipX;
    private int tipY;

    /** Where the back button ends: the server note comes after it. */
    private int backEnd = VideoLayout.MARGIN;

    /**
     * Draws the controls. {@code alpha} fades them with the panel; with the menu hidden, back and
     * the hide button stay, softer — the ways out of the picture. {@code room} is how wide the
     * bottom row can be.
     */
    void draw(
            VideoLayout l,
            int height,
            int room,
            int mx,
            int my,
            boolean night,
            boolean landscape,
            boolean canChooseView,
            boolean hidden,
            float alpha,
            float open,
            double[] fps,
            long delta) {
        tip = null;
        dayAt = Ui.towards(dayAt, night ? 1.0F : 0.0F, 10.0F, delta);
        viewAt = Ui.towards(viewAt, landscape ? 0.0F : 1.0F, 10.0F, delta);
        float stay = hidden ? 0.7F * open : alpha;

        // back, in the top left corner (Esc does the same)
        String back = VideoScreen.tr("steinui.video.back");
        backEnd = VideoLayout.MARGIN + buttonWidth(back, true);
        button(
                l,
                VideoLayout.Kind.BACK,
                "back",
                back,
                VideoLayout.MARGIN,
                VideoLayout.MARGIN,
                backEnd - VideoLayout.MARGIN,
                true,
                mx,
                my,
                stay);

        // the row in the bottom left corner: hide first, then day or night, then the view
        String day = VideoScreen.tr("steinui.video.day");
        String nightText = VideoScreen.tr("steinui.video.night");
        String hide = VideoScreen.tr(hidden ? "steinui.video.showMenu" : "steinui.video.hide");
        String scene = VideoScreen.tr("steinui.video.landscape");
        String own = VideoScreen.tr("steinui.video.ownView");
        boolean full =
                buttonWidth(hide, true)
                                + (landscape ? switchWidth(day, nightText, true) + GAP : 0)
                                + (canChooseView ? switchWidth(scene, own, true) + GAP : 0)
                        <= room;
        int y = height - VideoLayout.MARGIN - H;
        int x = VideoLayout.MARGIN;
        frameRate(fps, x, y - 18, stay);
        int w = buttonWidth(hide, full);
        button(
                l,
                VideoLayout.Kind.HIDE,
                hidden ? "eye" : "eyeOff",
                hide,
                x,
                y,
                w,
                full,
                mx,
                my,
                stay);
        x += w + GAP;
        if (landscape) {
            w = switchWidth(day, nightText, full);
            twoWay(
                    l,
                    VideoLayout.Kind.DAY_NIGHT,
                    "sun",
                    day,
                    "moon",
                    nightText,
                    dayAt,
                    x,
                    y,
                    w,
                    full,
                    mx,
                    my,
                    alpha);
            x += w + GAP;
        }
        if (canChooseView) {
            w = switchWidth(scene, own, full);
            twoWay(
                    l,
                    VideoLayout.Kind.VIEW,
                    "mountain",
                    scene,
                    "person",
                    own,
                    viewAt,
                    x,
                    y,
                    w,
                    full,
                    mx,
                    my,
                    alpha);
        }
        if (tip != null && stay > 0.01F) {
            float a = Math.max(alpha, stay);
            int tw = Ui.labelWidth(tip) + 12;
            int tx = Math.max(VideoLayout.MARGIN, tipX - tw / 2);
            int ty = tipY - 18;
            Shapes.fill(tx, ty, tw, 14, 4, Ui.alpha(Theme.BG_TOOLTIP, a));
            Ui.label(tip, tx + 6, ty + 3, Ui.alpha(Theme.INK, a));
        }
    }

    /**
     * The scene's frame rate and its 1% low (the rate of the slowest frames, what is felt as a
     * stutter), above the row: every change shows its cost.
     */
    private static void frameRate(double[] fps, int x, int y, float alpha) {
        if (fps[0] <= 0.0 || alpha <= 0.01F) {
            return;
        }
        String rate = Math.round(fps[0]) + " FPS";
        String low = VideoScreen.tr("steinui.video.low", Math.round(fps[1]));
        int w = Ui.labelWidth(rate) + 10 + Ui.labelWidth(low) + 12;
        // on its own field: readable over the sand and the snow too
        // the same proportions as the buttons' labels: 8 of text in the middle of the field
        Shapes.fill(x, y, w, 14, 7, Ui.alpha(FIELD, alpha));
        Ui.label(rate, x + 6, y + 3, Ui.alpha(Theme.INK, alpha));
        Ui.label(low, x + 6 + Ui.labelWidth(rate) + 10, y + 3, Ui.alpha(Theme.MUTED, alpha));
    }

    private static int buttonWidth(String text, boolean full) {
        return full ? 24 + Ui.labelWidth(text) + 10 : H + 4;
    }

    private static int switchWidth(String a, String b, boolean full) {
        return full ? 2 * (24 + Math.max(Ui.labelWidth(a), Ui.labelWidth(b)) + 8) : 2 * H + 4;
    }

    /** A button with an icon and, when it fits, its name. */
    private void button(
            VideoLayout l,
            VideoLayout.Kind kind,
            String icon,
            String text,
            int x,
            int y,
            int w,
            boolean full,
            int mx,
            int my,
            float alpha) {
        if (alpha <= 0.01F) {
            return;
        }
        boolean hover = inside(mx, my, x, y, w, H);
        Shapes.fill(x, y, w, H, H / 2.0F, Ui.alpha(FIELD, alpha));
        Shapes.stroke(
                x, y, w, H, H / 2.0F, 1, Ui.alpha(hover ? Theme.GOLD : Theme.RULE_STRONG, alpha));
        int ink = hover ? Theme.GOLD : Theme.INK_SOFT;
        float ix = full ? x + 13 : x + w / 2.0F;
        Icons.draw(icon, ix, y + H / 2.0F, 11.0F, Ui.alpha(ink, alpha));
        if (full) {
            Ui.label(text, x + 24, y + 6, Ui.alpha(ink, alpha));
        } else if (hover) {
            tip(text, x + w / 2, y);
        }
        l.add(kind, x, y, w, H);
    }

    /** Two choices in one field, the highlight at {@code at} (0 the first, 1 the second). */
    private void twoWay(
            VideoLayout l,
            VideoLayout.Kind kind,
            String iconA,
            String textA,
            String iconB,
            String textB,
            float at,
            int x,
            int y,
            int w,
            boolean full,
            int mx,
            int my,
            float alpha) {
        if (alpha <= 0.01F) {
            return;
        }
        boolean hover = inside(mx, my, x, y, w, H);
        Shapes.fill(x, y, w, H, H / 2.0F, Ui.alpha(FIELD, alpha));
        Shapes.stroke(
                x, y, w, H, H / 2.0F, 1, Ui.alpha(hover ? Theme.GOLD : Theme.RULE_STRONG, alpha));
        int half = w / 2;
        float hx = x + 2 + at * (half - 2);
        Shapes.fill(hx, y + 2, half - 2, H - 4, (H - 4) / 2.0F, Ui.alpha(Theme.ACCENT_SOFT, alpha));
        Shapes.stroke(hx, y + 2, half - 2, H - 4, (H - 4) / 2.0F, 1, Ui.alpha(Theme.ACCENT, alpha));
        side(iconA, textA, x, y, half, full, 1.0F - at, alpha);
        side(iconB, textB, x + half, y, half, full, at, alpha);
        if (!full && hover) {
            tip(mx < x + half ? textA : textB, x + w / 2, y);
        }
        // a click anywhere on it switches to the other side
        l.add(kind, x, y, w, H);
    }

    private static void side(
            String icon, String text, int x, int y, int w, boolean full, float on, float alpha) {
        int ink = Ui.mix(Theme.MUTED | 0xFF000000, Theme.INK, on);
        float ix = full ? x + 13 : x + w / 2.0F;
        Icons.draw(icon, ix, y + H / 2.0F, 10.0F, Ui.alpha(ink, alpha));
        if (full) {
            Ui.label(text, x + 23, y + 6, Ui.alpha(ink, alpha));
        }
    }

    private void tip(String text, int x, int y) {
        tip = text;
        tipX = x;
        tipY = y;
    }

    /**
     * Inside a server: the player is still there (the game does not pause), and while they take
     * damage the note says so in red.
     */
    void server(Minecraft mc, float alpha, long now) {
        if (mc.theWorld == null || mc.isSingleplayer() || mc.thePlayer == null || alpha <= 0.01F) {
            return;
        }
        boolean hurt = mc.thePlayer.hurtTime > 0;
        String text = VideoScreen.tr(hurt ? "steinui.video.hurt" : "steinui.video.server");
        int w = Ui.labelWidth(text) + 30;
        int h = 18;
        int x = backEnd + GAP;
        int y = VideoLayout.MARGIN + 1;
        float pulse = hurt ? 0.6F + 0.4F * (float) Math.sin(now / 90.0) : 1.0F;
        Shapes.fill(x, y, w, h, h / 2.0F, Ui.alpha(FIELD, alpha));
        Shapes.stroke(
                x,
                y,
                w,
                h,
                h / 2.0F,
                1,
                Ui.alpha(hurt ? Theme.DANGER : Theme.RULE_STRONG, alpha * pulse));
        Icons.draw(
                hurt ? "heart" : "ping",
                x + 12,
                y + h / 2.0F,
                10.0F,
                Ui.alpha(hurt ? Theme.DANGER : Theme.GOLD, alpha));
        Ui.label(text, x + 21, y + 5, Ui.alpha(hurt ? Theme.DANGER : Theme.INK_SOFT, alpha));
    }

    private static boolean inside(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }
}
