/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.ui;

import dev.xavier.stein.bridge.engine.FrameTime;
import dev.xavier.stein.bridge.showcase.Showcase;
import dev.xavier.stein.bridge.showcase.VideoSettings;
import dev.xavier.stein.gfx.SteinHdr;

import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.resources.I18n;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.util.ArrayList;
import java.util.List;

/**
 * The video settings, Stein's way: a narrow translucent column on the right, over a landscape of
 * Stein's own ({@link Showcase}) that shows each change as it is made. The game's options and
 * Stein's are in one compact list per topic ({@link VideoSettings}), one line each, the topics
 * chosen by icon at the top; under the list, what the option under the mouse does, in plain words.
 *
 * <p>Back in the top left corner (Esc does the same), and in the bottom left ({@link
 * VideoToolbar}): hide the menu to see the whole picture, day or night for the landscape (the clock
 * runs through the sunset or the dawn), and the landscape or the player's own view behind the menu
 * — that last choice is remembered ({@link VideoPrefs}). In the main menu there is no own view: the
 * landscape is drawn by the screen itself. The last topic is the credits ({@link VideoCredits}).
 *
 * <p>Where HDR can be shown, the line "Calibrate HDR" is added under HDR: three steps over the same
 * picture ({@link VideoCalibration}). Without the Bridge's options (nothing provided them) the
 * game's screen stays.
 */
public final class VideoScreen extends GuiScreen {

    private static final long OPEN_MS = 320L;

    /** H (the game's key code; the input shim has no constant for it). */
    private static final int KEY_H = 35;

    private static final long VEIL_MS = 600L;

    /** The topic shown, kept between openings. */
    private static int topic;

    private final GuiScreen parent;
    private List<VideoSettings.Section> sections;
    private final VideoLayout layout = new VideoLayout();
    private final VideoToolbar toolbar = new VideoToolbar();

    private long openedAt;
    private long last;
    private boolean hidden;
    private float hide;

    /** The slider being dragged and where it is (applied on release for the heavy ones). */
    private VideoSettings.Entry dragging;

    private double dragAt;

    /** The dragged slider's track: where it starts and how wide it is. */
    private int dragX;

    private int dragW = 1;
    private boolean landscape;

    /** When the landscape came in (the fade from black). */
    private long landscapeAt;

    /** The HDR calibration's step ({@link VideoCalibration}); -1 when not calibrating. */
    private int calibrating = -1;

    /** The step drawn while the card fades out after the end. */
    private int calibrationShown;

    private float calibrate;

    /** The frame rate and the 1% low of the last seconds, read twice per second. */
    private final double[] fps = new double[2];

    private long fpsAt;

    public VideoScreen(GuiScreen parent) {
        this.parent = parent;
    }

    /** Whether the Stein video settings can replace the game's: the options were provided. */
    public static boolean available() {
        return VideoSettings.source() != null;
    }

    @Override
    public void initGui() {
        if (sections == null) {
            sections = VideoCredits.append(withCalibration(VideoSettings.sections()));
            openedAt = Ui.now();
            last = openedAt;
            if (!ownViewChosen()) {
                showLandscape(true);
            }
            topic = Math.max(0, Math.min(topic, sections.size() - 1));
            // (diagnostics: also without HDR, to see the steps' layout)
            if (UiOptions.DIAG_HDR_STEP >= 0 && landscape) {
                calibrating = Math.min(UiOptions.DIAG_HDR_STEP, VideoCalibration.STEPS - 1);
                calibrationShown = calibrating;
                VideoCalibration.begin();
                VideoCalibration.arrive(calibrating);
            }
        }
        Keyboard.enableRepeatEvents(true);
    }

    /** The own view, chosen and possible (there is a world to see). */
    private boolean ownViewChosen() {
        return VideoPrefs.ownView() && mc.theWorld != null;
    }

    private void showLandscape(boolean on) {
        if (on) {
            Showcase.open();
            if (!landscape && Showcase.active()) {
                landscapeAt = Ui.now();
            }
        } else {
            Showcase.close();
        }
        landscape = Showcase.active();
    }

    /** The topics, with "Calibrate HDR" right under HDR where HDR can be shown. */
    private List<VideoSettings.Section> withCalibration(List<VideoSettings.Section> in) {
        if (!SteinHdr.available() && UiOptions.DIAG_HDR_STEP < 0) {
            return in;
        }
        List<VideoSettings.Section> out = new ArrayList<>(in.size());
        for (VideoSettings.Section s : in) {
            int at = -1;
            for (int i = 0; i < s.entries.size(); i++) {
                if ("hdr".equals(s.entries.get(i).id)) {
                    at = i;
                }
            }
            if (at < 0) {
                out.add(s);
                continue;
            }
            List<VideoSettings.Entry> entries = new ArrayList<>(s.entries);
            entries.add(
                    at + 1,
                    VideoSettings.Entry.action(
                            () -> tr("steinui.hdr.calibrate"),
                            () -> tr("steinui.hdr.start"),
                            () -> tr("steinui.hdr.calibrate.hint"),
                            () -> SteinHdr.available() || UiOptions.DIAG_HDR_STEP >= 0,
                            this::startCalibration));
            out.add(new VideoSettings.Section(s.id, s.title, entries));
        }
        return out;
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
        if (calibrating >= 0) {
            VideoCalibration.cancel(); // closed halfway
        }
        VideoCalibration.end();
        Showcase.close();
        VideoSettings.Source s = VideoSettings.source();
        if (s != null) {
            s.closed();
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return true;
    }

    // ---- drawing ----

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        followScale();
        long now = Ui.now();
        long delta = Math.min(100L, now - last);
        last = now;
        // in the main menu nothing draws a world: the landscape is drawn here, as the background
        if (landscape) {
            Showcase.drawInMenu(partialTicks);
        } else if (mc.theWorld == null) {
            drawDefaultBackground();
        }
        float open = Ui.clamp01((now - openedAt) / (float) OPEN_MS);
        open = open * open * (3 - 2 * open);
        hide = Ui.towards(hide, hidden || calibrating >= 0 ? 1.0F : 0.0F, 9.0F, delta);
        calibrate = Ui.towards(calibrate, calibrating >= 0 ? 1.0F : 0.0F, 9.0F, delta);
        if (dragging != null && Mouse.isButtonDown(0)) {
            dragAt = fraction(mouseX);
            live(dragging, dragAt);
        }
        if (now - fpsAt >= 500L) {
            fpsAt = now;
            FrameTime.recent(3.0, fps);
        }
        float alpha = open * (1.0F - hide);
        layout.place(width, height, layout.panelWidth(width) * (1.0F - open) + 40 * hide);
        VideoDrawing.panel(
                this, layout, sections, topic, mouseX, mouseY, dragging, dragAt, alpha, delta);
        int room = width - layout.panelWidth(width) - 3 * VideoLayout.MARGIN;
        if (calibrating < 0) {
            toolbar.draw(
                    layout,
                    height,
                    room,
                    mouseX,
                    mouseY,
                    Showcase.night(),
                    landscape,
                    mc.theWorld != null,
                    hidden,
                    alpha,
                    open,
                    fps,
                    delta);
        }
        toolbar.server(mc, alpha, now);
        if (calibrating >= 0 || calibrate > 0.01F) {
            VideoCalibration.draw(
                    layout,
                    width,
                    height,
                    calibrationShown,
                    calibrating >= 0,
                    mouseX,
                    mouseY,
                    dragging,
                    dragAt,
                    calibrate,
                    delta);
        }
        if (landscape) {
            // the landscape fades in from black: the switch from the real world is not a cut
            float veil = 1.0F - Ui.clamp01((now - landscapeAt) / (float) VEIL_MS);
            if (veil > 0.0F) {
                Ui.rect(0, 0, width, height, Ui.alpha(0xFF000000, veil * veil));
            }
        }
    }

    /** The GUI scale option rebuilds the screen at its new size, as the game's screen does. */
    private void followScale() {
        ScaledResolution sr = new ScaledResolution(mc);
        if (sr.getScaledWidth() != width || sr.getScaledHeight() != height) {
            setWorldAndResolution(mc, sr.getScaledWidth(), sr.getScaledHeight());
        }
    }

    // ---- input ----

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        VideoLayout.Hit hit = layout.hit(mouseX, mouseY);
        if (hidden && calibrating < 0 && (hit == null || hit.kind != VideoLayout.Kind.BACK)) {
            // anywhere but back, the hide button included: the menu comes back
            hidden = false;
            click();
            return;
        }
        if (hit == null) {
            return;
        }
        switch (hit.kind) {
            case TOPIC -> {
                if (hit.index != topic) {
                    topic = hit.index;
                    layout.resetScroll();
                    click();
                }
            }
            case BACK -> {
                click();
                mc.displayGuiScreen(parent);
            }
            case DAY_NIGHT -> {
                Showcase.setNight(!Showcase.night());
                click();
            }
            case HIDE -> {
                hidden = true;
                click();
            }
            case VIEW -> {
                showLandscape(!landscape);
                VideoPrefs.setOwnView(!landscape);
                click();
            }
            case ENTRY -> entry(hit, mouseX, button);
            case CAL_SLIDER -> drag(hit.entry, hit.trackX, hit.trackW, mouseX);
            case CAL_NEXT -> {
                calibrating = calibrating + 1 >= VideoCalibration.STEPS ? -1 : calibrating + 1;
                calibrated();
                click();
            }
            case CAL_BACK -> {
                if (calibrating == 0) {
                    VideoCalibration.cancel(); // "Leave"
                }
                calibrating--;
                calibrated();
                click();
            }
            default -> {}
        }
    }

    /** From the "Calibrate HDR" line: over the landscape, even if the own view is chosen. */
    private void startCalibration() {
        showLandscape(true);
        calibrating = 0;
        calibrationShown = 0;
        VideoCalibration.begin();
    }

    /** The calibration moved to another step, or ended. */
    private void calibrated() {
        if (calibrating < 0) {
            VideoCalibration.end();
            if (ownViewChosen()) {
                showLandscape(false);
            }
        } else {
            calibrationShown = calibrating;
            VideoCalibration.arrive(calibrating);
        }
    }

    /** Starts dragging {@code e} along a track at {@code x}, {@code w} wide. */
    private void drag(VideoSettings.Entry e, int x, int w, int mouseX) {
        if (e == null || !e.enabled.getAsBoolean()) {
            return;
        }
        dragging = e;
        dragX = x;
        dragW = Math.max(1, w);
        dragAt = fraction(mouseX);
        live(e, dragAt);
    }

    /**
     * While a track is dragged: the value applied at once, so the picture follows the hand; the
     * owner applies and saves only when the value shown changes (one step), not at every frame.
     */
    private void live(VideoSettings.Entry e, double at) {
        String before = e.value.get();
        e.setFraction.accept(at);
        VideoSettings.Source s = VideoSettings.source();
        if (s != null && !before.equals(e.value.get())) {
            s.changed();
        }
    }

    private double fraction(int mouseX) {
        return Math.max(0, Math.min(1, (mouseX - dragX) / (double) dragW));
    }

    private void entry(VideoLayout.Hit hit, int mouseX, int button) {
        VideoSettings.Entry e = hit.entry;
        if (!e.enabled.getAsBoolean()) {
            return;
        }
        if (e.kind == VideoSettings.Kind.SLIDER) {
            // only the track (and a little around it) grabs: a click on the name changes nothing
            if (mouseX >= hit.trackX - 6) {
                drag(e, hit.trackX, hit.trackW, mouseX);
            }
            return;
        }
        if (e.kind == VideoSettings.Kind.ACTION) {
            click();
            e.next.run();
            return;
        }
        if (e.kind != VideoSettings.Kind.CHOICE) {
            return;
        }
        // the left arrow, or a right click, goes back
        boolean back = button == 1 || hit.back;
        apply(back ? e.previous : e.next);
        click();
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        if (dragging != null) {
            VideoSettings.Entry e = dragging;
            dragging = null;
            double at = dragAt;
            apply(() -> e.setFraction.accept(at));
        }
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0 && !hidden) {
            layout.scroll(wheel > 0 ? -1 : 1);
        }
    }

    @Override
    protected void keyTyped(char c, int key) {
        if (key == Keyboard.KEY_ESCAPE) {
            if (calibrating >= 0) {
                VideoCalibration.cancel();
                calibrating = -1;
                calibrated();
                return;
            }
            if (hidden) {
                hidden = false;
                return;
            }
            mc.displayGuiScreen(parent);
            return;
        }
        if (key == KEY_H && calibrating < 0) {
            hidden = !hidden;
        }
    }

    /** A change: applied, then saved by the owner of the options. */
    private void apply(Runnable change) {
        if (change == null) {
            return;
        }
        change.run();
        VideoSettings.Source s = VideoSettings.source();
        if (s != null) {
            s.changed();
        }
    }

    private void click() {
        mc.getSoundHandler()
                .playSound(
                        net.minecraft.client.audio.PositionedSoundRecord.create(
                                new net.minecraft.util.ResourceLocation("gui.button.press"), 1.0F));
    }

    static String tr(String key, Object... args) {
        return I18n.format(key, args);
    }

    /** The topics now (the drawing reads them). */
    List<VideoSettings.Section> sections() {
        return sections == null ? new ArrayList<>() : sections;
    }
}
