/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

package dev.xavier.stein.bridge.engine;

import static dev.xavier.stein.bridge.engine.ChunkKeep.columnKey;
import static dev.xavier.stein.bridge.engine.ChunkKeep.epoch;
import static dev.xavier.stein.bridge.engine.ChunkKeep.keyX;
import static dev.xavier.stein.bridge.engine.ChunkKeep.keyZ;
import static dev.xavier.stein.bridge.engine.ChunkKeep.limitBytes;

import dev.xavier.stein.bridge.core.Log;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * The disk side of {@link ChunkKeep}: the terrain store (one compressed file per column, plus the
 * biome index), the single disk thread that writes and reads it, and the size limit with its
 * pruning. The game thread decides what to save and load; everything here that touches files runs
 * on the disk thread. On-disk names ("terreno", "biomas.idx", "servidor") are decided by ChunkKeep
 * and this class and must not change: they identify stored terrain from earlier runs.
 */
final class ChunkDiskStore {
    private static final int MAGIC = 0x53424331; // "SBC1"
    private static final String INDEX = "biomas.idx";

    /** Folder of the current world on disk, or null (local world, no world). */
    static volatile Path dir;

    /** Columns stored for the current world (all of them, including those in memory). */
    static final Set<Long> ON_DISK = ConcurrentHashMap.newKeySet();

    /** Biome hash of each stored column, to tell whether the stored world is the server's. */
    static final Map<Long, Integer> BIOMES = new ConcurrentHashMap<>();

    static final Set<Long> READING = ConcurrentHashMap.newKeySet();
    static final ConcurrentLinkedQueue<LoadedColumn> READY = new ConcurrentLinkedQueue<>();

    /** A column read from disk waiting to enter the world. */
    static final class LoadedColumn {
        final long key;
        final int mask;
        final byte[] data;

        LoadedColumn(long key, int mask, byte[] data) {
            this.key = key;
            this.mask = mask;
            this.data = data;
        }
    }

    private static final ExecutorService IO =
            Executors.newSingleThreadExecutor(
                    r -> {
                        Thread t = new Thread(r, "steinbridge-terreno");
                        t.setDaemon(true);
                        return t;
                    });

    /** Bytes of terrain on disk (all servers and worlds); -1 = not counted yet. Disk thread. */
    private static long diskBytes = -1;

    static Path root;

    private ChunkDiskStore() {}

    /** Game thread: another world; the disk thread reads its index. */
    static void loadIndexLater(Path d, int e) {
        IO.execute(() -> loadIndex(d, e));
    }

    /** Game thread: a column encoded by the game goes to the disk thread. */
    static void writeLater(Path d, long key, int mask, byte[] data) {
        IO.execute(() -> write(d, key, mask, data));
    }

    /** Game thread: a stored column is read by the disk thread (it ends up in READY). */
    static void readLater(Path d, long key, int e) {
        IO.execute(() -> read(d, key, e));
    }

    /** Game thread: the biome index (a snapshot) is written by the disk thread. */
    static void writeIndexLater(Path d, Map<Long, Integer> snapshot) {
        IO.execute(
                () -> {
                    try {
                        Files.createDirectories(d);
                        ByteArrayOutputStream bo =
                                new ByteArrayOutputStream(snapshot.size() * 12 + 4);
                        try (DataOutputStream out = new DataOutputStream(bo)) {
                            out.writeInt(snapshot.size());
                            for (Map.Entry<Long, Integer> e : snapshot.entrySet()) {
                                out.writeLong(e.getKey());
                                out.writeInt(e.getValue());
                            }
                        }
                        Files.write(d.resolve(INDEX), bo.toByteArray());
                    } catch (IOException e) {
                        warn(e);
                    }
                });
    }

    /** Game thread: the stored terrain of the world leaves the disk. */
    static void erase(Path d) {
        IO.execute(
                () -> {
                    try (Stream<Path> s = Files.walk(d)) {
                        s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
                    } catch (IOException e) {
                        warn(e);
                    }
                    diskBytes = -1;
                });
    }

    private static Path file(Path d, int x, int z) {
        return d.resolve("r." + (x >> 5) + "." + (z >> 5)).resolve(x + "." + z + ".bin");
    }

    /** Disk thread: the columns and biomes already stored for this world. */
    private static void loadIndex(Path d, int e) {
        if (!Files.isDirectory(d)) {
            return;
        }
        try (Stream<Path> s = Files.walk(d, 2)) {
            s.filter(p -> p.getFileName().toString().endsWith(".bin"))
                    .forEach(
                            p -> {
                                String[] xz = p.getFileName().toString().split("\\.");
                                if (xz.length == 3 && e == epoch) {
                                    try {
                                        ON_DISK.add(
                                                columnKey(
                                                        Integer.parseInt(xz[0]),
                                                        Integer.parseInt(xz[1])));
                                    } catch (NumberFormatException ignored) {
                                        // a file that is not ours
                                    }
                                }
                            });
        } catch (IOException ex) {
            warn(ex);
        }
        Path index = d.resolve(INDEX);
        if (Files.isRegularFile(index) && e == epoch) {
            try (DataInputStream in = new DataInputStream(Files.newInputStream(index))) {
                for (int n = in.readInt(); n > 0; n--) {
                    BIOMES.putIfAbsent(in.readLong(), in.readInt());
                }
            } catch (IOException ex) {
                warn(ex);
            }
        }
    }

    /** Disk thread. */
    private static void write(Path d, long key, int mask, byte[] data) {
        Path f = file(d, keyX(key), keyZ(key));
        try {
            Files.createDirectories(f.getParent());
            ByteArrayOutputStream bo = new ByteArrayOutputStream(data.length / 3 + 64);
            Deflater deflater = new Deflater(Deflater.BEST_SPEED);
            try (DataOutputStream out =
                    new DataOutputStream(new DeflaterOutputStream(bo, deflater))) {
                out.writeInt(MAGIC);
                out.writeInt(mask);
                out.writeInt(data.length);
                out.write(data);
            } finally {
                deflater.end();
            }
            byte[] bytes = bo.toByteArray();
            long old = Files.exists(f) ? Files.size(f) : 0L;
            Files.write(f, bytes);
            countDisk();
            diskBytes += bytes.length - old;
            if (diskBytes > limitBytes) {
                prune();
            }
        } catch (IOException e) {
            warn(e);
        }
    }

    /** Disk thread. */
    private static void read(Path d, long key, int e) {
        try (DataInputStream in =
                new DataInputStream(
                        new InflaterInputStream(
                                Files.newInputStream(file(d, keyX(key), keyZ(key)))))) {
            if (in.readInt() != MAGIC) {
                throw new IOException("unknown format");
            }
            int mask = in.readInt();
            byte[] data = new byte[in.readInt()];
            in.readFully(data);
            if (e == epoch) {
                READY.add(new LoadedColumn(key, mask, data));
                return;
            }
        } catch (IOException ex) {
            ON_DISK.remove(key);
        }
        READING.remove(key);
    }

    /** Disk thread. */
    private static void countDisk() {
        if (diskBytes >= 0 || root == null) {
            return;
        }
        long total = 0;
        if (Files.isDirectory(root)) {
            try (Stream<Path> s = Files.walk(root)) {
                total = s.filter(Files::isRegularFile).mapToLong(p -> p.toFile().length()).sum();
            } catch (IOException e) {
                warn(e);
            }
        }
        diskBytes = total;
    }

    /** Disk thread: past the limit, the oldest files (from any world) go, down to 90% of it. */
    private static void prune() {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> s = Files.walk(root)) {
            s.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".bin"))
                    .forEach(files::add);
        } catch (IOException e) {
            warn(e);
            return;
        }
        files.sort(Comparator.comparingLong(p -> p.toFile().lastModified()));
        long target = limitBytes / 10 * 9;
        Path current = dir;
        for (Path p : files) {
            if (diskBytes <= target) {
                break;
            }
            long size = p.toFile().length();
            if (p.toFile().delete()) {
                diskBytes -= size;
                if (current != null && p.startsWith(current)) {
                    String[] xz = p.getFileName().toString().split("\\.");
                    if (xz.length == 3) {
                        ON_DISK.remove(columnKey(Integer.parseInt(xz[0]), Integer.parseInt(xz[1])));
                    }
                }
            }
        }
    }

    private static boolean warned;

    private static void warn(Exception e) {
        if (!warned) {
            warned = true;
            Log.info("terrain", "disk failure: " + e);
        }
    }
}
