/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import dev.xavier.stein.bridge.core.Log;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.ITextureObject;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/**
 * Glowing blocks (the Lens option): the textures that give light — the torch's flame, glowstone,
 * the sea lantern, the lit lamp, lava, fire, the portal, the beacon — shine above white, so in HDR
 * they are brighter than anything lit, and the bloom puts a halo around them.
 *
 * <p>Which texels glow is a mask the size of the blocks' atlas ({@code R8}), made from the sprites'
 * names whenever the atlas is stitched again (a resource pack): the terrain shader reads it at the
 * texel's own coordinate, so the chunk's vertex format does not change. The code in the mask: 1 the
 * bright part of the texture glows (a torch's flame, not its stick), 2 the whole texture, 3 lava
 * (pulsing), 4 fire (flickering).
 *
 * <p>The option also raises the block light right next to a source ("intense"): the level the light
 * ends at is the game's, so it does not reach farther.
 */
public final class SteinGlow {

    /** The sprites and their codes. */
    private static final String[] NAMES = {
        "torch_on",
        "redstone_torch_on",
        "furnace_front_on",
        "pumpkin_face_on",
        "beacon",
        "glowstone",
        "sea_lantern",
        "redstone_lamp_on",
        "portal",
        "lava_still",
        "lava_flow",
        "fire_layer_0",
        "fire_layer_1",
        "end_portal_frame_eye"
    };

    private static final int[] CODES = {1, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 4, 4, 1};

    private static volatile int level;
    private static SteinTexture mask;
    private static SteinTexture none;
    private static SteinSampler nearest;

    /** The sprite the mask was made for: a new one after a reload. */
    private static TextureAtlasSprite madeFor;

    private static boolean failed;

    /** The textures drawn past white (game texture names; 0 not loaded yet). */
    private static int sunTexture;

    private static int moonTexture;
    private static int beamTexture;

    /** Their strength this frame (1: as the game draws them). */
    private static float sunBoost = 1f;

    private static float moonBoost = 1f;
    private static float beamBoost = 1f;

    private static final ResourceLocation SUN =
            new ResourceLocation("textures/environment/sun.png");
    private static final ResourceLocation MOON =
            new ResourceLocation("textures/environment/moon_phases.png");
    private static final ResourceLocation BEAM =
            new ResourceLocation("textures/entity/beacon_beam.png");

    private SteinGlow() {}

    /**
     * How much brighter than the game a dynamic draw with the texture {@code tex} goes (the
     * per-draw colour, past white in the 16-bit scene): the sun blinds, the moon shines, the
     * beacon's beam burns. Hot path: three comparisons.
     */
    static float boost(int tex) {
        return tex == sunTexture
                ? sunBoost
                : tex == moonTexture ? moonBoost : tex == beamTexture ? beamBoost : 1f;
    }

    /** Once per world frame: the textures' names and the strengths for the frame. */
    static void frame() {
        boolean wide = SteinPostFx.wideScene();
        sunBoost = wide ? 5f : 1f;
        moonBoost = wide ? 3.5f : 1f;
        beamBoost = wide && level() > 0 ? (level() == 2 ? 4.5f : 3f) : 1f;
        TextureManager tm = Minecraft.getMinecraft().getTextureManager();
        sunTexture = name(tm, SUN);
        moonTexture = name(tm, MOON);
        beamTexture = name(tm, BEAM);
    }

    private static int name(TextureManager tm, ResourceLocation r) {
        ITextureObject t = tm.getTexture(r);
        return t == null ? 0 : t.getGlTextureId();
    }

    /** The Lens level: 0 off, 1 on, 2 intense (any thread). */
    public static void configure(int glowLevel) {
        level = Math.max(0, Math.min(2, glowLevel));
    }

    static int level() {
        return failed ? 0 : level;
    }

    /** The mask to sample now (the stand-in when off), made again after a reload. */
    static SteinTexture mask(SteinDevice dev) {
        if (none == null) {
            none =
                    dev.createTexture(
                            SteinTexture.Desc.texture2D("glow: none", SteinFormat.R8, 1, 1, 1));
            ByteBuffer b = MemoryUtil.memAlloc(1);
            try {
                b.put(0, (byte) 0);
                none.upload(0, 0, 0, 0, 1, 1, b);
            } finally {
                MemoryUtil.memFree(b);
            }
            nearest = dev.createSampler(SteinSampler.Desc.nearestClamp());
        }
        if (level() == 0) {
            return none;
        }
        TextureMap atlas = Minecraft.getMinecraft().getTextureMapBlocks();
        TextureAtlasSprite probe = atlas.getAtlasSprite("minecraft:blocks/torch_on");
        if (probe != madeFor) {
            try {
                make(dev, atlas);
                madeFor = probe;
            } catch (RuntimeException | LinkageError e) {
                failed = true;
                Log.warn("gfx", "glowing blocks turned off for this session: " + e);
                return none;
            }
        }
        return mask != null ? mask : none;
    }

    static SteinSampler sampler() {
        return nearest;
    }

    /** The mask from the sprites' places in the atlas. */
    private static void make(SteinDevice dev, TextureMap atlas) {
        TextureAtlasSprite missing = atlas.getMissingSprite();
        int w = 0;
        int h = 0;
        TextureAtlasSprite[] found = new TextureAtlasSprite[NAMES.length];
        for (int i = 0; i < NAMES.length; i++) {
            TextureAtlasSprite s = atlas.getAtlasSprite("minecraft:blocks/" + NAMES[i]);
            if (s == null || s == missing) {
                continue;
            }
            found[i] = s;
            // the atlas' size from a sprite's place: minU = originX / width
            if (w == 0 && s.getOriginX() > 0 && s.getMinU() > 0f) {
                w = Math.round(s.getOriginX() / s.getMinU());
            }
            if (h == 0 && s.getOriginY() > 0 && s.getMinV() > 0f) {
                h = Math.round(s.getOriginY() / s.getMinV());
            }
        }
        if (w <= 0 || h <= 0 || w > 16384 || h > 16384) {
            return; // not known yet (every probe at the corner): the next reload
        }
        if (mask != null) {
            mask.destroy();
        }
        mask = dev.createTexture(SteinTexture.Desc.texture2D("glow mask", SteinFormat.R8, w, h, 1));
        ByteBuffer b = MemoryUtil.memCalloc(w * h);
        try {
            for (int i = 0; i < NAMES.length; i++) {
                TextureAtlasSprite s = found[i];
                if (s == null) {
                    continue;
                }
                byte code = (byte) (CODES[i] * 32);
                int x0 = s.getOriginX();
                int y0 = s.getOriginY();
                for (int y = y0; y < Math.min(h, y0 + s.getIconHeight()); y++) {
                    for (int x = x0; x < Math.min(w, x0 + s.getIconWidth()); x++) {
                        b.put(y * w + x, code);
                    }
                }
            }
            mask.upload(0, 0, 0, 0, w, h, b);
        } finally {
            MemoryUtil.memFree(b);
        }
        Log.info("gfx", "glowing blocks: mask " + w + "x" + h);
    }
}
