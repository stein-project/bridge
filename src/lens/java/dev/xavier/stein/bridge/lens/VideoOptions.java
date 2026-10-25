/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.lens;

import dev.xavier.stein.bridge.core.Log;
import dev.xavier.stein.bridge.engine.ChunkKeep;
import dev.xavier.stein.bridge.engine.Visuals;
import dev.xavier.stein.gfx.SteinBackend;
import dev.xavier.stein.gfx.SteinHdr;
import dev.xavier.stein.gfx.SteinPostFx;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiListExtended;
import net.minecraft.client.gui.GuiOptionButton;
import net.minecraft.client.gui.GuiOptionSlider;
import net.minecraft.client.gui.GuiOptionsRowList;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.settings.GameSettings;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * All the image options on a single screen: the game's own Video Settings, reorganized by topic —
 * General, Camera, Light and shadows, Sky and weather, Textures, Players and items, 1.7 animations,
 * Performance. The game's options and Stein's sit side by side in the topic they belong to; there
 * is no separate screen or button. "Graphics" becomes the Fast and Fancy selector ({@link
 * GraphicsSlider}), and "3D Anaglyph" is gone (it is not in the list, and {@link Lens} turns it off
 * if it is on).
 *
 * <p>The game's list (GuiOptionsRowList) and its rows are private; the fields are found by type,
 * never by name, so as not to depend on obfuscation. The game's buttons are created the way it
 * creates them (GuiOptionSlider for range options, GuiOptionButton for the rest), so they keep
 * behaving like its own: the row (GuiOptionsRowList.Row) applies the game's option by itself when
 * the button is a GuiOptionButton. Ours take care of themselves in their own mousePressed.
 *
 * <p>The hint of the option under the mouse appears in a small box next to the cursor, drawn after
 * the screen ({@link Lens#afterDrawScreen}).
 */
final class VideoOptions {

    /** Width of the buttons, the same as the game's. */
    private static final int BUTTON_WIDTH = 150;

    /** Id of our first button, far from the game's (which use the option's number). */
    private static final int FIRST_BUTTON_ID = 900;

    /** Nametag distances, in blocks; 0 = the game's. */
    private static final int[] NAMETAG_STEPS = {0, 48, 32, 16, 8};

    /** Cloud heights above the game's, in blocks. */
    private static final int[] CLOUD_STEPS = {0, 16, 32, 64, 128};

    /** Chunks sent per frame; 0 = as in the game. */
    private static final int[] UPLOAD_STEPS = {0, 1, 2, 4, 8};

    /** HDR calibration steps, in nits: the white of the menus and the screen's peak. */
    private static final int[] HDR_WHITE_STEPS = {100, 150, 200, 250, 300, 400};

    private static final int[] HDR_PEAK_STEPS = {400, 600, 800, 1000, 1400, 2000};

    /** Limits of the stored terrain, in MB. */
    private static final int[] KEEP_STEPS = {256, 512, 1024, 2048};

    /** Hint of the button under the mouse in the frame, for the box next to the cursor. */
    private static String hint = "";

    private final Lens mod;
    private final LensConfig c;
    private final List<GuiOptionsRowList.Row> rows;
    private final int left;
    private final int right;

    /** Our buttons and the game's, to rewrite the labels when a preset changes everything. */
    private final List<OptionButton> ours = new ArrayList<>();

    private final List<GuiButton> game = new ArrayList<>();
    private GraphicsSlider slider;

    /** Button waiting for a pair in the row. */
    private GuiButton pending;

    private int nextId = FIRST_BUTTON_ID;

    private VideoOptions(Lens mod, List<GuiOptionsRowList.Row> rows, int[] columns) {
        this.mod = mod;
        this.c = mod.config;
        this.rows = rows;
        this.left = columns[0];
        this.right = columns[1];
    }

    /** Called in the Video Settings' onGuiInit. */
    static void patch(GuiScreen screen, Lens mod) {
        try {
            GuiListExtended list = (GuiListExtended) byType(screen, GuiListExtended.class);
            if (list == null) {
                return;
            }
            @SuppressWarnings("unchecked")
            List<GuiOptionsRowList.Row> rows =
                    (List<GuiOptionsRowList.Row>) byType(list, List.class);
            if (rows == null) {
                return;
            }
            // The list does not move the buttons horizontally: each is born in the right column. We
            // copy the game's columns.
            int[] columns = columns(rows);
            VideoOptions v = new VideoOptions(mod, rows, columns);
            rows.clear();
            v.build();
            Log.info("lens", "video options: " + rows.size() + " rows");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            Log.info("lens", "game video options left untouched: " + e);
        }
    }

    // ---- the rows ----

    private void build() {
        buildGeneral();
        buildCamera();
        buildLight();
        buildImage();
        buildSky();
        buildTextures();
        buildPlayers();
        buildAnimations();
        buildPerformance();
        flush();
    }

    private void buildGeneral() {
        header("general");
        slider = new GraphicsSlider(nextId++, BUTTON_WIDTH, mod, this);
        button(slider);
        game(GameSettings.Options.RENDER_DISTANCE);
        game(GameSettings.Options.FRAMERATE_LIMIT);
        game(GameSettings.Options.ENABLE_VSYNC);
        game(GameSettings.Options.USE_FULLSCREEN);
        game(GameSettings.Options.GUI_SCALE);
        game(GameSettings.Options.GAMMA);
        game(GameSettings.Options.PARTICLES);
        graphicsApi(); // last: the rows above keep their places
    }

    /**
     * The graphics API in use (OpenGL or Vulkan), shown only: it is chosen in the Stein Client,
     * before the game opens, since the window is created for one of them. If Vulkan did not start
     * on this computer, or the last start with it died on the way, the hint says so.
     */
    private void graphicsApi() {
        SteinBackend.Fallback why = SteinBackend.fallback();
        addText(
                "steinlens.graphicsApi",
                () -> apiName(SteinBackend.running()),
                () -> {},
                SteinBackend.crashedLastTime()
                        ? tr("steinlens.graphicsApi.crashed")
                        : why == null
                                ? tr("steinlens.graphicsApi.hint")
                                : tr(
                                        "steinlens.graphicsApi.unavailable",
                                        tr(
                                                "steinlens.graphicsApi.reason."
                                                        + why.name().toLowerCase(Locale.ROOT))),
                // never clickable: the Stein Client chooses
                () -> false);
    }

    private static String apiName(SteinBackend api) {
        return api == SteinBackend.VULKAN ? "Vulkan" : "OpenGL";
    }

    private void buildCamera() {
        header("camera");
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
                tr("steinlens.zoom.hint", zoomKeyName()),
                null);
        toggle(
                "steinlens.zoomSmooth",
                () -> c.zoomSmoothCamera,
                () -> c.zoomSmoothCamera = !c.zoomSmoothCamera,
                () -> c.zoom);
    }

    private void buildLight() {
        header("light");
        game(GameSettings.Options.AMBIENT_OCCLUSION);
        game(GameSettings.Options.ENTITY_SHADOWS);
    }

    /** Stein's post-processing (SteinPostFx): only where Stein GFX draws. */
    private void buildImage() {
        header("image");
        BooleanSupplier post = SteinPostFx::available;
        add(
                "steinlens.bloom",
                () ->
                        c.bloom == SteinPostFx.BLOOM_HIGH
                                ? tr("steinlens.bloom.high")
                                : c.bloom == SteinPostFx.BLOOM_LOW
                                        ? tr("steinlens.bloom.low")
                                        : tr("options.off"),
                () -> c.bloom = (c.bloom + 1) % 3,
                "steinlens.bloom.hint",
                post);
        toggle(
                "steinlens.vividColors",
                () -> c.vividColors,
                () -> c.vividColors = !c.vividColors,
                post);
        toggle(
                "steinlens.smoothEdges",
                () -> c.smoothEdges,
                () -> c.smoothEdges = !c.smoothEdges,
                post);
        toggle("steinlens.hdr", () -> c.hdr, () -> c.hdr = !c.hdr, SteinHdr::available);
        BooleanSupplier hdrOn = () -> c.hdr && SteinHdr.available();
        add(
                "steinlens.hdrWhite",
                () -> tr("steinlens.nits", c.hdrWhite),
                () -> c.hdrWhite = next(HDR_WHITE_STEPS, c.hdrWhite),
                "steinlens.hdrWhite.hint",
                hdrOn);
        add(
                "steinlens.hdrPeak",
                () -> tr("steinlens.nits", c.hdrPeak),
                () -> c.hdrPeak = next(HDR_PEAK_STEPS, c.hdrPeak),
                "steinlens.hdrPeak.hint",
                hdrOn);
    }

    private void buildSky() {
        header("sky");
        game(GameSettings.Options.RENDER_CLOUDS);
        add(
                "steinlens.cloudHeight",
                () ->
                        c.cloudHeight > 0
                                ? tr("steinlens.cloudHeight.blocks", c.cloudHeight)
                                : tr("steinlens.default"),
                () -> c.cloudHeight = next(CLOUD_STEPS, c.cloudHeight),
                "steinlens.cloudHeight.hint",
                null);
        add(
                "steinlens.fog",
                () ->
                        c.fog == Visuals.FOG_LIGHT
                                ? tr("steinlens.fog.light")
                                : c.fog == Visuals.FOG_OFF
                                        ? tr("options.off")
                                        : tr("steinlens.default"),
                () -> c.fog = (c.fog + 1) % 3,
                "steinlens.fog.hint",
                null);
        toggle("steinlens.rain", () -> c.rain, () -> c.rain = !c.rain, null);
    }

    private void buildTextures() {
        header("textures");
        game(GameSettings.Options.MIPMAP_LEVELS);
        game(GameSettings.Options.BLOCK_ALTERNATIVES);
        toggleText(
                "steinlens.connectedTextures",
                () -> c.connectedTextures,
                () -> c.connectedTextures = !c.connectedTextures,
                tr("steinlens.connectedTextures.hint", mod.connected.ruleCount()),
                null);
        toggleText(
                "steinlens.customItems",
                () -> c.customItems,
                () -> c.customItems = !c.customItems,
                tr("steinlens.customItems.hint", mod.customItems.ruleCount()),
                null);
        addText(
                "steinlens.glint",
                () ->
                        c.glint == Visuals.GLINT_NEAR
                                ? tr("steinlens.glint.near")
                                : c.glint == Visuals.GLINT_OFF
                                        ? tr("options.off")
                                        : tr("options.on"),
                () -> c.glint = (c.glint + 1) % 3,
                tr("steinlens.glint.hint", Math.round(c.glintNearBlocks)),
                null);
    }

    private void buildPlayers() {
        header("players");
        add(
                "steinlens.armor",
                () -> tr(c.armor ? "steinlens.armor.shown" : "steinlens.armor.hidden"),
                () -> c.armor = !c.armor,
                "steinlens.armor.hint",
                null);
        add(
                "steinlens.nametags",
                () ->
                        c.nametagDistance > 0
                                ? tr("steinlens.nametags.blocks", c.nametagDistance)
                                : tr("steinlens.nametags.default"),
                () -> c.nametagDistance = next(NAMETAG_STEPS, c.nametagDistance),
                "steinlens.nametags.hint",
                null);
    }

    /**
     * 1.7 animations (engine/OldAnimations): the master switch and each part, which only counts
     * with it on.
     */
    private void buildAnimations() {
        header("animations");
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

    private void buildPerformance() {
        header("performance");
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
        add(
                "steinlens.chunkUploads",
                () -> c.chunkUploads > 0 ? String.valueOf(c.chunkUploads) : tr("steinlens.default"),
                () -> c.chunkUploads = next(UPLOAD_STEPS, c.chunkUploads),
                "steinlens.chunkUploads.hint",
                null);
        toggle(
                "steinlens.waitNeighbors",
                () -> c.waitNeighbors,
                () -> c.waitNeighbors = !c.waitNeighbors,
                null);
        add(
                "steinlens.keep",
                () ->
                        c.keepChunks == ChunkKeep.MEMORY
                                ? tr("steinlens.keep.memory")
                                : c.keepChunks == ChunkKeep.DISK
                                        ? tr("steinlens.keep.disk")
                                        : tr("options.off"),
                () -> c.keepChunks = (c.keepChunks + 1) % 3,
                "steinlens.keep.hint",
                null);
        add(
                "steinlens.keepLimit",
                () ->
                        c.keepChunksMb >= 1024
                                ? c.keepChunksMb / 1024 + " GB"
                                : c.keepChunksMb + " MB",
                () -> c.keepChunksMb = next(KEEP_STEPS, c.keepChunksMb),
                "steinlens.keepLimit.hint",
                () -> c.keepChunks != ChunkKeep.OFF);
    }

    /** Topic title, in a line of its own. */
    private void header(String key) {
        flush();
        rows.add(
                new GuiOptionsRowList.Row(
                        new HeaderButton(
                                nextId++,
                                tr("steinlens.topic." + key),
                                left,
                                right + BUTTON_WIDTH - left),
                        null));
    }

    /** A game option, created as the game creates it. */
    private void game(GameSettings.Options o) {
        GameSettings s = Minecraft.func_71410_x().field_71474_y;
        int id = o.func_74381_c();
        GuiButton b =
                o.func_74380_a()
                        ? new GuiOptionSlider(id, 0, 0, o)
                        : new GuiOptionButton(id, 0, 0, o, s.func_74297_c(o));
        game.add(b);
        button(b);
    }

    /** An on/off option of ours, whose hint is the key + ".hint". */
    private void toggle(String key, BooleanSupplier value, Runnable flip, BooleanSupplier enabled) {
        add(key, () -> onOff(value.getAsBoolean()), flip, key + ".hint", enabled);
    }

    /** An on/off option of ours, with the hint already assembled (the number inside). */
    private void toggleText(
            String key,
            BooleanSupplier value,
            Runnable flip,
            String hint,
            BooleanSupplier enabled) {
        addText(key, () -> onOff(value.getAsBoolean()), flip, hint, enabled);
    }

    private void add(
            String key,
            Supplier<String> value,
            Runnable click,
            String hintKey,
            BooleanSupplier enabled) {
        addText(key, value, click, tr(hintKey), enabled);
    }

    /**
     * One of our options: label "Name: value", click, hint already assembled (with the number
     * inside) and whether it is available.
     */
    private void addText(
            String key,
            Supplier<String> value,
            Runnable click,
            String hint,
            BooleanSupplier enabled) {
        OptionButton b = new OptionButton(nextId++, key, value, click, hint, enabled);
        ours.add(b);
        button(b);
    }

    private void button(GuiButton b) {
        if (pending == null) {
            b.field_146128_h = left;
            pending = b;
        } else {
            b.field_146128_h = right;
            rows.add(new GuiOptionsRowList.Row(pending, b));
            pending = null;
        }
    }

    private void flush() {
        if (pending != null) {
            rows.add(new GuiOptionsRowList.Row(pending, null));
            pending = null;
        }
    }

    // ---- after a change ----

    /**
     * Rewrites the labels: an option may depend on another, and a preset touches almost all of them
     * — including the game's, whose text (and the lever's position, for range ones) is read again
     * here.
     */
    void refresh() {
        GameSettings s = Minecraft.func_71410_x().field_71474_y;
        for (OptionButton b : ours) {
            b.update();
        }
        for (GuiButton b : game) {
            if (b instanceof GuiOptionButton o) {
                b.field_146126_j = s.func_74297_c(o.func_146136_c());
            } else if (b instanceof GuiOptionSlider) {
                slider(b, s);
            }
        }
        if (slider != null) {
            slider.refresh();
        }
    }

    /**
     * The lever of a game range option (distance, brightness, FPS limit, mipmap), after changing
     * from outside.
     */
    private static Field sliderOption;

    private static Field sliderValue;

    private static void slider(GuiButton b, GameSettings s) {
        try {
            if (sliderOption == null) {
                // Private in the SRG jar the visual options compile against, like a mod's: by
                // reflection (ModRemap maps the SRG names).
                Field option = GuiOptionSlider.class.getDeclaredField("field_146133_q");
                Field value = GuiOptionSlider.class.getDeclaredField("field_146134_p");
                option.setAccessible(true);
                value.setAccessible(true);
                sliderValue = value;
                sliderOption = option;
            }
            GameSettings.Options o = (GameSettings.Options) sliderOption.get(b);
            sliderValue.setFloat(b, o.func_148266_c(s.func_74296_a(o)));
            b.field_146126_j = s.func_74297_c(o);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // stays as it is: the label goes back to correct the next time the screen opens
        }
    }

    // ---- hint of the option under the mouse ----

    /** Before the screen draws: the hint is discovered again in this frame. */
    static void beforeDraw() {
        hint = "";
    }

    /** One of our buttons is under the mouse: its hint. */
    static void hover(String text) {
        hint = text == null ? "" : text;
    }

    /** After the screen: the hint of the option under the mouse, in a box next to the cursor. */
    static void drawHint(GuiScreen screen, int mouseX, int mouseY) {
        if (hint.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.func_71410_x();
        FontRenderer f = mc.field_71466_p;
        int max = Math.min(screen.field_146294_l - 24, 280);
        if (max < 16) {
            return;
        }
        List<?> lines = f.func_78271_c(hint, max);
        int n = Math.min(6, lines.size());
        int w = 0;
        for (int i = 0; i < n; i++) {
            w = Math.max(w, f.func_78256_a((String) lines.get(i)));
        }
        int h = n * 10 - 2;
        int x = Math.max(4, Math.min(mouseX + 10, screen.field_146294_l - w - 8));
        int y = Math.max(4, Math.min(mouseY - h - 8, screen.field_146295_m - h - 8));
        Gui.func_73734_a(x - 4, y - 4, x + w + 4, y + h + 4, 0xF0100010);
        Gui.func_73734_a(x - 3, y - 3, x + w + 3, y + h + 3, 0xFF3A2E5A);
        Gui.func_73734_a(x - 2, y - 2, x + w + 2, y + h + 2, 0xF0100010);
        for (int i = 0; i < n; i++) {
            f.func_175063_a((String) lines.get(i), x, y + i * 10, 0xFFE0E0E0);
        }
    }

    // ---- buttons ----

    /** Our option: handles its own click (the game's row only applies game options). */
    private final class OptionButton extends GuiButton {
        private final String key;
        private final Supplier<String> value;
        private final Runnable click;
        private final String hint;
        private final BooleanSupplier available;

        OptionButton(
                int id,
                String key,
                Supplier<String> value,
                Runnable click,
                String hint,
                BooleanSupplier available) {
            super(id, 0, 0, BUTTON_WIDTH, 20, "");
            this.key = key;
            this.value = value;
            this.click = click;
            this.hint = hint == null ? "" : hint;
            this.available = available;
            update();
        }

        void update() {
            this.field_146126_j = tr(key) + ": " + value.get();
            this.field_146124_l = available == null || available.getAsBoolean();
        }

        @Override
        public boolean func_146116_c(Minecraft mc, int mouseX, int mouseY) {
            if (!super.func_146116_c(mc, mouseX, mouseY)) {
                return false;
            }
            this.func_146113_a(mc.func_147118_V());
            click.run();
            mod.changed();
            refresh();
            return true;
        }

        @Override
        public void func_146112_a(Minecraft mc, int mouseX, int mouseY) {
            super.func_146112_a(mc, mouseX, mouseY);
            if (this.field_146125_m && this.field_146123_n) {
                hover(this.hint);
            }
        }
    }

    /** Topic title: just the text, centered, with a line on each side. */
    private static final class HeaderButton extends GuiButton {

        HeaderButton(int id, String text, int x, int width) {
            super(id, x, 0, width, 20, text);
            this.field_146124_l = false;
        }

        @Override
        public void func_146112_a(Minecraft mc, int mouseX, int mouseY) {
            if (!this.field_146125_m) {
                return;
            }
            FontRenderer f = mc.field_71466_p;
            int w = f.func_78256_a(this.field_146126_j);
            int cx = this.field_146128_h + this.field_146120_f / 2;
            int y = this.field_146129_i + 11;
            Gui.func_73734_a(this.field_146128_h, y, cx - w / 2 - 6, y + 1, 0x30FFFFFF);
            Gui.func_73734_a(
                    cx + w / 2 + 6,
                    y,
                    this.field_146128_h + this.field_146120_f,
                    y + 1,
                    0x30FFFFFF);
            f.func_175063_a(this.field_146126_j, cx - w / 2F, y - 4F, 0xFFC6C6C6);
        }

        @Override
        public boolean func_146116_c(Minecraft mc, int mouseX, int mouseY) {
            return false;
        }
    }

    // ---- utilities ----

    /** Left and right button columns, taken from one of the game's own rows. */
    private static int[] columns(List<GuiOptionsRowList.Row> rows)
            throws ReflectiveOperationException {
        for (GuiOptionsRowList.Row row : rows) {
            GuiButton[] two = buttons(row);
            if (two[0] != null && two[1] != null) {
                return new int[] {two[0].field_146128_h, two[1].field_146128_h};
            }
        }
        // As the game computes them (GuiOptionsRowList): width/2 - 155 and +160.
        int w = new ScaledResolution(Minecraft.func_71410_x()).func_78326_a();
        return new int[] {w / 2 - 155, w / 2 - 155 + 160};
    }

    /** The two buttons of a row (the second may be null), by the GuiButton-typed fields. */
    private static GuiButton[] buttons(GuiOptionsRowList.Row row)
            throws ReflectiveOperationException {
        GuiButton[] out = new GuiButton[2];
        int n = 0;
        for (Field f : row.getClass().getDeclaredFields()) {
            if (GuiButton.class.isAssignableFrom(f.getType()) && n < 2) {
                f.setAccessible(true);
                out[n++] = (GuiButton) f.get(row);
            }
        }
        return out;
    }

    /** The first field of the requested type, from the object or its superclasses. */
    private static Object byType(Object owner, Class<?> type) throws ReflectiveOperationException {
        for (Class<?> c = owner.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (type.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    return f.get(owner);
                }
            }
        }
        return null;
    }

    private String zoomKeyName() {
        return mod.zoomKey != null ? GameSettings.func_74298_c(mod.zoomKey.func_151463_i()) : "V";
    }

    private static String onOff(boolean on) {
        return tr(on ? "options.on" : "options.off");
    }

    private static int next(int[] steps, int value) {
        for (int i = 0; i < steps.length; i++) {
            if (steps[i] == value) {
                return steps[(i + 1) % steps.length];
            }
        }
        return steps[0];
    }

    private static String tr(String key, Object... args) {
        return I18n.func_135052_a(key, args);
    }
}
