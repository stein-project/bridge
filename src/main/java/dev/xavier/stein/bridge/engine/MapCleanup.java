/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EntityRenderer;

/**
 * The maps' textures are let go when the world changes.
 *
 * <p>1.8.9 clears the map textures (one 128x128 texture per map id seen) only when leaving to the
 * menu. Changing worlds — a dimension, or the next server behind a proxy, which reuses map ids —
 * kept every texture: memory that grew with each server visited, and a map showing the previous
 * server's picture until the new server sent its own. Before each Minecraft.loadWorld (rule in
 * redirects-mc.tsv) the textures go; the server sends the maps in view again, as it does on a join.
 */
public final class MapCleanup {
    private MapCleanup() {}

    /** Before Minecraft.loadWorld(world, message). */
    public static void worldChanging() {
        Minecraft mc = Minecraft.getMinecraft();
        EntityRenderer r = mc == null ? null : mc.entityRenderer;
        if (r != null && mc.theWorld != null) {
            r.getMapItemRenderer().clearLoadedMaps();
        }
    }
}
