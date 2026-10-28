/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import dev.xavier.stein.bridge.core.Log;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Fix for a 1.8.9 server race that loses a player after a long teleport: a chunk being unloaded
 * never takes a player who is still in the world with it.
 *
 * <p>After a teleport far away, PlayerManager.updateMountedMovingPlayer drops the old area, which
 * queues its chunks for unloading. In the same tick, WorldServer.tick runs unloadQueuedChunks
 * before updateEntities, and the player is still listed in the chunk they stood in before the
 * teleport (an entity changes chunk only in updateEntities). When that chunk is among the ones
 * unloaded, Chunk.onChunkUnload hands its entities to World.unloadEntities and updateEntities then
 * removes the player from loadedEntityList (and from the entity tracker), while the player stays in
 * playerEntities and in the PlayerManager: they are never ticked again and get no more chunks until
 * a dimension change.
 *
 * <p>The patcher sends the unloadEntities call of Chunk.onChunkUnload here; a player who is still
 * in the world's playerEntities is left out of the list, everything else goes to the game in the
 * same order. The player then stays in loadedEntityList and the next updateEntities moves them into
 * the chunk where they are, as for any other move (the old chunk is gone, so there is nothing to
 * remove them from). A player who already left the world (logged out, changed dimension) is no
 * longer in playerEntities and goes through the game's path as before. Players are never saved with
 * a chunk, so the saved chunk is the same. Only the server side can hold an EntityPlayerMP; the
 * client's chunks are untouched. {@code -Dsteinbridge.opt.vanillaPlayerUnload=true} restores the
 * game's behaviour.
 */
public final class PlayerUnload {

    private static final boolean VANILLA = EngineOptions.VANILLA_PLAYER_UNLOAD;

    private PlayerUnload() {}

    /** World.unloadEntities(collection) in Chunk.onChunkUnload. */
    @SuppressWarnings("unchecked")
    public static void unloadEntities(Object worldObj, Object entitiesObj) {
        World world = (World) worldObj;
        Collection<Entity> entities = (Collection<Entity>) entitiesObj;
        if (VANILLA || world.isRemote || !holdsPlayer(world, entities)) {
            world.unloadEntities(entities);
            return;
        }
        List<Entity> others = new ArrayList<>(entities.size());
        for (Entity e : entities) {
            if (inWorld(world, e)) {
                Log.info(
                        "server",
                        "%s stays in the world: the chunk they were in before moving was"
                                + " unloaded (1.8.9 would stop ticking them)",
                        e.getName());
            } else {
                others.add(e);
            }
        }
        world.unloadEntities(others);
    }

    private static boolean holdsPlayer(World world, Collection<Entity> entities) {
        for (Entity e : entities) {
            if (inWorld(world, e)) {
                return true;
            }
        }
        return false;
    }

    private static boolean inWorld(World world, Entity e) {
        return e instanceof EntityPlayerMP && world.playerEntities.contains(e);
    }
}
