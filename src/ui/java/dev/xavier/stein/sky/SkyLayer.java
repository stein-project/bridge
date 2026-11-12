/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */
package dev.xavier.stein.sky;

import net.minecraft.util.ResourceLocation;

import java.util.Locale;
import java.util.Properties;

/**
 * One layer of a resource pack's sky (a {@code skyN.properties}): its picture, when it shows (the
 * fade times, the weather, the days, the biomes, the heights), how it turns and how it blends over
 * what is below it. Read from the format's public description; the drawing is {@link CustomSky}'s.
 *
 * <p>Times are the format's 24-hour clock: 6:00 is the sunrise (world time 0), 18:00 the sunset.
 */
final class SkyLayer {

    /** How the layer goes over the sky below it. */
    enum Blend {
        ADD,
        SUBTRACT,
        MULTIPLY,
        DODGE,
        BURN,
        SCREEN,
        REPLACE,
        OVERLAY,
        ALPHA
    }

    private static final int DAY_MINUTES = 24 * 60;

    final ResourceLocation texture;
    final Blend blend;
    final boolean rotate;
    final float speed;
    final float axisX;
    final float axisY;
    final float axisZ;

    /**
     * The fade times in minutes of the clock, or -1 when the layer is always at full brightness.
     */
    private final int startIn;

    private final int endIn;
    private final int startOut;
    private final int endOut;

    /** The weathers it shows in: bit 0 clear, 1 rain, 2 thunder. */
    private final int weather;

    /** The days of the cycle it shows on (bits), or 0 for every day. */
    private final long days;

    private final int daysLoop;

    /** The biome ids it shows in, or null for all. */
    private final int[] biomes;

    /** Pairs of heights (from, to) it shows at, or null for all. */
    private final int[] heights;

    /** Seconds a change of brightness takes. */
    final float transition;

    /** The brightness shown now, moving towards the target over {@link #transition}. */
    float shown = -1f;

    private SkyLayer(
            ResourceLocation texture,
            Blend blend,
            boolean rotate,
            float speed,
            float[] axis,
            int[] fades,
            int weather,
            long days,
            int daysLoop,
            int[] biomes,
            int[] heights,
            float transition) {
        this.texture = texture;
        this.blend = blend;
        this.rotate = rotate;
        this.speed = speed;
        this.axisX = axis[0];
        this.axisY = axis[1];
        this.axisZ = axis[2];
        this.startIn = fades[0];
        this.endIn = fades[1];
        this.startOut = fades[2];
        this.endOut = fades[3];
        this.weather = weather;
        this.days = days;
        this.daysLoop = daysLoop;
        this.biomes = biomes;
        this.heights = heights;
        this.transition = transition;
    }

    /**
     * Reads a layer: {@code dir} is the folder of the properties file ("mcpatcher/sky/world0/"),
     * {@code number} the N of {@code skyN.properties}. Null when the layer cannot be used (the
     * reason goes to {@code why}).
     */
    static SkyLayer parse(Properties p, String dir, int number, StringBuilder why) {
        String source = p.getProperty("source", "sky" + number + ".png").trim();
        ResourceLocation texture = location(source, dir);
        Blend blend;
        try {
            blend = Blend.valueOf(p.getProperty("blend", "add").trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            why.append("unknown blend ").append(p.getProperty("blend"));
            return null;
        }
        float[] axis = {0f, 0f, 1f};
        String a = p.getProperty("axis");
        if (a != null) {
            String[] f = a.trim().split("\\s+");
            if (f.length == 3) {
                try {
                    axis =
                            new float[] {
                                Float.parseFloat(f[0]),
                                Float.parseFloat(f[1]),
                                Float.parseFloat(f[2])
                            };
                } catch (NumberFormatException e) {
                    why.append("bad axis ").append(a);
                    return null;
                }
            }
            if (axis[0] == 0f && axis[1] == 0f && axis[2] == 0f) {
                why.append("zero axis");
                return null;
            }
        }
        int[] fades = fades(p);
        int weather = 0;
        for (String w :
                p.getProperty("weather", "clear").trim().toLowerCase(Locale.ROOT).split("\\s+")) {
            weather |=
                    switch (w) {
                        case "clear" -> 1;
                        case "rain" -> 2;
                        case "thunder" -> 4;
                        default -> 0;
                    };
        }
        int loop = Math.max(1, integer(p.getProperty("daysLoop"), 8));
        long days = 0L;
        String d = p.getProperty("days");
        if (d != null) {
            for (int[] r : ranges(d)) {
                for (int i = Math.max(0, r[0]); i <= Math.min(63, r[1]); i++) {
                    days |= 1L << i;
                }
            }
        }
        int[] biomes = null;
        String b = p.getProperty("biomes");
        if (b != null) {
            String[] f = b.trim().split("\\s+");
            biomes = new int[f.length];
            int n = 0;
            for (String id : f) {
                int v = integer(id, -1);
                if (v >= 0) {
                    biomes[n++] = v;
                }
            }
            biomes = java.util.Arrays.copyOf(biomes, n);
        }
        int[] heights = null;
        String h = p.getProperty("heights");
        if (h != null) {
            java.util.List<int[]> rs = ranges(h);
            heights = new int[rs.size() * 2];
            for (int i = 0; i < rs.size(); i++) {
                heights[2 * i] = rs.get(i)[0];
                heights[2 * i + 1] = rs.get(i)[1];
            }
        }
        float speed = (float) decimal(p.getProperty("speed"), 1.0);
        float transition = (float) Math.max(0.0, decimal(p.getProperty("transition"), 1.0));
        boolean rotate = !"false".equalsIgnoreCase(p.getProperty("rotate", "true").trim());
        return new SkyLayer(
                texture,
                blend,
                rotate,
                speed,
                axis,
                fades,
                weather == 0 ? 1 : weather,
                days,
                loop,
                biomes,
                heights,
                transition);
    }

    /** The four fade times; the start of the fade-out, when not given, mirrors the fade-in. */
    private static int[] fades(Properties p) {
        int startIn = clock(p.getProperty("startFadeIn"));
        int endIn = clock(p.getProperty("endFadeIn"));
        int endOut = clock(p.getProperty("endFadeOut"));
        if (startIn < 0 || endIn < 0 || endOut < 0) {
            return new int[] {-1, -1, -1, -1};
        }
        int startOut = clock(p.getProperty("startFadeOut"));
        if (startOut < 0) {
            int fadeIn = Math.floorMod(endIn - startIn, DAY_MINUTES);
            startOut = Math.floorMod(endOut - fadeIn, DAY_MINUTES);
        }
        return new int[] {startIn, endIn, startOut, endOut};
    }

    /** The texture path as given: absolute ("minecraft:..."), from the assets, or by the file. */
    private static ResourceLocation location(String source, String dir) {
        if (source.contains(":")) {
            return new ResourceLocation(source);
        }
        String path = source;
        if (path.startsWith("./")) {
            path = dir + path.substring(2);
        } else if (path.startsWith("assets/minecraft/")) {
            path = path.substring("assets/minecraft/".length());
        } else if (!path.contains("/")) {
            path = dir + path;
        }
        return new ResourceLocation("minecraft", path);
    }

    /** Whether the layer shows at all in this weather, day, biome and height. */
    boolean applies(boolean rain, boolean thunder, long worldTime, int biome, int y) {
        int now = thunder ? 4 : rain ? 2 : 1;
        if ((weather & now) == 0) {
            return false;
        }
        if (days != 0L
                && (days & 1L << (int) Math.floorMod(worldTime / 24000L, (long) daysLoop)) == 0) {
            return false;
        }
        if (biomes != null) {
            boolean in = false;
            for (int b : biomes) {
                in |= b == biome;
            }
            if (!in) {
                return false;
            }
        }
        if (heights != null) {
            boolean in = false;
            for (int i = 0; i < heights.length; i += 2) {
                in |= y >= heights[i] && y <= heights[i + 1];
            }
            return in;
        }
        return true;
    }

    /** The brightness from the fade times, 0 to 1, at this world time. */
    float fade(long worldTime) {
        if (startIn < 0) {
            return 1f;
        }
        float t = Math.floorMod(worldTime, 24000L) * 60f / 1000f + 6 * 60;
        t %= DAY_MINUTES;
        if (within(t, startIn, endIn)) {
            return part(t, startIn, endIn);
        }
        if (within(t, endIn, startOut)) {
            return 1f;
        }
        if (within(t, startOut, endOut)) {
            return 1f - part(t, startOut, endOut);
        }
        return 0f;
    }

    /** Whether {@code t} is in [from, to) on the clock, across midnight too. */
    private static boolean within(float t, int from, int to) {
        return from <= to ? t >= from && t < to : t >= from || t < to;
    }

    private static float part(float t, int from, int to) {
        float span = Math.floorMod(to - from, DAY_MINUTES);
        float into = ((t - from) % DAY_MINUTES + DAY_MINUTES) % DAY_MINUTES;
        return span <= 0f ? 1f : Math.min(1f, into / span);
    }

    /** "hh:mm" in minutes of the clock, or -1. */
    private static int clock(String v) {
        if (v == null) {
            return -1;
        }
        String[] f = v.trim().split(":");
        if (f.length != 2) {
            return -1;
        }
        int h = integer(f[0], -1);
        int m = integer(f[1], -1);
        return h < 0 || m < 0 ? -1 : Math.floorMod(h * 60 + m, DAY_MINUTES);
    }

    /** "1 3-5 (-3)-64": numbers and ranges, negatives in parentheses. */
    private static java.util.List<int[]> ranges(String v) {
        java.util.List<int[]> out = new java.util.ArrayList<>();
        for (String r : v.trim().split("\\s+")) {
            String s = r.replace("(", "").replace(")", "");
            int dash = s.indexOf('-', 1);
            if (dash < 0) {
                int n = integer(s, Integer.MIN_VALUE);
                if (n != Integer.MIN_VALUE) {
                    out.add(new int[] {n, n});
                }
            } else {
                int a = integer(s.substring(0, dash), Integer.MIN_VALUE);
                int b = integer(s.substring(dash + 1), Integer.MIN_VALUE);
                if (a != Integer.MIN_VALUE && b != Integer.MIN_VALUE) {
                    out.add(new int[] {Math.min(a, b), Math.max(a, b)});
                }
            }
        }
        return out;
    }

    private static int integer(String v, int otherwise) {
        try {
            return v == null ? otherwise : Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return otherwise;
        }
    }

    private static double decimal(String v, double otherwise) {
        try {
            return v == null ? otherwise : Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return otherwise;
        }
    }
}
