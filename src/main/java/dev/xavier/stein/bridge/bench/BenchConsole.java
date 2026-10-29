/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.bench;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * Everything the game prints during a benchmark run — the Bridge's log, the mods', the GPU pass
 * reports, every stack trace — kept for the report's {@code console.log}, while it still goes where
 * it went before. Each line is also classified: exceptions and warnings are counted per part of the
 * route, so the report can say which part broke something. Installed as early as the options are
 * read, and again if the game swaps the standard streams later. Up to 64 MB is kept.
 */
public final class BenchConsole {

    private BenchConsole() {}

    private static final int MAX_BYTES = 64 << 20;

    private static final ByteArrayOutputStream KEPT = new ByteArrayOutputStream(1 << 20);
    private static PrintStream out;
    private static PrintStream err;
    private static long dropped;

    /** The part each line belongs to ({@link Bench}); -1 outside the measured route. */
    static volatile int segment = -1;

    private static final int MAX_SEGMENTS = 256;
    static final int[] EXCEPTIONS = new int[MAX_SEGMENTS + 1];
    static final int[] WARNINGS = new int[MAX_SEGMENTS + 1];
    static final String[] FIRST_EXCEPTION = new String[MAX_SEGMENTS + 1];

    /** Puts the copy in front of System.out and System.err (again, when someone replaced it). */
    public static synchronized void install() {
        if (System.out != out) {
            out = tee(System.out);
            System.setOut(out);
        }
        if (System.err != err) {
            err = tee(System.err);
            System.setErr(err);
        }
    }

    private static PrintStream tee(PrintStream to) {
        Charset cs = to.charset();
        return new PrintStream(new Copy(to, cs), true, cs);
    }

    /** The console so far. */
    static synchronized byte[] bytes() {
        return KEPT.toByteArray();
    }

    static synchronized long dropped() {
        return dropped;
    }

    private static synchronized void keep(byte[] b, int off, int len) {
        if (KEPT.size() + len > MAX_BYTES) {
            dropped += len;
            return;
        }
        KEPT.write(b, off, len);
    }

    private static void classify(String line) {
        int s = segment;
        int at = s < 0 || s >= MAX_SEGMENTS ? MAX_SEGMENTS : s;
        if (line.contains("Exception") || line.startsWith("Caused by") || line.contains("Error:")) {
            synchronized (BenchConsole.class) {
                if (EXCEPTIONS[at]++ == 0) {
                    FIRST_EXCEPTION[at] = line.length() > 300 ? line.substring(0, 300) : line;
                }
            }
        } else if (line.contains("warning:") || line.contains("/WARN]")) {
            WARNINGS[at]++;
        }
    }

    /** Exceptions and warnings outside the measured parts (index {@code MAX_SEGMENTS}). */
    static int outsideIndex() {
        return MAX_SEGMENTS;
    }

    /** The lines of one stream: forwarded, kept, and classified at each newline. */
    private static final class Copy extends OutputStream {
        private final PrintStream to;
        private final Charset cs;
        private final ByteArrayOutputStream line = new ByteArrayOutputStream(256);

        Copy(PrintStream to, Charset cs) {
            this.to = to;
            this.cs = cs;
        }

        @Override
        public void write(int b) {
            to.write(b);
            byte[] one = {(byte) b};
            keep(one, 0, 1);
            collect(one, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) {
            to.write(b, off, len);
            keep(b, off, len);
            collect(b, off, len);
        }

        private synchronized void collect(byte[] b, int off, int len) {
            for (int i = off; i < off + len; i++) {
                if (b[i] == '\n') {
                    classify(line.toString(cs));
                    line.reset();
                } else if (line.size() < 4096) {
                    line.write(b[i]);
                }
            }
        }

        @Override
        public void flush() throws IOException {
            to.flush();
        }
    }

    /** The part names with any exception or warning, for the report. */
    static List<String> lines(List<String> segmentNames) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i <= MAX_SEGMENTS; i++) {
            if (EXCEPTIONS[i] == 0 && WARNINGS[i] == 0) {
                continue;
            }
            String name =
                    i == MAX_SEGMENTS
                            ? "outside the parts"
                            : i < segmentNames.size() ? segmentNames.get(i) : "#" + i;
            out.add(
                    name
                            + ": "
                            + EXCEPTIONS[i]
                            + " exception line(s), "
                            + WARNINGS[i]
                            + " warning(s)"
                            + (FIRST_EXCEPTION[i] == null ? "" : "; first: " + FIRST_EXCEPTION[i]));
        }
        return out;
    }
}
