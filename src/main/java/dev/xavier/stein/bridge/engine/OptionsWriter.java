/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import dev.xavier.stein.bridge.core.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Fix for options.txt lost on a crash or a power cut: GameSettings.saveOptions writes a temporary
 * file next to it and moves it over options.txt only once it is complete and on disk.
 *
 * <p>saveOptions opens options.txt with a FileWriter, which empties the file first, and then writes
 * about a hundred lines. The game saves the options often (every change on an options screen, the
 * volume sliders while dragged, each F-key toggle), so a crash, a killed process or a power cut in
 * the middle left an empty or half-written options.txt, and on the next start every setting went
 * back to its default (keys, video settings, the server resource pack choice...). Here the patcher
 * hands saveOptions this writer in place of {@code new FileWriter(optionsFile)}: the lines go to
 * {@code options.txt.tmp}, and closing the writer (the game's PrintWriter.close at the end of
 * saveOptions) flushes it, forces it to disk and moves it over options.txt in one atomic step, so
 * the file is always the old complete one or the new complete one. If a write failed, the temporary
 * file is dropped and options.txt is left as it was. The text written is the game's, byte for byte
 * (same lines, same default charset as FileWriter).
 */
public final class OptionsWriter extends FileWriter {

    private final FileOutputStream out;
    private final File target;
    private final File temp;
    private boolean failed;
    private boolean closed;

    private OptionsWriter(FileOutputStream out, File target, File temp) throws IOException {
        super(out.getFD());
        this.out = out;
        this.target = target;
        this.temp = temp;
    }

    /** new FileWriter(optionsFile) in GameSettings.saveOptions. */
    public static Object open(Object file) throws IOException {
        File target = (File) file;
        File temp = new File(target.getPath() + ".tmp");
        return new OptionsWriter(new FileOutputStream(temp), target, temp);
    }

    @Override
    public void write(int c) throws IOException {
        try {
            super.write(c);
        } catch (IOException e) {
            failed = true;
            throw e;
        }
    }

    @Override
    public void write(char[] buf, int off, int len) throws IOException {
        try {
            super.write(buf, off, len);
        } catch (IOException e) {
            failed = true;
            throw e;
        }
    }

    @Override
    public void write(String str, int off, int len) throws IOException {
        try {
            super.write(str, off, len);
        } catch (IOException e) {
            failed = true;
            throw e;
        }
    }

    @Override
    public void flush() throws IOException {
        try {
            super.flush();
        } catch (IOException e) {
            failed = true;
            throw e;
        }
    }

    /** Flushes, forces to disk and moves the file over options.txt (or drops it on a failure). */
    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            if (!failed) {
                super.flush();
                out.getFD().sync();
            }
        } catch (IOException e) {
            failed = true;
        } finally {
            super.close();
            out.close();
        }
        if (failed) {
            Files.deleteIfExists(temp.toPath());
            Log.info("options", "options.txt not saved (a write failed); the old file is kept");
            return;
        }
        try {
            try {
                Files.move(
                        temp.toPath(),
                        target.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            // PrintWriter.close swallows this: say it here, or the options would be lost silently
            Log.info("options", "options.txt not saved (" + e + "); the old file is kept");
            Files.deleteIfExists(temp.toPath());
            throw e;
        }
    }
}
