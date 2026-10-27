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
import net.minecraft.server.MinecraftServer;

import java.lang.management.CompilationMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;

/**
 * Once a second, on its own thread, what the frame rows cannot see: the processor time of each
 * group of threads (render, integrated server, chunk builders, pose workers, network, the JVM's own
 * — garbage collector and compiler — as the process minus the Java threads), the heap, the
 * collections and the compiler, the integrated server's tick, the time of each mod (the Loader's
 * own measurement, when it is there), and the GPU and processor clocks the external tools report
 * ({@link BenchExternal}). Rows go to {@code samples.csv}.
 */
final class BenchSampler {

    static final String[] COLUMNS = {
        "t_s",
        "segment",
        "process_cpu_cores",
        "machine_cpu_pct",
        "render_thread_cpu_pct",
        "server_thread_cpu_pct",
        "chunk_threads_cpu_pct",
        "pose_threads_cpu_pct",
        "network_threads_cpu_pct",
        "bench_threads_cpu_pct",
        "other_java_threads_cpu_pct",
        "jvm_internal_cpu_pct",
        "java_threads",
        "heap_used_mb",
        "heap_committed_mb",
        "heap_max_mb",
        "gc_count",
        "gc_ms",
        "jit_ms",
        "server_tps",
        "server_tick_ms_avg",
        "server_tick_ms_max",
        "mods_ms_per_frame",
        "dimension",
        "client_chunks_loaded",
        "server_chunks_loaded",
        "chunks_pending",
        "client_entities",
        "server_entities",
        "gpu_name",
        "gpu_clock_mhz",
        "gpu_mem_clock_mhz",
        "gpu_power_w",
        "gpu_temp_c",
        "gpu_util_pct",
        "gpu_pstate",
        "gpu_throttle",
        "cpu_perf_pct",
        "cpu_freq_mhz",
        "gpu_3d_util_pct_game"
    };

    private static final int RENDER = 0;
    private static final int SERVER = 1;
    private static final int CHUNK = 2;
    private static final int POSE = 3;
    private static final int NET = 4;
    private static final int BENCH = 5;
    private static final int OTHER = 6;

    private final long renderThread;
    private final IntSupplier segment;
    private final BenchExternal external;
    private final List<String> rows = new ArrayList<>();

    /** The integrated server's ticks per second in the last sample (NaN without a server). */
    private double lastTps = Double.NaN;

    /** Per part: the sum of the samples' tps, how many, and the lowest. */
    private final Map<String, double[]> tps = new HashMap<>();

    private final List<String> segmentNames;
    private final Thread thread;
    private volatile boolean running = true;

    private final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
    private final com.sun.management.OperatingSystemMXBean os;
    private final Map<Long, Long> lastThreadCpu = new HashMap<>();
    private final long t0 = System.nanoTime();
    private long lastWall;
    private long lastProcessCpu;
    private long lastGcCount;
    private long lastGcMs;
    private long lastJit;
    private int lastServerTicks = -1;
    private long lastServerAt;
    private Method modTimes;
    private boolean modTimesTried;

    BenchSampler(
            long renderThread,
            IntSupplier segment,
            List<String> segmentNames,
            BenchExternal external) {
        this.renderThread = renderThread;
        this.segment = segment;
        this.segmentNames = segmentNames;
        this.external = external;
        java.lang.management.OperatingSystemMXBean b = ManagementFactory.getOperatingSystemMXBean();
        os = b instanceof com.sun.management.OperatingSystemMXBean s ? s : null;
        if (threads.isThreadCpuTimeSupported() && !threads.isThreadCpuTimeEnabled()) {
            threads.setThreadCpuTimeEnabled(true);
        }
        thread = new Thread(this::run, "Stein Bench sampler");
        thread.setDaemon(true);
        thread.start();
    }

    void stop() {
        running = false;
        thread.interrupt();
        try {
            thread.join(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** The rows so far, as CSV with the header. */
    synchronized String csv() {
        StringBuilder b = new StringBuilder(64 * 1024);
        b.append(String.join(",", COLUMNS)).append('\n');
        for (String r : rows) {
            b.append(r).append('\n');
        }
        return b.toString();
    }

    private void run() {
        sample(false); // the baseline of every delta
        while (running) {
            try {
                Thread.sleep(1000L);
            } catch (InterruptedException e) {
                if (!running) {
                    return;
                }
            }
            try {
                sample(true);
            } catch (RuntimeException e) {
                Log.warn("bench", "sampler: " + e);
            }
        }
    }

    private void sample(boolean keep) {
        long now = System.nanoTime();
        double wall = lastWall == 0 ? 0 : now - lastWall;
        lastWall = now;

        double[] group = new double[7];
        int javaThreads = 0;
        long javaCpu = 0;
        long[] ids = threads.getAllThreadIds();
        ThreadInfo[] infos = threads.getThreadInfo(ids);
        for (int i = 0; i < ids.length; i++) {
            if (infos[i] == null) {
                continue;
            }
            long cpu = threads.getThreadCpuTime(ids[i]);
            if (cpu < 0) {
                continue;
            }
            javaThreads++;
            Long before = lastThreadCpu.put(ids[i], cpu);
            long d = before == null ? 0 : cpu - before;
            javaCpu += d;
            group[groupOf(ids[i], infos[i].getThreadName())] += d;
        }
        long processCpu = os != null ? os.getProcessCpuTime() : -1;
        long processDelta = lastProcessCpu > 0 && processCpu > 0 ? processCpu - lastProcessCpu : 0;
        lastProcessCpu = processCpu;

        long gcCount = 0;
        long gcMs = 0;
        for (GarbageCollectorMXBean g : ManagementFactory.getGarbageCollectorMXBeans()) {
            gcCount += Math.max(0, g.getCollectionCount());
            gcMs += Math.max(0, g.getCollectionTime());
        }
        CompilationMXBean jit = ManagementFactory.getCompilationMXBean();
        long jitMs =
                jit != null && jit.isCompilationTimeMonitoringSupported()
                        ? jit.getTotalCompilationTime()
                        : 0;
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        String server = server(now);
        String mods = mods();
        if (!keep || wall <= 0) {
            lastGcCount = gcCount;
            lastGcMs = gcMs;
            lastJit = jitMs;
            return;
        }
        StringBuilder r = new StringBuilder(256);
        int seg = segment.getAsInt();
        r.append(String.format(java.util.Locale.ROOT, "%.1f", (now - t0) / 1e9)).append(',');
        r.append(seg >= 0 && seg < segmentNames.size() ? segmentNames.get(seg) : "-").append(',');
        r.append(fmt(processDelta / wall)).append(',');
        r.append(os != null ? fmt(os.getCpuLoad() * 100) : "").append(',');
        for (int g = 0; g < group.length; g++) {
            r.append(fmt(group[g] / wall * 100)).append(',');
        }
        r.append(fmt(Math.max(0, processDelta - javaCpu) / wall * 100)).append(',');
        r.append(javaThreads).append(',');
        r.append(heap.getUsed() >> 20).append(',');
        r.append(heap.getCommitted() >> 20).append(',');
        r.append(heap.getMax() >> 20).append(',');
        r.append(gcCount - lastGcCount).append(',');
        r.append(gcMs - lastGcMs).append(',');
        r.append(jitMs - lastJit).append(',');
        r.append(server).append(',');
        r.append(csvText(mods)).append(',');
        r.append(world()).append(',');
        r.append(external.sampleColumns());
        // only while a part is measured: its set-up (commands, a dimension change) is not its own
        int measured = Bench.measuringSegment();
        if (measured >= 0 && measured == seg && !Double.isNaN(lastTps)) {
            synchronized (this) {
                double[] a = tps.computeIfAbsent(segmentNames.get(seg), k -> new double[3]);
                a[0] += lastTps;
                a[1]++;
                a[2] = a[1] == 1 ? lastTps : Math.min(a[2], lastTps);
            }
        }
        lastGcCount = gcCount;
        lastGcMs = gcMs;
        lastJit = jitMs;
        synchronized (this) {
            rows.add(r.toString());
        }
    }

    private int groupOf(long id, String name) {
        if (id == renderThread) {
            return RENDER;
        }
        if (name.equals("Server thread")) {
            return SERVER;
        }
        if (name.startsWith("Chunk Batcher") || name.startsWith("steinbridge-terreno")) {
            return CHUNK;
        }
        if (name.startsWith("Stein pose")) {
            return POSE;
        }
        if (name.startsWith("Netty") || name.startsWith("Server Connector")) {
            return NET;
        }
        if (name.startsWith("Stein Bench")) {
            return BENCH;
        }
        return OTHER;
    }

    /** Per part: average and lowest tps of the integrated server ({avg, min}). */
    synchronized Map<String, double[]> tpsByPart() {
        Map<String, double[]> m = new HashMap<>();
        for (Map.Entry<String, double[]> e : tps.entrySet()) {
            double[] a = e.getValue();
            m.put(e.getKey(), new double[] {a[0] / a[1], a[2]});
        }
        return m;
    }

    /**
     * The world columns: dimension, chunks loaded on the client and on the server (the land being
     * generated and kept), chunks waiting to be built, entities on each side. Sizes only, read from
     * this thread (a sample may be a moment off).
     */
    private String world() {
        Minecraft mc = Minecraft.getMinecraft();
        net.minecraft.client.multiplayer.WorldClient w = mc.theWorld;
        if (w == null) {
            return ",,,,,";
        }
        int dim = w.provider.getDimensionId();
        String server = "";
        String serverEntities = "";
        MinecraftServer s = mc.getIntegratedServer();
        if (s != null) {
            net.minecraft.world.WorldServer ws = s.worldServerForDimension(dim);
            if (ws != null) {
                server = Integer.toString(ws.getChunkProvider().getLoadedChunkCount());
                serverEntities = Integer.toString(ws.loadedEntityList.size());
            }
        }
        return dim
                + ","
                + w.getChunkProvider().getLoadedChunkCount()
                + ","
                + server
                + ","
                + Bench.pendingChunks(mc)
                + ","
                + w.loadedEntityList.size()
                + ","
                + serverEntities;
    }

    /** "tps,avg,max" of the integrated server since the last sample, or ",," without one. */
    private String server(long now) {
        MinecraftServer s = Minecraft.getMinecraft().getIntegratedServer();
        lastTps = Double.NaN;
        if (s == null) {
            lastServerTicks = -1;
            return ",,";
        }
        int ticks = s.getTickCounter();
        String out = ",,";
        if (lastServerTicks >= 0 && now > lastServerAt) {
            lastTps = (ticks - lastServerTicks) / ((now - lastServerAt) / 1e9);
            long[] times = s.tickTimeArray;
            int n = Math.min(times.length, Math.max(0, ticks - lastServerTicks));
            long sum = 0;
            long max = 0;
            for (int i = 1; i <= n; i++) {
                long t = times[Math.floorMod(ticks - i, times.length)];
                sum += t;
                max = Math.max(max, t);
            }
            out =
                    fmt((ticks - lastServerTicks) / ((now - lastServerAt) / 1e9))
                            + ","
                            + (n == 0 ? "" : fmt(sum / 1e6 / n))
                            + ","
                            + fmt(max / 1e6);
        }
        lastServerTicks = ticks;
        lastServerAt = now;
        return out;
    }

    /**
     * The Loader's own per-mod time ("HUD 0.21 ms, Replay 0.04 ms"), read by reflection: the Bridge
     * does not depend on the Loader, and it may not be installed.
     */
    private String mods() {
        if (!modTimesTried) {
            modTimesTried = true;
            try {
                Class<?> c =
                        Class.forName(
                                "dev.xavier.stein.loader.ModTimes",
                                false,
                                Thread.currentThread().getContextClassLoader());
                Method m = c.getDeclaredMethod("summary");
                m.setAccessible(true);
                modTimes = m;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                modTimes = null;
            }
        }
        if (modTimes == null) {
            return "";
        }
        try {
            Object s = modTimes.invoke(null);
            return s == null ? "" : s.toString();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "";
        }
    }

    static String fmt(double x) {
        return String.format(java.util.Locale.ROOT, "%.1f", x);
    }

    static String csvText(String s) {
        if (s.indexOf(',') < 0 && s.indexOf('"') < 0) {
            return s;
        }
        return '"' + s.replace("\"", "\"\"") + '"';
    }
}
