/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.bench;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Windows performance counter names in the system's language. {@code typeperf} only accepts the
 * translated names ("\Informações do processador(_Total)\% de Desempenho do Processador" on a
 * Brazilian Windows), so each English name goes through the registry's tables: its index in {@code
 * Perflib\009}, then that index's name in {@code Perflib\CurrentLanguage}. A name not found stays
 * in English.
 */
final class BenchCounterNames {

    private static final String PERFLIB =
            "HKLM\\SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion\\Perflib\\";

    private final Map<String, String> indexOfEnglish;
    private final Map<String, String> localByIndex;

    private BenchCounterNames(Map<String, String> english, Map<String, String> local) {
        indexOfEnglish = english;
        localByIndex = local;
    }

    static BenchCounterNames load() {
        Map<String, String> english = new HashMap<>();
        for (Map.Entry<String, String> e : table("009").entrySet()) {
            english.putIfAbsent(e.getValue(), e.getKey()); // the first index of a name wins
        }
        return new BenchCounterNames(english, table("CurrentLanguage"));
    }

    /** {@code \object(instance)\counter} with the object and the counter translated. */
    String path(String object, String instance, String counter) {
        return "\\" + local(object) + "(" + instance + ")\\" + local(counter);
    }

    private String local(String english) {
        String i = indexOfEnglish.get(english);
        String l = i == null ? null : localByIndex.get(i);
        return l == null || l.isEmpty() ? english : l;
    }

    /** index to name, from a Perflib "Counter" value (REG_MULTI_SZ, printed with \0 between). */
    private static Map<String, String> table(String language) {
        Map<String, String> m = new HashMap<>();
        String data = "";
        try {
            Process p =
                    new ProcessBuilder("reg", "query", PERFLIB + language, "/v", "Counter")
                            .redirectErrorStream(true)
                            .start();
            try (BufferedReader r =
                    new BufferedReader(
                            new InputStreamReader(
                                    p.getInputStream(), BenchPower.consoleCharset()))) {
                for (String l; (l = r.readLine()) != null; ) {
                    int at = l.indexOf("REG_MULTI_SZ");
                    if (at >= 0) {
                        data = l.substring(at + "REG_MULTI_SZ".length()).trim();
                    }
                }
            }
            p.waitFor(20, TimeUnit.SECONDS);
        } catch (IOException e) {
            return m;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return m;
        }
        String[] parts = data.split("\\\\0");
        for (int i = 0; i + 1 < parts.length; i += 2) {
            m.put(parts[i].trim(), parts[i + 1]);
        }
        return m;
    }
}
