/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.lens;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.xavier.stein.bridge.core.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

/**
 * config/steinlens.json. Read and written field by field (no reflection): the distribution jar has
 * its names shrunk and renamed, and the field names here do not survive.
 */
final class LensConfig {

    boolean dynamicFov = false;
    boolean zoom = true;
    boolean zoomSmoothCamera = true;

    /** Field-of-view divisor when zooming. */
    float zoomFactor = 4.0F;

    /** Per-enchantment item textures from resource packs. */
    boolean customItems = true;

    /** Connected blocks (connected textures from resource packs). */
    boolean connectedTextures = true;

    /** Enchantment glint: 0 on, 1 near only, 2 off (the Visuals.GLINT_*). */
    int glint = 0;

    /** Up to how many blocks from the camera "near only" keeps the glint. */
    float glintNearBlocks = 16.0F;

    /** Maximum nametag distance, in blocks; 0 = the game's (64). */
    int nametagDistance = 0;

    /** Worn armor in view. */
    boolean armor = true;

    /** Sine and cosine from a compact table (Visuals.setCompactAngles). */
    boolean compactAngles = false;

    /** Fog: 0 normal, 1 light, 2 off (the Visuals.FOG_*). */
    int fog = 0;

    /** Blocks above the game's cloud height. */
    int cloudHeight = 0;

    /**
     * Drawing straight to the window, without the intermediate framebuffer
     * (Visuals.setDirectRendering).
     */
    boolean directRender = false;

    /** Finished chunks uploaded per frame; 0 = like the game. */
    int chunkUploads = 0;

    /** A chunk is only built with all 8 neighbors loaded. */
    boolean waitNeighbors = true;

    /** Extended distance: 0 off, 1 memory, 2 disk (the Visuals.KEEP_*). */
    int keepChunks = 0;

    /** Limit of the stored terrain, in MB (memory and disk). */
    int keepChunksMb = 512;

    /**
     * The game's rain and snow in view; when off, there is no drop, splash, sound or sky darkened
     * by rain.
     */
    boolean rain = true;

    /** 1.7 animations (OldAnimations): the master switch and each part. */
    boolean oldAnimations = true;

    boolean oldBlockHit = true;
    boolean oldItems = true;
    boolean oldSwordBlock = true;
    boolean oldBow = true;
    boolean oldRod = true;
    boolean oldBlockPose = true;
    boolean oldArmorHurt = true;

    /** Bloom: 0 off, 1 low, 2 high (the SteinPostFx.BLOOM_*). */
    int bloom = 0;

    /** Smoothing of object outlines (where the depth breaks; textures stay sharp). */
    boolean smoothEdges = false;

    /** Anisotropic filtering: 1 (off), 2, 4, 8 or 16 (SteinTextureFilter). */
    int anisotropy = 1;

    /** The terrain's pixels sampled four times (SteinTextureFilter). */
    boolean supersample = false;

    /** Sharpening, 0 (off) to 3 (SteinPostFx). */
    int sharpen = 0;

    /**
     * Light carried by entities: a torch in hand, a glowstone on the ground (SteinDynamicLights).
     */
    boolean dynamicLights = false;

    /** The atmosphere: 0 off, 1 soft, 2 strong (SteinAtmosphere). */
    int atmosphere = 0;

    /** The sun's shadows (SteinShadows): 0 off, 1 low, 2 medium, 3 high. */
    int shadows = 0;

    /** Moonlight: open sky turns blue at night (SteinWorldLight). */
    boolean moonlight = false;

    /** Darkness: the weak lights fall towards black (SteinWorldLight). */
    boolean darkness = false;

    /** Glowing blocks: 0 off, 1 on, 2 intense (SteinGlow). */
    int glowingBlocks = 0;

    /** HDR output (Vulkan with an HDR screen): lights brighter than the white of the menus. */
    boolean hdr = false;

    /** The HDR calibration's starting points (the calibration begins from them). */
    static final int HDR_WHITE = 200;

    static final int HDR_PEAK = 1000;
    static final int HDR_BLACK = 50;

    /** HDR calibration: the white of the menus and the world, in nits. */
    int hdrWhite = HDR_WHITE;

    /** HDR calibration: the screen's peak brightness, in nits. */
    int hdrPeak = HDR_PEAK;

    /**
     * HDR calibration: the shadows, 0 to 100; 50 leaves them as drawn, less deepens them, more
     * lifts them (SteinHdr).
     */
    int hdrBlack = HDR_BLACK;

    /** HDR in Stein's look (soft, vivid, moody) or as drawn (SteinHdr). */
    boolean hdrLook = true;

    /**
     * HDR's quick quality (the frame in 8 bits, the lights in a 16-bit layer) or the maximum (the
     * whole frame in 16 bits), SteinHdr.
     */
    boolean hdrQuick = true;

    void load(File file) {
        if (!file.isFile()) {
            save(file);
        } else {
            read(file);
        }
        overrides();
    }

    private void read(File file) {
        try (Reader r = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            JsonObject o = new JsonParser().parse(r).getAsJsonObject();
            dynamicFov = bool(o, "dynamicFov", dynamicFov);
            zoom = bool(o, "zoom", zoom);
            zoomSmoothCamera = bool(o, "zoomSmoothCamera", zoomSmoothCamera);
            if (o.has("zoomFactor")) {
                zoomFactor = Math.max(1.1F, Math.min(10.0F, o.get("zoomFactor").getAsFloat()));
            }
            customItems = bool(o, "customItems", customItems);
            connectedTextures = bool(o, "connectedTextures", connectedTextures);
            glint = integer(o, "glint", glint, 0, 2);
            if (o.has("glintNearBlocks")) {
                glintNearBlocks =
                        Math.max(2.0F, Math.min(64.0F, o.get("glintNearBlocks").getAsFloat()));
            }
            nametagDistance = integer(o, "nametagDistance", nametagDistance, 0, 64);
            armor = bool(o, "armor", armor);
            compactAngles = bool(o, "compactAngles", compactAngles);
            fog = integer(o, "fog", fog, 0, 2);
            cloudHeight = integer(o, "cloudHeight", cloudHeight, 0, 256);
            directRender = bool(o, "directRender", directRender);
            chunkUploads = integer(o, "chunkUploads", chunkUploads, 0, 64);
            waitNeighbors = bool(o, "waitNeighbors", waitNeighbors);
            keepChunks = integer(o, "keepChunks", keepChunks, 0, 2);
            keepChunksMb = integer(o, "keepChunksMB", keepChunksMb, 64, 8192);
            rain = bool(o, "rain", rain);
            oldAnimations = bool(o, "oldAnimations", oldAnimations);
            oldBlockHit = bool(o, "oldBlockHit", oldBlockHit);
            oldItems = bool(o, "oldItems", oldItems);
            oldSwordBlock = bool(o, "oldSwordBlock", oldSwordBlock);
            oldBow = bool(o, "oldBow", oldBow);
            oldRod = bool(o, "oldRod", oldRod);
            oldBlockPose = bool(o, "oldBlockPose", oldBlockPose);
            oldArmorHurt = bool(o, "oldArmorHurt", oldArmorHurt);
            bloom = integer(o, "bloom", bloom, 0, 2);
            smoothEdges = bool(o, "smoothEdges", smoothEdges);
            anisotropy = integer(o, "anisotropy", anisotropy, 1, 16);
            supersample = bool(o, "supersample", supersample);
            sharpen = integer(o, "sharpen", sharpen, 0, 3);
            dynamicLights = bool(o, "dynamicLights", dynamicLights);
            atmosphere = integer(o, "atmosphere", atmosphere, 0, 2);
            // (a switch before the levels: on = medium)
            shadows =
                    o.has("shadows") && o.get("shadows").getAsJsonPrimitive().isBoolean()
                            ? (o.get("shadows").getAsBoolean() ? 2 : 0)
                            : integer(o, "shadows", shadows, 0, 3);
            moonlight = bool(o, "moonlight", moonlight);
            darkness = bool(o, "darkness", darkness);
            glowingBlocks = integer(o, "glowingBlocks", glowingBlocks, 0, 2);
            hdr = bool(o, "hdr", hdr);
            hdrWhite = integer(o, "hdrWhite", hdrWhite, 80, 1000);
            hdrPeak = integer(o, "hdrPeak", hdrPeak, 200, 10000);
            hdrBlack = integer(o, "hdrBlack", hdrBlack, 0, 100);
            hdrLook = bool(o, "hdrLook", hdrLook);
            hdrQuick = bool(o, "hdrQuick", hdrQuick);
        } catch (Exception e) {
            Log.info("lens", file + " unreadable, using defaults: " + e);
        }
    }

    /** Measurement properties, with or without a file. */
    private void overrides() {
        // Measurement A/B: -Dsteinlens.customItems=true|false
        // and -Dsteinlens.connectedTextures=true|false override
        // the file, without saving to it.
        String cit = LensOptions.CUSTOM_ITEMS;
        if (cit != null) {
            customItems = Boolean.parseBoolean(cit);
        }
        String ctm = LensOptions.CONNECTED_TEXTURES;
        if (ctm != null) {
            connectedTextures = Boolean.parseBoolean(ctm);
        }
        if (LensOptions.BLOOM >= 0) {
            bloom = Math.min(2, LensOptions.BLOOM);
        }
        if (LensOptions.SMOOTH_EDGES != null) {
            smoothEdges = Boolean.parseBoolean(LensOptions.SMOOTH_EDGES);
        }
        if (LensOptions.HDR != null) {
            hdr = Boolean.parseBoolean(LensOptions.HDR);
        }
    }

    void save(File file) {
        JsonObject o = new JsonObject();
        o.addProperty("dynamicFov", dynamicFov);
        o.addProperty("zoom", zoom);
        o.addProperty("zoomSmoothCamera", zoomSmoothCamera);
        o.addProperty("zoomFactor", zoomFactor);
        o.addProperty("customItems", customItems);
        o.addProperty("connectedTextures", connectedTextures);
        o.addProperty("glint", glint);
        o.addProperty("glintNearBlocks", glintNearBlocks);
        o.addProperty("nametagDistance", nametagDistance);
        o.addProperty("armor", armor);
        o.addProperty("compactAngles", compactAngles);
        o.addProperty("fog", fog);
        o.addProperty("cloudHeight", cloudHeight);
        o.addProperty("directRender", directRender);
        o.addProperty("chunkUploads", chunkUploads);
        o.addProperty("waitNeighbors", waitNeighbors);
        o.addProperty("keepChunks", keepChunks);
        o.addProperty("keepChunksMB", keepChunksMb);
        o.addProperty("rain", rain);
        o.addProperty("oldAnimations", oldAnimations);
        o.addProperty("oldBlockHit", oldBlockHit);
        o.addProperty("oldItems", oldItems);
        o.addProperty("oldSwordBlock", oldSwordBlock);
        o.addProperty("oldBow", oldBow);
        o.addProperty("oldRod", oldRod);
        o.addProperty("oldBlockPose", oldBlockPose);
        o.addProperty("oldArmorHurt", oldArmorHurt);
        o.addProperty("bloom", bloom);
        o.addProperty("smoothEdges", smoothEdges);
        o.addProperty("anisotropy", anisotropy);
        o.addProperty("supersample", supersample);
        o.addProperty("sharpen", sharpen);
        o.addProperty("dynamicLights", dynamicLights);
        o.addProperty("atmosphere", atmosphere);
        o.addProperty("shadows", shadows);
        o.addProperty("moonlight", moonlight);
        o.addProperty("darkness", darkness);
        o.addProperty("glowingBlocks", glowingBlocks);
        o.addProperty("hdr", hdr);
        o.addProperty("hdrWhite", hdrWhite);
        o.addProperty("hdrPeak", hdrPeak);
        o.addProperty("hdrBlack", hdrBlack);
        o.addProperty("hdrLook", hdrLook);
        o.addProperty("hdrQuick", hdrQuick);
        file.getAbsoluteFile().getParentFile().mkdirs();
        try (Writer w =
                new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
            new GsonBuilder().setPrettyPrinting().create().toJson(o, w);
        } catch (IOException e) {
            Log.info("lens", "could not write " + file + ": " + e);
        }
    }

    private static boolean bool(JsonObject o, String key, boolean fallback) {
        return o.has(key) ? o.get(key).getAsBoolean() : fallback;
    }

    private static int integer(JsonObject o, String key, int fallback, int min, int max) {
        return o.has(key) ? Math.max(min, Math.min(max, o.get(key).getAsInt())) : fallback;
    }
}
