/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.lens;

import dev.xavier.stein.bridge.core.Log;
import dev.xavier.stein.bridge.engine.AngleTable;
import dev.xavier.stein.bridge.engine.ChunkKeep;
import dev.xavier.stein.bridge.engine.ChunkPace;
import dev.xavier.stein.bridge.engine.DirectRender;
import dev.xavier.stein.bridge.engine.KeyBindings;
import dev.xavier.stein.bridge.engine.OldAnimations;
import dev.xavier.stein.bridge.engine.Rain;
import dev.xavier.stein.bridge.engine.Visuals;
import dev.xavier.stein.bridge.hooks.GameHooks;
import dev.xavier.stein.bridge.hooks.HookSink;
import dev.xavier.stein.gfx.SteinHdr;
import dev.xavier.stein.gfx.SteinPostFx;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiVideoSettings;
import net.minecraft.client.renderer.texture.ITextureObject;
import net.minecraft.client.renderer.texture.SimpleTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.texture.TextureUtil;
import net.minecraft.client.resources.FileResourcePack;
import net.minecraft.client.resources.FolderResourcePack;
import net.minecraft.client.resources.IResourcePack;
import net.minecraft.client.resources.model.IBakedModel;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.item.ItemStack;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.IBlockAccess;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The visual options of SteinBridge, in the game's own Video Settings screen — what until 0.0.12
 * was the Stein Lens mod. It is no longer a mod: it is part of the Bridge, born with it
 * (ServiceLoader of {@link HookSink}) and works without the Stein Loader installed.
 *
 * <ul>
 *   <li><b>Graphics</b> in a single selector ({@link GraphicsSlider}) in place of the game's
 *       Fast/Fancy, which also applies to the game's performance options ({@link Presets}), and the
 *       whole video screen reorganized by topic, with the game's options and ours side by side
 *       ({@link VideoOptions});
 *   <li><b>Dynamic FOV</b> on/off: the game changes the field of view with speed (running, the
 *       Speed effect) and with a drawn bow. Off, the player's factor stays at 1 — the field of view
 *       is the option's —, and the game eases it there, as it does with any change of that factor;
 *   <li><b>Zoom</b> (V, held): field of view divided by 4 and a smooth camera while the key is
 *       held, with no screen open. On release, the smooth camera goes back to what it was and its
 *       filters are zeroed. On both switches the terrain redoes the visible-chunk search: standing
 *       still, the zoom narrows the view without moving the camera, and on leaving it the screen
 *       edges would lack the chunks outside the narrow cone;
 *   <li><b>Per-enchantment textures</b> from resource packs: {@link CustomItems};
 *   <li><b>Connected blocks</b> from resource packs: {@link ConnectedTextures};
 *   <li>the engine options ({@code dev.xavier.stein.bridge.engine}): brightness, armor, nametags,
 *       compact angles, fog, cloud height, rain, direct drawing, chunk pacing and 1.7 animations.
 * </ul>
 *
 * <p>The configuration stays in config/steinlens.json (also the zoom factor, {@code zoomFactor})
 * and the key is under Controls, category "Lens". The texts come from assets/steinlens/lang, inside
 * the Bridge's own jar, which joins the default resource packs in {@link #beforeStartGame()}.
 *
 * <p>Written with the MCP/SRG names; the build translates them into the game's, as it does with the
 * mods.
 */
public final class Lens implements HookSink {

    /** V, as an LWJGL 2 key code (unused in 1.8.9). */
    private static final int KEY_V = 47;

    final LensConfig config = new LensConfig();
    final CustomItems customItems = new CustomItems();
    final ConnectedTextures connected = new ConnectedTextures();
    KeyBinding zoomKey;
    private File configFile;

    private boolean zooming;

    /** Smooth camera before the zoom, and whether the zoom turned it on. */
    private boolean smoothBefore;

    private boolean smoothChanged;

    /**
     * Before startGame: the configuration is already valid on the first resource load, which
     * happens inside it (per-enchantment textures, direct drawing without creating the
     * framebuffer). The Bridge's jar joins the resource packs here, before that load.
     */
    @Override
    public void beforeStartGame() {
        addResourcePack();
        configFile = configFile();
        config.load(configFile);
        applyVisuals();
    }

    @Override
    public void afterStartGame() {
        if (configFile == null) {
            configFile = configFile();
            config.load(configFile);
        }
        applyVisuals();
        // The first resource load was earlier (in startGame), without the BlockRendererDispatcher
        // and with the option at its default: the connected blocks are resolved now.
        connected.resolve();
        if (connected.enabled() != config.connectedTextures) {
            connected.setEnabled(config.connectedTextures, false);
        }
        noAnaglyph();
        zoomKey = new KeyBinding("key.steinlens.zoom", KEY_V, "key.categories.steinlens");
        KeyBindings.register(zoomKey);
        Log.info("lens", "visual options (" + configFile + ")");
    }

    /**
     * The game's "3D Anaglyph" left the options (red and blue glasses, from 2011): if it is on, it
     * turns off once, through the game's own path — which reloads the resources, because the
     * textures are recolored on load.
     */
    private static void noAnaglyph() {
        GameSettings s = Minecraft.func_71410_x().field_71474_y;
        if (s.field_74337_g) {
            s.func_74306_a(GameSettings.Options.ANAGLYPH, 1);
            Log.info("lens", "3D Anaglyph turned off (the option left the video screen)");
        }
    }

    /**
     * The game's config/steinlens.json; -Dsteinlens.config=file reads another (measuring: each
     * variant with its own configuration, without touching the player's).
     */
    private static File configFile() {
        String other = LensOptions.CONFIG;
        return other != null
                ? new File(other)
                : new File(Minecraft.func_71410_x().field_71412_D, "config/steinlens.json");
    }

    /**
     * The Bridge's jar (or directory) joins the default packs (Minecraft.defaultResourcePacks): the
     * option texts and the key name come from it. The Stein Loader does the same with its jar and
     * the mods' jars; here we do not depend on it.
     */
    @SuppressWarnings("unchecked")
    private static void addResourcePack() {
        try {
            File self =
                    new File(
                            Lens.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            // The visual options compile like a mod, against the SRG jar where private members stay
            // closed: a private field is reached by reflection (ModRemap maps the SRG name).
            Field f = Minecraft.class.getDeclaredField("field_110449_ao");
            f.setAccessible(true);
            List<IResourcePack> packs = (List<IResourcePack>) f.get(Minecraft.func_71410_x());
            packs.add(
                    self.isDirectory() ? new FolderResourcePack(self) : new FileResourcePack(self));
        } catch (URISyntaxException | ReflectiveOperationException | RuntimeException e) {
            Log.info("lens", "option texts unavailable: " + e);
        }
    }

    // ---- pack textures (per-enchantment and connected blocks) ----

    /**
     * On every resource reload, before the atlas: reads the packs' rules and registers the
     * textures.
     */
    @Override
    public void textureStitch(Object textureMap) {
        customItems.reload((TextureMap) textureMap);
        connected.reload((TextureMap) textureMap);
    }

    /**
     * Atlas and models ready: the connected blocks find the tiles and the blocks that use the
     * rules.
     */
    @Override
    public void modelsReloaded() {
        connected.resolve();
        retryMissing(Minecraft.func_71410_x());
    }

    /**
     * The textures the reload left as "missing" and that exist again. When switching packs, the
     * game rereads every texture already used; one that only the pack that left had (the armor of a
     * CIT rule) becomes the "missing" texture forever, even when another pack brings the same file.
     * Runs after all the reload's listeners: nothing else is walking the texture list.
     */
    @SuppressWarnings("unchecked")
    private static void retryMissing(Minecraft mc) {
        try {
            TextureManager tm = mc.func_110434_K();
            // Private in the SRG jar the visual options compile against (see addResourcePack).
            Field f = TextureManager.class.getDeclaredField("field_110585_a");
            f.setAccessible(true);
            Map<ResourceLocation, ITextureObject> loaded =
                    (Map<ResourceLocation, ITextureObject>) f.get(tm);
            List<ResourceLocation> stuck = new ArrayList<>();
            for (Map.Entry<ResourceLocation, ITextureObject> e : loaded.entrySet()) {
                if (e.getValue() == TextureUtil.field_111001_a) {
                    stuck.add(e.getKey());
                }
            }
            for (ResourceLocation loc : stuck) {
                try {
                    mc.func_110442_L().func_110536_a(loc).func_110527_b().close();
                } catch (IOException absent) {
                    continue; // still missing
                }
                tm.func_110579_a(loc, new SimpleTexture(loc));
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.info("lens", "textures missing: " + e);
        }
    }

    /** The item's model: the one from the matching CIT rule, with the pack's texture. */
    @Override
    public Object itemModel(Object stack, Object model) {
        return config.customItems
                ? customItems.itemModel((ItemStack) stack, (IBakedModel) model)
                : model;
    }

    @Override
    public Object armorTexture(Object stack, int layer, String overlay, Object location) {
        return config.customItems
                ? customItems.armorTexture(
                        (ItemStack) stack, layer, overlay, (ResourceLocation) location)
                : location;
    }

    /** Chunk building threads: only the blocks that ConnectedTextures marked arrive here. */
    @Override
    public Object blockModel(Object world, Object state, Object pos, Object model) {
        return connected.blockModel(
                (IBlockAccess) world, (IBlockState) state, (BlockPos) pos, (IBakedModel) model);
    }

    // ---- dynamic FOV and zoom ----

    @Override
    public float fovModifier(float modifier) {
        return config.dynamicFov ? modifier : 1.0F;
    }

    @Override
    public float fov(float fov, float partialTicks, boolean useFovSetting) {
        diagPreset();
        updateZoom();
        // Applies to the world and to the hand.
        return zooming ? fov / config.zoomFactor : fov;
    }

    /**
     * {@code -Dsteinlens.diag.presetAtSec=S:P} (test): at S seconds of play, position P of the
     * graphics slider, through the same path as the slider on release.
     */
    private static final String DIAG_PRESET = LensOptions.DIAG_PRESET_AT_SEC;

    private static final long DIAG_START = System.nanoTime();
    private boolean diagPresetDone;

    private void diagPreset() {
        if (DIAG_PRESET == null || diagPresetDone) {
            return;
        }
        String[] p = DIAG_PRESET.split(":");
        if (p.length < 2) {
            diagPresetDone = true;
            Log.warn("lens", "diag: steinlens.diag.presetAtSec needs S:P, got " + DIAG_PRESET);
            return;
        }
        if (System.nanoTime() - DIAG_START < (long) (Double.parseDouble(p[0]) * 1e9)) {
            return;
        }
        diagPresetDone = true;
        // Like the slider on release: the preset is applied (slider position: 0 Fast, 1 Fancy).
        Presets.apply(this, Presets.at(Integer.parseInt(p[1].trim())));
        Log.info("lens", "diag: preset " + p[1]);
    }

    /** {@code -Dsteinlens.debug=true}: zoom state in the log (every 2 s and on every change). */
    private static final boolean DEBUG = LensOptions.DEBUG;

    private long debugCalls;
    private long debugLast;

    /** Turns the zoom on or off when the key changes — checked on every requested field of view. */
    private void updateZoom() {
        Minecraft mc = Minecraft.func_71410_x();
        boolean held =
                config.zoom
                        && zoomKey != null
                        && mc.field_71462_r == null
                        && zoomKey.func_151470_d();
        if (DEBUG) {
            debugZoom(mc);
        }
        if (held == zooming) {
            return;
        }
        if (DEBUG) {
            Log.info("lens", "zoom " + (held ? "on" : "off"));
        }
        zooming = held;
        GameSettings settings = mc.field_71474_y;
        if (held) {
            smoothBefore = settings.field_74326_T;
            smoothChanged = config.zoomSmoothCamera && !smoothBefore;
            if (smoothChanged) {
                settings.field_74326_T = true;
            }
        } else if (smoothChanged) {
            settings.field_74326_T = smoothBefore;
            smoothChanged = false;
            GameHooks.resetCameraSmoothing();
        }
        if (mc.field_71438_f != null) {
            mc.field_71438_f.func_174979_m();
        }
    }

    private void debugZoom(Minecraft mc) {
        debugCalls++;
        long now = System.nanoTime();
        if (now - debugLast > 2_000_000_000L) {
            debugLast = now;
            Log.info(
                    "lens",
                    "fov x"
                            + debugCalls
                            + " | key "
                            + (zoomKey == null
                                    ? "-"
                                    : zoomKey.func_151463_i() + " down=" + zoomKey.func_151470_d())
                            + " | screen "
                            + mc.field_71462_r
                            + " | zoom "
                            + zooming);
        }
    }

    // ---- options in the game's Video Settings ----

    /**
     * The options enter the game's own Video Settings list, by topic: the "Graphics" row becomes
     * the single selector (Fast, Fancy) and the game's options and ours sit side by side in each
     * one's topic. Without a screen, the part is button-free.
     */
    @Override
    public void guiInit(Object screen, List<Object> buttons) {
        if (screen instanceof GuiVideoSettings) {
            VideoOptions.patch((GuiScreen) screen, this);
        }
    }

    /**
     * The hint of the option under the mouse is found while drawing the list and comes out on top
     * of the screen.
     */
    @Override
    public void beforeDrawScreen(Object screen, int mouseX, int mouseY, float partialTicks) {
        if (screen instanceof GuiVideoSettings) {
            VideoOptions.beforeDraw();
        }
    }

    @Override
    public void afterDrawScreen(Object screen, int mouseX, int mouseY, float partialTicks) {
        if (screen instanceof GuiVideoSettings) {
            VideoOptions.drawHint((GuiScreen) screen, mouseX, mouseY);
        }
    }

    /** An option changed on screen: saves and applies. */
    void changed() {
        if (configFile != null) {
            config.save(configFile);
        }
        applyVisuals();
        if (connected.enabled() != config.connectedTextures) {
            connected.setEnabled(config.connectedTextures, true); // rebuilds the chunks
        }
    }

    /**
     * The options the engine applies (brightness, nametags, armor, angles, fog, clouds, chunks,
     * rain, 1.7 animations).
     */
    private void applyVisuals() {
        Visuals.setGlint(config.glint, config.glintNearBlocks);
        Visuals.setNametagDistance(config.nametagDistance);
        Visuals.setArmorVisible(config.armor);
        AngleTable.setCompact(config.compactAngles);
        Visuals.setFog(config.fog);
        Visuals.setCloudHeight(config.cloudHeight);
        DirectRender.setDirect(config.directRender);
        ChunkPace.setUploadsPerFrame(config.chunkUploads);
        ChunkPace.setWaitNeighbors(config.waitNeighbors);
        ChunkKeep.setMode(config.keepChunks, config.keepChunksMb);
        Rain.setHidden(!config.rain);
        OldAnimations.set(config.oldAnimations, oldAnimationParts(config));
        SteinPostFx.configure(config.bloom, config.smoothEdges);
        SteinHdr.configure(config.hdr, config.hdrWhite, config.hdrPeak);
    }

    /** The enabled parts of the 1.7 animations, in OldAnimations' bits. */
    private static int oldAnimationParts(LensConfig c) {
        return (c.oldBlockHit ? OldAnimations.BLOCK_HIT : 0)
                | (c.oldItems ? OldAnimations.ITEMS : 0)
                | (c.oldSwordBlock ? OldAnimations.SWORD_BLOCK : 0)
                | (c.oldBow ? OldAnimations.BOW : 0)
                | (c.oldRod ? OldAnimations.ROD : 0)
                | (c.oldBlockPose ? OldAnimations.BLOCK_POSE : 0)
                | (c.oldArmorHurt ? OldAnimations.ARMOR_HURT : 0);
    }
}
