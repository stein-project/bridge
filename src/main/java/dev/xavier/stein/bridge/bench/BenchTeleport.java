/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.bench;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;

import java.util.List;

/**
 * Moves the camera far in one go through the integrated server, so the player stays in the server's
 * world. When the client simply jumps far (its next position packet), 1.8.9's server drops the old
 * area's chunks in that packet's task, and unloads them at the start of the same tick
 * (unloadQueuedChunks runs before updateEntities); the player is still filed in its old chunk, so
 * Chunk.onChunkUnload takes it out of the world's entity list. From then on nothing ticks it, no
 * chunk is sent, and the part films a void. Here, on the server's thread, the player is put at the
 * new place, the new area is loaded (the same PlayerManager call a move makes), and the player is
 * filed in its new chunk before the old one goes; then the client is told where it is (the packet a
 * /tp sends), and its own moves count again once it answers.
 */
final class BenchTeleport {

    private BenchTeleport() {}

    /** Bumped by every teleport; the server's thread copies it when it has moved the player. */
    private static int asked;

    private static volatile int done;

    private static double tx;
    private static double ty;
    private static double tz;

    /** Moves the player to (x, y, z) facing (yaw, pitch); {@link #arrived} says when. */
    static void to(Minecraft mc, double x, double y, double z, float yaw, float pitch) {
        int id = ++asked;
        tx = x;
        ty = y;
        tz = z;
        BenchCommands.onServer(
                mc,
                () -> {
                    MinecraftServer server = mc.getIntegratedServer();
                    EntityPlayerMP p = player(server);
                    if (p != null) {
                        move(p, x, y, z, yaw, pitch);
                    }
                    done = id;
                });
    }

    /** On the server's thread: the move, without the race described above. */
    static void move(EntityPlayerMP p, double x, double y, double z, float yaw, float pitch) {
        WorldServer w = p.getServerForPlayer();
        p.setPositionAndRotation(x, y, z, yaw, pitch);
        // the new area loaded and the old one dropped (as a move does), then the player filed in
        // its new chunk, before the next tick unloads the old ones
        w.getPlayerManager().updateMountedMovingPlayer(p);
        w.updateEntityWithOptionalForce(p, false);
        p.playerNetServerHandler.setPlayerLocation(x, y, z, yaw, pitch);
    }

    /** The server has moved the player and the client is there too. */
    static boolean arrived(Minecraft mc) {
        if (done != asked) {
            return false;
        }
        if (mc.thePlayer == null) {
            return false;
        }
        double dx = mc.thePlayer.posX - tx;
        double dy = mc.thePlayer.posY - ty;
        double dz = mc.thePlayer.posZ - tz;
        return dx * dx + dy * dy + dz * dz < 4.0;
    }

    /** Whether a teleport is still on its way. */
    static boolean pending() {
        return done != asked;
    }

    /** The integrated server's player (single player: the first one), or null. */
    static EntityPlayerMP player(MinecraftServer server) {
        if (server == null) {
            return null;
        }
        try {
            List<EntityPlayerMP> players = server.getConfigurationManager().playerEntityList;
            return players.isEmpty() ? null : players.get(0);
        } catch (RuntimeException e) {
            return null; // read from another thread while the list changed
        }
    }
}
