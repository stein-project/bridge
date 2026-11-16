/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import net.minecraft.block.BlockRedstoneTorch;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Fix for the redstone torch burn-out list keeping every world: BlockRedstoneTorch.toggles becomes
 * a WeakHashMap.
 *
 * <p>The torch remembers its recent toggles per world in a static {@code HashMap<World, List>} (to
 * burn out a torch that flickers too fast), and nothing ever removes a world from it: every single
 * player world a torch toggled in, with its chunks and entities, stayed on the heap until the game
 * closed. Mojang made it a WeakHashMap in 1.13. The keys are compared by identity (World does not
 * override equals) and the lists only hold block positions, so the map behaves the same while the
 * world is alive and lets it go when it is unloaded. The patcher calls {@link #weakToggles} right
 * after the two torches are created in Block.registerBlocks, after BlockRedstoneTorch's static
 * initializer made the map and before any world exists.
 */
public final class TorchToggles {

    private TorchToggles() {}

    /** After each new BlockRedstoneTorch(boolean) in Block.registerBlocks. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void weakToggles() {
        // raw: the list's element type (BlockRedstoneTorch.Toggle) is private to the game class
        Map map = BlockRedstoneTorch.toggles;
        if (!(map instanceof WeakHashMap)) {
            BlockRedstoneTorch.toggles = new WeakHashMap(map);
        }
    }
}
