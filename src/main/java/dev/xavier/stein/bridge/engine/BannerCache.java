/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ResourceLocation;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Map;

/**
 * Fix for banners that stop being drawn once many designs were seen: the banner texture cache makes
 * room by dropping the design drawn longest ago.
 *
 * <p>TileEntityBannerRenderer keeps one generated texture per banner design, at most 256. When a
 * new design came with the cache full, 1.8.9 dropped only the designs not drawn for a whole minute;
 * if none was that old, the new banner was not drawn at all. Walking through a lobby or a map with
 * many different banners made the next ones invisible for up to a minute. Here, with the cache
 * full, the design drawn longest ago makes room as long as it was not drawn in the last second (one
 * not on screen now); with 256 designs all on screen at once nothing changes, which keeps the
 * textures of what is in view from being rebuilt every frame. The dropped texture is deleted from
 * the TextureManager, as the game's own cleanup does.
 *
 * <p>The patcher sends the cache's two size checks in TileEntityBannerRenderer's texture lookup
 * here (redirects-mc.tsv); below 256 designs they only return the size. {@code
 * -Dsteinbridge.opt.vanillaBannerCache=true} brings back 1.8.9's minute.
 */
public final class BannerCache {

    private BannerCache() {}

    private static final boolean VANILLA = RenderFixOptions.VANILLA_BANNER_CACHE;

    /** The game's cache size. */
    private static final int LIMIT = 256;

    /** A design drawn this recently is on screen: it is not dropped. */
    private static final long IN_VIEW_MS = 1000L;

    // The cache entry class (TileEntityBannerRenderer.TimedBannerTexture) is a nested class the
    // game keeps closed, so it cannot be named even in the readable jar: its two public fields are
    // reached by VarHandle, by their names in the real game ("a" the time, "b" the texture).
    private static VarHandle time;
    private static VarHandle texture;

    /** Map.size() of the design cache in TileEntityBannerRenderer's texture lookup. */
    public static int size(Object designs) {
        Map<?, ?> map = (Map<?, ?>) designs;
        int n = map.size();
        if (VANILLA || n < LIMIT) {
            return n;
        }
        dropOldest(map);
        return map.size();
    }

    private static void dropOldest(Map<?, ?> map) {
        long now = System.currentTimeMillis();
        Object oldestKey = null;
        Object oldest = null;
        long oldestTime = Long.MAX_VALUE;
        for (Map.Entry<?, ?> e : map.entrySet()) {
            Object entry = e.getValue();
            bind(entry);
            long t = (long) time.get(entry);
            if (t < oldestTime) {
                oldestTime = t;
                oldestKey = e.getKey();
                oldest = entry;
            }
        }
        if (oldest == null || now - oldestTime < IN_VIEW_MS) {
            return;
        }
        ResourceLocation location = (ResourceLocation) texture.get(oldest);
        Minecraft.getMinecraft().getTextureManager().deleteTexture(location);
        map.remove(oldestKey);
    }

    private static void bind(Object entry) {
        if (time == null) {
            try {
                Class<?> c = entry.getClass();
                MethodHandles.Lookup l = MethodHandles.privateLookupIn(c, MethodHandles.lookup());
                texture = l.findVarHandle(c, "b", ResourceLocation.class);
                time = l.findVarHandle(c, "a", long.class);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("banner cache entry", e);
            }
        }
    }
}
