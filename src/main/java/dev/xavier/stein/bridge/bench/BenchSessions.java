/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.bench;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * A benchmark session: one run per graphics mode, each writing its report into the session's folder
 * ({@code stein-bench/<session>/<mode>.zip}), and at the end one zip to send: with a single mode,
 * that mode's report; with several, every mode's report in its own folder inside the zip and {@code
 * compare.txt}/{@code compare.json} side by side (fps, lows and GPU time per part and mode). A mode
 * that could not run leaves a note, and the note goes in too.
 */
final class BenchSessions {

    private BenchSessions() {}

    static File folder(File gameDir, String session) {
        return new File(new File(gameDir, BenchOptions.DIR), session);
    }

    static File modeZip(File gameDir, String session, String mode) {
        return new File(folder(gameDir, session), mode + ".zip");
    }

    /** A mode that could not run (the reopened game came up in another mode, for instance). */
    static void note(File gameDir, String session, String mode, String why) {
        try {
            File f = new File(folder(gameDir, session), mode + ".skipped.txt");
            Files.createDirectories(f.getParentFile().toPath());
            Files.writeString(f.toPath(), why + "\n");
        } catch (IOException e) {
            Bench.event("note not written: " + e);
        }
    }

    /** Joins the session into the zip to send; returns it (null when there was nothing). */
    static File combine(File gameDir, String session) throws IOException {
        File dir = folder(gameDir, session);
        File[] zips = dir.listFiles((d, n) -> n.endsWith(".zip"));
        File[] notes = dir.listFiles((d, n) -> n.endsWith(".skipped.txt"));
        if (zips == null || zips.length == 0) {
            return null;
        }
        Arrays.sort(zips, Comparator.comparingLong(File::lastModified));
        File base = new File(gameDir, BenchOptions.DIR);
        if (zips.length == 1 && (notes == null || notes.length == 0)) {
            String mode = zips[0].getName().replace(".zip", "");
            File out = new File(base, "stein-bench-" + session + "-" + mode + ".zip");
            Files.move(zips[0].toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING);
            deleteFolder(dir);
            return out;
        }
        File out = new File(base, "stein-bench-" + session + ".zip");
        Map<String, JsonObject> summaries = new LinkedHashMap<>();
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(out.toPath()))) {
            for (File f : zips) {
                String mode = f.getName().replace(".zip", "");
                try (ZipFile in = new ZipFile(f)) {
                    for (ZipEntry e : java.util.Collections.list(in.entries())) {
                        z.putNextEntry(new ZipEntry(mode + "/" + e.getName()));
                        try (InputStream is = in.getInputStream(e)) {
                            byte[] b = is.readAllBytes();
                            z.write(b);
                            if (e.getName().equals("summary.json")) {
                                summaries.put(
                                        mode,
                                        new JsonParser()
                                                .parse(new String(b, StandardCharsets.UTF_8))
                                                .getAsJsonObject());
                            }
                        }
                        z.closeEntry();
                    }
                }
            }
            List<String> skipped = new ArrayList<>();
            if (notes != null) {
                for (File n : notes) {
                    skipped.add(
                            n.getName().replace(".skipped.txt", "")
                                    + ": "
                                    + Files.readString(n.toPath()).trim());
                }
            }
            z.putNextEntry(new ZipEntry("compare.txt"));
            z.write(compareText(summaries, skipped).getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
            z.putNextEntry(new ZipEntry("compare.json"));
            z.write(compareJson(summaries, skipped).getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        deleteFolder(dir);
        return out;
    }

    private static void deleteFolder(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                f.delete();
            }
        }
        dir.delete();
    }

    /** part -> mode -> {fps, 1% low, p99 ms, gpu ms}. */
    private static Map<String, Map<String, double[]>> table(Map<String, JsonObject> summaries) {
        Map<String, Map<String, double[]>> t = new LinkedHashMap<>();
        for (Map.Entry<String, JsonObject> s : summaries.entrySet()) {
            JsonArray parts = s.getValue().getAsJsonArray("parts");
            for (JsonElement pe : parts) {
                JsonObject p = pe.getAsJsonObject();
                JsonObject f = p.getAsJsonObject("frame");
                JsonObject g = p.getAsJsonObject("gpu");
                double[] v = {
                    num(f, "fps_avg"),
                    num(f, "fps_1pct_low"),
                    num(f, "ms_p99"),
                    num(g, "span_ms_avg")
                };
                t.computeIfAbsent(p.get("name").getAsString(), k -> new LinkedHashMap<>())
                        .put(s.getKey(), v);
            }
        }
        return t;
    }

    private static double num(JsonObject o, String key) {
        return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsDouble() : -1;
    }

    private static String compareText(Map<String, JsonObject> summaries, List<String> skipped) {
        StringBuilder b = new StringBuilder("Stein benchmark: the graphics modes side by side\n");
        b.append("each cell: average fps / 1% low fps / p99 ms / GPU ms\n\n");
        b.append(String.format(Locale.ROOT, "%-26s", "part"));
        for (String m : summaries.keySet()) {
            b.append(String.format(Locale.ROOT, " | %-30s", m));
        }
        b.append('\n');
        Map<String, double[]> sums = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, double[]>> row : table(summaries).entrySet()) {
            b.append(String.format(Locale.ROOT, "%-26s", row.getKey()));
            for (String m : summaries.keySet()) {
                double[] v = row.getValue().get(m);
                if (v == null) {
                    b.append(String.format(Locale.ROOT, " | %-30s", "-"));
                    continue;
                }
                b.append(
                        String.format(
                                Locale.ROOT,
                                " | %-30s",
                                String.format(
                                        Locale.ROOT,
                                        "%.0f / %.0f / %.2f / %s",
                                        v[0],
                                        v[1],
                                        v[2],
                                        v[3] < 0
                                                ? "-"
                                                : String.format(Locale.ROOT, "%.2f", v[3]))));
                double[] s = sums.computeIfAbsent(m, k -> new double[3]);
                s[0] += v[0];
                s[1] += v[1];
                s[2]++;
            }
            b.append('\n');
        }
        b.append('\n');
        for (Map.Entry<String, double[]> s : sums.entrySet()) {
            b.append(
                    String.format(
                            Locale.ROOT,
                            "%s: mean over the parts %.0f fps, 1%% low %.0f fps%n",
                            s.getKey(),
                            s.getValue()[0] / s.getValue()[2],
                            s.getValue()[1] / s.getValue()[2]));
        }
        for (String s : skipped) {
            b.append("not run: ").append(s).append('\n');
        }
        return b.toString();
    }

    private static String compareJson(Map<String, JsonObject> summaries, List<String> skipped) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("modes", new ArrayList<>(summaries.keySet()));
        Map<String, Object> parts = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, double[]>> row : table(summaries).entrySet()) {
            Map<String, Object> byMode = new LinkedHashMap<>();
            for (Map.Entry<String, double[]> e : row.getValue().entrySet()) {
                Map<String, Object> v = new LinkedHashMap<>();
                v.put("fps_avg", e.getValue()[0]);
                v.put("fps_1pct_low", e.getValue()[1]);
                v.put("ms_p99", e.getValue()[2]);
                v.put("gpu_ms", e.getValue()[3] < 0 ? null : e.getValue()[3]);
                byMode.put(e.getKey(), v);
            }
            parts.put(row.getKey(), byMode);
        }
        m.put("parts", parts);
        m.put("not_run", skipped);
        return BenchJson.write(m);
    }
}
