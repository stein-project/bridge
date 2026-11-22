/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiIngame;

/**
 * Fixes around changing worlds: the forced garbage collection of each change, and the title left
 * over from the previous server.
 *
 * <ul>
 *   <li>Minecraft.loadWorld ends with System.gc(), and launchIntegratedServer calls it once more: a
 *       full collection on the game thread at every dimension change, every server behind a proxy
 *       and every single player start, which with a heap of a few gigabytes froze the game for a
 *       noticeable moment each time (the game waits for the whole collection). The collector frees
 *       the old world on its own when it needs the memory, and the leaks that kept old worlds alive
 *       are fixed elsewhere ({@link MapCleanup}, {@link ServerRelease}, {@link TorchToggles}). Both
 *       calls are left out; {@code -Dsteinbridge.opt.vanillaWorldGc=true} brings them back. The
 *       calls of the shutdown and of the out of memory screen stay.
 *   <li>A title (and its subtitle) still on screen when the join packet arrives stayed on the next
 *       server: behind a proxy, the lobby's title showed over the game that had just started. The
 *       join (NetHandlerPlayClient.handleJoinGame) now clears it and its times, as the title reset
 *       packet does. {@code -Dsteinbridge.opt.vanillaJoinTitles=true} keeps it.
 * </ul>
 */
public final class WorldSwitch {

    private WorldSwitch() {}

    private static final boolean VANILLA_GC = NetFixOptions.VANILLA_WORLD_GC;

    private static final boolean VANILLA_TITLES = NetFixOptions.VANILLA_JOIN_TITLES;

    /** System.gc() in Minecraft.loadWorld and launchIntegratedServer. */
    public static void gc() {
        if (VANILLA_GC) {
            System.gc();
        }
    }

    /** Before Minecraft.loadWorld in NetHandlerPlayClient.handleJoinGame. */
    public static void joining() {
        if (VANILLA_TITLES) {
            return;
        }
        GuiIngame hud = Minecraft.getMinecraft().ingameGUI;
        if (hud != null) {
            hud.displayTitle(null, null, -1, -1, -1);
            hud.setDefaultTitlesTimes();
        }
    }
}
