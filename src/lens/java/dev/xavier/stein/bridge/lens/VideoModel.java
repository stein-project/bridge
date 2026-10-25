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

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.settings.GameSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Every image option, by topic — General, Camera, Light and shadows, Image, Sky and weather,
 * Textures, Players and items, 1.7 animations, Performance — as {@link VideoSettings} data: the
 * game's own options and Stein's side by side in the topic they belong to. "Graphics" is the Fast
 * to Fancy selector that also sets the game's performance options ({@link Presets}); "3D Anaglyph"
 * is not offered ({@link Lens} turns it off if it is on).
 *
 * <p>The game's options change the way its own buttons change them (setOptionValue for steps,
 * setOptionFloatValue for ranges), so they keep behaving like the game's: the game reloads its
 * renderer where it would.
 */
final class VideoModel implements VideoSettings.Source {

    /** Nametag distances, in blocks; 0 = the game's. */
    private static final int[] NAMETAG_STEPS = {0, 48, 32, 16, 8};

    /** Cloud heights above the game's, in blocks. */
    private static final int[] CLOUD_STEPS = {0, 16, 32, 64, 128};

    /** Chunks sent per frame; 0 = as in the game. */
    private static final int[] UPLOAD_STEPS = {0, 1, 2, 4, 8};

    /** Limits of the stored terrain, in MB. */
    private static final int[] KEEP_STEPS = {256, 512, 1024, 2048};

    /** HDR calibration ranges, in nits: the white of the menus and the screen's peak. */
    static final int HDR_WHITE_MIN = 80;

    static final int HDR_WHITE_MAX = 400;
    static final int HDR_PEAK_MIN = 400;
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
        camera();
        light();
        image();
        sky();
        textures();
        players();
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

    private void general() {
        topic("general");
        entries.add(
                Entry.slider(
                        () -> tr("steinlens.graphics"),
                        () -> tr("steinlens.graphics." + Presets.current().key()),
                        () -> tr("steinlens.graphics.hint"),
                        null,
                        () -> Presets.current().ordinal() / (double) Presets.MAX_POSITION,
                        f -> preset((int) Math.round(f * Presets.MAX_POSITION)),
                        true));
        game(GameSettings.Options.RENDER_DISTANCE, true);
        game(GameSettings.Options.FRAMERATE_LIMIT, false);
        game(GameSettings.Options.ENABLE_VSYNC, false);
        game(GameSettings.Options.USE_FULLSCREEN, false);
        game(GameSettings.Options.GUI_SCALE, false);
        game(GameSettings.Options.GAMMA, false);
        game(GameSettings.Options.PARTICLES, false);
        graphicsApi();
    }

    /** A preset position: the game's and Stein's options together, the chunks rebuilt. */
    private void preset(int position) {
        Presets.Position p = Presets.at(position);
        if (p == Presets.current()) {
            return;
        }
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

    private void camera() {
        topic("camera");
        game(GameSettings.Options.VIEW_BOBBING, false);
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
    }

    private void light() {
        topic("light");
        game(GameSettings.Options.AMBIENT_OCCLUSION, false);
        game(GameSettings.Options.ENTITY_SHADOWS, false);
    }

    /** Stein's post-processing (SteinPostFx, SteinHdr): only where Stein GFX draws. */
    private void image() {
        topic("image");
        BooleanSupplier post = SteinPostFx::available;
        choice(
                "steinlens.bloom",
                () ->
                        c.bloom == SteinPostFx.BLOOM_HIGH
                                ? tr("steinlens.bloom.high")
                                : c.bloom == SteinPostFx.BLOOM_LOW
                                        ? tr("steinlens.bloom.low")
                                        : tr("options.off"),
                () -> c.bloom = (c.bloom + 1) % 3,
                () -> c.bloom = (c.bloom + 2) % 3,
                post);
        toggle(
                "steinlens.smoothEdges",
                () -> c.smoothEdges,
                () -> c.smoothEdges = !c.smoothEdges,
                post);
        toggle("steinlens.hdr", () -> c.hdr, () -> c.hdr = !c.hdr, SteinHdr::available);
        last("hdr");
        BooleanSupplier hdrOn = () -> c.hdr && SteinHdr.available();
        nits(
                "steinlens.hdrWhite",
                () -> c.hdrWhite,
                v -> c.hdrWhite = v,
                HDR_WHITE_MIN,
                HDR_WHITE_MAX,
                10,
                hdrOn);
        last("hdrWhite");
        nits(
                "steinlens.hdrPeak",
                () -> c.hdrPeak,
                v -> c.hdrPeak = v,
                HDR_PEAK_MIN,
                HDR_PEAK_MAX,
                50,
                hdrOn);
        last("hdrPeak");
    }

    private void sky() {
        topic("sky");
        game(GameSettings.Options.RENDER_CLOUDS, false);
        steps(
                "steinlens.cloudHeight",
                CLOUD_STEPS,
                () -> c.cloudHeight,
                v -> c.cloudHeight = v,
                v -> v > 0 ? tr("steinlens.cloudHeight.blocks", v) : tr("steinlens.default"),
                null);
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
    }

    private void textures() {
        topic("textures");
        game(GameSettings.Options.MIPMAP_LEVELS, true);
        game(GameSettings.Options.BLOCK_ALTERNATIVES, false);
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

    private void players() {
        topic("players");
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
        game(GameSettings.Options.USE_VBO, false);
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

    /** Names the option just added, for the screens that look for it (the HDR calibration). */
    private void last(String id) {
        entries.get(entries.size() - 1).id(id);
    }

    private void topic(String id) {
        entries = new ArrayList<>();
        sections.add(new Section(id, () -> tr("steinlens.topic." + id), entries));
    }

    /** A game option, changed as the game's own button would change it. */
    private void game(GameSettings.Options o, boolean onRelease) {
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
                            onRelease));
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
