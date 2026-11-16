/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.core;

import dev.xavier.stein.bridge.SteinBridge;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The session's log, always on: {@code logs/stein/latest.log} gets everything the game process
 * prints — the game's own log lines and chat, the Bridge, the Loader and the mods, warnings and
 * stack traces — whatever launcher started the game. On every start, the previous session's files
 * in {@code logs/stein/} (this log and the optional subjects of {@link SteinLog}) go together into
 * {@code logs/stein/<date>-<n>.zip}, as the game archives its own latest.log.
 *
 * <p>Before, the only full copy was the Stein Client's {@code game.log}, rewritten on every start:
 * a player who froze, closed the game and opened it again had already lost the session that
 * mattered (15/11/2026).
 */
public final class SessionLog {

    /** The newest archives kept; older ones are deleted on start. */
    private static final int KEEP = 200;

    /** Past this, the session's file stops growing (a runaway error would fill the disk). */
    private static final long MAX_BYTES = 256L << 20;

    /** A line longer than this goes out in pieces (a stack trace has no long lines). */
    private static final int MAX_LINE = 16 * 1024;

    static final String LATEST = "latest.log";

    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private static File dir;
    private static File file;
    private static OutputStream out;
    private static long written;
    private static boolean capped;

    private SessionLog() {}

    /**
     * From the launch, before the game and its log system: archives the previous session, opens
     * this one's file and copies the process's output into it.
     */
    public static void start(String[] args) {
        try {
            dir = new File(gameDir(args), "logs/stein");
            if (!dir.isDirectory() && !dir.mkdirs()) {
                return;
            }
            archivePrevious();
            prune();
            file = new File(dir, LATEST);
            if (file.exists() && !file.delete()) {
                // Another game is running from the same folder and holds its latest.log: this
                // session gets a file of its own instead of writing over that one.
                file = new File(dir, "latest-" + ProcessHandle.current().pid() + ".log");
            }
            out = new FileOutputStream(file);
            line(header());
            System.setOut(tee(System.out));
            System.setErr(tee(System.err));
        } catch (IOException | RuntimeException e) {
            System.err.println("[log] the session log is off: " + e);
            out = null;
        }
    }

    /** This session's file; null if it could not be opened. */
    public static File file() {
        return out == null ? null : file;
    }

    /** {@code logs/stein/} under the game folder; null before {@link #start}. */
    static File dir() {
        return dir;
    }

    private static String header() {
        Runtime rt = Runtime.getRuntime();
        return "--- Stein Bridge "
                + SteinBridge.VERSION
                + ", session of "
                + DATE_TIME.format(Instant.now())
                + " | Java "
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
                + " MB ---";
    }

    /** {@code --gameDir} from the game's arguments; the working folder without it. */
    private static File gameDir(String[] args) {
        for (int i = 0; i + 1 < args.length; i++) {
            if ("--gameDir".equals(args[i])) {
                return new File(args[i + 1]);
            }
        }
        return new File(".");
    }

    // ---- archives ----

    /** The previous session's {@code .log} files, into one zip named after the newest of them. */
    private static void archivePrevious() throws IOException {
        File[] logs = dir.listFiles(f -> f.isFile() && f.getName().endsWith(".log"));
        if (logs == null || logs.length == 0) {
            return;
        }
        long newest = 0;
        for (File f : logs) {
            newest = Math.max(newest, f.lastModified());
        }
        String day = DAY.format(Instant.ofEpochMilli(newest));
        File zip = null;
        for (int n = 1; n < 10_000; n++) {
            File z = new File(dir, day + "-" + n + ".zip");
            if (!z.exists()) {
                zip = z;
                break;
            }
        }
        if (zip == null) {
            return;
        }
        Arrays.sort(logs, Comparator.comparing(File::getName));
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(zip))) {
            for (File f : logs) {
                ZipEntry e = new ZipEntry(f.getName());
                e.setTime(f.lastModified());
                z.putNextEntry(e);
                Files.copy(f.toPath(), z);
                z.closeEntry();
            }
        }
        zip.setLastModified(newest);
        for (File f : logs) {
            // a file held by another game in the same folder stays (it is that game's session);
            // its copy up to now is in the zip
            f.delete();
        }
    }

    /** Deletes the oldest archives beyond {@link #KEEP}. */
    private static void prune() {
        File[] zips =
                dir.listFiles(
                        f -> f.isFile() && f.getName().matches("\\d{4}-\\d\\d-\\d\\d-\\d+\\.zip"));
        if (zips == null || zips.length <= KEEP) {
            return;
        }
        List<File> sorted = new ArrayList<>(Arrays.asList(zips));
        sorted.sort(Comparator.comparingLong(File::lastModified));
        for (int i = 0; i < sorted.size() - KEEP; i++) {
            sorted.get(i).delete();
        }
    }

    // ---- the copy of the output ----

    /**
     * {@code console} keeps getting the bytes as before (the launcher's window, the Stein Client's
     * game.log); the file gets each complete line again in UTF-8, whatever the console's encoding.
     */
    private static PrintStream tee(PrintStream console) {
        Charset cs = console.charset();
        return new PrintStream(new Tee(console, cs), true, cs);
    }

    private static synchronized void line(String text) {
        write(text.getBytes(StandardCharsets.UTF_8));
    }

    private static synchronized void write(byte[] utf8) {
        OutputStream o = out;
        if (o == null || capped) {
            return;
        }
        try {
            if (written + utf8.length + 1 > MAX_BYTES) {
                capped = true;
                o.write(
                        "--- the session log reached its limit; the rest is not written ---\n"
                                .getBytes(StandardCharsets.UTF_8));
                o.flush();
                return;
            }
            o.write(utf8);
            o.write('\n');
            o.flush();
            written += utf8.length + 1;
        } catch (IOException e) {
            out = null; // a disk error: the game goes on, only without the file
        }
    }

    /** The console's bytes, plus each line decoded and written to the session file. */
    private static final class Tee extends OutputStream {
        private final PrintStream console;
        private final Charset cs;
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream(256);

        Tee(PrintStream console, Charset cs) {
            this.console = console;
            this.cs = cs;
        }

        @Override
        public void write(int b) {
            console.write(b);
            synchronized (this) {
                take(b);
            }
        }

        @Override
        public void write(byte[] b, int off, int len) {
            console.write(b, off, len);
            synchronized (this) {
                for (int i = off; i < off + len; i++) {
                    take(b[i]);
                }
            }
        }

        private void take(int b) {
            if (b == '\n') {
                emit();
            } else if (b != '\r') {
                pending.write(b);
                if (pending.size() >= MAX_LINE) {
                    emit();
                }
            }
        }

        /**
         * The line as UTF-8 when it is valid UTF-8, else in the console's encoding: the game's log
         * system writes its own UTF-8 bytes to the stream (the chat, with accents), while
         * System.out's prints come in the console's encoding (Windows' code page when the output
         * goes to a file). Decoding everything one way garbled the other.
         */
        private void emit() {
            byte[] bytes = pending.toByteArray();
            pending.reset();
            String text;
            try {
                text =
                        StandardCharsets.UTF_8
                                .newDecoder()
                                .onMalformedInput(CodingErrorAction.REPORT)
                                .onUnmappableCharacter(CodingErrorAction.REPORT)
                                .decode(ByteBuffer.wrap(bytes))
                                .toString();
            } catch (CharacterCodingException e) {
                text = new String(bytes, cs);
            }
            line(text);
        }

        @Override
        public void flush() {
            console.flush();
        }
    }
}
