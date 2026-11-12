/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.ui;

import java.util.HashMap;
import java.util.Map;

/**
 * The Stein icons, the same as Stein Client's: geometric marks that are <b>stroke only</b>, in a 24
 * x 24 box, with round caps and joins and the same thickness throughout — no ready-made images, no
 * filled icons.
 *
 * <p>Each one is a list of shapes in the format of the SVG they come from ({@link Shapes#strokes});
 * the drawing is rasterized <b>once, with smoothing, at the real screen resolution</b> ({@link
 * Shapes#vector}) and cached — it comes out smooth at any interface scale, and takes the color (and
 * opacity, for animations) of whoever draws it.
 *
 * <p>{@code size} is the icon's size in interface units; the 24 box ends up a little larger than it
 * ({@link #BOX}), because each icon's drawing does not reach the edge of the box.
 */
public final class Icons {

    /** The 24 box relative to the requested size: the drawing fills its middle. */
    private static final float BOX = 1.3F;

    private static final Map<String, String[]> shapesByName = new HashMap<>();

    static {
        // ---- the Stein Client ones ----
        put("x", "M6 6L18 18", "M18 6L6 18");
        put("chevronDown", "M6 9l6 6 6-6");
        put("trash", "M5 7h14", "M8 7V5h8v2", "M6.5 7l1 13h9l1-13");
        put(
                "list",
                "M9 7L20 7",
                "M9 12L20 12",
                "M9 17L20 17",
                "d 4.5 7 1",
                "d 4.5 12 1",
                "d 4.5 17 1");
        put(
                "gear",
                "o 12 12 3.2",
                "M12 2.5v3M12 18.5v3M2.5 12h3M18.5 12h3M5.2 5.2l2.1 2.1M16.7 16.7l2.1 2.1M18.8"
                        + " 5.2l-2.1 2.1M5.2 18.8l2.1-2.1");
        put(
                "grid",
                "r 4 4 6.5 6.5 1",
                "r 13.5 4 6.5 6.5 1",
                "r 4 13.5 6.5 6.5 1",
                "r 13.5 13.5 6.5 6.5 1");
        put("users", "o 12 8 3.3", "M5.5 19a6.5 6.5 0 0 1 13 0");
        put("layers", "M12 3L21 8L12 13L3 8z", "M3 13l9 5 9-5");
        put("shield", "M12 3 20 6.5v5.5c0 5-3.6 7.5-8 8.5-4.4-1-8-3.5-8-8.5V6.5z");
        put("clock", "o 12 12 8.5", "M12 7.5v5l3.2 2");
        put("check", "o 12 12 8.5", "M8.4 12.4l2.6 2.6 4.6-5.2");
        // Only the check mark, without the circle: the one-line checkbox of the Panel.
        put("tick", "M6.5 12.5l3.5 3.5 7.5-8");
        put("bolt", "o 12 12 8.5", "M12 7.5v5", "d 12 16 0.9");
        put("lock", "r 6 11 12 8 1.2", "M9 11V8a3 3 0 0 1 6 0v3");
        put("key", "o 8 15 3.2", "M10.2 12.8 20 3M17 6l2 2M14 9l2 2");
        put("search", "o 11 11 7", "M16 16L21 21");
        put("plus", "M12 5L12 19", "M5 12L19 12");
        put("minus", "M6 12L18 12");
        put("play", "M8 5L19 12L8 19z");
        put("star", "M12 3L14.5 9L21 9.5L16 14L17.5 20.5L12 17L6.5 20.5L8 14L3 9.5L9.5 9z");
        put("heart", "M12 20s-7-4.5-7-9.5a3.5 3.5 0 0 1 7-1 3.5 3.5 0 0 1 7 1c0 5-7 9.5-7 9.5z");
        put("pin", "M12 21s6-5.5 6-10a6 6 0 0 0-12 0c0 4.5 6 10 6 10z", "o 12 11 2.2");
        put("ping", "M3 12h4l2-5 3 11 2.5-8 1.5 2h5");
        put("folder", "M3.5 7V18.5h17V9H11L9 6.5H3.5z", "M3.5 11L20.5 11");
        put("sword", "M12 3v10", "M9 13h6", "M12 13v6", "d 12 20 1");
        put(
                "loop",
                "M4.5 9.5a8 8 0 0 1 13.5-2.5",
                "M18.5 4v3h-3",
                "M19.5 14.5a8 8 0 0 1-13.5 2.5",
                "M5.5 20v-3h3");
        // ---- the Loader ones, built the same way ----
        // Screen: the frame with the item bar at the bottom — the HUD.
        put("screen", "r 3 5 18 13.5 1.2", "M8 15h8");
        // Two crossed blades, each with its guard.
        put("combat", "M5 19L19 5", "M19 19L5 5", "M6 13.5l4.5 4.5", "M18 13.5l-4.5 4.5");
        // Account and connection: two links.
        put(
                "link",
                "M10.2 13.8a4 4 0 0 0 5.66 0l2.6-2.6a4 4 0 0 0-5.66-5.66l-1.2 1.2",
                "M13.8 10.2a4 4 0 0 0-5.66 0l-2.6 2.6a4 4 0 0 0 5.66 5.66l1.2-1.2");
        // Edit: the tilted pencil.
        put("pencil", "M4.5 19.5l1-4.2L16.2 4.6l3.2 3.2L8.7 18.5z", "M13.6 7.2l3.2 3.2");
        // Restore: the arrow that goes back.
        put("restore", "M5.2 14a7.2 7.2 0 1 0 1.7-7.1L4 9.8", "M4 5v4.8h4.8");
        // ---- the video settings' ----
        // Day: the sun and its rays. Night: the crescent.
        put(
                "sun",
                "o 12 12 4",
                "M12 3v2M12 19v2M3 12h2M19 12h2M5.6 5.6l1.4 1.4M17 17l1.4 1.4M18.4 5.6L17 7M7"
                        + " 17l-1.4 1.4");
        put("moon", "M19.5 14.5A8 8 0 0 1 9.5 4.5a8 8 0 1 0 10 10z");
        // Show the picture: the eye. Hide it: the eye crossed out.
        put(
                "eye",
                "M2.5 12s3.5-6.5 9.5-6.5 9.5 6.5 9.5 6.5-3.5 6.5-9.5 6.5S2.5 12 2.5 12z",
                "o 12 12 2.8");
        put(
                "eyeOff",
                "M2.5 12s3.5-6.5 9.5-6.5 9.5 6.5 9.5 6.5-3.5 6.5-9.5 6.5S2.5 12 2.5 12z",
                "M4 4l16 16");
        // The topics: settings (two sliders), camera, light (a bulb), image (a sparkle), sky (a
        // cloud), performance (a gauge).
        put("sliders", "M5 8h14", "M5 16h14", "o 9 8 2", "o 15 16 2");
        put("camera", "r 3 7 18 12.5 2", "M8.5 7l1.5-2.5h4L15.5 7", "o 12 13.2 3.3");
        put(
                "bulb",
                "M9 18h6",
                "M10 21h4",
                "M9 15.5c-2-1.4-3-3.2-3-5.3a6 6 0 0 1 12 0c0 2.1-1 3.9-3 5.3z");
        put("sparkle", "M12 3l2 7 7 2-7 2-2 7-2-7-7-2 7-2z");
        put("cloud", "M7.5 18.5a4 4 0 0 1-.6-8 5.5 5.5 0 0 1 10.6 1.5 3.3 3.3 0 0 1-.5 6.5z");
        put("gauge", "M4.5 17a8 8 0 1 1 15 0", "M12 14l3.5-4.5", "d 12 14 1.2");
        // World detail: a block.
        put("cube", "M12 3l8 4.5v9L12 21l-8-4.5v-9z", "M4 7.5l8 4.5 8-4.5", "M12 12v9");
        // The views: Stein's landscape (mountains and the sun) or the player's own (a person).
        put("mountain", "M2.5 19.5l6.5-10 4.5 6 3-4 5 8z", "o 17 6 1.8");
        put("person", "o 12 8 3.6", "M5 20.5a7 7 0 0 1 14 0");
        // Back: the arrow to the left.
        put("back", "M10 5.5L3.5 12l6.5 6.5", "M4 12h16.5");
    }

    private Icons() {}

    private static void put(String name, String... shapes) {
        shapesByName.put(name, shapes);
    }

    /** Is there an icon with this name? (the names a Panel page can ask for) */
    public static boolean has(String name) {
        return shapesByName.containsKey(name);
    }

    /** The icon named {@code name}, in a box with side {@code size} centered at (cx, cy). */
    public static void draw(String name, float cx, float cy, float size, int color) {
        draw(name, cx, cy, size, 0.0F, color);
    }

    private static void draw(
            String name, float cx, float cy, float size, float degrees, int color) {
        String[] shapes = shapesByName.get(name);
        if (shapes == null) {
            return;
        }
        Shapes.vector(
                degrees == 0.0F ? name : name + '|' + degrees,
                cx,
                cy,
                size,
                color,
                () -> Shapes.strokes(cx, cy, size * BOX, degrees, shapes));
    }

    public static void pencil(float cx, float cy, float s, int color) {
        draw("pencil", cx, cy, s, color);
    }

    public static void close(float cx, float cy, float s, int color) {
        draw("x", cx, cy, s, color);
    }

    public static void restore(float cx, float cy, float s, int color) {
        draw("restore", cx, cy, s, color);
    }

    public static void trash(float cx, float cy, float s, int color) {
        draw("trash", cx, cy, s, color);
    }

    /** Open/close arrow, pointing down; rotated by whoever draws it (0 closed, 180 open). */
    public static void chevron(float cx, float cy, float s, float degrees, int color) {
        // The rotation is animated (opening and closing): in steps of 3 degrees, so as not to cache
        // one drawing per frame.
        draw("chevronDown", cx, cy, s, Math.round(degrees / 3.0F) * 3.0F, color);
    }
}
