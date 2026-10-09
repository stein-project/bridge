/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

package org.lwjgl;

import java.awt.Desktop;
import java.net.URI;

/** Global utilities of LWJGL 2. Removed in LWJGL 3. */
public final class Sys {

    /**
     * Anchors on nanoTime at initialization so that getTime() starts near zero. Minecraft computes
     * millis as {@code getTime() * 1000 / getTimerResolution()}; anchoring avoids overflow of that
     * product when the OS clock has a high origin.
     */
    private static final long START_NANOS = System.nanoTime();

    /** Microseconds per second: reported timer resolution. */
    private static final long RESOLUTION = 1_000_000L;

    private Sys() {}

    /** Ticks elapsed since initialization, in the unit of {@link #getTimerResolution()}. */
    public static long getTime() {
        return (System.nanoTime() - START_NANOS) / 1_000L;
    }

    public static long getTimerResolution() {
        return RESOLUTION;
    }

    /**
     * Reports the LWJGL 2 version we are emulating, not SteinBridge's — existing code may parse
     * this value.
     */
    public static String getVersion() {
        return "2.9.4";
    }

    public static boolean openURL(String url) {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop desktop = Desktop.getDesktop();
                if (desktop.isSupported(Desktop.Action.BROWSE)) {
                    desktop.browse(new URI(url));
                    return true;
                }
            }
        } catch (Throwable ignored) { // never let a failed browser launch reach the game
            // Headless environment or invalid URI: falls through to the false return below.
        }
        return false;
    }
}
