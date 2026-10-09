/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

package dev.xavier.stein.bridge.core;

import net.minecraft.client.Minecraft;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPOutputStream;

/**
 * Stein's log: a log of our own, in {@code logs/stein/}, separate from the game's latest.log.
 *
 * <p>It is for finding problems the game log does not show — network, packets, frames, memory. It
 * is <b>off</b> by default and the player turns it on in the Stein Loader menu, on the Log tab;
 * only then is the file opened.
 *
 * <p>One session per file, as the game does: the previous session's {@code latest.log} becomes
 * {@code <year-month-day>-<n>.log.gz} when the log is turned on again.
 *
 * <p>None of this may weigh on the game. Callers only enqueue the finished line; a background
 * thread writes and flushes the buffer. The queue is bounded: when full, the line is dropped and
 * counted — better to lose a debug line than to hold the game thread waiting on disk. When off,
 * each call is a volatile field read.
 */
public final class SteinLog {

    /** Lines waiting for disk. Enough for a dump spike; when full, it drops. */
    private static final int QUEUE_CAPACITY = 8192;

    private static final ArrayBlockingQueue<String> LINES =
            new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private static final String STOP = new String("stop"); // sentinel, by identity
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    /**
     * What gets logged, each one switched on by itself: the volumes are very different and whoever
     * is chasing a network problem does not want the rest in the way.
     */
    public enum Topic {
        /** Packets arriving and leaving, by type, summarised every second. */
        NET("rede"),
        /** Frames, ticks, memory and garbage collection, every second. */
        PERF("desempenho");

        /** The name under which the choice is stored in config/steinloader.json. */
        public final String key;

        Topic(String key) {
            this.key = key;
        }
    }

    /** One bit per subject that is on; zero closes the file. */
    private static volatile int topics;

    private static volatile boolean on;
    private static volatile File file;
    private static volatile long written;
    private static final AtomicLong dropped = new AtomicLong();
    private static Thread writer;
    private static boolean hooked;

    private SteinLog() {}

    /** Is this subject on? Whoever is about to build an expensive line asks first. */
    public static boolean on(Topic topic) {
        return (topics & (1 << topic.ordinal())) != 0;
    }

    /** This session's file, or null with the log off. */
    public static File file() {
        return file;
    }

    /** The log folder, whether or not it exists. */
    public static File dir() {
        return new File(gameDir(), "logs/stein");
    }

    public static long written() {
        return written;
    }

    /** Lines lost to a full queue: if this shows up, too much is being logged. */
    public static long dropped() {
        return dropped.get();
    }

    /**
     * Turns a subject on or off. The file opens on the first subject turned on and closes when the
     * last one is turned off — turning a subject on in the middle of a match starts a new file, and
     * that is intended: each file is a log session.
     */
    public static synchronized void enable(Topic topic, boolean enable) {
        int bit = 1 << topic.ordinal();
        int now = enable ? topics | bit : topics & ~bit;
        if (now == topics) {
            return;
        }
        boolean was = on;
        topics = now;
        if (now != 0 && !was) {
            open();
        } else if (now == 0 && was) {
            close();
        } else {
            note("log", topic.key + (enable ? " on" : " off"));
        }
    }

    /** Turns everything off (the end of the session). */
    private static synchronized void disableAll() {
        for (Topic t : Topic.values()) {
            enable(t, false);
        }
    }

    /**
     * A log-only line, for the given subject: when off, it costs nothing and goes nowhere. This is
     * where the volume enters — packets, frames.
     */
    public static void debug(Topic topic, String tag, String msg) {
        if (on(topic)) {
            put(tag, msg);
        }
    }

    /**
     * A line that matters even without the log on: it goes to the game log and, with the log on, to
     * this one too. For the little the player needs to send along with a report — a freeze, an
     * error.
     */
    public static void note(String tag, String msg) {
        Log.info(tag, msg);
        if (on) {
            put(tag, msg);
        }
    }

    private static void put(String tag, String msg) {
        String line =
                "["
                        + TIME.format(LocalTime.now())
                        + "] ["
                        + Thread.currentThread().getName()
                        + "/"
                        + tag
                        + "] "
                        + msg;
        if (!LINES.offer(line)) {
            dropped.incrementAndGet();
        }
    }

    private static void open() {
        if (!hooked) {
            hooked = true;
            // The writer is a daemon: without this, what was in the buffer when the game closes
            // would be lost — and the end of the session is exactly what matters for someone
            // reporting a freeze.
            Runtime.getRuntime()
                    .addShutdownHook(new Thread(SteinLog::disableAll, "stein-log-shutdown"));
        }
        File dir = dir();
        dir.mkdirs();
        File latest = new File(dir, "latest.log");
        if (latest.isFile()) {
            archive(dir, latest);
        }
        file = latest;
        LINES.clear();
        written = 0;
        dropped.set(0);
        on = true;
        writer = new Thread(SteinLog::run, "stein-log");
        writer.setDaemon(true);
        writer.setPriority(Thread.NORM_PRIORITY - 1);
        writer.start();
        note("log", "on at " + latest.getAbsolutePath());
    }

    private static void close() {
        note(
                "log",
                "off: "
                        + written
                        + " line(s)"
                        + (dropped.get() > 0 ? ", " + dropped.get() + " dropped" : ""));
        on = false;
        LINES.offer(STOP);
        Thread t = writer;
        writer = null;
        file = null;
        if (t != null) {
            try {
                t.join(2000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** The previous session becomes {@code <date>-<n>.log.gz}, as the game archives its own. */
    private static void archive(File dir, File latest) {
        String day = DAY.format(Instant.ofEpochMilli(latest.lastModified()));
        for (int n = 1; n < 1000; n++) {
            File gz = new File(dir, day + "-" + n + ".log.gz");
            if (gz.exists()) {
                continue;
            }
            try (InputStream in = Files.newInputStream(latest.toPath());
                    OutputStream out = new GZIPOutputStream(new FileOutputStream(gz))) {
                in.transferTo(out);
            } catch (IOException e) {
                Log.warn("log", "could not archive the previous log: " + e);
                return;
            }
            if (!latest.delete()) {
                // It happens with the file still open by another game in the same folder; the copy
                // is already in the .gz and the new file starts from scratch (the writer opens
                // without append), so two sessions do not end up together.
                Log.warn(
                        "log",
                        "the previous log is already in "
                                + gz.getName()
                                + ", but it could not be deleted");
            }
            return;
        }
    }

    private static void run() {
        try (BufferedWriter out =
                new BufferedWriter(
                        new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8),
                        1 << 16)) {
            head(out);
            while (true) {
                String line = LINES.take();
                if (line == STOP) {
                    tail(out);
                    return;
                }
                out.write(line);
                out.write(System.lineSeparator());
                written++;
                if (LINES.isEmpty()) {
                    out.flush(); // with nothing waiting, the disk stays up to date
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            on = false;
            Log.warn("log", "stopped writing: " + e);
        }
    }

    private static void head(BufferedWriter out) throws IOException {
        Runtime rt = Runtime.getRuntime();
        out.write("--- Stein log, session of " + DATE_TIME.format(Instant.now()) + " ---");
        out.write(System.lineSeparator());
        // The architecture comes from experience: in a Mac report of a freeze, the header said
        // system and cores but not the arch, and there was no way to tell from the log whether the
        // game ran natively or translated by Rosetta.
        out.write(
                "--- Java "
                        + System.getProperty("java.version")
                        + " "
                        + System.getProperty("os.arch")
                        + ", "
                        + System.getProperty("os.name")
                        + " "
                        + System.getProperty("os.version")
                        + ", "
                        + rt.availableProcessors()
                        + " cores, max memory "
                        + (rt.maxMemory() >> 20)
                        + " MB ---");
        out.write(System.lineSeparator());
        out.flush();
    }

    private static void tail(BufferedWriter out) throws IOException {
        if (dropped.get() > 0) {
            out.write("--- " + dropped.get() + " line(s) lost to a full queue ---");
            out.write(System.lineSeparator());
        }
        out.write("--- end of session ---");
        out.write(System.lineSeparator());
        out.flush();
    }

    /** The game folder (the same as latest.log); without the game loaded, the working folder. */
    private static File gameDir() {
        try {
            Minecraft game = Minecraft.getMinecraft();
            File dir = game == null ? null : game.mcDataDir;
            if (dir != null) {
                return dir;
            }
        } catch (RuntimeException | LinkageError e) {
            // game not loaded yet
        }
        return new File(".");
    }
}
