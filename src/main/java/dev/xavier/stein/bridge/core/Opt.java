/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.core;

/**
 * Reads the Bridge's {@code -D} options. Options are declared as {@code static final} fields of one
 * {@code *Options} class per area (for example {@code EngineOptions}), each with a Javadoc that
 * says what it does, so every option is in one place and {@code tools/OptionsDoc.java} ({@code
 * ./gradlew options}) can list them all in docs/OPTIONS.md.
 *
 * <p>The parsing matches the JDK methods the code used before: a flag is on only when the property
 * is {@code true} (any case), like {@link Boolean#getBoolean}; numbers that do not parse fall back
 * to the default, like {@link Integer#getInteger}.
 */
public final class Opt {

    private Opt() {}

    /**
     * The benchmark ({@code -Dsteinbridge.bench}) measures the GPU time of each pass: the option is
     * set here, before any options class reads it, unless the command line already decides ({@code
     * -Dsteinbridge.bench.gpu=false} keeps it off, to measure what the timing costs).
     */
    static {
        if (Boolean.getBoolean("steinbridge.bench")) {
            // the whole console from here on goes into the report too
            dev.xavier.stein.bridge.bench.BenchConsole.install();
        }
        if (Boolean.getBoolean("steinbridge.bench")
                && System.getProperty("steinbridge.gfx.gpuPassTiming") == null
                && !"false".equals(System.getProperty("steinbridge.bench.gpu"))) {
            System.setProperty("steinbridge.gfx.gpuPassTiming", "true");
            if (System.getProperty("steinbridge.gfx.gpuPassTiming.every") == null) {
                // the timestamps cost the frames that carry them: one in 16, left out of the stats
                System.setProperty("steinbridge.gfx.gpuPassTiming.every", "16");
            }
            System.setProperty("steinbridge.bench.setByBench", "steinbridge.gfx.gpuPassTiming");
        }
    }

    /** A flag that is off unless the property is {@code true}. */
    public static boolean flag(String name) {
        return Boolean.getBoolean(name);
    }

    /**
     * A flag with a default: the property, when set, decides ({@code true} on, anything else off).
     */
    public static boolean flag(String name, boolean defaultValue) {
        String value = System.getProperty(name);
        return value == null ? defaultValue : Boolean.parseBoolean(value);
    }

    /**
     * A flag that is on unless the property is exactly {@code false}: unset or any other value
     * keeps it on.
     */
    public static boolean unlessFalse(String name) {
        return !"false".equals(System.getProperty(name));
    }

    public static int integer(String name, int defaultValue) {
        return Integer.getInteger(name, defaultValue);
    }

    public static long number(String name, long defaultValue) {
        return Long.getLong(name, defaultValue);
    }

    public static double decimal(String name, double defaultValue) {
        String value = System.getProperty(name);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /** A text option, or {@code defaultValue} (may be {@code null}) when unset. */
    public static String text(String name, String defaultValue) {
        return System.getProperty(name, defaultValue);
    }
}
