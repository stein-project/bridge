/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

package dev.xavier.stein.bridge.engine;

import dev.xavier.stein.bridge.core.Log;

import net.minecraft.client.renderer.entity.layers.LayerArmorBase;
import net.minecraft.item.ItemArmor;

import java.util.IdentityHashMap;

/**
 * LayerArmorBase.getArmorResource without the String.format on every piece.
 *
 * <p>For each armor piece of each entity on every frame, the game builds the path
 * "textures/models/armor/%s_layer_%d%s.png" with String.format — only to look up, in its map, the
 * ResourceLocation, which is always the same for the same material, layer and overlay. In a crowd
 * (400 armored players) this was ~4% of the render thread. Here the ResourceLocation that the game
 * returned is cached per item, layer and overlay (an ItemArmor's material does not change): the
 * same object, without the formatting.
 *
 * <p>{@code -Dsteinbridge.opt.vanillaArmorTexture=true}: the game's own computation on every piece.
 */
public final class ArmorTextures {

    private static final boolean VANILLA = EngineOptions.VANILLA_ARMOR_TEXTURE;

    static {
        if (VANILLA) {
            Log.info("armor", "armor texture built for every piece (vanilla)");
        }
    }

    /**
     * item -> {layer 1, layer 2, layer 1 with overlay, layer 2 with overlay}. Only the render
     * thread uses it.
     */
    private static final IdentityHashMap<Object, Object[]> CACHE = new IdentityHashMap<>();

    private static final String OVERLAY = "overlay";

    private ArmorTextures() {}

    /** getArmorResource(item, leggings) — or with the "overlay" overlay (dyed leather). */
    public static Object resource(Object layer, Object item, boolean legs, String overlay) {
        if (VANILLA || (overlay != null && !overlay.equals(OVERLAY))) {
            return original(layer, item, legs, overlay);
        }
        Object[] slots = CACHE.get(item);
        if (slots == null) {
            slots = new Object[4];
            CACHE.put(item, slots);
        }
        int i = (legs ? 1 : 0) + (overlay != null ? 2 : 0);
        Object r = slots[i];
        if (r == null) {
            r = original(layer, item, legs, overlay);
            slots[i] = r;
        }
        return r;
    }

    private static Object original(Object layer, Object item, boolean legs, String overlay) {
        LayerArmorBase base = (LayerArmorBase) layer;
        return overlay == null
                ? base.getArmorResource((ItemArmor) item, legs)
                : base.getArmorResource((ItemArmor) item, legs, overlay);
    }
}
