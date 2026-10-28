/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.EntitySelectors;
import net.minecraft.world.World;

import java.util.List;

/**
 * World.getClosestPlayer and World.isAnyPlayerWithinRangeAt with the distance tested before the
 * spectator mode.
 *
 * <p>Both walk every player in the world and ask NOT_SPECTATING first. On the client, a remote
 * player's isSpectator looks its tab-list entry up by UUID in a HashMap, so every mob spawner and
 * enchanting table cost one hash lookup per player per tick: with a crowd, 4% of the client tick.
 * Here the distance (plain arithmetic) comes first and the spectator question is only asked of a
 * player who would be taken.
 *
 * <p>Why it is exact: both tests are free of side effects and a player is taken only when both
 * pass, so their order cannot change which player is returned, or whether one is; the loop, the
 * comparisons (strict, so the first of two at the same distance wins) and the -1 conventions are
 * the game's. {@code -Dsteinbridge.opt.vanillaPlayerRange=true}: the game's order.
 */
public final class PlayerRange {

    private static final boolean VANILLA = EngineOptions.VANILLA_PLAYER_RANGE;

    private PlayerRange() {}

    /** The body of World.getClosestPlayer(x, y, z, range). */
    public static Object closest(Object worldObj, double x, double y, double z, double range) {
        List<EntityPlayer> players = ((World) worldObj).playerEntities;
        double best = -1.0;
        EntityPlayer found = null;
        for (int i = 0; i < players.size(); i++) {
            EntityPlayer p = players.get(i);
            if (VANILLA && !EntitySelectors.NOT_SPECTATING.apply(p)) {
                continue;
            }
            double d = p.getDistanceSq(x, y, z);
            if ((range < 0.0 || d < range * range)
                    && (best == -1.0 || d < best)
                    && (VANILLA || EntitySelectors.NOT_SPECTATING.apply(p))) {
                best = d;
                found = p;
            }
        }
        return found;
    }

    /** The body of World.isAnyPlayerWithinRangeAt(x, y, z, range). */
    public static boolean anyWithin(Object worldObj, double x, double y, double z, double range) {
        List<EntityPlayer> players = ((World) worldObj).playerEntities;
        for (int i = 0; i < players.size(); i++) {
            EntityPlayer p = players.get(i);
            if (VANILLA && !EntitySelectors.NOT_SPECTATING.apply(p)) {
                continue;
            }
            double d = p.getDistanceSq(x, y, z);
            if ((range < 0.0 || d < range * range)
                    && (VANILLA || EntitySelectors.NOT_SPECTATING.apply(p))) {
                return true;
            }
        }
        return false;
    }
}
