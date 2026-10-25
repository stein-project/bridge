/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.lens;

import dev.xavier.stein.bridge.core.Log;
import dev.xavier.stein.bridge.showcase.VideoSettings;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiListExtended;
import net.minecraft.client.gui.GuiOptionsRowList;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * The game's own Video Settings screen with every image option on it ({@link VideoModel}), by
 * topic: what is shown when the Stein Loader is not there to open its own video settings. One
 * button per option, two per row, a title line per topic.
 *
 * <p>The game's list (GuiOptionsRowList) and its rows are private; the fields are found by type,
 * never by name, so as not to depend on obfuscation. Our buttons take care of their own clicks (the
 * row only applies the game's option buttons, and here there are none).
 *
 * <p>The hint of the option under the mouse appears in a small box next to the cursor, drawn after
 * the screen ({@link Lens#afterDrawScreen}).
 */
final class VideoOptions {

    /** Width of the buttons, the same as the game's. */
    private static final int BUTTON_WIDTH = 150;

    /** Id of our first button, far from the game's (which use the option's number). */
    private static final int FIRST_BUTTON_ID = 900;

    /** Hint of the button under the mouse in the frame, for the box next to the cursor. */
    private static String hint = "";

    private final Lens mod;
    private final List<GuiOptionsRowList.Row> rows;
    private final int left;
    private final int right;

    /** Our buttons, to rewrite the labels when an option changes others. */
    private final List<EntryButton> buttons = new ArrayList<>();

    /** Button waiting for a pair in the row. */
    private GuiButton pending;

    private int nextId = FIRST_BUTTON_ID;

    private VideoOptions(Lens mod, List<GuiOptionsRowList.Row> rows, int[] columns) {
        this.mod = mod;
        this.rows = rows;
        this.left = columns[0];
        this.right = columns[1];
    }

    /** Called in the Video Settings' onGuiInit. */
    static void patch(GuiScreen screen, Lens mod) {
        try {
            GuiListExtended list = (GuiListExtended) byType(screen, GuiListExtended.class);
            if (list == null) {
                return;
            }
            @SuppressWarnings("unchecked")
            List<GuiOptionsRowList.Row> rows =
                    (List<GuiOptionsRowList.Row>) byType(list, List.class);
            if (rows == null) {
                return;
            }
            // The list does not move the buttons horizontally: each is born in the right column. We
            // copy the game's columns.
            int[] columns = columns(rows);
            VideoOptions v = new VideoOptions(mod, rows, columns);
            rows.clear();
            v.build();
            Log.info("lens", "video options: " + rows.size() + " rows");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            Log.info("lens", "game video options left untouched: " + e);
        }
    }

    private void build() {
        for (VideoSettings.Section s : VideoSettings.sections()) {
            header(s.title.get());
            for (VideoSettings.Entry e : s.entries) {
                EntryButton b =
                        e.kind == VideoSettings.Kind.SLIDER
                                ? new SliderButton(nextId++, e)
                                : new EntryButton(nextId++, e);
                buttons.add(b);
                button(b);
            }
        }
        flush();
    }

    /** Topic title, in a line of its own. */
    private void header(String title) {
        flush();
        rows.add(
                new GuiOptionsRowList.Row(
                        new HeaderButton(nextId++, title, left, right + BUTTON_WIDTH - left),
                        null));
    }

    private void button(GuiButton b) {
        if (pending == null) {
            b.field_146128_h = left;
            pending = b;
        } else {
            b.field_146128_h = right;
            rows.add(new GuiOptionsRowList.Row(pending, b));
            pending = null;
        }
    }

    private void flush() {
        if (pending != null) {
            rows.add(new GuiOptionsRowList.Row(pending, null));
            pending = null;
        }
    }

    /**
     * After a change: saved and applied, and every label read again (an option may depend on
     * another, and the graphics preset touches almost all of them).
     */
    private void changed() {
        mod.changed();
        for (EntryButton b : buttons) {
            b.update();
        }
    }

    // ---- hint of the option under the mouse ----

    /** Before the screen draws: the hint is discovered again in this frame. */
    static void beforeDraw() {
        hint = "";
    }

    /** One of our buttons is under the mouse: its hint. */
    static void hover(String text) {
        hint = text == null ? "" : text;
    }

    /** After the screen: the hint of the option under the mouse, in a box next to the cursor. */
    static void drawHint(GuiScreen screen, int mouseX, int mouseY) {
        if (hint.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.func_71410_x();
        FontRenderer f = mc.field_71466_p;
        int max = Math.min(screen.field_146294_l - 24, 280);
        if (max < 16) {
            return;
        }
        List<?> lines = f.func_78271_c(hint, max);
        int n = Math.min(6, lines.size());
        int w = 0;
        for (int i = 0; i < n; i++) {
            w = Math.max(w, f.func_78256_a((String) lines.get(i)));
        }
        int h = n * 10 - 2;
        int x = Math.max(4, Math.min(mouseX + 10, screen.field_146294_l - w - 8));
        int y = Math.max(4, Math.min(mouseY - h - 8, screen.field_146295_m - h - 8));
        Gui.func_73734_a(x - 4, y - 4, x + w + 4, y + h + 4, 0xF0100010);
        Gui.func_73734_a(x - 3, y - 3, x + w + 3, y + h + 3, 0xFF3A2E5A);
        Gui.func_73734_a(x - 2, y - 2, x + w + 2, y + h + 2, 0xF0100010);
        for (int i = 0; i < n; i++) {
            f.func_175063_a((String) lines.get(i), x, y + i * 10, 0xFFE0E0E0);
        }
    }

    // ---- buttons ----

    /** One option: "Name: value"; left click steps forward, right click (in the row) back. */
    private class EntryButton extends GuiButton {
        final VideoSettings.Entry entry;

        EntryButton(int id, VideoSettings.Entry entry) {
            super(id, 0, 0, BUTTON_WIDTH, 20, "");
            this.entry = entry;
            update();
        }

        void update() {
            this.field_146126_j = entry.label.get() + ": " + entry.value.get();
            this.field_146124_l = entry.enabled.getAsBoolean();
        }

        @Override
        public boolean func_146116_c(Minecraft mc, int mouseX, int mouseY) {
            if (!super.func_146116_c(mc, mouseX, mouseY)) {
                return false;
            }
            this.func_146113_a(mc.func_147118_V());
            if (entry.next != null) {
                entry.next.run();
                changed();
            }
            return true;
        }

        @Override
        public void func_146112_a(Minecraft mc, int mouseX, int mouseY) {
            super.func_146112_a(mc, mouseX, mouseY);
            if (this.field_146125_m && this.field_146123_n) {
                hover(entry.hint.get());
            }
        }
    }

    /** A track: drags like the game's own sliders; a heavy one applies when released. */
    private final class SliderButton extends EntryButton {
        private boolean dragging;
        private double drag;

        SliderButton(int id, VideoSettings.Entry entry) {
            super(id, entry);
        }

        @Override
        protected int func_146114_a(boolean hover) {
            return 0; // like the game's sliders: the box does not light up
        }

        private void drag(int mouseX) {
            drag =
                    Math.max(
                            0,
                            Math.min(
                                    1,
                                    (mouseX - (field_146128_h + 4))
                                            / (double) (field_146120_f - 8)));
            if (!entry.onRelease) {
                entry.setFraction.accept(drag);
                changed();
            }
        }

        @Override
        protected void func_146119_b(Minecraft mc, int mouseX, int mouseY) {
            if (!this.field_146125_m) {
                return;
            }
            if (dragging) {
                drag(mouseX);
            }
            double at = dragging ? drag : entry.fraction.getAsDouble();
            mc.func_110434_K().func_110577_a(field_146122_a);
            GlStateManager.func_179131_c(1.0F, 1.0F, 1.0F, 1.0F);
            int knob = this.field_146128_h + (int) (at * (this.field_146120_f - 8));
            this.func_73729_b(knob, this.field_146129_i, 0, 66, 4, 20);
            this.func_73729_b(knob + 4, this.field_146129_i, 196, 66, 4, 20);
        }

        @Override
        public boolean func_146116_c(Minecraft mc, int mouseX, int mouseY) {
            if (this.field_146124_l
                    && this.field_146125_m
                    && mouseX >= this.field_146128_h
                    && mouseY >= this.field_146129_i
                    && mouseX < this.field_146128_h + this.field_146120_f
                    && mouseY < this.field_146129_i + this.field_146121_g) {
                dragging = true;
                drag(mouseX);
                return true;
            }
            return false;
        }

        @Override
        public void func_146118_a(int mouseX, int mouseY) {
            if (dragging && entry.onRelease) {
                entry.setFraction.accept(drag);
                changed();
            }
            dragging = false;
        }
    }

    /** Topic title: just the text, centered, with a line on each side. */
    private static final class HeaderButton extends GuiButton {

        HeaderButton(int id, String text, int x, int width) {
            super(id, x, 0, width, 20, text);
            this.field_146124_l = false;
        }

        @Override
        public void func_146112_a(Minecraft mc, int mouseX, int mouseY) {
            if (!this.field_146125_m) {
                return;
            }
            FontRenderer f = mc.field_71466_p;
            int w = f.func_78256_a(this.field_146126_j);
            int cx = this.field_146128_h + this.field_146120_f / 2;
            int y = this.field_146129_i + 11;
            Gui.func_73734_a(this.field_146128_h, y, cx - w / 2 - 6, y + 1, 0x30FFFFFF);
            Gui.func_73734_a(
                    cx + w / 2 + 6,
                    y,
                    this.field_146128_h + this.field_146120_f,
                    y + 1,
                    0x30FFFFFF);
            f.func_175063_a(this.field_146126_j, cx - w / 2F, y - 4F, 0xFFC6C6C6);
        }

        @Override
        public boolean func_146116_c(Minecraft mc, int mouseX, int mouseY) {
            return false;
        }
    }

    // ---- utilities ----

    /** Left and right button columns, taken from one of the game's own rows. */
    private static int[] columns(List<GuiOptionsRowList.Row> rows)
            throws ReflectiveOperationException {
        for (GuiOptionsRowList.Row row : rows) {
            GuiButton[] two = buttons(row);
            if (two[0] != null && two[1] != null) {
                return new int[] {two[0].field_146128_h, two[1].field_146128_h};
            }
        }
        // As the game computes them (GuiOptionsRowList): width/2 - 155 and +160.
        int w = new ScaledResolution(Minecraft.func_71410_x()).func_78326_a();
        return new int[] {w / 2 - 155, w / 2 - 155 + 160};
    }

    /** The two buttons of a row (the second may be null), by the GuiButton-typed fields. */
    private static GuiButton[] buttons(GuiOptionsRowList.Row row)
            throws ReflectiveOperationException {
        GuiButton[] out = new GuiButton[2];
        int n = 0;
        for (Field f : row.getClass().getDeclaredFields()) {
            if (GuiButton.class.isAssignableFrom(f.getType()) && n < 2) {
                f.setAccessible(true);
                out[n++] = (GuiButton) f.get(row);
            }
        }
        return out;
    }

    /** The first field of the requested type, from the object or its superclasses. */
    private static Object byType(Object owner, Class<?> type) throws ReflectiveOperationException {
        for (Class<?> c = owner.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (type.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    return f.get(owner);
                }
            }
        }
        return null;
    }
}
