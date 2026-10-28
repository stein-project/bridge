/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.bench;

import dev.xavier.stein.bridge.engine.TaskPost;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.BlockPos;

/**
 * Whether the world a part films is really there: the integrated server still ticks the player
 * (when it does not, no chunk is sent and the part would measure a void), the client has chunks,
 * the land around the camera has arrived, and a biome part's camera is over its biome. Read on the
 * render thread once per client tick, except {@link #probe}, which asks the server's thread.
 */
final class BenchGuard {

    private BenchGuard() {}

    /** Server ticks without the player ticking before the part fails (3 s at 20 tps). */
    private static final int STALLED_TICKS = 60;

    /** Client ticks with no chunk on the client before the part fails (10 s). */
    private static final int EMPTY_TICKS = 200;

    private static EntityPlayerMP lastPlayer;
    private static int lastPlayerTicks;
    private static int stalledFrom;
    private static int emptyTicks;

    /** Whether the server's world had the player in its entity list at the last probe (-1: ?). */
    static volatile int inWorld = -1;

    /** A part starts: nothing has been seen yet. */
    static void reset() {
        lastPlayer = null;
        emptyTicks = 0;
    }

    /**
     * After the part's arrival, every client tick: why the part must fail now, or null. The
     * player's tick counter (Entity.ticksExisted, which only World.updateEntities advances) is read
     * from this thread: one int, a moment off at worst.
     */
    static String check(Minecraft mc) {
        MinecraftServer s = mc.getIntegratedServer();
        WorldClient w = mc.theWorld;
        if (s == null || w == null || BenchTeleport.pending()) {
            return null;
        }
        EntityPlayerMP p = BenchTeleport.player(s);
        int serverTick = s.getTickCounter();
        if (p != null) {
            int ticks = p.ticksExisted;
            if (p != lastPlayer || ticks != lastPlayerTicks) {
                lastPlayer = p;
                lastPlayerTicks = ticks;
                stalledFrom = serverTick;
            } else if (serverTick - stalledFrom >= STALLED_TICKS) {
                return "the integrated server stopped ticking the player for "
                        + (serverTick - stalledFrom)
                        + " of its ticks (out of its world's entity list): no chunk would come";
            }
        }
        emptyTicks = w.getChunkProvider().getLoadedChunkCount() == 0 ? emptyTicks + 1 : 0;
        if (emptyTicks >= EMPTY_TICKS) {
            return "no chunk on the client for " + emptyTicks / 20 + " s";
        }
        return null;
    }

    /**
     * Whether every chunk within the render distance (less two) around the camera has reached the
     * client.
     */
    static boolean landArrived(Minecraft mc) {
        WorldClient w = mc.theWorld;
        if (w == null || mc.thePlayer == null) {
            return true;
        }
        int r = Math.max(2, Math.min(mc.gameSettings.renderDistanceChunks, 16) - 2);
        int px = (int) Math.floor(mc.thePlayer.posX) >> 4;
        int pz = (int) Math.floor(mc.thePlayer.posZ) >> 4;
        for (int x = px - r; x <= px + r; x++) {
            for (int z = pz - r; z <= pz + r; z++) {
                if (w.getChunkFromChunkCoords(x, z).isEmpty()) {
                    return false;
                }
            }
        }
        return true;
    }

    /** The biome under the camera, from the client's chunks (-1: no world or no chunk). */
    static int biomeUnder(Minecraft mc) {
        WorldClient w = mc.theWorld;
        if (w == null || mc.thePlayer == null) {
            return -1;
        }
        BlockPos at = new BlockPos(mc.thePlayer.posX, 64, mc.thePlayer.posZ);
        if (w.getChunkFromBlockCoords(at).isEmpty()) {
            return -1;
        }
        return w.getBiomeGenForCoords(at).biomeID;
    }

    /** The biome's name, for the report. */
    static String biomeName(int id) {
        net.minecraft.world.biome.BiomeGenBase b =
                id < 0 ? null : net.minecraft.world.biome.BiomeGenBase.getBiome(id);
        if (id < 0) {
            return "no land (no chunk on the client under the camera)";
        }
        return b == null ? "biome " + id : b.biomeName;
    }

    /**
     * The player's tick counter on the server, read from any thread (-1 without one): the sampler
     * writes how far it moved in a second.
     */
    static int playerTicks(MinecraftServer s) {
        EntityPlayerMP p = BenchTeleport.player(s);
        return p == null ? -1 : p.ticksExisted;
    }

    /**
     * Asks the server's thread whether its world has the player in its entity list ({@link
     * #inWorld} holds the answer by the next sample). The list is only read there.
     */
    static void probe(MinecraftServer s) {
        if (s == null) {
            inWorld = -1;
            return;
        }
        TaskPost.toServer(
                s,
                (Runnable)
                        () -> {
                            EntityPlayerMP p = BenchTeleport.player(s);
                            inWorld =
                                    p == null
                                            ? -1
                                            : p.getServerForPlayer().loadedEntityList.contains(p)
                                                    ? 1
                                                    : 0;
                        });
    }
}
