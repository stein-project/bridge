/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import net.minecraft.util.MathHelper;

/**
 * Sine and cosine of MathHelper (sin and cos), with the option of a compact table.
 *
 * <p>The game looks up a table of 65,536 floats (256 KB), thousands of times per frame, in the
 * model animations (arms, legs and head of every player and mob). With "compact angles" (Visuals,
 * off by default) the lookup goes to a table of 4,096 entries (16 KB, fits in the L1 cache): a step
 * of 0.09 degrees instead of 0.0055. The difference in the animations is small, but the image is
 * not identical — hence it is an option. When off, the lookup is the game's own, in its own table
 * (SIN_TABLE), and the result is bit-for-bit the same.
 */
public final class AngleTable {

    private static final int COMPACT_SIZE = 4096;
    private static final int COMPACT_MASK = COMPACT_SIZE - 1;
    private static final int GAME_MASK = 65535;
    private static final float COMPACT_SCALE = 651.8986F;
    private static final float GAME_SCALE = 10430.378F;
    private static final float COMPACT_QUARTER = 1024.0F;
    private static final float GAME_QUARTER = 16384.0F;

    private static final float[] GAME = MathHelper.SIN_TABLE;
    private static final float[] COMPACT = new float[COMPACT_SIZE];
    private static boolean compact = EngineOptions.COMPACT_ANGLES;

    static {
        for (int i = 0; i < COMPACT.length; i++) {
            COMPACT[i] = (float) Math.sin((double) i * Math.PI * 2.0 / COMPACT_SIZE);
        }
    }

    private AngleTable() {}

    public static void setCompact(boolean on) {
        compact = on;
    }

    /** The table in use right now (the game's, 65536, or the compact one, 4096): read-only. */
    public static float[] table() {
        return compact ? COMPACT : GAME;
    }

    /** MathHelper.sin: 10430.378 = 65536 / 2pi; 651.8986 = 4096 / 2pi. */
    public static float sin(float x) {
        return compact
                ? COMPACT[(int) (x * COMPACT_SCALE) & COMPACT_MASK]
                : GAME[(int) (x * GAME_SCALE) & GAME_MASK];
    }

    /** MathHelper.cos: a quarter turn ahead in the same table. */
    public static float cos(float x) {
        return compact
                ? COMPACT[(int) (x * COMPACT_SCALE + COMPACT_QUARTER) & COMPACT_MASK]
                : GAME[(int) (x * GAME_SCALE + GAME_QUARTER) & GAME_MASK];
    }
}
