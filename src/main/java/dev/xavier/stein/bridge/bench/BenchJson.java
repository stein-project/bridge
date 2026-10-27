/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.bench;

import java.util.Collection;
import java.util.Map;

/**
 * Writes the report's maps, lists, strings and numbers as indented JSON. A value of type {@link
 * Raw} goes in as it is (JSON that an external tool already produced).
 */
final class BenchJson {

    private BenchJson() {}

    /** JSON text kept as it is. */
    record Raw(String json) {}

    static String write(Object value) {
        StringBuilder b = new StringBuilder(16384);
        value(b, value, 0);
        return b.append('\n').toString();
    }

    private static void value(StringBuilder b, Object v, int depth) {
        if (v == null) {
            b.append("null");
        } else if (v instanceof Raw r) {
            String j = r.json().trim();
            b.append(j.isEmpty() ? "null" : j);
        } else if (v instanceof Map<?, ?> m) {
            object(b, m, depth);
        } else if (v instanceof Collection<?> c) {
            array(b, c, depth);
        } else if (v instanceof Double d) {
            b.append(d.isNaN() || d.isInfinite() ? "null" : trim(d));
        } else if (v instanceof Float f) {
            value(b, (double) f, depth);
        } else if (v instanceof Number || v instanceof Boolean) {
            b.append(v);
        } else {
            string(b, v.toString());
        }
    }

    private static String trim(double d) {
        if (d == Math.rint(d) && Math.abs(d) < 1e15) {
            return Long.toString((long) d);
        }
        return Double.toString(d);
    }

    private static void object(StringBuilder b, Map<?, ?> m, int depth) {
        if (m.isEmpty()) {
            b.append("{}");
            return;
        }
        b.append("{\n");
        int i = 0;
        for (Map.Entry<?, ?> e : m.entrySet()) {
            indent(b, depth + 1);
            string(b, String.valueOf(e.getKey()));
            b.append(": ");
            value(b, e.getValue(), depth + 1);
            b.append(++i < m.size() ? ",\n" : "\n");
        }
        indent(b, depth);
        b.append('}');
    }

    private static void array(StringBuilder b, Collection<?> c, int depth) {
        if (c.isEmpty()) {
            b.append("[]");
            return;
        }
        b.append("[\n");
        int i = 0;
        for (Object o : c) {
            indent(b, depth + 1);
            value(b, o, depth + 1);
            b.append(++i < c.size() ? ",\n" : "\n");
        }
        indent(b, depth);
        b.append(']');
    }

    private static void indent(StringBuilder b, int depth) {
        for (int i = 0; i < depth; i++) {
            b.append("  ");
        }
    }

    static void string(StringBuilder b, String s) {
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        b.append('"');
    }
}
