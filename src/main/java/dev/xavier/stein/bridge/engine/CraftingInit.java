/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import dev.xavier.stein.bridge.core.Log;

import net.minecraft.inventory.ContainerPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Skips the recipe search in each player's inventory constructor.
 *
 * <p>EntityPlayer.&lt;init&gt; creates a ContainerPlayer, whose constructor ends with
 * onCraftMatrixChanged(craftMatrix): it walks all the game's recipes looking for one that matches
 * the 2x2 crafting grid. At that point the grid has just been created and is empty, no recipe
 * matches an empty grid, and the result (nothing) is already the initial state of the result slot.
 * 1.8.9 does this search for every remote player that comes into view; with 400 arriving together,
 * JFR showed it at 17% of a 125 ms spike (ShapedRecipes.checkMatch).
 *
 * <p>The patcher diverts only the call INSIDE the constructor (rule scoped to xy.&lt;init&gt;); the
 * real calls, when someone touches the grid, stay intact.
 *
 * <p>{@code -Dsteinbridge.opt.vanillaCraftInit=true} restores the call, for A/B. {@code
 * -Dsteinbridge.opt.checkCraftInit=true} runs the original search and checks that the result slot
 * stayed empty, to prove the equivalence in the real game.
 */
public final class CraftingInit {

    /** A summary line is printed every this many checks. */
    private static final int REPORT_EVERY = 100;

    private static final boolean VANILLA = EngineOptions.VANILLA_CRAFT_INIT;
    private static final boolean CHECK = EngineOptions.CHECK_CRAFT_INIT;

    /** Constructor is called by the client and by the integrated server, on different threads. */
    private static final AtomicLong CHECKS = new AtomicLong();

    private static final AtomicLong NON_EMPTY = new AtomicLong();

    private CraftingInit() {}

    /**
     * Replaces this.onCraftMatrixChanged(craftMatrix) at the end of ContainerPlayer's constructor.
     */
    public static void skipInitialCraftCheck(Object container, Object matrix) {
        if (VANILLA) {
            ((ContainerPlayer) container).onCraftMatrixChanged((IInventory) matrix);
            return;
        }
        if (!CHECK) {
            return;
        }
        ((ContainerPlayer) container).onCraftMatrixChanged((IInventory) matrix);
        ItemStack result = ((ContainerPlayer) container).craftResult.getStackInSlot(0);
        long n = CHECKS.incrementAndGet();
        if (result != null) {
            NON_EMPTY.incrementAndGet();
            Log.info("craftinit", "DIVERGENCE: initial grid produced result " + result);
        }
        if (n % REPORT_EVERY == 0) {
            Log.info("craftinit", "%d constructions checked, %d with a result", n, NON_EMPTY.get());
        }
    }
}
