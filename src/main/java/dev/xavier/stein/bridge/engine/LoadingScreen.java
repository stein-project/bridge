/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import dev.xavier.stein.bridge.core.Log;
import dev.xavier.stein.gfx.SteinGfxOptions;
import dev.xavier.stein.gfx.SteinLoadingScreen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.IResourceManagerReloadListener;
import net.minecraft.client.resources.SimpleReloadableResourceManager;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.ScreenShotHelper;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import javax.imageio.ImageIO;

/**
 * Stein's loading screen, in place of the game's: the dark of Stein's panels over the whole window,
 * the "Stein" logo small in the top right corner and a thin violet bar under it with the progress.
 * Only with Stein GFX; without it the game's own screens stay.
 *
 * <p>Where it shows:
 *
 * <ul>
 *   <li>when the game opens (in place of the 1.8.9 splash), the bar following the resource
 *       listeners that startGame registers;
 *   <li>at every resource reload — a resource pack, the mipmap levels, the language, F3+T — which
 *       in 1.8.9 freezes the game until it ends: a frame is drawn inside the reload itself, before
 *       each listener (patcher rule on both calls of SimpleReloadableResourceManager), darkening
 *       over the frozen picture.
 * </ul>
 *
 * When the load ends the bar fades, then the screen, over the game already running ({@link
 * #fadeOut}). The drawing goes through the API ({@link SteinLoadingScreen}), straight to the window
 * and then shown (updateDisplay): during a load the game's own immediate drawing would not reach
 * the screen on Vulkan.
 *
 * <p>The same logo stays in the inventory's top right corner ({@link #inventoryLogo}).
 *
 * <p>Pipeline: Stein GFX only (the inventory's logo: both).
 */
public final class LoadingScreen {

    /** Listeners startGame runs (with Stein's): the opening's bar counts up to here. */
    private static final int STARTUP_STEPS = 20;

    /** How much each frame of a reload darkens the frozen picture (it builds up). */
    private static final float RELOAD_DARKEN = 0.35f;

    private static final long BAR_FADE = 400_000_000L;
    private static final long SCREEN_FADE = 500_000_000L;

    /** The inventory's logo, in the game's texture manager (drawn inside the game's frame). */
    private static final ResourceLocation LOGO = new ResourceLocation("steinbridge", "logo/ui");

    private static boolean broken;
    private static BufferedImage source;
    private static float aspect = 2f;

    /** The logo scaled with care to each height drawn. */
    private static final Map<Integer, BufferedImage> SIZES = new HashMap<>();

    /** The height the inventory's texture was made for. */
    private static int inventoryHeight = -1;

    /** The opening in progress (beforeStartGame to afterStartGame). */
    private static boolean startup;

    /** A reload in progress (outside the opening). */
    private static boolean reloading;

    private static int step;
    private static int total = STARTUP_STEPS;
    private static float shown;

    /** When the load ended (0: still loading, or already gone). */
    private static long doneAt;

    /** The diagnostic capture was taken ({@link EngineOptions#LOADING_SHOT}). */
    private static boolean shot;

    private LoadingScreen() {}

    private static boolean enabled() {
        return !broken && SteinGfxOptions.ENABLED;
    }

    // ---- the opening ----

    /** Before startGame: the opening begins (the window is not there yet). */
    public static void startupBegin() {
        startup = true;
        step = 0;
        total = STARTUP_STEPS;
        shown = 0f;
    }

    /** After startGame: the screen fades over the title screen. */
    public static void startupEnd() {
        if (startup) {
            startup = false;
            doneAt = System.nanoTime();
        }
    }

    /** In place of Minecraft.drawSplashScreen in startGame. */
    public static void splash(Object minecraft, Object textureManager) throws Exception {
        Minecraft mc = (Minecraft) minecraft;
        if (enabled()) {
            try {
                frame(mc, 1f, shown);
                return;
            } catch (RuntimeException | LinkageError e) {
                fail(e);
            }
        }
        mc.drawSplashScreen((TextureManager) textureManager);
    }

    // ---- resource reloads ----

    /**
     * IResourceManagerReloadListener.onResourceManagerReload, in both calls of
     * SimpleReloadableResourceManager (registering a listener, reloading them all): a frame with
     * the bar before each one.
     */
    public static void listener(Object listener, Object manager) {
        boolean draw = enabled();
        if (draw) {
            try {
                if (!startup && !reloading) {
                    reloading = true;
                    step = 0;
                    total =
                            Math.max(
                                    1,
                                    ((SimpleReloadableResourceManager) manager)
                                            .reloadListeners.size());
                    shown = 0f;
                    doneAt = 0L;
                }
                step++;
                shown = progress();
                frame(Minecraft.getMinecraft(), startup ? 1f : RELOAD_DARKEN, shown);
            } catch (RuntimeException | LinkageError e) {
                fail(e);
            }
        }
        ((IResourceManagerReloadListener) listener)
                .onResourceManagerReload((IResourceManager) manager);
        if (draw && reloading && step >= total) {
            reloading = false;
            doneAt = System.nanoTime();
        }
    }

    /** Eases towards the share of listeners done, never backwards. */
    private static float progress() {
        float target = Math.min(1f, step / (float) Math.max(1, total));
        return Math.max(shown, Math.min(target, shown * 0.5f + target * 0.5f + 0.02f));
    }

    /**
     * Before the game's framebuffer is unbound in the game loop (it is still the draw target): the
     * end of a load, over the game already running — the bar fades, then the screen.
     */
    public static void fadeOut() {
        if (doneAt == 0L || !enabled()) {
            return;
        }
        long since = System.nanoTime() - doneAt;
        if (since >= BAR_FADE + SCREEN_FADE) {
            doneAt = 0L;
            return;
        }
        try {
            Minecraft mc = Minecraft.getMinecraft();
            float bar = 1f - Math.min(1f, since / (float) BAR_FADE);
            float screen = since < BAR_FADE ? 1f : 1f - (since - BAR_FADE) / (float) SCREEN_FADE;
            draw(mc.displayWidth, mc.displayHeight, screen, 1f, screen, bar);
        } catch (RuntimeException | LinkageError e) {
            fail(e);
        }
    }

    // ---- frames ----

    /** One frame in the middle of a load: drawn on the window and shown (the game waits here). */
    private static void frame(Minecraft mc, float darken, float progress) {
        if (mc.displayWidth <= 0 || mc.displayHeight <= 0) {
            return;
        }
        OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, 0);
        draw(mc.displayWidth, mc.displayHeight, darken, progress, 1f, 1f);
        if (EngineOptions.LOADING_SHOT && !shot && startup && progress >= 0.5f) {
            shot = true;
            // the capture reads the game's framebuffer: the same frame drawn there too
            mc.getFramebuffer().bindFramebuffer(true);
            draw(mc.displayWidth, mc.displayHeight, darken, progress, 1f, 1f);
            Log.info(
                    "loading",
                    "capture: "
                            + ScreenShotHelper.saveScreenshot(
                                            mc.mcDataDir,
                                            "loading.png",
                                            mc.displayWidth,
                                            mc.displayHeight,
                                            mc.getFramebuffer())
                                    .getUnformattedText());
            OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, 0);
        }
        mc.updateDisplay();
    }

    /** The screen on the current draw target, {@code w} by {@code h} pixels. */
    private static void draw(
            int w, int h, float background, float progress, float logo, float bar) {
        int lh = Math.max(24, Math.round(h * LOADING_LOGO));
        BufferedImage img = sized(lh);
        int margin = Math.round(lh * 0.6f);
        int lx = w - margin - img.getWidth();
        // the bar where the new versions have theirs: centred, at four fifths of the height
        int bw = Math.round(Math.min(w * 0.5f, h * 0.9f));
        int bh = Math.max(4, Math.round(h * 0.008f));
        int bx = (w - bw) / 2;
        int by = Math.round(h * 0.8f);
        SteinLoadingScreen.draw(
                w, h, img, lx, margin, bx, by, bw, bh, background, progress, logo, bar * logo);
    }

    /** The loading screen's logo, as a share of the window's height. */
    private static final float LOADING_LOGO = 0.06f;

    /** The inventory's logo in pixels for a window this tall: small, like a header's mark. */
    private static int logoPixels(int windowHeight) {
        return Math.max(16, Math.round(windowHeight * 0.034f));
    }

    /**
     * The logo at {@code height} pixels: the source halved step by step down to it, so the thin
     * strokes survive (sampled from far bigger, they would break).
     */
    private static BufferedImage sized(int height) {
        BufferedImage img = SIZES.get(height);
        if (img != null) {
            return img;
        }
        if (source == null) {
            try (InputStream in =
                    LoadingScreen.class.getResourceAsStream("/assets/steinbridge/logo/stein.png")) {
                source = in == null ? null : ImageIO.read(in);
            } catch (IOException e) {
                source = null;
            }
            if (source == null) {
                throw new IllegalStateException("the Stein logo is missing from the Bridge");
            }
            aspect = source.getWidth() / (float) source.getHeight();
        }
        img = source;
        while (img.getHeight() / 2 >= height) {
            img = scaled(img, img.getWidth() / 2, img.getHeight() / 2);
        }
        if (img.getHeight() != height) {
            img = scaled(img, Math.max(1, Math.round(height * aspect)), height);
        }
        SIZES.put(height, img);
        return img;
    }

    private static BufferedImage scaled(BufferedImage img, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(img, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    // ---- the inventory ----

    /**
     * After the inventory's screen is drawn: the logo in the top right corner, where the loading
     * screen has it and at the same size. The screen's projection is in GUI units: the pixels are
     * divided by the GUI scale, and the texture is the logo at exactly that many pixels. With Stein
     * GFX it goes through the loading screen's own pass instead.
     */
    public static void inventoryLogo(int guiScale) {
        if (broken) {
            return;
        }
        try {
            Minecraft mc = Minecraft.getMinecraft();
            int lh = logoPixels(mc.displayHeight);
            BufferedImage img = sized(lh);
            if (SteinGfxOptions.ENABLED) {
                // the same pass as the loading screen's, without the dark and the bar: the
                // game's own immediate drawing after the screen would not reach Vulkan
                int margin = Math.round(lh * 0.9f);
                int lx = mc.displayWidth - margin - img.getWidth();
                SteinLoadingScreen.draw(
                        mc.displayWidth,
                        mc.displayHeight,
                        img,
                        lx,
                        margin,
                        lx,
                        0,
                        0,
                        0,
                        0f,
                        0f,
                        0.9f,
                        0f);
                return;
            }
            TextureManager tm = mc.getTextureManager();
            if (lh != inventoryHeight || tm.getTexture(LOGO) == null) {
                tm.deleteTexture(LOGO);
                tm.loadTexture(LOGO, new DynamicTexture(img));
                inventoryHeight = lh;
            }
            float s = Math.max(1, guiScale);
            float h = lh / s;
            float w = img.getWidth() / s;
            float margin = Math.round(lh * 0.9f) / s;
            float x = mc.displayWidth / s - margin - w;
            GlStateManager.disableDepth();
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
            GlStateManager.enableTexture2D();
            tm.bindTexture(LOGO);
            GlStateManager.color(0xE8 / 255f, 0xE1 / 255f, 0xF5 / 255f, 0.9f);
            WorldRenderer wr = Tessellator.getInstance().getWorldRenderer();
            wr.begin(7, DefaultVertexFormats.POSITION_TEX);
            wr.pos(x, margin + h, 0.0).tex(0.0, 1.0).endVertex();
            wr.pos(x + w, margin + h, 0.0).tex(1.0, 1.0).endVertex();
            wr.pos(x + w, margin, 0.0).tex(1.0, 0.0).endVertex();
            wr.pos(x, margin, 0.0).tex(0.0, 0.0).endVertex();
            Tessellator.getInstance().draw();
            GlStateManager.color(1f, 1f, 1f, 1f);
            GlStateManager.enableDepth();
        } catch (RuntimeException | LinkageError e) {
            fail(e);
        }
    }

    private static void fail(Throwable e) {
        broken = true;
        Log.warn("loading", "Stein loading screen turned off for this session: " + e);
    }
}
