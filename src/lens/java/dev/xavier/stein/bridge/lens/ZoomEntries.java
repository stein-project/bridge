/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.lens;

import static dev.xavier.stein.bridge.lens.VideoModel.tr;

import dev.xavier.stein.bridge.showcase.VideoSettings.Entry;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

/**
 * The zoom's options in the video settings' "Camera and players" topic ({@link VideoModel}): the
 * switch and smooth camera it always had, then the level, the mode, the transition, the wheel, the
 * second key's level, the mouse and the hand ({@link LensZoom}).
 */
final class ZoomEntries {

    /** Zoom levels on the level tracks. */
    private static final double[] LEVELS = {
        1.5, 2, 2.5, 3, 4, 5, 6, 8, 10, 12, 15, 20, 25, 30, 40, 50
    };

    /** Transition times, in milliseconds; 0 = at once. */
    private static final double[] TIMES = {
        0, 100, 150, 200, 250, 300, 400, 500, 750, 1000, 1500, 2000
    };

    /** How much each wheel notch changes the level. */
    private static final double[] SCROLL_STEPS = {
        1.05, 1.1, 1.15, 1.2, 1.25, 1.3, 1.4, 1.5, 1.75, 2.0
    };

    private static final String[] CURVES = {
        "steinlens.zoomCurve.linear",
        "steinlens.zoomCurve.smooth",
        "steinlens.zoomCurve.easeOut",
        "steinlens.zoomCurve.easeOutStrong"
    };

    private ZoomEntries() {}

    /** Adds the zoom's options to the topic being built. */
    static void add(List<Entry> entries, Lens mod) {
        LensConfig c = mod.config;
        BooleanSupplier on = () -> c.zoom;
        entries.add(
                toggle(
                        "steinlens.zoom",
                        () -> c.zoom,
                        () -> c.zoom = !c.zoom,
                        () -> tr("steinlens.zoom.hint", mod.zoom.keyName()),
                        null));
        entries.add(
                track(
                        "steinlens.zoomFactor",
                        LEVELS,
                        () -> c.zoomFactor,
                        v -> c.zoomFactor = (float) v,
                        LensZoom::formatLevel,
                        on));
        entries.add(
                toggle(
                        "steinlens.zoomToggle",
                        () -> c.zoomToggle,
                        () -> c.zoomToggle = !c.zoomToggle,
                        null,
                        on));
        entries.add(
                toggle(
                        "steinlens.zoomSmooth",
                        () -> c.zoomSmoothCamera,
                        () -> c.zoomSmoothCamera = !c.zoomSmoothCamera,
                        null,
                        on));
        transition(entries, c, on);
        scroll(entries, c, on);
        entries.add(
                track(
                        "steinlens.zoomSecondaryFactor",
                        LEVELS,
                        () -> c.zoomSecondaryFactor,
                        v -> c.zoomSecondaryFactor = (float) v,
                        LensZoom::formatLevel,
                        on));
        entries.add(
                toggle(
                        "steinlens.zoomScaleSensitivity",
                        () -> c.zoomScaleSensitivity,
                        () -> c.zoomScaleSensitivity = !c.zoomScaleSensitivity,
                        null,
                        on));
        entries.add(
                toggle(
                        "steinlens.zoomHideHand",
                        () -> c.zoomHideHand,
                        () -> c.zoomHideHand = !c.zoomHideHand,
                        null,
                        on));
    }

    /** The zoom in and out times and the curve, which only counts with some time. */
    private static void transition(List<Entry> entries, LensConfig c, BooleanSupplier on) {
        DoubleFunction<String> time =
                v -> v < 1 ? tr("steinlens.zoomInstant") : tr("steinlens.ms", Math.round(v));
        entries.add(
                track(
                        "steinlens.zoomInMs",
                        TIMES,
                        () -> c.zoomInMs,
                        v -> c.zoomInMs = (int) v,
                        time,
                        on));
        entries.add(
                track(
                        "steinlens.zoomOutMs",
                        TIMES,
                        () -> c.zoomOutMs,
                        v -> c.zoomOutMs = (int) v,
                        time,
                        on));
        entries.add(
                Entry.choice(
                        () -> tr("steinlens.zoomCurve"),
                        () -> tr(CURVES[c.zoomCurve]),
                        () -> tr("steinlens.zoomCurve.hint"),
                        () -> c.zoom && (c.zoomInMs > 0 || c.zoomOutMs > 0),
                        null,
                        () -> c.zoomCurve = (c.zoomCurve + 1) % ZoomState.CURVES,
                        () ->
                                c.zoomCurve =
                                        (c.zoomCurve + ZoomState.CURVES - 1) % ZoomState.CURVES));
    }

    /** The wheel switch and what only counts with it on. */
    private static void scroll(List<Entry> entries, LensConfig c, BooleanSupplier on) {
        entries.add(
                toggle(
                        "steinlens.zoomScroll",
                        () -> c.zoomScroll,
                        () -> c.zoomScroll = !c.zoomScroll,
                        null,
                        on));
        BooleanSupplier wheel = () -> c.zoom && c.zoomScroll;
        entries.add(
                track(
                        "steinlens.zoomScrollStep",
                        SCROLL_STEPS,
                        () -> c.zoomScrollStep,
                        v -> c.zoomScrollStep = (float) v,
                        v -> "+" + Math.round((v - 1.0) * 100.0) + "%",
                        wheel));
        entries.add(
                track(
                        "steinlens.zoomScrollMin",
                        LEVELS,
                        () -> c.zoomScrollMin,
                        v -> c.zoomScrollMin = (float) Math.min(v, c.zoomScrollMax),
                        LensZoom::formatLevel,
                        wheel));
        entries.add(
                track(
                        "steinlens.zoomScrollMax",
                        LEVELS,
                        () -> c.zoomScrollMax,
                        v -> c.zoomScrollMax = (float) Math.max(v, c.zoomScrollMin),
                        LensZoom::formatLevel,
                        wheel));
        entries.add(
                toggle(
                        "steinlens.zoomScrollSmooth",
                        () -> c.zoomScrollSmooth,
                        () -> c.zoomScrollSmooth = !c.zoomScrollSmooth,
                        null,
                        wheel));
        entries.add(
                toggle(
                        "steinlens.zoomScrollRemember",
                        () -> c.zoomScrollRemember,
                        () -> c.zoomScrollRemember = !c.zoomScrollRemember,
                        null,
                        wheel));
        entries.add(
                toggle(
                        "steinlens.zoomShowLevel",
                        () -> c.zoomShowLevel,
                        () -> c.zoomShowLevel = !c.zoomShowLevel,
                        null,
                        wheel));
    }

    /** An on/off option; its hint is the key + ".hint" unless one is given. */
    private static Entry toggle(
            String key,
            BooleanSupplier value,
            Runnable flip,
            Supplier<String> hint,
            BooleanSupplier enabled) {
        return Entry.choice(
                () -> tr(key),
                () -> tr(value.getAsBoolean() ? "options.on" : "options.off"),
                hint != null ? hint : () -> tr(key + ".hint"),
                enabled,
                value,
                flip,
                flip);
    }

    /**
     * A value from a fixed list on a track, one stop per value; a value from the file that is not
     * on the list shows at the nearest stop until the track is moved.
     */
    private static Entry track(
            String key,
            double[] stops,
            DoubleSupplier get,
            DoubleConsumer set,
            DoubleFunction<String> text,
            BooleanSupplier enabled) {
        int last = stops.length - 1;
        return Entry.slider(
                () -> tr(key),
                () -> text.apply(get.getAsDouble()),
                () -> tr(key + ".hint"),
                enabled,
                () -> nearest(stops, get.getAsDouble()) / (double) last,
                f -> set.accept(stops[(int) Math.round(f * last)]),
                false);
    }

    /** The stop closest to {@code value}. */
    private static int nearest(double[] stops, double value) {
        int best = 0;
        for (int i = 1; i < stops.length; i++) {
            if (Math.abs(stops[i] - value) < Math.abs(stops[best] - value)) {
                best = i;
            }
        }
        return best;
    }
}
