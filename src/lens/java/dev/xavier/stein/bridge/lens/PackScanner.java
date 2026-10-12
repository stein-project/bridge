/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.lens;

import dev.xavier.stein.bridge.core.Log;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.ResourcePackRepository;
import net.minecraft.util.ResourceLocation;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Walks the active resource packs (folders and zips) for the {@code .properties} rule files under
 * given roots, and hands each one to a {@link Sink}; shared by the connected-textures and
 * custom-item loaders, which read the same pack layout.
 */
final class PackScanner {

    static final String ASSETS = "assets/minecraft/";

    private PackScanner() {}

    /** Counters of one reload. */
    static final class LoadStats {
        int files;
        int ignored;
        int packs;

        /** Logs the totals (nothing when no rule file was found). */
        void report(String what) {
            if (files > 0) {
                Log.info(
                        "lens",
                        ""
                                + (files - ignored)
                                + " "
                                + what
                                + " in "
                                + packs
                                + " pack(s)"
                                + (ignored > 0 ? " (" + ignored + " ignored)" : ""));
            }
        }
    }

    /** Receives one rule file: its folder and name inside the pack, and its properties. */
    interface Sink {
        void add(String basePath, String name, Properties p, LoadStats stats);
    }

    /** Scans every active pack for the roots' rule files. */
    static void scanActivePacks(String[] roots, Sink sink, LoadStats stats) {
        ResourcePackRepository repo = Minecraft.func_71410_x().func_110438_M();
        if (repo != null) {
            File dir = repo.func_110612_e();
            for (ResourcePackRepository.Entry e : repo.func_110613_c()) {
                scan(new File(dir, e.func_110515_d()), roots, sink, stats);
            }
        }
    }

    private static void scan(File pack, String[] roots, Sink sink, LoadStats stats) {
        int before = stats.files;
        if (pack.isDirectory()) {
            Path packPath = pack.toPath();
            for (String root : roots) {
                Path base = packPath.resolve(root);
                if (!Files.isDirectory(base)) {
                    continue;
                }
                List<Path> files = new ArrayList<>();
                try (Stream<Path> st = Files.walk(base)) {
                    st.filter(p -> p.toString().endsWith(".properties")).forEach(files::add);
                } catch (IOException e) {
                    Log.info("lens", "could not read " + base + ": " + e);
                }
                for (Path p : files) {
                    String entry = packPath.relativize(p).toString().replace('\\', '/');
                    try (InputStream in = Files.newInputStream(p)) {
                        read(entry, in, sink, stats);
                    } catch (IOException e) {
                        Log.info("lens", "could not read " + p + ": " + e);
                    }
                }
            }
        } else if (pack.isFile()) {
            try (ZipFile zip = new ZipFile(pack)) {
                for (ZipEntry z : Collections.list(zip.entries())) {
                    String n = z.getName();
                    if (n.endsWith(".properties")
                            && (n.startsWith(roots[0]) || n.startsWith(roots[1]))) {
                        try (InputStream in = zip.getInputStream(z)) {
                            read(n, in, sink, stats);
                        }
                    }
                }
            } catch (IOException e) {
                Log.info("lens", "could not read " + pack + ": " + e);
            }
        }
        if (stats.files > before) {
            stats.packs++;
        }
    }

    /** A rule file: entry is the path in the pack (assets/minecraft/.../x.properties). */
    private static void read(String entry, InputStream in, Sink sink, LoadStats stats)
            throws IOException {
        stats.files++;
        Properties p = new Properties();
        p.load(in);
        String path = entry.substring(ASSETS.length());
        int slash = path.lastIndexOf('/');
        String basePath = path.substring(0, slash);
        String name = path.substring(slash + 1, path.length() - ".properties".length());
        sink.add(basePath, name, p, stats);
    }

    /** Higher weight first; then the folder and the file name (the format's tie-break). */
    static int order(
            int weightA,
            String basePathA,
            String nameA,
            int weightB,
            String basePathB,
            String nameB) {
        return weightA != weightB
                ? weightB - weightA
                : !basePathA.equals(basePathB)
                        ? basePathA.compareTo(basePathB)
                        : nameA.compareTo(nameB);
    }

    static int intOr(String s, int fallback) {
        try {
            return s == null ? fallback : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * Name in the atlas, with the domain (as the atlas stores it: ResourceLocation.toString());
     * without the leading textures/.
     */
    static String spriteName(String path) {
        return new ResourceLocation(
                        path.startsWith("textures/") ? path.substring("textures/".length()) : path)
                .toString();
    }
}
