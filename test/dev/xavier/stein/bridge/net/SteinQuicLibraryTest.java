/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.net;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * The native QUIC library's packaging, against the Bridge jar on the test classpath: the platform
 * names match the build's folders, the jar's library is copied out under its hash, a damaged copy
 * is replaced instead of loaded, and the copy loads with the ABI this Bridge expects. Run by
 * SteinSyncClientTest (bridge.sync), which counts the failures.
 */
public final class SteinQuicLibraryTest {

    private static int failures;

    private SteinQuicLibraryTest() {}

    /** Runs the checks; returns how many failed. */
    public static int run() throws IOException {
        failures = 0;
        platforms();
        String platform =
                SteinQuicLibrary.platform(
                        System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
        String file = SteinQuicLibrary.fileName();
        if (platform == null
                || SteinQuicLibraryTest.class
                                .getClassLoader()
                                .getResource(SteinQuicLibrary.RESOURCE_ROOT + platform + "/" + file)
                        == null) {
            // A build without cargo (or -Pstein.noNative) packs no library: the client then uses
            // Kwik, and there is nothing to copy out here.
            System.out.println("SteinQuicLibraryTest: no bundled library for " + platform);
            return failures;
        }
        Path root = Files.createTempDirectory("stein-quic-test");
        try {
            SteinQuicLibrary.cacheRoot(root);
            Path first = SteinQuicLibrary.extract(platform, file);
            check(
                    "the library is copied out",
                    "true",
                    "" + (first != null && Files.isRegularFile(first)));
            String hash = SteinQuicLibrary.sha256(first);
            check(
                    "into a folder named after its hash",
                    "stein-quic-" + hash.substring(0, 16),
                    first.getParent().getFileName().toString());
            check(
                    "the copy has the jar's hash",
                    hash,
                    SteinQuicLibrary.sha256(SteinQuicLibrary.extract(platform, file)));
            Files.write(first, new byte[] {1, 2, 3});
            Path again = SteinQuicLibrary.extract(platform, file);
            check("a damaged copy is replaced", hash, SteinQuicLibrary.sha256(again));
            // The real load, through the same path the game takes.
            check(
                    "the bundled library loads with this Bridge's ABI ("
                            + SteinQuic.unavailableReason()
                            + ")",
                    "true",
                    "" + SteinQuic.available());
        } finally {
            SteinQuicLibrary.cacheRoot(null);
            deleteQuietly(root);
        }
        return failures;
    }

    private static void platforms() {
        check("windows x64", "windows-x64", SteinQuicLibrary.platform("Windows 11", "amd64"));
        check(
                "windows arm64 has no build",
                "null",
                "" + SteinQuicLibrary.platform("Windows 11", "aarch64"));
        check("linux x64", "linux-x64", SteinQuicLibrary.platform("Linux", "amd64"));
        check("linux arm64", "linux-arm64", SteinQuicLibrary.platform("Linux", "aarch64"));
        check("macos x64", "macos-x64", SteinQuicLibrary.platform("Mac OS X", "x86_64"));
        check("macos arm64", "macos-arm64", SteinQuicLibrary.platform("Mac OS X", "aarch64"));
        check("32-bit x86 has no build", "null", "" + SteinQuicLibrary.platform("Linux", "x86"));
    }

    private static void deleteQuietly(Path root) {
        // The loaded library stays locked on Windows until the JVM exits: what cannot be deleted
        // stays in the temporary folder.
        try (Stream<Path> files = Files.walk(root)) {
            files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException e) {
            // best effort
        }
    }

    private static void check(String name, String expected, String got) {
        if (!expected.equals(got)) {
            failures++;
            System.out.println("FAILED " + name + ": expected " + expected + ", got " + got);
        }
    }
}
