/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.net;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * Where the native QUIC library ({@link SteinQuic}) comes from: the Bridge's own jar, which carries
 * it for the system it was built for, next to its SHA-256.
 *
 * <p>A shared library cannot be loaded from inside a jar, so it is copied out once into a folder
 * named after its hash ({@code <game dir>/steinbridge/natives/stein-quic-<hash>/}): a new Bridge
 * with another build gets a new folder, two games running at once share the same file, and a file
 * already there is used only if its hash still matches (a half-written or altered copy is replaced,
 * never loaded). The copy is written beside its final name and moved into place, so another game
 * starting at the same moment never sees half a file.
 *
 * <p>{@code -Dsteinbridge.net.quicLib=<file>} overrides all of this (development, the bench); a
 * library in {@code java.library.path} is the last resort (a development run from the build).
 */
final class SteinQuicLibrary {

    /** Inside the jar: {@code steinbridge/natives/stein-quic/<platform>/<file>} (+ ".sha256"). */
    static final String RESOURCE_ROOT = "steinbridge/natives/stein-quic/";

    /** The cache folder's parent when the game's folder is not known. */
    private static volatile Path cacheRoot;

    /** What happened while looking, for the one log line when nothing loads. */
    static final class Found {
        final Path file;
        final String why;

        Found(Path file, String why) {
            this.file = file;
            this.why = why;
        }
    }

    private SteinQuicLibrary() {}

    /**
     * Sets the folder the library is copied under (the game's folder); called before the first QUIC
     * join. Without it the system's temporary folder is used.
     */
    static void cacheRoot(Path dir) {
        cacheRoot = dir;
    }

    /**
     * The platform name of this system as the build names its library folders ({@code windows-x64},
     * {@code linux-x64}, {@code linux-arm64}, {@code macos-x64}, {@code macos-arm64}), or null for
     * one the build has no library for.
     */
    static String platform(String osName, String osArch) {
        String os = osName.toLowerCase(Locale.ROOT);
        String arch = osArch.toLowerCase(Locale.ROOT);
        String cpu =
                arch.equals("amd64") || arch.equals("x86_64")
                        ? "x64"
                        : arch.equals("aarch64") || arch.equals("arm64") ? "arm64" : null;
        if (cpu == null) {
            return null;
        }
        if (os.contains("win")) {
            return cpu.equals("x64") ? "windows-x64" : null;
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return "macos-" + cpu;
        }
        return os.contains("linux") ? "linux-" + cpu : null;
    }

    /** The library's file name on this system. */
    static String fileName() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return "stein_quic.dll";
        }
        return os.contains("mac") ? "libstein_quic.dylib" : "libstein_quic.so";
    }

    /** Finds the library to load; {@code file} is null when there is none (with the reason). */
    static Found locate() {
        String explicit = System.getProperty("steinbridge.net.quicLib");
        if (explicit != null && !explicit.isEmpty()) {
            Path p = Paths.get(explicit);
            return Files.isRegularFile(p)
                    ? new Found(p, "explicit")
                    : new Found(null, "-Dsteinbridge.net.quicLib names no file: " + explicit);
        }
        String platform =
                platform(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
        String bundledWhy;
        if (platform == null) {
            bundledWhy = "no build for this system";
        } else {
            try {
                Path bundled = extract(platform, fileName());
                if (bundled != null) {
                    return new Found(bundled, "bundled");
                }
                bundledWhy = "the Bridge jar has no library for " + platform;
            } catch (IOException | RuntimeException e) {
                bundledWhy = "cannot copy the bundled library out: " + e;
            }
        }
        for (String dir : System.getProperty("java.library.path", "").split(File.pathSeparator)) {
            if (!dir.isEmpty()) {
                Path p = Paths.get(dir, fileName());
                if (Files.isRegularFile(p)) {
                    return new Found(p, "library path");
                }
            }
        }
        return new Found(null, bundledWhy);
    }

    /**
     * Copies the jar's library for {@code platform} into its hash-named cache folder and returns
     * it, or null when the jar has none.
     *
     * @throws IOException when it cannot be read, written, or its hash does not match
     */
    static Path extract(String platform, String file) throws IOException {
        ClassLoader loader = SteinQuicLibrary.class.getClassLoader();
        String resource = RESOURCE_ROOT + platform + "/" + file;
        String expected;
        try (InputStream in = loader.getResourceAsStream(resource + ".sha256")) {
            if (in == null) {
                return null;
            }
            expected = new String(in.readAllBytes(), StandardCharsets.US_ASCII).trim();
        }
        if (!expected.matches("[0-9a-f]{64}")) {
            throw new IOException("bad hash file for " + resource);
        }
        Path dir = root().resolve("stein-quic-" + expected.substring(0, 16));
        Path target = dir.resolve(file);
        if (Files.isRegularFile(target) && expected.equals(sha256(target))) {
            return target;
        }
        Files.createDirectories(dir);
        Path part = Files.createTempFile(dir, file, ".part");
        try {
            try (InputStream in = loader.getResourceAsStream(resource)) {
                if (in == null) {
                    throw new IOException("the jar lists " + resource + " but has no file");
                }
                Files.copy(in, part, StandardCopyOption.REPLACE_EXISTING);
            }
            String got = sha256(part);
            if (!expected.equals(got)) {
                throw new IOException(resource + " has hash " + got + ", not " + expected);
            }
            try {
                Files.move(
                        part,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                // Windows will not replace a library another running game has loaded; that copy
                // is the right one if its hash matches.
                if (!(Files.isRegularFile(target) && expected.equals(sha256(target)))) {
                    throw e;
                }
            }
            return target;
        } finally {
            Files.deleteIfExists(part);
        }
    }

    private static Path root() {
        Path base = cacheRoot;
        if (base == null) {
            base = Paths.get(System.getProperty("java.io.tmpdir"), "steinbridge");
            return base.resolve("natives");
        }
        return base.resolve("steinbridge").resolve("natives");
    }

    /** The SHA-256 of {@code file}, lowercase hex. */
    static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        byte[] buf = new byte[64 * 1024];
        try (InputStream in = Files.newInputStream(file)) {
            for (int n; (n = in.read(buf)) > 0; ) {
                digest.update(buf, 0, n);
            }
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte b : digest.digest()) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                    .append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }
}
