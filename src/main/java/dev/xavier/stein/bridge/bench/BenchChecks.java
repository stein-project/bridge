/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.bench;

import dev.xavier.stein.bridge.core.CoreOptions;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The validation of each part: did the game do what the part asked of it? The world drew (chunks on
 * screen), the entities, tile entities and particles the part puts there were drawn, the picture is
 * not blank, nothing threw, OpenGL queued no error, the integrated server kept its ticks, it was
 * the right dimension, the land had settled, the window stayed in front. Each check is PASS, WARN
 * (the numbers are less trustworthy) or FAIL (something did not work).
 */
final class BenchChecks {

    private BenchChecks() {}

    static final String PASS = "PASS";
    static final String WARN = "WARN";
    static final String FAIL = "FAIL";

    /** The checks of one part: name to {status, detail}. */
    static Map<String, Object> of(
            BenchStats s,
            BenchShots.Shot shot,
            double[] tps,
            double[] otherCpu,
            boolean gpuExpected) {
        Map<String, Object> c = new LinkedHashMap<>();
        BenchScene.Part p = s.part;
        boolean world = p.dimension() != BenchScene.ANY_DIMENSION;
        if (s.aborted != null) {
            put(c, "stopped", FAIL, s.aborted);
        }
        put(c, "frames", s.count >= 20 ? PASS : FAIL, s.count + " measured frames");
        put(
                c,
                "window",
                s.clean() ? PASS : WARN,
                (CoreOptions.DIAG_BACKGROUND
                                ? "background run, minimized throughout expected: "
                                : "")
                        + s.unfocused
                        + " frames out of focus, "
                        + s.minimized
                        + " minimized, "
                        + s.resized
                        + " resizes");
        double chunks = avg(s, 9);
        if (world) {
            put(c, "world_drawn", chunks >= 30 ? PASS : FAIL, fmt(chunks) + " chunks on screen");
            put(
                    c,
                    "dimension",
                    s.dimension == p.dimension() ? PASS : FAIL,
                    "in " + s.dimension + ", expected " + p.dimension());
            put(
                    c,
                    "land_settled",
                    s.settled || !p.waitChunks() ? PASS : WARN,
                    s.settled ? "chunks done before measuring" : "still loading when measured");
        }
        if (s.biome >= 0 && s.biomeSamples > 0) {
            double share = (double) s.biomeHits / s.biomeSamples;
            put(
                    c,
                    "biome",
                    share >= 0.8 ? PASS : share >= 0.5 ? WARN : FAIL,
                    fmt(share * 100) + "% of the samples over " + BenchGuard.biomeName(s.biome));
        }
        if (p.minEntities() > 0) {
            double e = avg(s, 10);
            put(
                    c,
                    "entities_drawn",
                    e >= p.minEntities() ? PASS : FAIL,
                    fmt(e) + " drawn on average, at least " + p.minEntities() + " expected");
        }
        if (p.minTiles() > 0) {
            double t = avg(s, 12);
            put(
                    c,
                    "tile_entities",
                    t >= p.minTiles() ? PASS : FAIL,
                    fmt(t) + " in the world, at least " + p.minTiles() + " expected");
        }
        if (p.minParticles() > 0) {
            double t = avg(s, 13);
            put(
                    c,
                    "particles",
                    t >= p.minParticles() ? PASS : FAIL,
                    fmt(t) + " on average, at least " + p.minParticles() + " expected");
        }
        if (shot == null) {
            put(c, "picture", WARN, "no picture");
        } else {
            boolean blank = shot.stdev() < 4 || shot.mean() < 3 || shot.mean() > 252;
            put(
                    c,
                    "picture",
                    blank ? FAIL : PASS,
                    "brightness " + fmt(shot.mean()) + ", contrast " + fmt(shot.stdev()));
        }
        int ex =
                s.segment < BenchConsole.EXCEPTIONS.length ? BenchConsole.EXCEPTIONS[s.segment] : 0;
        String first =
                s.segment < BenchConsole.FIRST_EXCEPTION.length
                        ? BenchConsole.FIRST_EXCEPTION[s.segment]
                        : null;
        put(
                c,
                "exceptions",
                ex == 0 ? PASS : FAIL,
                ex == 0 ? "none in the console" : ex + " line(s); first: " + first);
        if (s.glErrors >= 0) {
            put(c, "opengl_errors", s.glErrors == 0 ? PASS : FAIL, s.glErrors + " queued");
        }
        if (gpuExpected && world) {
            put(c, "gpu_timing", s.gpuCount > 0 ? PASS : WARN, s.gpuCount + " GPU frames measured");
        }
        boolean serverPaused =
                p.kind() == BenchScene.Kind.MENUS_INGAME || p.kind() == BenchScene.Kind.MENUS_TITLE;
        if (tps != null && !serverPaused) {
            put(
                    c,
                    "server_ticks",
                    tps[0] >= 19 && tps[1] >= 15 ? PASS : WARN,
                    "tps " + fmt(tps[0]) + " on average, lowest " + fmt(tps[1]));
        }
        if (otherCpu != null) {
            put(
                    c,
                    "quiet_machine",
                    noisy(otherCpu) ? WARN : PASS,
                    "other processes used "
                            + fmt(otherCpu[0])
                            + "% of the processor on average, "
                            + fmt(otherCpu[1])
                            + "% at most");
        }
        return c;
    }

    /** Whether other processes competed for the processor while the part was measured. */
    static boolean noisy(double[] otherCpu) {
        return otherCpu != null && otherCpu[0] > BenchSampler.NOISY_OTHER_CPU_PCT;
    }

    /** The worst status of a part's checks. */
    @SuppressWarnings("unchecked")
    static String worst(Map<String, Object> checks) {
        String w = PASS;
        for (Object o : checks.values()) {
            String st = String.valueOf(((Map<String, Object>) o).get("status"));
            if (st.equals(FAIL)) {
                return FAIL;
            }
            if (st.equals(WARN)) {
                w = WARN;
            }
        }
        return w;
    }

    /** The failed and warned checks of a part, as short lines. */
    @SuppressWarnings("unchecked")
    static List<String> problems(Map<String, Object> checks) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Object> e : checks.entrySet()) {
            Map<String, Object> v = (Map<String, Object>) e.getValue();
            if (!PASS.equals(v.get("status"))) {
                out.add(v.get("status") + " " + e.getKey() + ": " + v.get("detail"));
            }
        }
        return out;
    }

    private static void put(Map<String, Object> c, String name, String status, String detail) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("status", status);
        v.put("detail", detail);
        c.put(name, v);
    }

    private static double avg(BenchStats s, int tail) {
        return s.count == 0 ? 0 : (double) s.tailSum[tail] / s.count;
    }

    private static String fmt(double x) {
        return String.format(java.util.Locale.ROOT, "%.1f", x);
    }
}
