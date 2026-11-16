/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import dev.xavier.stein.bridge.core.Log;

import net.minecraft.client.settings.GameSettings;
import net.minecraft.util.MathHelper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * The frame limit goes up to 3000 fps, in steps of 5.
 *
 * <p>1.8.9's limit runs from 10 to 250 in steps of 10, and 260 means unlimited. With 360-540 Hz
 * monitors, and PvP players capping at a multiple of their refresh rate, 250 was too low and too
 * coarse: the only way past it was no limit at all. The scale is now 10 to 3000 in steps of 5, and
 * one step more, {@link #UNLIMITED}, is unlimited (the game shows "Unlimited" at the maximum).
 *
 * <p>The slider is not linear: its position is the cube root of the step's place on the scale, so
 * the left half covers 10 to about 380 fps, a few fps per pixel where people pick 144 or 240, and
 * the right half runs up to 3000. The game, the Stein video screen and the game's own video screen
 * all go through GameSettings.Options' normalize/denormalize/snap, whose bodies are here (rules in
 * redirects-mc.tsv); for every other option they do exactly what 1.8.9 does.
 *
 * <p>260, 1.8.9's unlimited, is a step of the new scale: an options.txt that says 260 is read as
 * unlimited once, on the first start with this scale (a marker file in config/ says it was done),
 * and from then on 260 means 260.
 */
public final class FramerateScale {
    private FramerateScale() {}

    private static final int MIN = 10;
    private static final int STEP = 5;
    private static final int MAX = 3000;

    /** The value saved for "unlimited": one step past {@link #MAX}. */
    public static final int UNLIMITED = MAX + STEP;

    /** Places on the scale, unlimited included. */
    private static final int PLACES = (UNLIMITED - MIN) / STEP + 1;

    /** The slider position is (place / last place) ^ (1 / CURVE). */
    private static final double CURVE = 3.0;

    /** 1.8.9's "unlimited". */
    private static final int OLD_UNLIMITED = 260;

    /** Written once the old "unlimited" was carried over (next to options.txt, in config/). */
    private static final String MARKER = "config/stein-framerate-scale.txt";

    /**
     * In place of loadOptions in the GameSettings constructor: the new maximum, before anything
     * reads it, and the old "unlimited" carried over once.
     */
    public static void loadOptions(Object settings) {
        GameSettings s = (GameSettings) settings;
        GameSettings.Options.FRAMERATE_LIMIT.setValueMax(UNLIMITED);
        s.loadOptions();
        if (s.limitFramerate > UNLIMITED) {
            s.limitFramerate = UNLIMITED;
        }
        File options = s.optionsFile;
        File dir = options == null ? null : options.getParentFile();
        if (dir == null) {
            return;
        }
        File marker = new File(dir, MARKER);
        if (marker.isFile()) {
            return;
        }
        if (s.limitFramerate == OLD_UNLIMITED) {
            s.limitFramerate = UNLIMITED;
        }
        try {
            marker.getParentFile().mkdirs();
            Files.writeString(
                    marker.toPath(),
                    "The frame limit's scale goes to "
                            + UNLIMITED
                            + " (unlimited); 1.8.9's 260 was carried over."
                            + System.lineSeparator());
        } catch (IOException e) {
            Log.warn("options", "could not write " + marker + ": " + e);
        }
    }

    /** The body of GameSettings.Options.normalizeValue. */
    public static float normalize(Object option, float value) {
        GameSettings.Options o = (GameSettings.Options) option;
        if (o == GameSettings.Options.FRAMERATE_LIMIT) {
            return (float) Math.pow((double) place(value) / (PLACES - 1), 1.0 / CURVE);
        }
        return MathHelper.clamp_float(
                (o.snapToStepClamp(value) - o.valueMin) / (o.valueMax - o.valueMin), 0.0F, 1.0F);
    }

    /** The body of GameSettings.Options.denormalizeValue. */
    public static float denormalize(Object option, float value) {
        GameSettings.Options o = (GameSettings.Options) option;
        if (o == GameSettings.Options.FRAMERATE_LIMIT) {
            double p = MathHelper.clamp_float(value, 0.0F, 1.0F);
            return MIN + STEP * (int) Math.round(Math.pow(p, CURVE) * (PLACES - 1));
        }
        return o.snapToStepClamp(
                o.valueMin + (o.valueMax - o.valueMin) * MathHelper.clamp_float(value, 0.0F, 1.0F));
    }

    /** The body of GameSettings.Options.snapToStep. */
    public static float snap(Object option, float value) {
        GameSettings.Options o = (GameSettings.Options) option;
        if (o == GameSettings.Options.FRAMERATE_LIMIT) {
            return MIN + STEP * place(value);
        }
        float step = o.valueStep;
        if (step > 0.0F) {
            value = step * (float) Math.round(value / step);
        }
        return value;
    }

    /** The place on the scale nearest to {@code value} (0 = 10 fps, the last = unlimited). */
    private static int place(float value) {
        int i = Math.round((value - MIN) / STEP);
        return Math.max(0, Math.min(PLACES - 1, i));
    }
}
