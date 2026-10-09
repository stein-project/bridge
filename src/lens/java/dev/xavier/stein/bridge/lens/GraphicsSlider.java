/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

package dev.xavier.stein.bridge.lens;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.I18n;

/**
 * Graphics in a single selector, which drags like the render distance one: Fast and Fancy are the
 * pure game (its own Fast and Fancy). Each position also sets the game's own performance options —
 * Fast makes the game lighter (see {@link Presets}).
 */
final class GraphicsSlider extends GuiButton {

    private final Lens mod;
    private final VideoOptions screen;
    private float slider;
    private boolean dragging;

    /**
     * The position under the mouse during the drag (null: none). It only takes effect on release.
     */
    private Presets.Position pending;

    GraphicsSlider(int id, int width, Lens mod, VideoOptions screen) {
        super(id, 0, 0, width, 20, "");
        this.mod = mod;
        this.screen = screen;
        Presets.Position position = Presets.current();
        this.slider = position.ordinal() / (float) (Presets.MAX_POSITION);
        label();
    }

    private void label() {
        Presets.Position position = pending != null ? pending : Presets.current();
        this.field_146126_j =
                I18n.func_135052_a("steinlens.graphics")
                        + ": "
                        + I18n.func_135052_a("steinlens.graphics." + position.key());
    }

    private void drag(int mouseX) {
        float v = (mouseX - (this.field_146128_h + 4)) / (float) (this.field_146120_f - 8);
        slider = Math.max(0.0F, Math.min(1.0F, v));
        // During the drag, only the lever and the name: each applied position would rebuild the
        // chunks with the mouse still held. The position takes effect on release.
        pending = Presets.at(Math.round(slider * (Presets.MAX_POSITION)));
        label();
    }

    @Override
    protected int func_146114_a(boolean hover) {
        return 0; // like the game's selector: the box does not light up
    }

    @Override
    protected void func_146119_b(Minecraft mc, int mouseX, int mouseY) {
        if (!this.field_146125_m) {
            return;
        }
        if (this.field_146123_n) {
            VideoOptions.hover(I18n.func_135052_a("steinlens.graphics.hint"));
        }
        if (dragging) {
            drag(mouseX);
        }
        mc.func_110434_K().func_110577_a(field_146122_a);
        GlStateManager.func_179131_c(1.0F, 1.0F, 1.0F, 1.0F);
        int knob = this.field_146128_h + (int) (slider * (this.field_146120_f - 8));
        this.func_73729_b(knob, this.field_146129_i, 0, 66, 4, 20);
        this.func_73729_b(knob + 4, this.field_146129_i, 196, 66, 4, 20);
    }

    @Override
    public boolean func_146116_c(Minecraft mc, int mouseX, int mouseY) {
        if (super.func_146116_c(mc, mouseX, mouseY)) {
            drag(mouseX);
            dragging = true;
            return true;
        }
        return false;
    }

    @Override
    public void func_146118_a(int mouseX, int mouseY) {
        dragging = false;
        Presets.Position position = pending;
        pending = null;
        if (position == null || position == Presets.current()) {
            label();
            return;
        }
        boolean rebuild = Presets.apply(mod, position);
        screen.refresh(); // the game's options and ours changed together
        if (rebuild) {
            Minecraft mc = Minecraft.func_71410_x();
            if (mc.field_71438_f != null) {
                mc.field_71438_f
                        .func_72712_a(); // the game rebuilds the chunks when switching Fast/Fancy
            }
        }
    }

    /**
     * The value may have changed from outside (a loose option): rewrites the label and repositions
     * the lever.
     */
    void refresh() {
        Presets.Position position = Presets.current();
        if (!dragging) {
            slider = position.ordinal() / (float) (Presets.MAX_POSITION);
        }
        label();
    }
}
