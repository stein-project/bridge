/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.bench;

import jdk.jfr.Configuration;
import jdk.jfr.Recording;

import java.io.File;
import java.io.IOException;
import java.text.ParseException;

/**
 * The run's flight recording: Java's own profiler with its "profile" settings (method samples,
 * allocation samples, collections, compilations, locks, file and socket I/O, threads, and Stein's
 * frame events), from the start of the run to its end, put in the report as {@code recording.jfr}.
 * Only touched when the running Java has the {@code jdk.jfr} module ({@link Bench} checks): a class
 * that names the recorder fails to load without it.
 */
final class BenchJfr {

    private BenchJfr() {}

    private static Recording recording;

    static void start() {
        try {
            recording = new Recording(Configuration.getConfiguration("profile"));
            recording.setName("Stein benchmark");
            recording.setToDisk(true);
            recording.setMaxSize(1L << 30);
            recording.start();
            Bench.event("flight recording started (profile)");
        } catch (IOException | ParseException | RuntimeException e) {
            recording = null;
            Bench.event("flight recording not started: " + e);
        }
    }

    /** Stops the recording and writes it to {@code to}; false when there was none. */
    static boolean stop(File to) {
        Recording r = recording;
        recording = null;
        if (r == null) {
            return false;
        }
        try {
            r.stop();
            r.dump(to.toPath());
            return true;
        } catch (IOException | RuntimeException e) {
            Bench.event("flight recording not written: " + e);
            return false;
        } finally {
            r.close();
        }
    }
}
