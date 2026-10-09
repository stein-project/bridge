/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import static dev.xavier.stein.bridge.engine.ChunkDiskStore.BIOMES;
import static dev.xavier.stein.bridge.engine.ChunkDiskStore.ON_DISK;
import static dev.xavier.stein.bridge.engine.ChunkDiskStore.READING;
import static dev.xavier.stein.bridge.engine.ChunkDiskStore.READY;
import static dev.xavier.stein.bridge.engine.ChunkDiskStore.dir;
import static dev.xavier.stein.bridge.engine.ChunkDiskStore.root;

import dev.xavier.stein.bridge.core.Log;
import dev.xavier.stein.bridge.engine.ChunkDiskStore.LoadedColumn;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.play.server.S21PacketChunkData;
import net.minecraft.util.BlockPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import java.net.SocketAddress;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

/**
 * Extended distance: chunk columns the server tells the client to drop (they leave its radius) stay
 * on the client and keep being drawn up to the render distance. An option (Visuals, through the
 * Bridge's visual options), off by default.
 *
 * <ul>
 *   <li><b>memory</b>: the chunk handlers' doPreChunk(x, z, false) does not remove the column; when
 *       the server sends it again, the stored one is removed before the server's enters (the game's
 *       loadChunk does not remove the old one from the list);
 *   <li><b>disk</b>: also writes every dropped column — and, on a world change, all the loaded ones
 *       — in the format of the game's chunk packet (the S21PacketChunkData encoder, the same as the
 *       server's), compressed, in &lt;game
 *       folder&gt;/steinbridge/terreno/&lt;server&gt;/&lt;world&gt;/. Stored columns within the
 *       render distance that the server did not send come back in, read on a disk thread; what is
 *       far away is a snapshot of the last visit. Servers only (in a local world the game already
 *       has everything);
 *   <li><b>limit</b>: bytes of the columns stored in memory (estimated from the sections) and of
 *       the terrain on disk; once exceeded, the column farthest from the player goes (memory) and
 *       the oldest file (disk);
 *   <li><b>world change</b> (joining, respawning in another dimension, switching servers on the
 *       proxy, leaving): the game creates another WorldClient and the stored columns start over. On
 *       disk, the world is the server plus the dimension and the spawn point; the first columns the
 *       server sends are checked against the stored ones by biome, and if they do not match
 *       (another world at the same address, or regenerated) the stored terrain of that world is
 *       deleted.
 * </ul>
 *
 * <p>Initial values for measurement: {@code -Dsteinbridge.visuals.keepChunks=0|1|2} (off, memory,
 * disk) and {@code -Dsteinbridge.visuals.keepChunksMB=512}.
 */
public final class ChunkKeep {

    public static final int OFF = 0;
    public static final int MEMORY = 1;
    public static final int DISK = 2;

    private static volatile int mode = clampMode(EngineOptions.KEEP_CHUNKS);
    static volatile long limitBytes = megabytesToBytes(EngineOptions.KEEP_CHUNKS_MB);

    /** Bytes of a section in memory: blocks (char) and block and sky light. */
    private static final int SECTION_BYTES = 4096 * 2 + 2048 * 2;

    // Game thread.
    private static WorldClient world;

    /**
     * Stored columns (outside the server's radius) of the current world: key -> estimated bytes.
     */
    private static final HashMap<Long, Integer> KEPT = new HashMap<>();

    private static long keptBytes;
    private static boolean restoring;
    private static long nextScan;
    private static int checks;
    private static int mismatches;
    private static int savesSinceIndex;
    private static boolean dirResolved;

    // Disk: the game thread decides, the disk thread writes and reads.
    private static final int MAX_BIOME_CHECKS = 8;
    private static final int MISMATCH_LIMIT = 3;
    private static final int INDEX_EVERY_SAVES = 64;
    private static final int MAX_READS_PER_SCAN = 64;
    private static final int MIN_LIMIT_MB = 64;
    private static final int MAX_LIMIT_MB = 8192;
    private static final int ALL_SECTIONS_MASK = 65535;
    private static final int COLUMN_OVERHEAD_BYTES = 1280;
    private static final long SCAN_INTERVAL_NANOS = 250_000_000L;
    private static final long DEBUG_INTERVAL_NANOS = 5_000_000_000L;

    /** Goes up on every world change and every deleted terrain: an earlier read does not get in. */
    static volatile int epoch;

    /**
     * Dropped columns waiting for encoding to disk (column, key): frame() encodes them a bit at a
     * time, up to SAVE_BUDGET per frame. Game thread.
     */
    private static final ArrayDeque<PendingSave> PENDING = new ArrayDeque<>();

    /** A dropped column waiting to be encoded: the chunk and its key. */
    private static final class PendingSave {
        final Chunk chunk;
        final long key;

        PendingSave(Chunk chunk, long key) {
            this.chunk = chunk;
            this.key = key;
        }
    }

    private static final long SAVE_BUDGET = 500_000L; // 0.5 ms

    private ChunkKeep() {}

    // ---- option (Loader API) ----

    /** OFF, MEMORY or DISK; limit in MB (memory and disk). */
    public static void setMode(int m, int limitMB) {
        mode = clampMode(m);
        limitBytes = megabytesToBytes(limitMB);
    }

    private static int clampMode(int m) {
        return m == MEMORY || m == DISK ? m : OFF;
    }

    private static long megabytesToBytes(int mb) {
        return (long) Math.max(MIN_LIMIT_MB, Math.min(MAX_LIMIT_MB, mb)) << 20;
    }

    private static void release(WorldClient w, long key) {
        w.doPreChunk(keyX(key), keyZ(key), false);
    }

    private static int playerChunkX(EntityPlayerSP player) {
        return (int) Math.floor(player.posX) >> 4;
    }

    private static int playerChunkZ(EntityPlayerSP player) {
        return (int) Math.floor(player.posZ) >> 4;
    }

    static long columnKey(int x, int z) {
        return (long) x << 32 | (z & 0xFFFFFFFFL);
    }

    static int keyX(long key) {
        return (int) (key >> 32);
    }

    static int keyZ(long key) {
        return (int) key;
    }

    // ---- hooks (patcher/redirects-mods.tsv) ----

    /**
     * In place of doPreChunk in the chunk handlers (S21 and S26): a server drop becomes a stored
     * column.
     */
    public static void preChunk(Object worldObj, int x, int z, boolean load) {
        WorldClient w = (WorldClient) worldObj;
        if (w != world) {
            switchWorld(w);
        }
        long key = columnKey(x, z);
        Integer kept = KEPT.remove(key);
        if (kept != null) {
            keptBytes -= kept;
        }
        if (load) {
            if (kept != null) {
                w.doPreChunk(x, z, false); // the stored one leaves before the server's enters
            }
            w.doPreChunk(x, z, true);
            return;
        }
        if (mode == OFF) {
            w.doPreChunk(x, z, false);
            return;
        }
        Chunk chunk = w.getChunkFromChunkCoords(x, z);
        if (chunk.isEmpty()) {
            w.doPreChunk(x, z, false);
            return;
        }
        if (mode == DISK) {
            // The encoding (a copy of tens to hundreds of KB per column) goes to frame(), a bit at
            // a time: on an area change hundreds of columns leave in the same tick, and encoding
            // all of them here caused a 40-50 ms stutter (measured on the terrain tour with the
            // server sending 6 chunks).
            PENDING.add(new PendingSave(chunk, key));
        }
        int bytes = estimate(chunk);
        KEPT.put(key, bytes);
        keptBytes += bytes;
        trim(w);
    }

    /**
     * Before every Minecraft.loadWorld (joining, respawning in another dimension, leaving): the
     * disk writes the world that is leaving.
     */
    public static void worldChanging() {
        WorldClient w = world;
        if (w == null) {
            return;
        }
        try {
            if (mode == DISK && dir() != null) {
                List<Chunk> list = w.clientChunkProvider.chunkListing;
                for (Chunk c : new ArrayList<>(list)) {
                    if (!c.isEmpty()) {
                        save(w, c, columnKey(c.xPosition, c.zPosition));
                    }
                }
                writeIndex();
            }
        } catch (Throwable e) {
            Log.info("terrain", "failed to save the world being left: " + e);
        }
        switchWorld(null);
    }

    /**
     * On every frame (ChunkPace.uploads, in updateChunks): columns read from disk come in (a few
     * per frame) and, every 250 ms, it fetches the stored ones that are missing around the player.
     */
    public static void frame() {
        WorldClient w = world;
        if (w == null) {
            return;
        }
        if (mode == OFF) {
            PENDING.clear();
            if (!KEPT.isEmpty()) {
                releaseAll(w);
            }
            return;
        }
        if (!PENDING.isEmpty()) {
            // Columns dropped to disk: up to 0.5 ms per frame. Only one that is still stored and
            // still the same in the world (the server may have sent it again; the limit may have
            // removed it).
            long start = System.nanoTime();
            do {
                PendingSave p = PENDING.poll();
                long key = p.key;
                if (KEPT.containsKey(key)) {
                    Chunk current = w.getChunkFromChunkCoords(keyX(key), keyZ(key));
                    if (current == p.chunk) {
                        save(w, current, key);
                    }
                }
            } while (!PENDING.isEmpty() && System.nanoTime() - start < SAVE_BUDGET);
        }
        for (int i = 0; i < 2; i++) {
            LoadedColumn r = READY.poll();
            if (r == null) {
                break;
            }
            apply(w, r);
        }
        long now = System.nanoTime();
        if (mode != DISK || now - nextScan < 0) {
            return;
        }
        nextScan = now + SCAN_INTERVAL_NANOS;
        scan(w);
    }

    /** Server column filled (ChunkPrepare.fill): the first ones check the stored world by biome. */
    static void filled(Object chunkObj, boolean full) {
        Chunk chunk = (Chunk) chunkObj;
        if (restoring || !full || mode != DISK || checks >= MAX_BIOME_CHECKS || world == null) {
            return;
        }
        try {
            Path d = dir();
            if (d == null) {
                return;
            }
            Integer h = BIOMES.get(columnKey(chunk.xPosition, chunk.zPosition));
            if (h == null) {
                return;
            }
            checks++;
            if (h != Arrays.hashCode(chunk.getBiomeArray())) {
                mismatches++;
            }
            if (mismatches >= MISMATCH_LIMIT) {
                wipe(d);
            }
        } catch (Throwable e) {
            Log.info("terrain", "failed to verify the world: " + e);
        }
    }

    // ---- memory ----

    private static void switchWorld(WorldClient w) {
        world = w;
        KEPT.clear();
        keptBytes = 0;
        dir = null;
        dirResolved = false;
        epoch++;
        ON_DISK.clear();
        BIOMES.clear();
        READING.clear();
        READY.clear();
        PENDING.clear(); // worldChanging already wrote the whole world that is leaving
        checks = 0;
        mismatches = 0;
        savesSinceIndex = 0;
    }

    private static int estimate(Chunk chunk) {
        int n = 0;
        for (ExtendedBlockStorage s : chunk.getBlockStorageArray()) {
            if (s != null) {
                n++;
            }
        }
        return n * SECTION_BYTES + COLUMN_OVERHEAD_BYTES;
    }

    /** Past the limit: the stored columns farthest from the player leave, down to 90% of it. */
    private static void trim(WorldClient w) {
        if (keptBytes <= limitBytes) {
            return;
        }
        EntityPlayerSP player = Minecraft.getMinecraft().thePlayer;
        int cx = player == null ? 0 : playerChunkX(player);
        int cz = player == null ? 0 : playerChunkZ(player);
        List<Long> keys = new ArrayList<>(KEPT.keySet());
        keys.sort(
                Comparator.comparingLong(
                                (Long k) -> {
                                    long dx = keyX(k) - cx;
                                    long dz = keyZ(k) - cz;
                                    return dx * dx + dz * dz;
                                })
                        .reversed());
        long target = limitBytes / 10 * 9;
        for (Long k : keys) {
            if (keptBytes <= target) {
                break;
            }
            keptBytes -= KEPT.remove(k);
            release(w, k);
        }
    }

    private static void releaseAll(WorldClient w) {
        for (Long k : new ArrayList<>(KEPT.keySet())) {
            release(w, k);
        }
        KEPT.clear();
        keptBytes = 0;
    }

    // ---- disk ----

    /**
     * Server name: the address from the server list or, without it (entry through the command line,
     * --server, which does not create the ServerData), the connection's.
     */
    private static String serverName(Minecraft mc) {
        ServerData server = mc.getCurrentServerData();
        if (server != null) {
            return server.serverIP;
        }
        NetHandlerPlayClient handler = mc.getNetHandler();
        SocketAddress remote =
                handler == null ? null : handler.getNetworkManager().getRemoteAddress();
        return remote == null ? null : remote.toString();
    }

    private static final boolean DEBUG = EngineOptions.KEEP_DEBUG;
    private static long nextDebug;

    /**
     * -Dsteinbridge.visuals.keepDebug=true: every 5 s, stored columns, bytes and what the disk has.
     */
    static void debug() {
        if (!DEBUG || mode == OFF || world == null) {
            return;
        }
        long now = System.nanoTime();
        if (now - nextDebug < 0) {
            return;
        }
        nextDebug = now + DEBUG_INTERVAL_NANOS;
        Log.info(
                "terrain",
                ""
                        + KEPT.size()
                        + " columns kept ("
                        + (keptBytes >> 20)
                        + " MB), "
                        + ON_DISK.size()
                        + " on disk, "
                        + READING.size()
                        + " reading"
                        + (dir != null ? " | " + dir : ""));
    }

    /** Folder of the current world on disk, or null (local world, no world). */
    private static Path dir() {
        if (dirResolved) {
            return dir;
        }
        dirResolved = true;
        Minecraft mc = Minecraft.getMinecraft();
        WorldClient w = world;
        String server = w == null || mc.isSingleplayer() ? null : serverName(mc);
        if (server == null) {
            dir = null; // local world: the game already has the whole terrain
            return null;
        }
        if (root == null) {
            root = mc.mcDataDir.toPath().resolve("steinbridge").resolve("terreno");
        }
        String ip = server.toLowerCase().replaceAll("[^a-z0-9._-]", "_");
        int dim = w.provider.getDimensionId();
        BlockPos spawn = w.getSpawnPoint();
        Path d =
                root.resolve(ip.isEmpty() ? "servidor" : ip)
                        .resolve(
                                "dim"
                                        + dim
                                        + "_"
                                        + spawn.getX()
                                        + "_"
                                        + spawn.getY()
                                        + "_"
                                        + spawn.getZ());
        dir = d;
        int e = epoch;
        ChunkDiskStore.loadIndexLater(d, e);
        return d;
    }

    /**
     * Encodes on the game thread (copies the column's arrays); compresses and writes on the disk
     * one.
     */
    private static void save(WorldClient w, Chunk chunk, long key) {
        Path d = dir();
        if (d == null) {
            return;
        }
        boolean sky = !w.provider.getHasNoSky();
        S21PacketChunkData.Extracted extracted =
                S21PacketChunkData.getExtractedData(chunk, true, sky, ALL_SECTIONS_MASK);
        byte[] data = extracted.data;
        int mask = extracted.dataSize;
        BIOMES.put(key, Arrays.hashCode(chunk.getBiomeArray()));
        ON_DISK.add(key);
        ChunkDiskStore.writeLater(d, key, mask, data);
        if (++savesSinceIndex >= INDEX_EVERY_SAVES) {
            writeIndex();
        }
    }

    private static void writeIndex() {
        Path d = dir;
        if (d == null) {
            return;
        }
        savesSinceIndex = 0;
        ChunkDiskStore.writeIndexLater(d, new HashMap<>(BIOMES));
    }

    /** Fetches the stored columns that are missing within the render distance. */
    private static void scan(WorldClient w) {
        Path d = dir();
        if (d == null || ON_DISK.isEmpty() || keptBytes > limitBytes) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        if (player == null) {
            return;
        }
        int cx = playerChunkX(player);
        int cz = playerChunkZ(player);
        int r = mc.gameSettings.renderDistanceChunks;
        int started = 0;
        int e = epoch;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > r * r) {
                    continue;
                }
                long key = columnKey(cx + dx, cz + dz);
                if (!ON_DISK.contains(key) || READING.contains(key) || KEPT.containsKey(key)) {
                    continue;
                }
                if (!w.getChunkFromChunkCoords(cx + dx, cz + dz).isEmpty()) {
                    continue; // the server already sent it
                }
                READING.add(key);
                ChunkDiskStore.readLater(d, key, e);
                if (++started >= MAX_READS_PER_SCAN) {
                    return;
                }
            }
        }
    }

    /**
     * A column read from disk enters the world: like the packet handler, through the same
     * fillChunk.
     */
    private static void apply(WorldClient w, LoadedColumn r) {
        long key = r.key;
        int x = keyX(key);
        int z = keyZ(key);
        READING.remove(key);
        if (!w.getChunkFromChunkCoords(x, z).isEmpty() || keptBytes > limitBytes) {
            return;
        }
        w.doPreChunk(x, z, true);
        Chunk chunk = w.getChunkFromChunkCoords(x, z);
        restoring = true;
        try {
            ChunkPrepare.fill(chunk, r.data, r.mask, true);
        } finally {
            restoring = false;
        }
        w.markBlockRangeForRenderUpdate(x << 4, 0, z << 4, (x << 4) + 15, 256, (z << 4) + 15);
        int bytes = estimate(chunk);
        KEPT.put(key, bytes);
        keptBytes += bytes;
    }

    /**
     * The stored terrain is not from this world: it leaves the disk, and the columns that came from
     * it leave the screen.
     */
    private static void wipe(Path d) {
        Log.info(
                "terrain",
                "the server columns do not match the stored ones ("
                        + mismatches
                        + " of "
                        + checks
                        + "): another world at the same address; its stored terrain is erased");
        checks = MAX_BIOME_CHECKS;
        epoch++;
        ON_DISK.clear();
        BIOMES.clear();
        READING.clear();
        READY.clear();
        releaseAll(world);
        ChunkDiskStore.erase(d);
    }
}
