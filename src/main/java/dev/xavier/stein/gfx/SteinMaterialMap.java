/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import dev.xavier.stein.bridge.core.Log;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;

import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * What each texel of the blocks' atlas is made of, for the Stein mode (stage 2 of
 * docs/stein-mode-plan.md): a map the size of the atlas, RGBA8 — r smoothness (0 rough, 1 a
 * mirror), g metal, b its own light, a light through (leaves, plants). Made from the player's own
 * textures whenever the atlas is stitched (any resource pack): a table of ours by the texture's
 * name gives the material, and the texels themselves say where it applies — in an ore only the gems
 * or the metal shine, the stone around stays rough; on a metal or gem block the bright texels shine
 * more; in a torch only the flame gives light. No relief: the texture stays as drawn, only how it
 * takes the light changes.
 *
 * <p>Only made when the session has the Stein mode ({@link SteinRayLight#capable}); the texels are
 * caught as the atlas uploads them ({@link SteinOpaqueCutout#uploadMipmap}). Pipeline: Stein GFX on
 * Vulkan.
 */
public final class SteinMaterialMap {

    /** How a material is spread over its texture's texels. */
    private enum Spread {
        /** The same everywhere. */
        ALL,
        /** Brighter texels more (metal and gem blocks). */
        BRIGHT,
        /** Only the texels unlike the stone around (ores); the rest is stone. */
        ORE,
        /** Its own light only on the bright texels (a torch's flame, a lit furnace's fire). */
        GLOW_BRIGHT
    }

    /** A material: smoothness, metal, own light, light through (0..1), and its spread. */
    private record Material(float smooth, float metal, float emit, float through, Spread spread) {}

    private static final Material DEFAULT = new Material(0.1f, 0f, 0f, 0f, Spread.ALL);
    private static final Material STONE = new Material(0.12f, 0f, 0f, 0f, Spread.ALL);

    /** By the texture's name (after "blocks/"): the first rule it starts with. */
    private static final Object[][] RULES = {
        // ores: the gems or the metal in the stone
        {"diamond_ore", new Material(0.92f, 0f, 0f, 0f, Spread.ORE)},
        {"emerald_ore", new Material(0.9f, 0f, 0f, 0f, Spread.ORE)},
        {"lapis_ore", new Material(0.55f, 0f, 0f, 0f, Spread.ORE)},
        {"redstone_ore", new Material(0.6f, 0f, 0f, 0f, Spread.ORE)},
        {"gold_ore", new Material(0.75f, 1f, 0f, 0f, Spread.ORE)},
        {"iron_ore", new Material(0.55f, 0.8f, 0f, 0f, Spread.ORE)},
        {"coal_ore", new Material(0.5f, 0f, 0f, 0f, Spread.ORE)},
        {"quartz_ore", new Material(0.75f, 0f, 0f, 0f, Spread.ORE)},
        // metal and gem blocks
        {"diamond_block", new Material(0.88f, 0f, 0f, 0f, Spread.BRIGHT)},
        {"emerald_block", new Material(0.85f, 0f, 0f, 0f, Spread.BRIGHT)},
        {"lapis_block", new Material(0.5f, 0f, 0f, 0f, Spread.BRIGHT)},
        {"gold_block", new Material(0.85f, 1f, 0f, 0f, Spread.BRIGHT)},
        {"iron_block", new Material(0.75f, 1f, 0f, 0f, Spread.BRIGHT)},
        {"redstone_block", new Material(0.5f, 0f, 0f, 0f, Spread.BRIGHT)},
        {"coal_block", new Material(0.35f, 0f, 0f, 0f, Spread.ALL)},
        {"iron_bars", new Material(0.6f, 0.9f, 0f, 0f, Spread.ALL)},
        {"anvil", new Material(0.5f, 0.85f, 0f, 0f, Spread.ALL)},
        {"cauldron", new Material(0.45f, 0.7f, 0f, 0f, Spread.ALL)},
        {"hopper", new Material(0.45f, 0.7f, 0f, 0f, Spread.ALL)},
        {"door_iron", new Material(0.55f, 0.85f, 0f, 0f, Spread.ALL)},
        {"iron_trapdoor", new Material(0.55f, 0.85f, 0f, 0f, Spread.ALL)},
        {"rail", new Material(0.5f, 0.6f, 0f, 0f, Spread.BRIGHT)},
        // glass, ice and polished stone
        {"glass", new Material(0.95f, 0f, 0f, 0f, Spread.ALL)},
        {"ice_packed", new Material(0.78f, 0f, 0f, 0f, Spread.ALL)},
        {"ice", new Material(0.9f, 0f, 0f, 0f, Spread.ALL)},
        {"obsidian", new Material(0.78f, 0f, 0f, 0f, Spread.ALL)},
        {"quartz_block", new Material(0.6f, 0f, 0f, 0f, Spread.ALL)},
        {"prismarine", new Material(0.45f, 0f, 0f, 0f, Spread.ALL)},
        {"sea_lantern", new Material(0.6f, 0f, 1f, 0f, Spread.ALL)},
        {"slime", new Material(0.7f, 0f, 0f, 0.3f, Spread.ALL)},
        {"hardened_clay", new Material(0.3f, 0f, 0f, 0f, Spread.ALL)},
        {"stone_slab", new Material(0.25f, 0f, 0f, 0f, Spread.ALL)},
        {"stonebrick", new Material(0.15f, 0f, 0f, 0f, Spread.ALL)},
        {"brick", new Material(0.2f, 0f, 0f, 0f, Spread.ALL)},
        {"nether_brick", new Material(0.25f, 0f, 0f, 0f, Spread.ALL)},
        {"clay", new Material(0.3f, 0f, 0f, 0f, Spread.ALL)},
        {"snow", new Material(0.2f, 0f, 0f, 0f, Spread.ALL)},
        {"cobblestone", new Material(0.05f, 0f, 0f, 0f, Spread.ALL)},
        {"stone", new Material(0.18f, 0f, 0f, 0f, Spread.ALL)},
        // light givers
        {"glowstone", new Material(0.3f, 0f, 1f, 0f, Spread.ALL)},
        {"lava", new Material(0.2f, 0f, 1f, 0f, Spread.ALL)},
        {"redstone_lamp_on", new Material(0.4f, 0f, 1f, 0f, Spread.ALL)},
        {"beacon", new Material(0.9f, 0f, 0.8f, 0f, Spread.ALL)},
        {"portal", new Material(0.5f, 0f, 1f, 0.5f, Spread.ALL)},
        {"fire_layer", new Material(0f, 0f, 1f, 0f, Spread.ALL)},
        {"torch_on", new Material(0.1f, 0f, 1f, 0f, Spread.GLOW_BRIGHT)},
        {"redstone_torch_on", new Material(0.1f, 0f, 1f, 0f, Spread.GLOW_BRIGHT)},
        {"furnace_front_on", new Material(0.15f, 0f, 1f, 0f, Spread.GLOW_BRIGHT)},
        {"pumpkin_face_on", new Material(0.2f, 0f, 1f, 0f, Spread.GLOW_BRIGHT)},
        {"end_portal_frame_eye", new Material(0.8f, 0f, 0.6f, 0f, Spread.GLOW_BRIGHT)},
        // wood, cloth, soil
        {"planks", new Material(0.22f, 0f, 0f, 0f, Spread.ALL)},
        {"log", new Material(0.08f, 0f, 0f, 0f, Spread.ALL)},
        {"bookshelf", new Material(0.2f, 0f, 0f, 0f, Spread.ALL)},
        {"wool", new Material(0f, 0f, 0f, 0f, Spread.ALL)},
        {"sand", new Material(0.06f, 0f, 0f, 0f, Spread.ALL)},
        {"gravel", new Material(0.05f, 0f, 0f, 0f, Spread.ALL)},
        {"dirt", new Material(0.02f, 0f, 0f, 0f, Spread.ALL)},
        {"farmland", new Material(0.04f, 0f, 0f, 0f, Spread.ALL)},
        {"soul_sand", new Material(0.02f, 0f, 0f, 0f, Spread.ALL)},
        {"netherrack", new Material(0.05f, 0f, 0f, 0f, Spread.ALL)},
        {"end_stone", new Material(0.1f, 0f, 0f, 0f, Spread.ALL)},
        {"mycelium", new Material(0.04f, 0f, 0f, 0f, Spread.ALL)},
        // plants: the sun comes through; small plants (light through exactly 0.45) cast no shadow
        // in the
        // Stein mode's sun rays (ray_world.glsl, sunBlocked)
        {"leaves", new Material(0.15f, 0f, 0f, 0.6f, Spread.ALL)},
        {"grass_top", new Material(0.1f, 0f, 0f, 0.25f, Spread.ALL)},
        {"grass_side", new Material(0.06f, 0f, 0f, 0.15f, Spread.ALL)},
        {"tallgrass", new Material(0.1f, 0f, 0f, 0.45f, Spread.ALL)},
        {"fern", new Material(0.1f, 0f, 0f, 0.45f, Spread.ALL)},
        {"double_plant", new Material(0.1f, 0f, 0f, 0.45f, Spread.ALL)},
        {"flower", new Material(0.12f, 0f, 0f, 0.45f, Spread.ALL)},
        {"sapling", new Material(0.1f, 0f, 0f, 0.45f, Spread.ALL)},
        {"vine", new Material(0.1f, 0f, 0f, 0.5f, Spread.ALL)},
        {"reeds", new Material(0.15f, 0f, 0f, 0.45f, Spread.ALL)},
        {"waterlily", new Material(0.35f, 0f, 0f, 0.35f, Spread.ALL)},
        {"wheat", new Material(0.08f, 0f, 0f, 0.45f, Spread.ALL)},
        {"carrots", new Material(0.08f, 0f, 0f, 0.45f, Spread.ALL)},
        {"potatoes", new Material(0.08f, 0f, 0f, 0.45f, Spread.ALL)},
        {"cactus", new Material(0.25f, 0f, 0f, 0.15f, Spread.ALL)},
        {"melon", new Material(0.3f, 0f, 0f, 0f, Spread.ALL)},
        {"pumpkin", new Material(0.3f, 0f, 0f, 0f, Spread.ALL)},
        {"mushroom", new Material(0.2f, 0f, 0f, 0.2f, Spread.ALL)},
    };

    /** While stitching: each uploaded texture's texels (level 0), by its origin in the atlas. */
    private static Map<Long, int[]> caught;

    /** Made on the game thread, uploaded on the next use. */
    private static ByteBuffer pending;

    private static int pendingW;
    private static int pendingH;

    private static SteinTexture map;
    private static SteinTexture none;
    private static SteinSampler nearest;
    private static boolean failed;

    private SteinMaterialMap() {}

    // ---- the atlas (game thread, on every resource reload) ----

    /** Before the block atlas is stitched. */
    static void beginStitch() {
        caught = SteinRayLight.capable() ? new HashMap<>() : null;
    }

    /** A texture going up at {@code x, y}: its texels are kept for {@link #endStitch}. */
    static void uploaded(int[][] levels, int width, int height, int x, int y) {
        Map<Long, int[]> c = caught;
        if (c != null && levels != null && levels.length > 0 && levels[0] != null) {
            int[] copy = levels[0].clone();
            c.put(((long) x << 32) | (y & 0xFFFFFFFFL), copy);
            c.put(-1L - (((long) x << 32) | (y & 0xFFFFFFFFL)), new int[] {width, height});
        }
    }

    /** After the block atlas is stitched: the map from the sprites' names and texels. */
    static void endStitch(TextureMap atlas) {
        Map<Long, int[]> c = caught;
        caught = null;
        if (c == null) {
            return;
        }
        try {
            build(atlas, c);
        } catch (RuntimeException e) {
            Log.error("gfx", "Stein materials: map not made (atlas not understood)", e);
        }
    }

    private static void build(TextureMap atlas, Map<Long, int[]> texels) {
        TextureAtlasSprite[] sprites =
                atlas.mapUploadedSprites.values().toArray(new TextureAtlasSprite[0]);
        int w = 0;
        int h = 0;
        for (TextureAtlasSprite t : sprites) {
            if (t.getIconWidth() > 0 && t.getMaxU() > t.getMinU() && t.getMaxV() > t.getMinV()) {
                // initSprite insets each side by 0.01 texel
                w = Math.round((t.getIconWidth() - 0.02f) / (t.getMaxU() - t.getMinU()));
                h = Math.round((t.getIconHeight() - 0.02f) / (t.getMaxV() - t.getMinV()));
                break;
            }
        }
        if (w <= 0 || h <= 0 || w > 16384 || h > 16384) {
            return;
        }
        ByteBuffer b = MemoryUtil.memCalloc(w * h * 4);
        int made = 0;
        for (TextureAtlasSprite t : sprites) {
            int ox = t.getOriginX();
            int oy = t.getOriginY();
            int sw = t.getIconWidth();
            int sh = t.getIconHeight();
            long key = ((long) ox << 32) | (oy & 0xFFFFFFFFL);
            int[] px = texels.get(key);
            int[] size = texels.get(-1L - key);
            String name = t.getIconName();
            int slash = name.lastIndexOf('/');
            Material m = of(slash >= 0 ? name.substring(slash + 1) : name);
            fill(b, w, h, ox, oy, sw, sh, px, size == null ? sw : size[0], m);
            made++;
        }
        synchronized (SteinMaterialMap.class) {
            if (pending != null) {
                MemoryUtil.memFree(pending);
            }
            pending = b;
            pendingW = w;
            pendingH = h;
        }
        Log.info("gfx", "Stein materials: map " + w + "x" + h + ", " + made + " textures");
    }

    private static Material of(String name) {
        for (Object[] r : RULES) {
            if (name.startsWith((String) r[0])) {
                return (Material) r[1];
            }
        }
        return DEFAULT;
    }

    /** One texture's texels into the map. {@code px}: its texels (row {@code stride}), or null. */
    private static void fill(
            ByteBuffer b,
            int w,
            int h,
            int ox,
            int oy,
            int sw,
            int sh,
            int[] px,
            int stride,
            Material m) {
        // the texture's typical brightness (the median) and its brightest, for the spreads
        float median = 0.5f;
        float top = 1f;
        if (px != null && m.spread != Spread.ALL) {
            float[] lum = new float[sw * sh];
            int n = 0;
            for (int y = 0; y < sh; y++) {
                for (int x = 0; x < sw; x++) {
                    int i = y * stride + x;
                    if (i < px.length && (px[i] >>> 24) > 16) {
                        lum[n++] = luminance(px[i]);
                    }
                }
            }
            if (n > 0) {
                java.util.Arrays.sort(lum, 0, n);
                median = lum[n / 2];
                top = Math.max(lum[n - 1], median + 1e-3f);
            }
        }
        for (int y = 0; y < sh && oy + y < h; y++) {
            for (int x = 0; x < sw && ox + x < w; x++) {
                int i = y * stride + x;
                int argb = px != null && i < px.length ? px[i] : 0xFFFFFFFF;
                Material t = texel(m, argb, median, top);
                int at = ((oy + y) * w + ox + x) * 4;
                b.put(at, unorm(t.smooth));
                b.put(at + 1, unorm(t.metal));
                b.put(at + 2, unorm(t.emit));
                b.put(at + 3, unorm(t.through));
            }
        }
    }

    /** The material at one texel. */
    private static Material texel(Material m, int argb, float median, float top) {
        float lum = luminance(argb);
        switch (m.spread) {
            case BRIGHT -> {
                float k = clamp((lum - median * 0.6f) / Math.max(top - median * 0.6f, 1e-3f));
                return new Material(
                        m.smooth * (0.55f + 0.45f * k), m.metal, m.emit, m.through, m.spread);
            }
            case ORE -> {
                // unlike the stone: coloured, or far from its brightness
                float sat = saturation(argb);
                boolean ore = sat > 0.22f || Math.abs(lum - median) > 0.22f;
                return ore ? m : STONE;
            }
            case GLOW_BRIGHT -> {
                float k = clamp((lum - 0.55f) / 0.3f);
                return new Material(m.smooth, m.metal, m.emit * k, m.through, m.spread);
            }
            default -> {
                return m;
            }
        }
    }

    private static float luminance(int argb) {
        float r = (argb >> 16 & 255) / 255f;
        float g = (argb >> 8 & 255) / 255f;
        float bl = (argb & 255) / 255f;
        return 0.2126f * r + 0.7152f * g + 0.0722f * bl;
    }

    private static float saturation(int argb) {
        float r = (argb >> 16 & 255) / 255f;
        float g = (argb >> 8 & 255) / 255f;
        float bl = (argb & 255) / 255f;
        float max = Math.max(r, Math.max(g, bl));
        float min = Math.min(r, Math.min(g, bl));
        return max <= 0f ? 0f : (max - min) / max;
    }

    private static float clamp(float v) {
        return v < 0f ? 0f : v > 1f ? 1f : v;
    }

    private static byte unorm(float v) {
        return (byte) Math.round(clamp(v) * 255f);
    }

    // ---- drawing (render thread) ----

    /** The map for the terrain's G-buffer (a 1x1 default until the atlas has one). */
    static SteinTexture texture(SteinDevice dev) {
        if (none == null) {
            none =
                    dev.createTexture(
                            SteinTexture.Desc.texture2D(
                                    "stein materials none", SteinFormat.RGBA8, 1, 1, 1));
            ByteBuffer d = MemoryUtil.memAlloc(4);
            try {
                d.put(0, unorm(DEFAULT.smooth)).put(1, (byte) 0).put(2, (byte) 0).put(3, (byte) 0);
                none.upload(0, 0, 0, 0, 1, 1, d);
            } finally {
                MemoryUtil.memFree(d);
            }
            nearest = dev.createSampler(SteinSampler.Desc.nearestClamp());
        }
        ByteBuffer p;
        int w;
        int h;
        synchronized (SteinMaterialMap.class) {
            p = pending;
            w = pendingW;
            h = pendingH;
            pending = null;
        }
        if (p != null && !failed) {
            try {
                if (map != null) {
                    map.destroy();
                }
                map =
                        dev.createTexture(
                                SteinTexture.Desc.texture2D(
                                        "stein materials", SteinFormat.RGBA8, w, h, 1));
                map.upload(0, 0, 0, 0, w, h, p);
            } catch (RuntimeException | LinkageError e) {
                failed = true;
                map = null;
                Log.warn("gfx", "Stein materials turned off for this session: " + e);
            } finally {
                MemoryUtil.memFree(p);
            }
        }
        return map != null ? map : none;
    }

    static SteinSampler sampler() {
        return nearest;
    }
}
