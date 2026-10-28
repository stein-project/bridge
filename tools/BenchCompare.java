/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * A developer tool that compares two benchmark reports part by part, so an A/B run (main against a
 * change) reads as numbers side by side instead of two summaries opened by hand. Not in the jar.
 *
 * <p>Each zip is either a single-mode report ({@code summary.json} at the root) or a session
 * ({@code <mode>/summary.json} per graphics mode). Modes present in both are compared; when each
 * side has exactly one and the names differ, those two are compared. For every part present in
 * both: fps avg, 1% and 0.1% low, p99, GPU time and each CPU zone in ms per frame, as {@code A -> B
 * (change %)}, then the allocation rate and the collections.
 *
 * <pre>
 *   ./gradlew benchCompare -Pa=&lt;zipA&gt; -Pb=&lt;zipB&gt;   (BenchCompare &lt;zipA&gt; &lt;zipB&gt;)
 * </pre>
 */
public final class BenchCompare {

    private BenchCompare() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("usage: BenchCompare <zipA> <zipB>");
            System.exit(2);
        }
        Map<String, Map<String, Object>> a = runs(Path.of(args[0]));
        Map<String, Map<String, Object>> b = runs(Path.of(args[1]));
        StringBuilder out = new StringBuilder();
        out.append("A: ").append(args[0]).append('\n');
        out.append("B: ").append(args[1]).append('\n');
        boolean any = false;
        for (Map.Entry<String, Map<String, Object>> e : a.entrySet()) {
            Map<String, Object> other = b.get(e.getKey());
            if (other != null) {
                compareRun(out, e.getKey(), e.getValue(), other);
                any = true;
            }
        }
        if (!any && a.size() == 1 && b.size() == 1) {
            String ka = a.keySet().iterator().next();
            String kb = b.keySet().iterator().next();
            compareRun(out, ka + " vs " + kb, a.get(ka), b.get(kb));
            any = true;
        }
        if (!any) {
            out.append("\nno graphics mode in common: A has ")
                    .append(a.keySet())
                    .append(", B has ")
                    .append(b.keySet())
                    .append('\n');
        }
        System.out.print(out);
    }

    /** The summary of each mode in the zip (a single-mode report is the mode "run"). */
    private static Map<String, Map<String, Object>> runs(Path zip) throws IOException {
        Map<String, Map<String, Object>> m = new LinkedHashMap<>();
        try (ZipFile z = new ZipFile(zip.toFile())) {
            ZipEntry root = z.getEntry("summary.json");
            if (root != null) {
                m.put("run", object(read(z, root)));
                return m;
            }
            for (ZipEntry e : java.util.Collections.list(z.entries())) {
                String n = e.getName();
                if (n.endsWith("/summary.json") && n.indexOf('/') == n.lastIndexOf('/')) {
                    m.put(n.substring(0, n.indexOf('/')), object(read(z, e)));
                }
            }
        }
        if (m.isEmpty()) {
            throw new IOException(zip + ": no summary.json (nor <mode>/summary.json)");
        }
        return m;
    }

    private static Object read(ZipFile z, ZipEntry e) throws IOException {
        try (InputStream in = z.getInputStream(e)) {
            return new Json(new String(in.readAllBytes(), StandardCharsets.UTF_8)).value();
        }
    }

    private static void compareRun(
            StringBuilder out, String mode, Map<String, Object> a, Map<String, Object> b) {
        out.append("\n== ").append(mode).append(" ==\n");
        Map<String, Map<String, Object>> pa = parts(a);
        Map<String, Map<String, Object>> pb = parts(b);
        for (Map.Entry<String, Map<String, Object>> e : pa.entrySet()) {
            Map<String, Object> other = pb.get(e.getKey());
            if (other != null) {
                comparePart(out, e.getKey(), e.getValue(), other);
            }
        }
        Set<String> onlyA = new LinkedHashSet<>(pa.keySet());
        onlyA.removeAll(pb.keySet());
        Set<String> onlyB = new LinkedHashSet<>(pb.keySet());
        onlyB.removeAll(pa.keySet());
        if (!onlyA.isEmpty()) {
            out.append("\nonly in A: ").append(String.join(", ", onlyA)).append('\n');
        }
        if (!onlyB.isEmpty()) {
            out.append("\nonly in B: ").append(String.join(", ", onlyB)).append('\n');
        }
    }

    /** The parts by name; a part repeated ({@code bench.repeat}) gets its round after an "@". */
    private static Map<String, Map<String, Object>> parts(Map<String, Object> summary) {
        Map<String, Map<String, Object>> m = new LinkedHashMap<>();
        Object list = summary.get("parts");
        if (!(list instanceof List<?> l)) {
            return m;
        }
        for (Object o : l) {
            Map<String, Object> p = object(o);
            String name = String.valueOf(p.get("name"));
            if (m.containsKey(name)) {
                name = name + "@" + text(p.get("round"));
            }
            m.put(name, p);
        }
        return m;
    }

    private static void comparePart(
            StringBuilder out, String name, Map<String, Object> a, Map<String, Object> b) {
        out.append('\n').append(name).append('\n');
        line(out, "fps avg", at(a, "frame", "fps_avg"), at(b, "frame", "fps_avg"));
        line(out, "fps 1% low", at(a, "frame", "fps_1pct_low"), at(b, "frame", "fps_1pct_low"));
        line(
                out,
                "fps 0.1% low",
                at(a, "frame", "fps_0_1pct_low"),
                at(b, "frame", "fps_0_1pct_low"));
        line(out, "p99 ms", at(a, "frame", "ms_p99"), at(b, "frame", "ms_p99"));
        line(out, "GPU ms", at(a, "gpu", "span_ms_avg"), at(b, "gpu", "span_ms_avg"));
        Map<String, Object> za = object(a.get("cpu_zones"));
        Map<String, Object> zb = object(b.get("cpu_zones"));
        Set<String> zones = new LinkedHashSet<>(za.keySet());
        zones.addAll(zb.keySet());
        for (String z : zones) {
            line(out, "  " + z, at(za, z, "ms_avg"), at(zb, z, "ms_avg"));
        }
        line(
                out,
                "alloc MB/s",
                at(a, "counters", "alloc_mb_per_s"),
                at(b, "counters", "alloc_mb_per_s"));
        line(
                out,
                "GC count",
                at(a, "counters", "gc_events_total"),
                at(b, "counters", "gc_events_total"));
    }

    private static void line(StringBuilder out, String label, Double a, Double b) {
        String change;
        if (a == null || b == null) {
            change = "";
        } else if (a == 0) {
            change = b == 0 ? "  (=)" : "  (new)";
        } else {
            change = String.format(Locale.ROOT, "  (%+.1f%%)", 100.0 * (b - a) / a);
        }
        out.append(
                String.format(
                        Locale.ROOT, "  %-22s %10s -> %-10s%s%n", label, num(a), num(b), change));
    }

    private static String num(Double v) {
        if (v == null) {
            return "-";
        }
        return v == Math.rint(v) && Math.abs(v) < 1e15
                ? String.valueOf(v.longValue())
                : String.format(Locale.ROOT, "%.3f", v);
    }

    /** {@code m.k1.k2} as a number, or null when it is missing or not a number. */
    private static Double at(Map<String, Object> m, String k1, String k2) {
        Object v = object(m.get(k1)).get(k2);
        return v instanceof Double d ? d : null;
    }

    private static String text(Object o) {
        return o instanceof Double d && d == Math.rint(d)
                ? String.valueOf(d.longValue())
                : String.valueOf(o);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object o) {
        return o instanceof Map<?, ?> ? (Map<String, Object>) o : Map.of();
    }

    /**
     * A minimal JSON reader for the benchmark's own files: objects become ordered maps, arrays
     * lists, numbers doubles. The report is written by Gson, so no leniency is needed.
     */
    private static final class Json {
        private final String s;
        private int i;

        Json(String s) {
            this.s = s;
        }

        Object value() {
            skip();
            char c = s.charAt(i);
            switch (c) {
                case '{':
                    return obj();
                case '[':
                    return arr();
                case '"':
                    return str();
                case 't':
                    i += 4;
                    return Boolean.TRUE;
                case 'f':
                    i += 5;
                    return Boolean.FALSE;
                case 'n':
                    i += 4;
                    return null;
                default:
                    return num();
            }
        }

        private Map<String, Object> obj() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++;
            skip();
            if (s.charAt(i) == '}') {
                i++;
                return m;
            }
            while (true) {
                skip();
                String k = str();
                skip();
                expect(':');
                m.put(k, value());
                skip();
                if (s.charAt(i++) == '}') {
                    return m;
                }
            }
        }

        private List<Object> arr() {
            List<Object> l = new ArrayList<>();
            i++;
            skip();
            if (s.charAt(i) == ']') {
                i++;
                return l;
            }
            while (true) {
                l.add(value());
                skip();
                if (s.charAt(i++) == ']') {
                    return l;
                }
            }
        }

        private String str() {
            expect('"');
            StringBuilder b = new StringBuilder();
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') {
                    return b.toString();
                }
                if (c != '\\') {
                    b.append(c);
                    continue;
                }
                char e = s.charAt(i++);
                switch (e) {
                    case 'n' -> b.append('\n');
                    case 't' -> b.append('\t');
                    case 'r' -> b.append('\r');
                    case 'b' -> b.append('\b');
                    case 'f' -> b.append('\f');
                    case 'u' -> {
                        b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                    }
                    default -> b.append(e);
                }
            }
        }

        private Object num() {
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
                i++;
            }
            String t = s.substring(start, i);
            try {
                return Double.parseDouble(t);
            } catch (NumberFormatException e) {
                // NaN/Infinity written by a lenient writer: kept as text
                while (i < s.length() && Character.isLetter(s.charAt(i))) {
                    i++;
                }
                return s.substring(start, i);
            }
        }

        private void expect(char c) {
            if (s.charAt(i) != c) {
                throw new IllegalStateException("JSON: expected '" + c + "' at " + i);
            }
            i++;
        }

        private void skip() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }
    }
}
