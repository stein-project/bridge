/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.lens;

import dev.xavier.stein.bridge.core.Log;
import dev.xavier.stein.bridge.engine.KeyBindings;
import dev.xavier.stein.bridge.hooks.GameHooks;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;

import java.util.Locale;

/**
 * The Lens zoom: a key (V) that divides the field of view by the zoom level, held or switched, with
 * an optional eased transition, the wheel for the level, a second key with its own level, and the
 * mouse, hand and smooth camera adjusted while zoomed.
 *
 * <p>Only the view changes. The mouse turn is scaled the way a lower sensitivity would be, the
 * wheel the zoom takes never reaches the hotbar (GameHooks' camera hooks), and nothing about the
 * game's logic or what the server sees changes.
 *
 * <p>The keys are read on every requested field of view, not every tick, so the zoom starts on the
 * frame the key goes down. With the defaults (held, at once, no wheel, the mouse as is) it is the
 * zoom the Lens always had.
 */
final class LensZoom {

    /** V, as an LWJGL 2 key code (unused in 1.8.9). */
    private static final int KEY_V = 47;

    /** LWJGL 2's "no key". */
    private static final int KEY_NONE = 0;

    /** Which key zooms. */
    private static final int NONE = 0;

    private static final int PRIMARY = 1;
    private static final int SECONDARY = 2;

    /** {@code -Dsteinlens.debug=true}: zoom state in the log (every 2 s and on every change). */
    private static final boolean DEBUG = LensOptions.DEBUG;

    private final LensConfig c;
    private final ZoomState state = new ZoomState();

    KeyBinding key;
    KeyBinding secondaryKey;

    /** The key zooming now (NONE, PRIMARY, SECONDARY). */
    private int active = NONE;

    /** Toggle mode: the key that was switched on. */
    private int toggled = NONE;

    /** The level the wheel left on each key, for zoomScrollRemember (0 = none). */
    private final float[] scrolled = new float[3];

    /** This frame's divisor, which the hand's field of view (asked after the world's) reuses. */
    private float divisor = 1.0F;

    /** What was last handed to GameHooks, so it is only called on a change. */
    private float lookScale = 1.0F;

    private boolean handHidden;
    private boolean smooth;

    private long debugCalls;
    private long debugLast;

    LensZoom(LensConfig config) {
        this.c = config;
    }

    /** The zoom keys, under Controls, category "Stein"; the second one starts unbound. */
    void register() {
        key = new KeyBinding("key.steinlens.zoom", KEY_V, "key.categories.steinlens");
        secondaryKey =
                new KeyBinding("key.steinlens.zoomSecondary", KEY_NONE, "key.categories.steinlens");
        KeyBindings.register(key);
        KeyBindings.register(secondaryKey);
    }

    /**
     * The field of view with the zoom. The world's (useFovSetting) moves the zoom on; the hand's,
     * asked later in the frame, is divided by the same amount, as the Lens zoom always did.
     */
    float fov(float fov, boolean useFovSetting) {
        if (useFovSetting) {
            Minecraft mc = Minecraft.func_71410_x();
            long now = System.nanoTime();
            follow(mc, wanted(mc), now);
            state.update(c.zoomCurve, now);
            divisor = state.divisor();
            applyCamera(fov, divisor);
            if (DEBUG) {
                debug(mc, now);
            }
        }
        return divisor == 1.0F ? fov : fov / divisor;
    }

    /** The key that should be zooming now; the presses since the last frame switch a toggle. */
    private int wanted(Minecraft mc) {
        // Presses pile up in the bindings whatever the mode: always drained.
        boolean primaryPressed = false;
        boolean secondaryPressed = false;
        if (key != null) {
            while (key.func_151468_f()) {
                primaryPressed = !primaryPressed;
            }
            while (secondaryKey.func_151468_f()) {
                secondaryPressed = !secondaryPressed;
            }
        }
        if (!c.zoom || key == null || mc.field_71462_r != null || mc.field_71439_g == null) {
            // a screen (or leaving the world) ends the zoom, a switched one too
            toggled = NONE;
            return NONE;
        }
        if (c.zoomToggle) {
            if (secondaryPressed) {
                toggled = toggled == SECONDARY ? NONE : SECONDARY;
            } else if (primaryPressed) {
                toggled = toggled == PRIMARY ? NONE : PRIMARY;
            }
            return toggled;
        }
        toggled = NONE;
        if (secondaryKey.func_151463_i() != KEY_NONE && secondaryKey.func_151470_d()) {
            return SECONDARY;
        }
        return key.func_151470_d() ? PRIMARY : NONE;
    }

    /** Starts the zoom in, out, or onto the other key's level, when the wanted key changes. */
    private void follow(Minecraft mc, int want, long now) {
        if (want == active) {
            return;
        }
        boolean switched = (want == NONE) != (active == NONE);
        if (want == NONE) {
            state.aim(false, 1.0F, c.zoomOutMs, now);
        } else if (active == NONE) {
            state.aim(true, startLevel(want), c.zoomInMs, now);
        } else {
            state.setLevel(startLevel(want), c.zoomScrollSmooth);
        }
        active = want;
        if (!switched) {
            return;
        }
        if (DEBUG) {
            Log.info("lens", "zoom " + (want != NONE ? "on" : "off"));
        }
        // Standing still, the zoom narrows the view without moving the camera: the terrain redoes
        // the visible-chunk search, or the screen edges would lack the chunks outside the cone.
        if (mc.field_71438_f != null) {
            mc.field_71438_f.func_174979_m();
        }
    }

    private float startLevel(int which) {
        float own = which == SECONDARY ? c.zoomSecondaryFactor : c.zoomFactor;
        return c.zoomScrollRemember && scrolled[which] > 0.0F ? scrolled[which] : own;
    }

    /** Mouse, hand and smooth camera for this frame's zoom. */
    private void applyCamera(float fov, float div) {
        float scale = 1.0F;
        if (c.zoomScaleSensitivity && div != 1.0F) {
            // The turn that keeps the same on-screen speed: the ratio of the half-angle tangents.
            double half = Math.toRadians(fov) * 0.5;
            scale = (float) (Math.tan(half / div) / Math.tan(half));
        }
        if (scale != lookScale) {
            lookScale = scale;
            GameHooks.setLookScale(scale);
        }
        boolean hide = c.zoomHideHand && !state.idle();
        if (hide != handHidden) {
            handHidden = hide;
            GameHooks.setHandHidden(hide);
        }
        boolean cine = c.zoomSmoothCamera && state.zoomingIn();
        if (cine != smooth) {
            smooth = cine;
            GameHooks.setSmoothCamera(cine);
        }
    }

    /**
     * A wheel turn while zoomed changes the level (when the option is on): the game gets no wheel,
     * so the hotbar slot stays.
     */
    boolean wheel(int wheel) {
        Minecraft mc = Minecraft.func_71410_x();
        if (active == NONE || !c.zoomScroll || mc.field_71462_r != null) {
            return false;
        }
        float step = c.zoomScrollStep;
        float next = state.levelTarget() * (wheel > 0 ? step : 1.0F / step);
        next = Math.max(c.zoomScrollMin, Math.min(c.zoomScrollMax, next));
        state.setLevel(next, c.zoomScrollSmooth);
        scrolled[active] = next;
        if (c.zoomShowLevel && mc.field_71456_v != null) {
            mc.field_71456_v.func_110326_a(
                    I18n.func_135052_a("steinlens.zoomLevelShown", formatLevel(next)), false);
        }
        return true;
    }

    /** "4x", "2.5x". */
    static String formatLevel(double level) {
        double r = Math.round(level * 10.0) / 10.0;
        return (r == Math.rint(r)
                        ? String.valueOf((long) r)
                        : String.format(Locale.ROOT, "%.1f", r))
                + "x";
    }

    /** The zoom key's name, for the option's hint. */
    String keyName() {
        return key != null ? GameSettings.func_74298_c(key.func_151463_i()) : "V";
    }

    private void debug(Minecraft mc, long now) {
        debugCalls++;
        if (now - debugLast <= LensOptions.DEBUG_EVERY_MS * 1_000_000L) {
            return;
        }
        debugLast = now;
        Log.info(
                "lens",
                String.format(
                        Locale.ROOT,
                        "fov x%d | key %s | screen %s | zoom %d div %.3f look %.4f hand %b"
                                + " smooth %b slot %d yaw %.2f",
                        debugCalls,
                        key == null ? "-" : key.func_151463_i() + " down=" + key.func_151470_d(),
                        mc.field_71462_r,
                        active,
                        divisor,
                        lookScale,
                        handHidden,
                        smooth,
                        mc.field_71439_g == null
                                ? -1
                                : mc.field_71439_g.field_71071_by.field_70461_c,
                        mc.field_71439_g == null ? 0.0F : mc.field_71439_g.field_70177_z));
    }
}
