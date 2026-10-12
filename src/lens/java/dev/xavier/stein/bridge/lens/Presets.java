/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.lens;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.GameSettings;

/**
 * The two positions of the graphics selector ({@link GraphicsSlider}), which also apply to the
 * game's own options:
 *
 * <ul>
 *   <li><b>Fast</b>: the lightest the game can be — fast graphics, no clouds, few particles, no
 *       smooth lighting, no ground shadow, no rain, direct drawing (without the intermediate
 *       frame), compact angles and the render distance at 8 chunks at most (if already lower, it
 *       stays);
 *   <li><b>Fancy</b>: the game as Mojang ships it — clouds and particles back, smooth lighting at
 *       maximum and the ground shadow.
 * </ul>
 *
 * <p>The render distance only goes down in Fast, and does not come back by itself: whoever wants it
 * larger picks it on its own line.
 */
final class Presets {

    /** A slider position; the order is the slider's, from left to right. */
    enum Position {
        FAST("fast"),
        FANCY("fancy");

        private final String key;

        Position(String key) {
            this.key = key;
        }

        /** Name of the position, for the translation key (steinlens.graphics.&lt;key&gt;). */
        String key() {
            return key;
        }
    }

    private static final Position[] POSITIONS = Position.values();

    /** Highest slider position, as an index. */
    static final int MAX_POSITION = POSITIONS.length - 1;

    /** The position at a slider index (0 = Fast), clamped to the slider's range. */
    static Position at(int index) {
        return POSITIONS[Math.clamp(index, 0, MAX_POSITION)];
    }

    /** Maximum distance, in chunks, that "Fast" allows. */
    private static final int FAST_DISTANCE = 8;

    private Presets() {}

    /** Position from the current configuration: the game's graphics. */
    static Position current() {
        return Minecraft.func_71410_x().field_71474_y.field_74347_j
                ? Position.FANCY
                : Position.FAST;
    }

    /**
     * Applies the position: game and Stein options. Returns true when the terrain needs to be
     * rebuilt (fast/fancy graphics and smooth lighting change the chunk mesh) — the caller rebuilds
     * once, when the selector is released, and not at every position the drag crosses.
     */
    static boolean apply(Lens mod, Position position) {
        Minecraft mc = Minecraft.func_71410_x();
        GameSettings s = mc.field_71474_y;
        LensConfig c = mod.config;
        boolean fancy = position == Position.FANCY;
        boolean rebuild = s.field_74347_j != fancy;
        s.field_74347_j = fancy;
        if (position == Position.FAST) {
            rebuild |= s.field_74348_k != 0;
            s.field_74348_k = 0; // smooth lighting off
            s.field_151451_c = Math.min(s.field_151451_c, FAST_DISTANCE); // render distance
            s.field_74345_l = 0; // clouds off
            s.field_74362_aa = 2; // minimal particles
            s.field_181151_V = false; // ground shadow
            s.field_178881_t = true; // VBO (faster than lists)
            c.directRender = true;
            c.compactAngles = true;
            c.rain = false;
            c.fog = 0;
            c.cloudHeight = 0;
        } else {
            rebuild |= s.field_74348_k != 2;
            s.field_74348_k = 2; // smooth lighting at maximum
            s.field_74345_l = 2; // fancy clouds
            s.field_74362_aa = 0; // all particles
            s.field_181151_V = true;
            s.field_178881_t = true;
            c.directRender = false;
            c.compactAngles = false;
            c.rain = true;
        }
        s.func_74303_b();
        mod.changed();
        return rebuild;
    }
}
