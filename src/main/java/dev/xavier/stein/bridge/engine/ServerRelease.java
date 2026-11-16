/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import net.minecraft.server.MinecraftServer;

/**
 * Fix for the stopped integrated server staying on the heap: MinecraftServer's static instance is
 * cleared when that server's thread finishes.
 *
 * <p>MinecraftServer keeps the last server created in a static field (what getServer returns), and
 * 1.8.9 never clears it: after leaving a single player world, the stopped integrated server, its
 * worlds, chunks, entities and player list stayed reachable until the next world was opened, a few
 * hundred MB on a large world. Mojang removed the static instance in 1.9. The patcher calls {@link
 * #stopped} after each systemExitNow at the end of MinecraftServer.run, the last thing the server
 * thread does; the field is cleared only if it still holds the server of this thread (a new world
 * opened meanwhile already put its own there). Minecraft.loadWorld(null) puts the stopping server
 * back in the field (setStaticInstance) right after asking it to stop; its thread finishes only
 * after saving the worlds, and clears it then.
 *
 * <p>The game's client code already copes with no server: in multiplayer the field was always null
 * (TileEntitySkull.updateGameprofile, ChatComponentScore, ItemBlock.setTileEntityNBT and HttpUtil
 * test it), and the server's own code only runs while its thread is alive. The Bridge, the Loader
 * and the mods reach the integrated server through Minecraft.getIntegratedServer or a world's
 * server, never through the static field.
 */
public final class ServerRelease {

    private ServerRelease() {}

    /** After systemExitNow in MinecraftServer.run, on the server thread. */
    public static void stopped() {
        MinecraftServer server = MinecraftServer.mcServer;
        if (server != null && server.serverThread == Thread.currentThread()) {
            MinecraftServer.mcServer = null;
        }
    }
}
