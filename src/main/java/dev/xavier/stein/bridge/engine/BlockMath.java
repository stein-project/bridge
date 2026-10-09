/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

package dev.xavier.stein.bridge.engine;

/**
 * Block-coordinate arithmetic shared by the chunk engine classes: the game's {@code
 * MathHelper.floor_double}, {@code MathHelper.bucketInt(i, 16)} and the packed {@code BlockPos}
 * key.
 */
final class BlockMath {
    private BlockMath() {}

    /** MathHelper.floor_double, like BlockPos(double, double, double). */
    static int floor(double d) {
        int i = (int) d;
        return d < (double) i ? i - 1 : i;
    }

    /** MathHelper.bucketInt(i, 16). */
    static int bucket(int i) {
        return i < 0 ? -((-i - 1) / 16) - 1 : i / 16;
    }

    /** The block position packed in a long (26 bits x, 12 bits y, 26 bits z). */
    static long pack(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) y & 0xFFFL) << 26 | ((long) z & 0x3FFFFFFL);
    }
}
