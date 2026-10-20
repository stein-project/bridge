/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
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
 * Stein's log: logs of our own, in {@code logs/stein/}, separate from the game's latest.log.
 *
 * <p>They are for finding problems the game log does not show — network, packets, frames, memory,
 * how items are drawn. They are <b>off</b> by default; the player turns each subject on in the
 * Stein Loader menu (Log tab) or in the Stein Client's developer options, and only then is its file
 * opened.
 *
 * <p>One file per subject, so whoever is chasing one problem does not read through the others:
 * {@code network.log}, {@code performance.log}, {@code render.log}, plus {@code stein.log} with the
 * general notes ({@link #note}) of the session. One session per file, as the game does: a subject's
 * previous file goes to {@code archive/<year-month-day>-<n>-<subject>.log.gz} when the subject is
 * turned on again.
 *
 * <p>None of this may weigh on the game. Callers only enqueue the finished line; a background
 * thread writes and flushes the buffers. The queue is bounded: when full, the line is dropped and
 * counted — better to lose a debug line than to hold the game thread waiting on disk. When off,
 * each call is a volatile field read.
 */
public final class SteinLog {

    /** Lines waiting for disk. Enough for a dump spike; when full, it drops. */
    private static final int QUEUE_CAPACITY = 8192;

    /** The general file's channel; each subject's channel is its ordinal plus one. */
    private static final int GENERAL = 0;

    /** Name of the general file (without the extension). */
    private static final String GENERAL_NAME = "stein";

    /** The first character of a queued line is its channel ({@link #channelChar}). */
    private static final char CHANNEL_BASE = '0';

    /** A queued line starting with this, after the channel, closes that channel's file. */
    private static final char CLOSE = '\u0001';

    private static final ArrayBlockingQueue<String> LINES =
            new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private static final String STOP = new String("stop"); // sentinel, by identity
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    /**
     * What gets logged, each one switched on by itself and written to its own file: the volumes are
     * very different and whoever is chasing a network problem does not want the rest in the way.
     */
    public enum Topic {
        /** Packets arriving and leaving, by type, summarised every second. */
        NET("rede", "network"),
        /** Frames, ticks, memory and garbage collection, every second. */
        PERF("desempenho", "performance"),
        /**
         * How items are drawn: the environment once, then each item model the first time it shows
         * up (where it came from, its faces and edges, the vertices that reach the draw).
         */
        RENDER("render", "render");

        /** The name under which the choice is stored in config/steinloader.json. */
        public final String key;

        /** The file name in logs/stein/, without the extension. */
        public final String fileName;

        Topic(String key, String fileName) {
            this.key = key;
            this.fileName = fileName;
        }
    }

    /** One bit per subject that is on; zero closes every file. */
    private static volatile int topics;

    private static volatile boolean on;
    private static volatile long written;
    private static final AtomicLong dropped = new AtomicLong();
    private static Thread writer;
    private static boolean hooked;

    private SteinLog() {}

    /** Is this subject on? Whoever is about to build an expensive line asks first. */
    public static boolean on(Topic topic) {
        return (topics & (1 << topic.ordinal())) != 0;
    }

    /** This session's general file, or null with the log off. */
    public static File file() {
        return on ? new File(dir(), GENERAL_NAME + ".log") : null;
    }

    /** The subject's file in this session, or null with the subject off. */
    public static File file(Topic topic) {
        return on(topic) ? new File(dir(), topic.fileName + ".log") : null;
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
     * Turns a subject on or off. The writer starts on the first subject turned on and stops when
     * the last one is turned off; each subject's file opens with its first line and closes when the
     * subject is turned off — turning a subject on in the middle of a match starts a new file for
     * it, and that is intended: each file is a log session.
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
        }
        if (on) {
            note("log", topic.key + (enable ? " on" : " off"));
            int channel = topic.ordinal() + 1;
            if (enable) {
                enqueue(channel, "--- " + topic.key + " on ---");
            } else {
                enqueue(channel, String.valueOf(CLOSE));
            }
        }
        if (now == 0 && was) {
            close();
        }
    }

    /** Turns everything off (the end of the session). */
    private static synchronized void disableAll() {
        for (Topic t : Topic.values()) {
            enable(t, false);
        }
    }

    /**
     * A log-only line, for the given subject and in its file: when off, it costs nothing and goes
     * nowhere. This is where the volume enters — packets, frames.
     */
    public static void debug(Topic topic, String tag, String msg) {
        if (on(topic)) {
            put(topic.ordinal() + 1, tag, msg);
        }
    }

    /**
     * A line that matters even without the log on: it goes to the game log and, with the log on, to
     * the general file too. For the little the player needs to send along with a report — a freeze,
     * an error.
     */
    public static void note(String tag, String msg) {
        Log.info(tag, msg);
        if (on) {
            put(GENERAL, tag, msg);
        }
    }

    private static void put(int channel, String tag, String msg) {
        enqueue(
                channel,
                "["
                        + TIME.format(LocalTime.now())
                        + "] ["
                        + Thread.currentThread().getName()
                        + "/"
                        + tag
                        + "] "
                        + msg);
    }

    private static void enqueue(int channel, String line) {
        if (!LINES.offer(channelChar(channel) + line)) {
            dropped.incrementAndGet();
        }
    }

    private static char channelChar(int channel) {
        return (char) (CHANNEL_BASE + channel);
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
        // Before the files were split, everything went to a single latest.log: it is archived like
        // any previous session.
        File old = new File(dir, "latest.log");
        if (old.isFile()) {
            archive(dir, old, "all");
        }
        LINES.clear();
        written = 0;
        dropped.set(0);
        on = true;
        writer = new Thread(SteinLog::run, "stein-log");
        writer.setDaemon(true);
        writer.setPriority(Thread.NORM_PRIORITY - 1);
        writer.start();
        note("log", "on at " + dir.getAbsolutePath());
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
        if (t != null) {
            try {
                t.join(2000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * A subject's previous session becomes {@code archive/<date>-<n>-<name>.log.gz}, as the game
     * archives its own.
     */
    private static void archive(File dir, File latest, String name) {
        File archive = new File(dir, "archive");
        archive.mkdirs();
        String day = DAY.format(Instant.ofEpochMilli(latest.lastModified()));
        for (int n = 1; n < 1000; n++) {
            File gz = new File(archive, day + "-" + n + "-" + name + ".log.gz");
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

    /** The writer thread: routes each line to its channel's file, opened on its first line. */
    private static void run() {
        int channels = Topic.values().length + 1;
        BufferedWriter[] out = new BufferedWriter[channels];
        try {
            while (true) {
                String line = LINES.take();
                if (line == STOP) {
                    for (BufferedWriter w : out) {
                        if (w != null) {
                            tail(w);
                            w.close();
                        }
                    }
                    return;
                }
                int channel = line.charAt(0) - CHANNEL_BASE;
                if (channel < 0 || channel >= channels) {
                    continue;
                }
                if (line.length() == 2 && line.charAt(1) == CLOSE) {
                    if (out[channel] != null) {
                        tail(out[channel]);
                        out[channel].close();
                        out[channel] = null;
                    }
                    continue;
                }
                if (out[channel] == null) {
                    out[channel] = openChannel(channel);
                }
                out[channel].write(line, 1, line.length() - 1);
                out[channel].write(System.lineSeparator());
                written++;
                if (LINES.isEmpty()) {
                    // with nothing waiting, the disk stays up to date
                    for (BufferedWriter w : out) {
                        if (w != null) {
                            w.flush();
                        }
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            on = false;
            Log.warn("log", "stopped writing: " + e);
        }
    }

    /** Opens a channel's file for a new session, archiving its previous one. */
    private static BufferedWriter openChannel(int channel) throws IOException {
        String name = channel == GENERAL ? GENERAL_NAME : Topic.values()[channel - 1].fileName;
        File dir = dir();
        dir.mkdirs();
        File f = new File(dir, name + ".log");
        if (f.isFile()) {
            archive(dir, f, name);
        }
        BufferedWriter w =
                new BufferedWriter(
                        new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8),
                        1 << 16);
        head(w, name);
        return w;
    }

    private static void head(BufferedWriter out, String name) throws IOException {
        Runtime rt = Runtime.getRuntime();
        out.write(
                "--- Stein log ("
                        + name
                        + "), session of "
                        + DATE_TIME.format(Instant.now())
                        + " ---");
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
