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
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * What only the operating system and the driver know, from their own tools: {@code nvidia-smi}
 * (ships with NVIDIA's driver) for the GPU clock, power, temperature, load and throttling; Windows'
 * {@code typeperf} for the processor's real clock (laptops throttle) and the 3D load of this
 * process on any GPU; and, once at the start, read-only registry and powercfg queries for the
 * machine ({@link #probeMachine}). No script is run. Each tool runs in its own process and is read
 * on its own thread; a missing tool leaves its columns empty. Paths under the user's folder are
 * written as {@code ~}.
 */
final class BenchExternal {

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private final List<Process> processes = new ArrayList<>();

    /** The latest nvidia-smi line (first GPU), split. */
    private volatile String[] nvidia;

    /** The latest typeperf values: processor performance %, frequency MHz, game's 3D load %. */
    private volatile String[] perf;

    private volatile Map<String, Object> machine = Map.of();
    private volatile String nvidiaError = "";
    private volatile String typeperfError = "";

    void start() {
        if (!BenchOptions.EXTERNAL) {
            return;
        }
        startNvidia(true);
        if (WINDOWS) {
            startTypeperf();
            thread("machine", this::probeMachine);
        }
    }

    void stop() {
        for (Process p : processes) {
            p.destroy();
        }
    }

    /** The external columns of a sample row (the last 11 of {@link BenchSampler#COLUMNS}). */
    String sampleColumns() {
        String[] n = nvidia;
        String[] p = perf;
        StringBuilder b = new StringBuilder(128);
        for (int i = 0; i < 8; i++) {
            b.append(n != null && i < n.length ? BenchSampler.csvText(n[i].trim()) : "")
                    .append(',');
        }
        for (int i = 0; i < 3; i++) {
            b.append(p != null && i < p.length ? p[i] : "").append(i < 2 ? "," : "");
        }
        return b.toString();
    }

    /** The description of the machine (empty until read, or off Windows). */
    Map<String, Object> machine() {
        return machine;
    }

    String notes() {
        return (nvidiaError.isEmpty() ? "" : "nvidia-smi: " + nvidiaError + "; ")
                + (typeperfError.isEmpty() ? "" : "typeperf: " + typeperfError);
    }

    // ---- nvidia-smi ----

    private void startNvidia(boolean withThrottle) {
        String fields =
                "name,clocks.gr,clocks.mem,power.draw,temperature.gpu,utilization.gpu,pstate"
                        + (withThrottle ? ",clocks_throttle_reasons.active" : "");
        Process p =
                launch(
                        "nvidia-smi",
                        "--query-gpu=" + fields,
                        "--format=csv,noheader,nounits",
                        "-lms",
                        "1000");
        if (p == null) {
            nvidiaError = "not found (no NVIDIA driver)";
            return;
        }
        thread(
                "nvidia-smi",
                () -> {
                    int lines = 0;
                    try (BufferedReader r = reader(p, StandardCharsets.UTF_8)) {
                        for (String l; (l = r.readLine()) != null; ) {
                            if (l.isBlank()) {
                                continue;
                            }
                            if (l.contains("not a valid field") || l.contains("Field")) {
                                // an old or a new driver without the throttle field: once more
                                // without it
                                if (withThrottle) {
                                    p.destroy();
                                    startNvidia(false);
                                    return;
                                }
                            }
                            String[] f = l.split(",");
                            if (f.length >= 7) {
                                nvidia = f;
                                lines++;
                            } else if (lines == 0) {
                                nvidiaError = l.trim();
                            }
                        }
                    } catch (IOException e) {
                        nvidiaError = e.toString();
                    }
                });
    }

    // ---- typeperf ----

    private void startTypeperf() {
        // typeperf only takes the names in the system's language: translated through the
        // registry's counter tables
        BenchCounterNames names = BenchCounterNames.load();
        Process p =
                launch(
                        "typeperf",
                        "-si",
                        "1",
                        names.path("Processor Information", "_Total", "% Processor Performance"),
                        names.path("Processor Information", "_Total", "Processor Frequency"),
                        names.path("GPU Engine", "*", "Utilization Percentage"));
        if (p == null) {
            typeperfError = "not found";
            return;
        }
        String pid = "pid_" + ProcessHandle.current().pid() + "_";
        thread(
                "typeperf",
                () -> {
                    int perfCol = -1;
                    int freqCol = -1;
                    List<Integer> gpuCols = new ArrayList<>();
                    try (BufferedReader r = reader(p, BenchPower.consoleCharset())) {
                        for (String l; (l = r.readLine()) != null; ) {
                            List<String> f = csv(l);
                            if (f.size() < 2) {
                                if (!l.isBlank() && perfCol < 0) {
                                    typeperfError = l.trim();
                                }
                                continue;
                            }
                            if (f.get(0).startsWith("(PDH-CSV")) {
                                // the columns follow the counters asked for, in order; their
                                // names are in the system's language, the GPU instances are not
                                perfCol = 1;
                                freqCol = 2;
                                for (int i = 3; i < f.size(); i++) {
                                    String h = f.get(i);
                                    if (h.contains(pid) && h.contains("engtype_3D")) {
                                        gpuCols.add(i);
                                    }
                                }
                                continue;
                            }
                            double gpu = 0;
                            for (int c : gpuCols) {
                                gpu += number(f, c);
                            }
                            perf =
                                    new String[] {
                                        perfCol > 0 ? BenchSampler.fmt(number(f, perfCol)) : "",
                                        freqCol > 0 ? BenchSampler.fmt(number(f, freqCol)) : "",
                                        gpuCols.isEmpty() ? "" : BenchSampler.fmt(gpu)
                                    };
                        }
                    } catch (IOException e) {
                        typeperfError = e.toString();
                    }
                });
    }

    private static double number(List<String> f, int i) {
        if (i >= f.size()) {
            return 0;
        }
        try {
            return Double.parseDouble(f.get(i).trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** One line of quoted CSV. */
    private static List<String> csv(String l) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < l.length(); i++) {
            char c = l.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (c == ',' && !quoted) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }

    // ---- the machine, once ----

    private static final String CPU_KEY =
            "HKLM\\HARDWARE\\DESCRIPTION\\System\\CentralProcessor\\0";
    private static final String NT_KEY = "HKLM\\SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion";
    private static final String GPU_CLASS =
            "HKLM\\SYSTEM\\CurrentControlSet\\Control\\Class\\"
                    + "{4d36e968-e325-11ce-bfc1-08002be10318}";

    /**
     * The machine from the registry and powercfg (read-only queries; no script runs), and the power
     * state from Windows' own call ({@link BenchPower}): processor, GPUs with their driver version
     * and date, the GPU Windows assigns to Java (a laptop can run the game on the integrated one),
     * Game Mode, hardware GPU scheduling, power plan, AC or battery.
     */
    private void probeMachine() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cpu", regValue(CPU_KEY, "ProcessorNameString"));
        m.put("cpu_mhz_nominal", dword(regValue(CPU_KEY, "~MHz")));
        m.put("gpus", gpus());
        m.put(
                "windows",
                regValue(NT_KEY, "DisplayVersion") + " build " + regValue(NT_KEY, "CurrentBuild"));
        m.put("gpu_preference_for_java", gpuPreference());
        m.put(
                "game_mode",
                dword(regValue("HKCU\\Software\\Microsoft\\GameBar", "AutoGameModeEnabled")));
        m.put(
                "hardware_gpu_scheduling",
                dword(
                        regValue(
                                "HKLM\\SYSTEM\\CurrentControlSet\\Control\\GraphicsDrivers",
                                "HwSchMode")));
        m.put("power_plan", String.join(" ", output("powercfg", "/getactivescheme")).trim());
        m.putAll(BenchPower.status());
        machine = m;
    }

    /** Each display adapter's name, driver version and driver date. */
    private static List<Object> gpus() {
        Map<String, Map<String, Object>> byKey = new LinkedHashMap<>();
        for (String value : new String[] {"DriverDesc", "DriverVersion", "DriverDate"}) {
            String key = null;
            for (String l : output("reg", "query", GPU_CLASS, "/s", "/v", value)) {
                if (l.startsWith("HKEY_")) {
                    key = l.trim();
                } else if (key != null && l.contains("REG_")) {
                    byKey.computeIfAbsent(key, k -> new LinkedHashMap<>()).put(value, after(l));
                }
            }
        }
        List<Object> out = new ArrayList<>();
        for (Map<String, Object> g : byKey.values()) {
            if (g.containsKey("DriverDesc")) {
                out.add(g);
            }
        }
        return out;
    }

    /**
     * Windows' graphics setting for the Java running the game ("GpuPreference=2" is the
     * high-performance GPU), or "" when there is none. Only this executable's entry: the others
     * name the player's other programs, which are not the report's business.
     */
    private static String gpuPreference() {
        String exe = ProcessHandle.current().info().command().orElse("");
        if (exe.isEmpty()) {
            return "";
        }
        for (String l :
                output("reg", "query", "HKCU\\Software\\Microsoft\\DirectX\\UserGpuPreferences")) {
            if (l.contains("REG_")
                    && l.substring(0, l.indexOf("REG_")).trim().equalsIgnoreCase(exe)) {
                return after(l);
            }
        }
        return "";
    }

    /** A REG_DWORD as printed by reg ("0x10fe") as a number, or the text as it is. */
    private static Object dword(String v) {
        if (v.startsWith("0x")) {
            try {
                return Long.parseLong(v.substring(2), 16);
            } catch (NumberFormatException e) {
                return v;
            }
        }
        return v;
    }

    /** One registry value as text ("" when absent). */
    private static String regValue(String key, String value) {
        for (String l : output("reg", "query", key, "/v", value)) {
            if (l.contains("REG_")) {
                return after(l);
            }
        }
        return "";
    }

    /** The data after the "REG_TYPE" of a reg query line. */
    private static String after(String line) {
        String rest = line.substring(line.indexOf("REG_"));
        int sp = rest.indexOf(' ');
        return sp < 0 ? "" : rest.substring(sp).trim();
    }

    /** The lines a short command prints (what it printed so far after 20 s). */
    private static List<String> output(String... cmd) {
        List<String> lines = new ArrayList<>();
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            try (BufferedReader r = reader(p, BenchPower.consoleCharset())) {
                for (String l; (l = r.readLine()) != null; ) {
                    lines.add(l);
                }
            }
            p.waitFor(20, TimeUnit.SECONDS);
        } catch (IOException e) {
            return lines;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return lines;
    }

    // ---- plumbing ----

    private Process launch(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            synchronized (processes) {
                processes.add(p);
            }
            return p;
        } catch (IOException e) {
            return null;
        }
    }

    private static BufferedReader reader(Process p, Charset cs) {
        return new BufferedReader(new InputStreamReader(p.getInputStream(), cs));
    }

    private static void thread(String name, Runnable r) {
        Thread t = new Thread(r, "Stein Bench " + name);
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }
}
