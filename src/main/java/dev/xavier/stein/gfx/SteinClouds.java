/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import dev.xavier.stein.bridge.core.Log;
import dev.xavier.stein.bridge.engine.Visuals;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.texture.ITextureObject;
import net.minecraft.client.renderer.texture.TextureUtil;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.IResourceManagerReloadListener;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.system.MemoryUtil;

import java.awt.image.BufferedImage;
import java.io.InputStream;

/**
 * The game's clouds over the sun: their shadows on the world, and the sun dimmed while one passes
 * in front of it.
 *
 * <p>The clouds are the game's texture (clouds.png, a texel every 12 blocks) at their height,
 * drifting 0.03 blocks a tick along x (RenderGlobal.renderCloudsFancy, CloudCache). A point lies in
 * a cloud's shadow when the line from it towards the sun crosses the cloud layer inside a cloud: a
 * matrix, made here per frame, takes the point from eye space straight to the texture's coordinates
 * there (to the world, along the sun up to the layer, onto the texture), so the shaders read the
 * texture once ({@code common/shadow.glsl}). The same reading at the camera says how much of the
 * sun a cloud hides from the view, for the glare and the light in the air, eased over half a
 * second; that one is read from a copy of the texture kept here, made again at each resource
 * reload.
 *
 * <p>Only with the clouds on (the video option) and the shadows' map.
 */
final class SteinClouds {

    /** Bytes of the block: the matrix and x the strength. */
    static final int BYTES = 64 + 16;

    private static final ResourceLocation CLOUDS =
            new ResourceLocation("textures/environment/clouds.png");

    /**
     * The share of the direct light a cloud takes, on the world and off the sun. At 0.6 a cloud
     * passing over turned the whole view a lot darker (the direct light is most of the day's).
     */
    private static final float SHADE = 0.35f;

    /** Blocks per texel of the clouds' texture. */
    private static final double CELL = 12.0;

    /** Seconds for the sun to follow a cloud passing in front of it. */
    private static final float EASE_SECONDS = 0.5f;

    // this frame
    private static final float[] MATRIX = new float[16];
    private static final float[] INV = new float[16];
    private static float strength;
    private static float sunVisible = 1f;
    private static long lastNs;

    // the texture: the game's for the shaders, a copy of its alpha for the camera
    private static SteinTexture texture;
    private static SteinTexture none;
    private static SteinSampler sampler;
    private static byte[] cover;
    private static int coverW;
    private static int coverH;
    private static boolean listening;
    private static boolean failed;

    private SteinClouds() {}

    /**
     * At the shadows' frame: the matrix for the eye space of camera matrix {@code m} (camera at
     * {@code cx, cy, cz}) and the sun towards {@code lx, ly, lz}; and the sun's cover at the
     * camera.
     */
    static void frame(
            SteinDevice dev,
            WorldClient world,
            float[] m,
            double cx,
            double cy,
            double cz,
            double lx,
            double ly,
            double lz) {
        strength = 0f;
        Minecraft mc = Minecraft.getMinecraft();
        if (SteinGfxOptions.NO_CLOUD_SHADOWS
                || failed
                || world == null
                || mc.gameSettings.shouldRenderClouds() == 0
                || ly < 0.05) {
            ease(1f);
            return;
        }
        try {
            if (!bind(dev, mc)) {
                ease(1f);
                return;
            }
            float pt = mc.timer.renderPartialTicks;
            double drift = ((float) mc.renderGlobal.cloudTickCounter + pt) * (double) 0.03F;
            double height = Visuals.cloudHeight(world.provider) + 2.0; // the layer's middle
            double kx = lx / ly;
            double kz = lz / ly;
            // m^-1 for a rigid matrix: world - camera = R^T * eye - R^T t
            float[] inv = INV;
            for (int r = 0; r < 3; r++) {
                for (int c = 0; c < 3; c++) {
                    inv[c * 4 + r] = m[r * 4 + c];
                }
            }
            for (int r = 0; r < 3; r++) {
                inv[12 + r] = -(inv[r] * m[12] + inv[4 + r] * m[13] + inv[8 + r] * m[14]);
            }
            // u = (x - kx * y + kx * height + drift) / CELL / 256, v = ((z - kz * y + kz * height)
            // / CELL + 0.33) / 256, with x, y, z = camera + inv * eye; the constant in double,
            // wrapped
            double scale = 1.0 / (CELL * 256.0);
            double u0 = (cx - kx * cy + kx * height + drift) * scale;
            double v0 = (cz - kz * cy + kz * height) * scale + 0.33 / 256.0;
            float[] out = MATRIX;
            for (int c = 0; c < 4; c++) {
                out[c * 4] = (float) ((inv[c * 4] - kx * inv[c * 4 + 1]) * scale);
                out[c * 4 + 1] = (float) ((inv[c * 4 + 2] - kz * inv[c * 4 + 1]) * scale);
                out[c * 4 + 2] = 0f;
                out[c * 4 + 3] = c == 3 ? 1f : 0f;
            }
            out[12] += (float) (u0 - Math.floor(u0));
            out[13] += (float) (v0 - Math.floor(v0));
            strength = SHADE;
            ease(1f - SHADE * coverAt(u0 - Math.floor(u0), v0 - Math.floor(v0)));
        } catch (RuntimeException | LinkageError e) {
            failed = true;
            strength = 0f;
            Log.warn("gfx", "cloud shadows turned off for this session: " + e);
        }
    }

    /** Writes the block at {@code p}: the matrix and the strength ({@code on}: the world's). */
    static void write(long p, boolean on) {
        for (int i = 0; i < 16; i++) {
            MemoryUtil.memPutFloat(p + i * 4L, MATRIX[i]);
        }
        MemoryUtil.memPutFloat(p + 64, on ? strength : 0f);
        MemoryUtil.memPutFloat(p + 68, 0f);
        MemoryUtil.memPutFloat(p + 72, 0f);
        MemoryUtil.memPutFloat(p + 76, 0f);
    }

    /** The clouds' texture to bind (a clear one when there are none). */
    static SteinTexture texture() {
        return strength > 0f && texture != null ? texture : none;
    }

    static SteinSampler sampler() {
        return sampler;
    }

    /** How much of the sun reaches the camera past the clouds (1 none in front), eased. */
    static float sunVisible() {
        return sunVisible;
    }

    /** The clear stand-in and the sampler, before any draw binds them. */
    static void ensure(SteinDevice dev) {
        if (none != null) {
            return;
        }
        none =
                dev.createTexture(
                        SteinTexture.Desc.texture2D("clouds: none", SteinFormat.RGBA8, 1, 1, 1));
        java.nio.ByteBuffer clear = MemoryUtil.memCalloc(4);
        try {
            none.upload(0, 0, 0, 0, 1, 1, clear);
        } finally {
            MemoryUtil.memFree(clear);
        }
        sampler =
                dev.createSampler(
                        new SteinSampler.Desc(
                                SteinSampler.Filter.LINEAR,
                                SteinSampler.Filter.LINEAR,
                                SteinSampler.Mip.NONE,
                                SteinSampler.Wrap.REPEAT,
                                SteinSampler.Wrap.REPEAT,
                                1f,
                                false));
    }

    /** The game's clouds texture wrapped for the draws; false while the game has not made it. */
    private static boolean bind(SteinDevice dev, Minecraft mc) {
        ensure(dev);
        if (!listening) {
            listening = true;
            ((net.minecraft.client.resources.IReloadableResourceManager) mc.getResourceManager())
                    .registerReloadListener((IResourceManagerReloadListener) SteinClouds::reload);
        }
        ITextureObject t = mc.getTextureManager().getTexture(CLOUDS);
        if (t == null) {
            return false;
        }
        int id = t.getGlTextureId();
        if (texture == null || texture.handle() != id) {
            texture =
                    dev.importTexture(
                            SteinTexture.Desc.texture2D("clouds", SteinFormat.RGBA8, 256, 256, 1),
                            id);
        }
        if (cover == null) {
            reload(mc.getResourceManager());
        }
        return cover != null;
    }

    /** The copy of the texture's alpha (at each resource reload: a pack can change the clouds). */
    private static void reload(IResourceManager resources) {
        try (InputStream in = resources.getResource(CLOUDS).getInputStream()) {
            BufferedImage img = TextureUtil.readBufferedImage(in);
            int w = img.getWidth();
            int h = img.getHeight();
            byte[] a = new byte[w * h];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    a[y * w + x] = (byte) (img.getRGB(x, y) >>> 24);
                }
            }
            cover = a;
            coverW = w;
            coverH = h;
        } catch (java.io.IOException | RuntimeException e) {
            cover = null;
            Log.warn("gfx", "cloud shadows: the clouds texture could not be read: " + e);
        }
    }

    /** The clouds' alpha (0..1) at texture coordinates {@code u, v}, bilinear and repeating. */
    private static float coverAt(double u, double v) {
        double x = u * coverW - 0.5;
        double y = v * coverH - 0.5;
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        float fx = (float) (x - x0);
        float fy = (float) (y - y0);
        float a = alpha(x0, y0) + (alpha(x0 + 1, y0) - alpha(x0, y0)) * fx;
        float b = alpha(x0, y0 + 1) + (alpha(x0 + 1, y0 + 1) - alpha(x0, y0 + 1)) * fx;
        return a + (b - a) * fy;
    }

    private static float alpha(int x, int y) {
        int px = Math.floorMod(x, coverW);
        int py = Math.floorMod(y, coverH);
        return (cover[py * coverW + px] & 0xFF) / 255f;
    }

    private static void ease(float target) {
        long now = System.nanoTime();
        float dt = lastNs == 0 ? 1f : Math.min(0.5f, (now - lastNs) / 1e9f);
        lastNs = now;
        sunVisible += (target - sunVisible) * (1f - (float) Math.exp(-dt / (EASE_SECONDS / 3f)));
    }
}
