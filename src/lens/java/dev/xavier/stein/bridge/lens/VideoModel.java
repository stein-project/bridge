/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.lens;

import dev.xavier.stein.bridge.engine.ChunkKeep;
import dev.xavier.stein.bridge.engine.Visuals;
import dev.xavier.stein.bridge.showcase.VideoSettings;
import dev.xavier.stein.bridge.showcase.VideoSettings.Entry;
import dev.xavier.stein.bridge.showcase.VideoSettings.Section;
import dev.xavier.stein.gfx.SteinBackend;
import dev.xavier.stein.gfx.SteinHdr;
import dev.xavier.stein.gfx.SteinPostFx;
import dev.xavier.stein.gfx.SteinRayLight;
import dev.xavier.stein.gfx.SteinShadows;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiYesNo;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.settings.GameSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Every image option, by topic, as {@link VideoSettings} data: the game's own options and Stein's
 * side by side in the topic they belong to.
 *
 * <ul>
 *   <li><b>General</b>: what most players change — the quality level, which sets the options of the
 *       other topics ({@link Presets}) and replaces the game's Fast/Fancy, the graphics API, the
 *       distance, the frame limit, the screen and the brightness;
 *   <li><b>Image</b>: light, particles, Stein's post-processing and HDR;
 *   <li><b>World</b>: sky, weather and textures;
 *   <li><b>Camera and players</b>, <b>1.7 animations</b> and <b>Performance</b>.
 * </ul>
 *
 * <p>"3D Anaglyph" is not offered ({@link Lens} turns it off if it is on). Every track applies
 * while it is dragged.
 *
 * <p>The game's options change the way its own buttons change them (setOptionValue for steps,
 * setOptionFloatValue for ranges), so they keep behaving like the game's: the game reloads its
 * renderer where it would.
 */
final class VideoModel implements VideoSettings.Source {

    /** Nametag distances, in blocks; 0 = the game's. */
    private static final int[] NAMETAG_STEPS = {0, 48, 32, 16, 8};

    /** The highest the clouds go above the game's, in blocks (in steps of 4). */
    private static final int CLOUD_MAX = 128;

    /** Chunks sent per frame; 0 = as in the game. */
    private static final int[] UPLOAD_STEPS = {0, 1, 2, 4, 8};

    /** Anisotropic filtering, 1 = off. */
    private static final int[] ANISOTROPY_STEPS = {1, 2, 4, 8, 16};

    /** The game's highest mipmap level. */
    private static final int MIPMAP_MAX = 4;

    /** Limits of the stored terrain, in MB. */
    private static final int[] KEEP_STEPS = {256, 512, 1024, 2048};

    /** HDR calibration ranges, in nits: the white of the menus and the screen's peak. */
    static final int HDR_WHITE_MIN = 80;

    static final int HDR_WHITE_MAX = 400;
    static final int HDR_PEAK_MIN = 200;
    static final int HDR_PEAK_MAX = 2000;

    private final Lens mod;
    private final LensConfig c;

    /** The topics being built. */
    private List<Section> sections;

    private List<Entry> entries;

    VideoModel(Lens mod) {
        this.mod = mod;
        this.c = mod.config;
    }

    // ---- VideoSettings.Source ----

    @Override
    public List<Section> sections() {
        sections = new ArrayList<>();
        general();
        image();
        light();
        world();
        camera();
        animations();
        performance();
        List<Section> out = sections;
        sections = null;
        return out;
    }

    @Override
    public void changed() {
        mod.changed();
    }

    @Override
    public void closed() {
        Minecraft.func_71410_x().field_71474_y.func_74303_b();
        mod.changed();
    }

    // ---- the topics ----

    /**
     * What most players change: the quality level, the graphics API, the distance, the frame limit,
     * the screen, the interface size and the brightness.
     */
    private void general() {
        topic("general");
        entries.add(
                Entry.choice(
                        () -> tr("steinlens.quality"),
                        () -> {
                            Presets.Position p = Presets.current(c);
                            return tr(
                                    p == null
                                            ? "steinlens.quality.custom"
                                            : "steinlens.quality." + p.key());
                        },
                        () -> tr("steinlens.quality.hint"),
                        null,
                        null,
                        () -> preset(1),
                        () -> preset(-1)));
        graphicsApi();
        game(GameSettings.Options.RENDER_DISTANCE);
        game(GameSettings.Options.FRAMERATE_LIMIT);
        game(GameSettings.Options.ENABLE_VSYNC);
        game(GameSettings.Options.USE_FULLSCREEN);
        game(GameSettings.Options.GUI_SCALE);
        game(GameSettings.Options.GAMMA);
    }

    /**
     * The next or previous quality level: the game's and Stein's options together, the chunks
     * rebuilt. From Custom, it starts at the game's Fancy.
     */
    private void preset(int by) {
        Presets.Position now = Presets.current(c);
        int from =
                now == null ? Presets.Position.FANCY.ordinal() - Integer.signum(by) : now.ordinal();
        Presets.Position p = Presets.at(from + by);
        if (p.rayTracing() && !SteinBackend.rayTracingSupported()) {
            return; // the Stein mode needs a GPU with ray tracing on Vulkan
        }
        if (p == now) {
            return;
        }
        if (p.rayTracing()) {
            confirmStein(p);
            return;
        }
        applyPreset(p);
    }

    /**
     * The Stein mode asks first: its terms (a joke, and what it really needs), then applies on
     * "accept" and comes back to the settings either way.
     */
    private void confirmStein(Presets.Position p) {
        Minecraft mc = Minecraft.func_71410_x();
        GuiScreen back = mc.field_71462_r;
        StringBuilder terms = new StringBuilder();
        for (int i = 1; i <= 4; i++) {
            terms.append(tr("steinlens.stein.term" + i)).append('\n');
        }
        terms.append('\n').append(tr("steinlens.stein.needs"));
        mc.func_147108_a(
                new GuiYesNo(
                        (accepted, id) -> {
                            if (accepted) {
                                applyPreset(p);
                            }
                            mc.func_147108_a(back);
                        },
                        tr("steinlens.stein.title"),
                        terms.toString(),
                        tr("steinlens.stein.accept"),
                        tr("steinlens.stein.cancel"),
                        0));
    }

    private void applyPreset(Presets.Position p) {
        if (Presets.apply(mod, p)) {
            Minecraft mc = Minecraft.func_71410_x();
            if (mc.field_71438_f != null) {
                mc.field_71438_f.func_72712_a(); // the game rebuilds the chunks on Fast/Fancy
            }
        }
    }

    /**
     * The graphics API in use (OpenGL or Vulkan), shown only: it is chosen in the Stein Client,
     * before the game opens, since the window is created for one of them. If Vulkan did not start
     * on this computer, or the last start with it died on the way, the hint says so.
     */
    private void graphicsApi() {
        SteinBackend.Fallback why = SteinBackend.fallback();
        String hint =
                SteinBackend.crashedLastTime()
                        ? tr("steinlens.graphicsApi.crashed")
                        : why == null
                                ? tr("steinlens.graphicsApi.hint")
                                : tr(
                                        "steinlens.graphicsApi.unavailable",
                                        tr(
                                                "steinlens.graphicsApi.reason."
                                                        + why.name().toLowerCase(Locale.ROOT)));
        entries.add(
                Entry.info(
                        () -> tr("steinlens.graphicsApi"),
                        () -> SteinBackend.running() == SteinBackend.VULKAN ? "Vulkan" : "OpenGL",
                        () -> hint));
    }

    /**
     * Light and shadows (only where Stein GFX draws): the sun's shadows in levels, the light that
     * comes with them, the moonlight, the darkness, the dynamic lights and the atmosphere.
     */
    private void light() {
        topic("light");
        BooleanSupplier shadowsCan = SteinShadows::available;
        choice(
                "steinlens.shadows",
                () ->
                        !shadowsCan.getAsBoolean() || c.shadows == 0
                                ? tr("options.off")
                                : tr("steinlens.shadows." + c.shadows),
                () -> c.shadows = (c.shadows + 1) % 4,
                () -> c.shadows = (c.shadows + 3) % 4,
                shadowsCan);
        BooleanSupplier gfx = SteinPostFx::available;
        toggle(
                "steinlens.moonlight",
                shown(() -> c.moonlight, gfx),
                () -> c.moonlight = !c.moonlight,
                gfx);
        toggle(
                "steinlens.darkness",
                shown(() -> c.darkness, gfx),
                () -> c.darkness = !c.darkness,
                gfx);
        choice(
                "steinlens.glowingBlocks",
                () ->
                        !gfx.getAsBoolean() || c.glowingBlocks == 0
                                ? tr("options.off")
                                : tr("steinlens.glowingBlocks." + c.glowingBlocks),
                () -> c.glowingBlocks = (c.glowingBlocks + 1) % 3,
                () -> c.glowingBlocks = (c.glowingBlocks + 2) % 3,
                gfx);
        toggle(
                "steinlens.dynamicLights",
                shown(() -> c.dynamicLights, gfx),
                () -> c.dynamicLights = !c.dynamicLights,
                gfx);
        choice(
                "steinlens.atmosphere",
                () ->
                        !gfx.getAsBoolean() || c.atmosphere == 0
                                ? tr("options.off")
                                : tr("steinlens.atmosphere." + c.atmosphere),
                () -> c.atmosphere = (c.atmosphere + 1) % 3,
                () -> c.atmosphere = (c.atmosphere + 2) % 3,
                gfx);
        toggle("steinlens.sky", shown(() -> c.sky, gfx), () -> c.sky = !c.sky, gfx);
    }

    /**
     * How the picture is lit and finished: the game's light and particles, Stein's post-processing
     * (only where Stein GFX draws) and HDR (only where the screen and the API can show it).
     */
    private void image() {
        topic("image");
        game(GameSettings.Options.AMBIENT_OCCLUSION);
        game(GameSettings.Options.ENTITY_SHADOWS);
        game(GameSettings.Options.PARTICLES);
        BooleanSupplier post = SteinPostFx::available;
        choice(
                "steinlens.bloom",
                () ->
                        !post.getAsBoolean() || c.bloom == SteinPostFx.BLOOM_OFF
                                ? tr("options.off")
                                : c.bloom == SteinPostFx.BLOOM_HIGH
                                        ? tr("steinlens.bloom.high")
                                        : c.bloom == SteinPostFx.BLOOM_LOW
                                                ? tr("steinlens.bloom.low")
                                                : tr("options.off"),
                () -> c.bloom = (c.bloom + 1) % 3,
                () -> c.bloom = (c.bloom + 2) % 3,
                post);
        toggle(
                "steinlens.smoothEdges",
                shown(() -> c.smoothEdges, post),
                () -> c.smoothEdges = !c.smoothEdges,
                post);
        // the special light effects: the Stein mode (ray tracing) only
        BooleanSupplier stein = () -> post.getAsBoolean() && SteinRayLight.active();
        choice(
                "steinlens.occlusion",
                () ->
                        !stein.getAsBoolean() || c.occlusion == 0
                                ? tr("options.off")
                                : tr("steinlens.occlusion." + c.occlusion),
                () -> c.occlusion = (c.occlusion + 1) % 3,
                () -> c.occlusion = (c.occlusion + 2) % 3,
                stein);
        toggle(
                "steinlens.reflections",
                shown(() -> c.reflections, stein),
                () -> c.reflections = !c.reflections,
                stein);
        filters(post);
        BooleanSupplier hdrCan = SteinHdr::available;
        toggle("steinlens.hdr", shown(() -> c.hdr, hdrCan), () -> c.hdr = !c.hdr, hdrCan);
        last("hdr");
        BooleanSupplier hdrOn = () -> c.hdr && SteinHdr.available();
        entries.add(
                Entry.choice(
                        () -> tr("steinlens.hdrLook"),
                        () ->
                                tr(
                                        c.hdrLook
                                                ? "steinlens.hdrLook.stein"
                                                : "steinlens.hdrLook.natural"),
                        () -> tr("steinlens.hdrLook.hint"),
                        hdrOn,
                        null,
                        () -> c.hdrLook = !c.hdrLook,
                        null));
        entries.add(
                Entry.choice(
                        () -> tr("steinlens.hdrQuality"),
                        () ->
                                tr(
                                        c.hdrQuick
                                                ? "steinlens.hdrQuality.quick"
                                                : "steinlens.hdrQuality.max"),
                        () -> tr("steinlens.hdrQuality.hint"),
                        hdrOn,
                        null,
                        () -> c.hdrQuick = !c.hdrQuick,
                        null));
        nits(
                "steinlens.hdrPeak",
                () -> c.hdrPeak,
                v -> c.hdrPeak = v,
                HDR_PEAK_MIN,
                HDR_PEAK_MAX,
                25,
                hdrOn);
        last("hdrPeak").byDefault(fraction(LensConfig.HDR_PEAK, HDR_PEAK_MIN, HDR_PEAK_MAX));
        entries.add(
                Entry.slider(
                        () -> tr("steinlens.hdrBlack"),
                        () -> {
                            int v = c.hdrBlack - 50;
                            return v == 0 ? tr("steinlens.default") : (v > 0 ? "+" : "") + v;
                        },
                        () -> tr("steinlens.hdrBlack.hint"),
                        hdrOn,
                        () -> c.hdrBlack / 100.0,
                        f -> c.hdrBlack = (int) Math.round(f * 100),
                        false));
        last("hdrBlack").byDefault(LensConfig.HDR_BLACK / 100.0);
        nits(
                "steinlens.hdrWhite",
                () -> c.hdrWhite,
                v -> c.hdrWhite = v,
                HDR_WHITE_MIN,
                HDR_WHITE_MAX,
                10,
                hdrOn);
        last("hdrWhite").byDefault(fraction(LensConfig.HDR_WHITE, HDR_WHITE_MIN, HDR_WHITE_MAX));
    }

    /**
     * The texture filters (SteinTextureFilter, SteinPostFx), only where Stein GFX draws:
     * anisotropic filtering and the supersampled pixels (both with mipmaps: their taps stay inside
     * each texture's cell of the atlas, known only then) and the sharpening.
     */
    private void filters(BooleanSupplier post) {
        GameSettings s = Minecraft.func_71410_x().field_71474_y;
        BooleanSupplier mips = () -> s.field_151442_I > 0 && post.getAsBoolean();
        steps(
                "steinlens.anisotropy",
                ANISOTROPY_STEPS,
                () -> mips.getAsBoolean() ? c.anisotropy : 1,
                v -> c.anisotropy = v,
                v -> v > 1 ? v + "x" : tr("options.off"),
                mips);
        toggle(
                "steinlens.supersample",
                shown(() -> c.supersample, mips),
                () -> c.supersample = !c.supersample,
                mips);
        choice(
                "steinlens.sharpen",
                () ->
                        !post.getAsBoolean() || c.sharpen == 0
                                ? tr("options.off")
                                : tr("steinlens.sharpen." + c.sharpen),
                () -> c.sharpen = (c.sharpen + 1) % (SteinPostFx.SHARPEN_MAX + 1),
                () ->
                        c.sharpen =
                                (c.sharpen + SteinPostFx.SHARPEN_MAX)
                                        % (SteinPostFx.SHARPEN_MAX + 1),
                post);
    }

    /** The world around: sky and weather, then the textures of blocks and items. */
    private void world() {
        topic("world");
        game(GameSettings.Options.RENDER_CLOUDS);
        entries.add(
                Entry.slider(
                        () -> tr("steinlens.cloudHeight"),
                        () ->
                                c.cloudHeight > 0
                                        ? tr("steinlens.cloudHeight.blocks", c.cloudHeight)
                                        : tr("steinlens.default"),
                        () -> tr("steinlens.cloudHeight.hint"),
                        null,
                        () -> c.cloudHeight / (double) CLOUD_MAX,
                        f -> c.cloudHeight = (int) Math.round(f * CLOUD_MAX / 4) * 4,
                        false));
        choice(
                "steinlens.fog",
                () ->
                        c.fog == Visuals.FOG_LIGHT
                                ? tr("steinlens.fog.light")
                                : c.fog == Visuals.FOG_OFF
                                        ? tr("options.off")
                                        : tr("steinlens.default"),
                () -> c.fog = (c.fog + 1) % 3,
                () -> c.fog = (c.fog + 2) % 3,
                null);
        toggle("steinlens.rain", () -> c.rain, () -> c.rain = !c.rain, null);
        mipmaps();
        game(GameSettings.Options.BLOCK_ALTERNATIVES);
        toggleText(
                "steinlens.connectedTextures",
                () -> c.connectedTextures,
                () -> c.connectedTextures = !c.connectedTextures,
                () -> tr("steinlens.connectedTextures.hint", mod.connected.ruleCount()),
                null);
        toggleText(
                "steinlens.customItems",
                () -> c.customItems,
                () -> c.customItems = !c.customItems,
                () -> tr("steinlens.customItems.hint", mod.customItems.ruleCount()),
                null);
        entries.add(
                Entry.choice(
                        () -> tr("steinlens.glint"),
                        () ->
                                c.glint == Visuals.GLINT_NEAR
                                        ? tr("steinlens.glint.near")
                                        : c.glint == Visuals.GLINT_OFF
                                                ? tr("options.off")
                                                : tr("options.on"),
                        () -> tr("steinlens.glint.hint", Math.round(c.glintNearBlocks)),
                        null,
                        null,
                        () -> c.glint = (c.glint + 1) % 3,
                        () -> c.glint = (c.glint + 2) % 3));
    }

    /**
     * The mipmap levels, step by step instead of a track: each change reloads every texture of the
     * game (seconds), which a track would do at every step of a drag.
     */
    private void mipmaps() {
        GameSettings s = Minecraft.func_71410_x().field_71474_y;
        GameSettings.Options o = GameSettings.Options.MIPMAP_LEVELS;
        entries.add(
                Entry.choice(
                        () -> tr(o.func_74378_d()),
                        () -> valueOf(s.func_74297_c(o)),
                        null,
                        null,
                        null,
                        () -> s.func_74304_a(o, (s.field_151442_I + 1) % (MIPMAP_MAX + 1)),
                        () ->
                                s.func_74304_a(
                                        o, (s.field_151442_I + MIPMAP_MAX) % (MIPMAP_MAX + 1))));
    }

    /** The camera, then how the other players and their names are shown. */
    private void camera() {
        topic("camera");
        game(GameSettings.Options.FOV);
        game(GameSettings.Options.VIEW_BOBBING);
        toggle(
                "steinlens.dynamicFov",
                () -> c.dynamicFov,
                () -> c.dynamicFov = !c.dynamicFov,
                null);
        toggleText(
                "steinlens.zoom",
                () -> c.zoom,
                () -> c.zoom = !c.zoom,
                () -> tr("steinlens.zoom.hint", zoomKeyName()),
                null);
        toggle(
                "steinlens.zoomSmooth",
                () -> c.zoomSmoothCamera,
                () -> c.zoomSmoothCamera = !c.zoomSmoothCamera,
                () -> c.zoom);
        entries.add(
                Entry.choice(
                        () -> tr("steinlens.armor"),
                        () -> tr(c.armor ? "steinlens.armor.shown" : "steinlens.armor.hidden"),
                        () -> tr("steinlens.armor.hint"),
                        null,
                        () -> c.armor,
                        () -> c.armor = !c.armor,
                        null));
        steps(
                "steinlens.nametags",
                NAMETAG_STEPS,
                () -> c.nametagDistance,
                v -> c.nametagDistance = v,
                v -> v > 0 ? tr("steinlens.nametags.blocks", v) : tr("steinlens.nametags.default"),
                null);
    }

    /**
     * 1.7 animations (engine/OldAnimations): the master switch and each part, which only counts
     * with it on.
     */
    private void animations() {
        topic("animations");
        toggle(
                "steinlens.oldAnimations",
                () -> c.oldAnimations,
                () -> c.oldAnimations = !c.oldAnimations,
                null);
        BooleanSupplier old = () -> c.oldAnimations;
        toggle(
                "steinlens.oldBlockHit",
                () -> c.oldBlockHit,
                () -> c.oldBlockHit = !c.oldBlockHit,
                old);
        toggle("steinlens.oldItems", () -> c.oldItems, () -> c.oldItems = !c.oldItems, old);
        toggle(
                "steinlens.oldSwordBlock",
                () -> c.oldSwordBlock,
                () -> c.oldSwordBlock = !c.oldSwordBlock,
                old);
        toggle("steinlens.oldBow", () -> c.oldBow, () -> c.oldBow = !c.oldBow, old);
        toggle("steinlens.oldRod", () -> c.oldRod, () -> c.oldRod = !c.oldRod, old);
        toggle(
                "steinlens.oldBlockPose",
                () -> c.oldBlockPose,
                () -> c.oldBlockPose = !c.oldBlockPose,
                old);
        toggle(
                "steinlens.oldArmorHurt",
                () -> c.oldArmorHurt,
                () -> c.oldArmorHurt = !c.oldArmorHurt,
                old);
    }

    private void performance() {
        topic("performance");
        game(GameSettings.Options.USE_VBO);
        toggle(
                "steinlens.directRender",
                () -> c.directRender,
                () -> c.directRender = !c.directRender,
                null);
        toggle(
                "steinlens.compactAngles",
                () -> c.compactAngles,
                () -> c.compactAngles = !c.compactAngles,
                null);
        steps(
                "steinlens.chunkUploads",
                UPLOAD_STEPS,
                () -> c.chunkUploads,
                v -> c.chunkUploads = v,
                v -> v > 0 ? String.valueOf(v) : tr("steinlens.default"),
                null);
        toggle(
                "steinlens.waitNeighbors",
                () -> c.waitNeighbors,
                () -> c.waitNeighbors = !c.waitNeighbors,
                null);
        choice(
                "steinlens.keep",
                () ->
                        c.keepChunks == ChunkKeep.MEMORY
                                ? tr("steinlens.keep.memory")
                                : c.keepChunks == ChunkKeep.DISK
                                        ? tr("steinlens.keep.disk")
                                        : tr("options.off"),
                () -> c.keepChunks = (c.keepChunks + 1) % 3,
                () -> c.keepChunks = (c.keepChunks + 2) % 3,
                null);
        steps(
                "steinlens.keepLimit",
                KEEP_STEPS,
                () -> c.keepChunksMb,
                v -> c.keepChunksMb = v,
                v -> v >= 1024 ? v / 1024 + " GB" : v + " MB",
                () -> c.keepChunks != ChunkKeep.OFF);
    }

    // ---- building blocks ----

    /**
     * What a switch shows: off where it cannot act (an option of Stein GFX or of Vulkan on another
     * renderer), while the saved choice stays as it is for when it can.
     */
    private static BooleanSupplier shown(BooleanSupplier value, BooleanSupplier can) {
        return () -> value.getAsBoolean() && can.getAsBoolean();
    }

    /** Names the option just added, for the screens that look for it (the HDR calibration). */
    private Entry last(String id) {
        return entries.get(entries.size() - 1).id(id);
    }

    private static double fraction(int value, int min, int max) {
        return (value - min) / (double) (max - min);
    }

    private void topic(String id) {
        entries = new ArrayList<>();
        sections.add(new Section(id, () -> tr("steinlens.topic." + id), entries));
    }

    /** A game option, changed as the game's own button would change it. */
    private void game(GameSettings.Options o) {
        GameSettings s = Minecraft.func_71410_x().field_71474_y;
        Supplier<String> label = () -> tr(o.func_74378_d());
        Supplier<String> value = () -> valueOf(s.func_74297_c(o));
        if (o.func_74380_a()) {
            entries.add(
                    Entry.slider(
                            label,
                            value,
                            null,
                            null,
                            () -> o.func_148266_c(s.func_74296_a(o)),
                            f -> s.func_74304_a(o, o.func_148262_d((float) f)),
                            false));
            return;
        }
        BooleanSupplier state = o.func_74382_b() ? () -> s.func_74308_b(o) : null;
        entries.add(
                Entry.choice(label, value, null, null, state, () -> s.func_74306_a(o, 1), null));
    }

    /** The value part of the game's "Name: value" text. */
    private static String valueOf(String text) {
        int i = text.indexOf(": ");
        return i < 0 ? text : text.substring(i + 2);
    }

    /** An on/off option of ours, whose hint is the key + ".hint". */
    private void toggle(String key, BooleanSupplier value, Runnable flip, BooleanSupplier enabled) {
        toggleText(key, value, flip, () -> tr(key + ".hint"), enabled);
    }

    private void toggleText(
            String key,
            BooleanSupplier value,
            Runnable flip,
            Supplier<String> hint,
            BooleanSupplier enabled) {
        entries.add(
                Entry.choice(
                        () -> tr(key),
                        () -> tr(value.getAsBoolean() ? "options.on" : "options.off"),
                        hint,
                        enabled,
                        value,
                        flip,
                        flip));
    }

    private void choice(
            String key,
            Supplier<String> value,
            Runnable next,
            Runnable previous,
            BooleanSupplier enabled) {
        entries.add(
                Entry.choice(
                        () -> tr(key),
                        value,
                        () -> tr(key + ".hint"),
                        enabled,
                        null,
                        next,
                        previous));
    }

    /** A value from a fixed list, forward and back. */
    private void steps(
            String key,
            int[] steps,
            IntSupplier get,
            java.util.function.IntConsumer set,
            java.util.function.IntFunction<String> text,
            BooleanSupplier enabled) {
        choice(
                key,
                () -> text.apply(get.getAsInt()),
                () -> set.accept(step(steps, get.getAsInt(), 1)),
                () -> set.accept(step(steps, get.getAsInt(), -1)),
                enabled);
    }

    /** A brightness in nits on a track, in steps of {@code step}. */
    private void nits(
            String key,
            IntSupplier get,
            java.util.function.IntConsumer set,
            int min,
            int max,
            int step,
            BooleanSupplier enabled) {
        entries.add(
                Entry.slider(
                        () -> tr(key),
                        () -> tr("steinlens.nits", get.getAsInt()),
                        () -> tr(key + ".hint"),
                        enabled,
                        () -> (get.getAsInt() - min) / (double) (max - min),
                        f -> set.accept(min + (int) Math.round(f * (max - min) / step) * step),
                        false));
    }

    private static int step(int[] steps, int value, int by) {
        for (int i = 0; i < steps.length; i++) {
            if (steps[i] == value) {
                return steps[((i + by) % steps.length + steps.length) % steps.length];
            }
        }
        return steps[0];
    }

    private String zoomKeyName() {
        return mod.zoomKey != null ? GameSettings.func_74298_c(mod.zoomKey.func_151463_i()) : "V";
    }

    static String tr(String key, Object... args) {
        return I18n.func_135052_a(key, args);
    }
}
