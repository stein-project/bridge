/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

/**
 * Checks of {@link SyncMove} that need no world: the kept-column set against a HashSet under random
 * adds and removes, the chest neighbour rule and the anvil axis (physics-spec.md 15.6, 15.7), and
 * that it starts off. The boxes themselves are checked in the game (the physics trace's anvil and
 * chest scenes in move mode).
 *
 * <p>Usage: {@code ./gradlew syncMoveTest} (part of {@code ./gradlew check}).
 */
public final class SyncMoveTest {

    private static int failures;

    public static void main(String[] args) {
        columnSetMatchesAHashSet();
        chestSides();
        anvilBoxes();
        keptColumnsFollowChunkKeep();
        if (failures > 0) {
            throw new AssertionError(failures + " SyncMove checks failed");
        }
        System.out.println("SyncMoveTest: ok");
    }

    private static void check(String what, boolean ok) {
        if (!ok) {
            failures++;
            System.out.println("FAILED: " + what);
        }
    }

    private static void columnSetMatchesAHashSet() {
        ColumnSet set = new ColumnSet();
        Set<Long> reference = new HashSet<>();
        Random r = new Random(42);
        for (int i = 0; i < 200_000; i++) {
            // A small range, so adds, removes and probe runs collide often; negative coordinates
            // too.
            int x = r.nextInt(64) - 32;
            int z = r.nextInt(64) - 32;
            long key = ColumnSet.key(x, z);
            int op = r.nextInt(10);
            if (op < 5) {
                set.add(x, z);
                reference.add(key);
            } else if (op < 9) {
                set.remove(x, z);
                reference.remove(key);
            } else if (r.nextInt(500) == 0) {
                set.clear();
                reference.clear();
            }
            int qx = r.nextInt(64) - 32;
            int qz = r.nextInt(64) - 32;
            if (set.contains(qx, qz) != reference.contains(ColumnSet.key(qx, qz))) {
                check("contains(" + qx + ", " + qz + ") after " + i + " operations", false);
                return;
            }
        }
        check("size " + set.size() + " = " + reference.size(), set.size() == reference.size());
        for (long key : reference) {
            check("still holds " + key, set.contains((int) (key >> 32), (int) key));
        }
        // Far columns and the extremes of int.
        set.add(Integer.MIN_VALUE, Integer.MAX_VALUE);
        set.add(1_875_000, -1_875_000);
        check(
                "extremes",
                set.contains(Integer.MIN_VALUE, Integer.MAX_VALUE)
                        && set.contains(1_875_000, -1_875_000)
                        && !set.contains(Integer.MAX_VALUE, Integer.MIN_VALUE));
    }

    private static void chestSides() {
        check("single", SyncMove.chestSide(false, false, false, false) == SyncMove.SINGLE);
        // North first, then south, west, east (the game's order).
        check("north wins", SyncMove.chestSide(true, true, true, true) == SyncMove.NORTH);
        check("south", SyncMove.chestSide(false, true, true, false) == SyncMove.SOUTH);
        check("west", SyncMove.chestSide(false, false, true, true) == SyncMove.WEST);
        check("east", SyncMove.chestSide(false, false, false, true) == SyncMove.EAST);
    }

    private static void anvilBoxes() {
        // Metadata: facing in the low two bits (0 south, 1 west, 2 north, 3 east), damage above.
        // West and east lie along X.
        for (int meta = 0; meta < 12; meta++) {
            boolean alongX = (meta & 3) == 1 || (meta & 3) == 3;
            check("anvil metadata " + meta, SyncMove.anvilAlongX(meta) == alongX);
        }
    }

    private static void keptColumnsFollowChunkKeep() {
        SyncMove.releasedAll();
        SyncMove.kept(5, -7);
        check("kept", SyncMove.isKept(5, -7) && !SyncMove.isKept(-7, 5));
        SyncMove.released(5, -7);
        check("released", !SyncMove.isKept(5, -7));
        SyncMove.kept(1, 1);
        SyncMove.releasedAll();
        check("all released", !SyncMove.isKept(1, 1));
        check("off by default", !SyncMove.active());
    }
}
