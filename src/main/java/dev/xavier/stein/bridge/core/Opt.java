/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
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
