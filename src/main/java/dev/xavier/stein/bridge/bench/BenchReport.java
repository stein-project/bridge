/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.bench;

import dev.xavier.stein.bridge.core.Log;

import net.minecraft.client.Minecraft;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The report's zip, closed at the end of a run (or when the window closes mid-run): {@code
 * frames.csv} (already streamed, every frame), {@code summary.json} (environment, machine and every
 * part's frame times, lows, zones, counters and GPU passes), {@code summary.txt} (the same, as a
 * table to read), {@code samples.csv} (once a second: threads, clocks, server, mods), {@code
 * gpu.csv} (each part's GPU passes), {@code events.log}, and copies of {@code options.txt}, the
 * graphics choice and the game log.
 */
final class BenchReport {

    /** Bumped when a reader of the report would have to change. */
    static final int FORMAT = 1;

    private BenchReport() {}

    /** Writes and closes the zip; returns null, or what went wrong. */
    static synchronized String write(
            ZipOutputStream zip,
            BenchFrames frames,
            BenchSampler sampler,
            BenchExternal external,
            Map<String, Object> environment,
            List<BenchStats> results,
            List<String> skipped,
            List<String> segments,
            long start,
            boolean stopped,
            Minecraft mc) {
        if (zip == null) {
            return "no report file";
        }
        String error = null;
        try {
            if (sampler != null) {
                sampler.stop();
            }
            if (external != null) {
                external.stop();
            }
            if (frames != null) {
                frames.finish();
            }
            Map<BenchStats, BenchShots.Shot> shots = new LinkedHashMap<>();
            for (BenchStats s : results) {
                BenchShots.Shot shot = BenchShots.take(s.shot);
                if (shot != null) {
                    shots.put(s, shot);
                    zip.putNextEntry(new ZipEntry(picture(s)));
                    zip.write(shot.jpeg());
                    zip.closeEntry();
                }
            }
            Map<String, double[]> tps = sampler == null ? Map.of() : sampler.tpsByPart();
            Map<String, Object> summary =
                    summary(frames, external, environment, results, start, stopped);
            validation(summary, results, shots, tps, skipped, segments, environment);
            entry(zip, "summary.json", BenchJson.write(summary));
            entry(zip, "summary.txt", text(summary, results));
            if (sampler != null) {
                entry(zip, "samples.csv", sampler.csv());
            }
            entry(zip, "gpu.csv", gpuCsv(results));
            entry(zip, "events.log", String.join("\n", Bench.events()) + "\n");
            zip.putNextEntry(new ZipEntry("console.log"));
            zip.write(BenchConsole.bytes());
            zip.closeEntry();
            copy(zip, new File(mc.mcDataDir, "options.txt"), "game/options.txt");
            copy(
                    zip,
                    new File(mc.mcDataDir, "config/stein-graphics.properties"),
                    "game/stein-graphics.properties");
            copy(zip, new File(mc.mcDataDir, "logs/latest.log"), "game/latest.log");
        } catch (IOException | RuntimeException e) {
            error = e.toString();
            Log.warn("bench", "report: " + e);
        } finally {
            try {
                zip.close();
            } catch (IOException e) {
                error = error == null ? e.toString() : error;
            }
        }
        return error;
    }

    private static Map<String, Object> summary(
            BenchFrames frames,
            BenchExternal external,
            Map<String, Object> environment,
            List<BenchStats> results,
            long start,
            boolean stopped) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("format", FORMAT);
        m.put("tool", "Stein benchmark");
        m.put("finished_at", LocalDateTime.now().toString());
        m.put("duration_s", BenchStats.round1((System.nanoTime() - start) / 1e9));
        m.put("stopped_before_the_end", stopped);
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("settings", BenchOptions.SETTINGS);
        run.put("scale", BenchOptions.SCALE);
        run.put("repeat", BenchOptions.REPEAT);
        run.put("only", BenchOptions.ONLY);
        run.put("world_seed", BenchScene.SEED);
        run.put("frames_written", frames == null ? 0 : frames.written());
        run.put("frames_dropped", frames == null ? 0 : frames.dropped());
        run.put("gpu_timing", BenchSystem.SET_BY_BENCH.isEmpty() ? "as set by the player" : "on");
        m.put("run", run);
        m.put("environment", environment);
        if (external != null) {
            m.put("machine", external.machine());
            m.put("external_tools", external.notes());
        }
        List<Object> parts = new ArrayList<>();
        for (BenchStats s : results) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("name", s.name);
            p.put("round", s.round);
            p.put("clean", s.clean());
            p.put("unfocused_frames", s.unfocused);
            p.put("minimized_frames", s.minimized);
            p.put("resizes", s.resized);
            p.put("window", s.lastWidth + "x" + s.lastHeight);
            p.put("frame", s.frameSummary());
            p.put("cpu_zones", s.zoneSummary());
            p.put("counters", s.counterSummary());
            p.put("gpu", s.gpuSummary());
            parts.add(p);
        }
        m.put("parts", parts);
        return m;
    }

    /** The zip entry of a part's picture. */
    private static String picture(BenchStats s) {
        return String.format(Locale.ROOT, "shots/%02d-%s.jpg", s.segment, s.name.replace('#', '_'));
    }

    /**
     * The checks of every part into the summary (each part's "checks" and "status"), and the run's
     * "validation": how many parts passed, warned or failed, what was skipped, and the console's
     * exceptions and warnings per part.
     */
    @SuppressWarnings("unchecked")
    private static void validation(
            Map<String, Object> summary,
            List<BenchStats> results,
            Map<BenchStats, BenchShots.Shot> shots,
            Map<String, double[]> tps,
            List<String> skipped,
            List<String> segments,
            Map<String, Object> environment) {
        boolean gpu =
                environment != null
                        && Boolean.TRUE.equals(
                                ((Map<String, Object>) environment.get("graphics"))
                                        .get("stein_gfx"));
        List<Object> parts = (List<Object>) summary.get("parts");
        int pass = 0;
        int warn = 0;
        int fail = 0;
        List<String> problems = new ArrayList<>();
        for (int i = 0; i < results.size(); i++) {
            BenchStats s = results.get(i);
            Map<String, Object> checks = BenchChecks.of(s, shots.get(s), tps.get(s.name), gpu);
            String status = BenchChecks.worst(checks);
            Map<String, Object> p = (Map<String, Object>) parts.get(i);
            p.put("status", status);
            p.put("checks", checks);
            if (shots.containsKey(s)) {
                p.put("picture", picture(s));
            }
            switch (status) {
                case BenchChecks.PASS -> pass++;
                case BenchChecks.WARN -> warn++;
                default -> fail++;
            }
            for (String line : BenchChecks.problems(checks)) {
                problems.add(s.name + ": " + line);
            }
        }
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("parts_pass", pass);
        v.put("parts_warn", warn);
        v.put("parts_fail", fail);
        v.put("problems", problems);
        v.put("skipped", skipped);
        v.put("console", BenchConsole.lines(segments));
        v.put("console_bytes_dropped", BenchConsole.dropped());
        summary.put("validation", v);
    }

    /** The same numbers as a table, for whoever opens the zip without a tool. */
    @SuppressWarnings("unchecked")
    private static String text(Map<String, Object> summary, List<BenchStats> results) {
        StringBuilder b = new StringBuilder(8192);
        Map<String, Object> env = (Map<String, Object>) summary.get("environment");
        Map<String, Object> gfx =
                env == null ? Map.of() : (Map<String, Object>) env.get("graphics");
        b.append("Stein benchmark report (format ").append(FORMAT).append(")\n");
        b.append("pipeline: ").append(gfx.get("pipeline")).append('\n');
        b.append("gpu: ")
                .append(gfx.getOrDefault("device_renderer", gfx.getOrDefault("gl_renderer", "?")))
                .append('\n');
        if (env != null) {
            b.append("window: ").append(env.get("window")).append('\n');
            b.append("diagnostic options on: ")
                    .append(env.get("diagnostic_options_on"))
                    .append('\n');
        }
        if (Boolean.TRUE.equals(summary.get("stopped_before_the_end"))) {
            b.append("STOPPED BEFORE THE END\n");
        }
        Map<String, Object> v = (Map<String, Object>) summary.get("validation");
        if (v != null) {
            b.append("\nValidation: ")
                    .append(v.get("parts_pass"))
                    .append(" parts pass, ")
                    .append(v.get("parts_warn"))
                    .append(" warn, ")
                    .append(v.get("parts_fail"))
                    .append(" fail\n");
            for (Object line : (List<Object>) v.get("problems")) {
                b.append("  ").append(line).append('\n');
            }
            for (Object line : (List<Object>) v.get("skipped")) {
                b.append("  skipped: ").append(line).append('\n');
            }
            for (Object line : (List<Object>) v.get("console")) {
                b.append("  console: ").append(line).append('\n');
            }
        }
        b.append('\n');
        b.append(
                String.format(
                        Locale.ROOT,
                        "%-26s %7s %7s %8s %8s %8s %8s %8s  %s%n",
                        "part",
                        "fps",
                        "1%low",
                        "0.1%low",
                        "p50 ms",
                        "p99 ms",
                        "max ms",
                        "gpu ms",
                        "note"));
        for (BenchStats s : results) {
            Map<String, Object> f = s.frameSummary();
            Map<String, Object> g = s.gpuSummary();
            b.append(
                    String.format(
                            Locale.ROOT,
                            "%-26s %7s %7s %8s %8s %8s %8s %8s  %s%n",
                            s.name,
                            f.get("fps_avg"),
                            f.get("fps_1pct_low"),
                            f.get("fps_0_1pct_low"),
                            f.get("ms_p50"),
                            f.get("ms_p99"),
                            f.get("ms_max"),
                            g.getOrDefault("span_ms_avg", "-"),
                            s.clean() ? "" : "window out of focus/minimized/resized"));
        }
        b.append("\nWhere the CPU time of a frame goes (ms per frame, top zones)\n");
        for (BenchStats s : results) {
            b.append(String.format(Locale.ROOT, "%-26s", s.name));
            List<Integer> order = new ArrayList<>();
            for (int z = 0; z < BenchZones.COUNT; z++) {
                order.add(z);
            }
            order.sort(Comparator.comparingLong(z -> -s.zoneSum[z]));
            int shown = 0;
            for (int z : order) {
                if (BenchZones.PARENT[z] == -1 && z != BenchZones.RENDER) {
                    continue; // the leaves and the world say more than the top level
                }
                if (s.count == 0 || shown++ == 7) {
                    break;
                }
                b.append(
                        String.format(
                                Locale.ROOT,
                                " %s %.3f |",
                                BenchZones.NAMES[z],
                                s.zoneSum[z] / 1e6 / s.count));
            }
            b.append('\n');
        }
        b.append("\nFrame handoff (ms per frame)\n");
        int[] handoff = {
            BenchZones.SUBMIT,
            BenchZones.SWAP,
            BenchZones.EVENTS,
            BenchZones.AWAIT,
            BenchZones.LIMITER,
            BenchZones.IDLE,
            BenchZones.BENCH
        };
        for (BenchStats s : results) {
            b.append(String.format(Locale.ROOT, "%-26s", s.name));
            for (int z : handoff) {
                b.append(
                        String.format(
                                Locale.ROOT,
                                " %s %.3f |",
                                BenchZones.NAMES[z],
                                s.count == 0 ? 0 : s.zoneSum[z] / 1e6 / s.count));
            }
            b.append('\n');
        }
        return b.toString();
    }

    private static String gpuCsv(List<BenchStats> results) {
        StringBuilder b = new StringBuilder("part,pass,ms_avg_per_frame,per_frame\n");
        for (BenchStats s : results) {
            if (s.gpuCount == 0) {
                continue;
            }
            for (Map.Entry<String, long[]> e : s.gpuPasses.entrySet()) {
                b.append(s.name)
                        .append(',')
                        .append(BenchSampler.csvText(e.getKey()))
                        .append(',')
                        .append(BenchStats.ms((double) e.getValue()[0] / s.gpuCount))
                        .append(',')
                        .append(BenchStats.round3((double) e.getValue()[1] / s.gpuCount))
                        .append('\n');
            }
        }
        return b.toString();
    }

    private static void entry(ZipOutputStream zip, String name, String text) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(text.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static void copy(ZipOutputStream zip, File f, String name) throws IOException {
        if (!f.isFile() || f.length() > 64L << 20) {
            return;
        }
        String text = Files.readString(f.toPath(), StandardCharsets.UTF_8);
        String home = System.getProperty("user.home", "");
        if (!home.isEmpty()) {
            text = text.replace(home, "~");
        }
        entry(zip, name, text);
    }
}
