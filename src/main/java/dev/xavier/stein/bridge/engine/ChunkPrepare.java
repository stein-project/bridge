/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.network.INetHandler;
import net.minecraft.network.Packet;
import net.minecraft.network.PacketThreadUtil;
import net.minecraft.network.play.server.S21PacketChunkData;
import net.minecraft.network.play.server.S26PacketMapChunkBulk;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.IThreadListener;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.NibbleArray;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Decoding of chunk packets off the game thread.
 *
 * <p>The chunk packet (S21PacketChunkData, and the S26PacketMapChunkBulk with up to 10 chunks)
 * first goes through the network thread: the handler runs there up to checkThreadAndEnqueue, which
 * reschedules it onto the game thread. There, fillChunk converts the bytes into sections (blocks,
 * block light, sky light), copies the biomes and recounts the blocks of each section — work that
 * does not depend on the world.
 *
 * <p>Here that work moves to the network thread, on the handler's first pass: the sections
 * (ExtendedBlockStorage) come out ready, with the count done. On the game thread, fillChunk only
 * swaps the sections into the chunk and does the rest of the original (flags, height map, tile
 * entities). The same bytes, the same walk as the original: the world comes out identical. Without
 * preparation (world changed, odd packet), the game's fillChunk runs as always.
 *
 * <p>{@code -Dsteinbridge.opt.vanillaChunkPrepare=true}: no preparation.
 */
public final class ChunkPrepare {

    private static final boolean VANILLA = EngineOptions.VANILLA_CHUNK_PREPARE;
    private static final int SECTIONS = 16;
    private static final int BLOCKS = 4096;
    private static final int NIBBLES = 2048;
    private static final int BIOMES = 256;

    /** Preparation per packet data array (identity: array does not override equals). */
    private static final Map<byte[], Prepared> READY =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** Ready sections of a chunk: index = height/16; null where the mask has no section. */
    private static final class Prepared {
        final ExtendedBlockStorage[] sections = new ExtendedBlockStorage[SECTIONS];
        final int mask;
        final boolean full;
        final boolean sky;
        byte[] biomes;

        Prepared(int mask, boolean full, boolean sky) {
            this.mask = mask;
            this.full = full;
            this.sky = sky;
        }
    }

    private ChunkPrepare() {}

    /**
     * In place of checkThreadAndEnqueue in the chunk handlers. On the network thread (before the
     * packet goes to the game queue), prepares the sections; then continues with the original,
     * which reschedules and exits with the usual exception.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void checkThread(Object packet, Object handler, Object listener) {
        IThreadListener threads = (IThreadListener) listener;
        if (!VANILLA && !threads.isCallingFromMinecraftThread()) {
            prepare(packet);
        }
        PacketThreadUtil.checkThreadAndEnqueue((Packet) packet, (INetHandler) handler, threads);
    }

    private static void prepare(Object packet) {
        try {
            WorldClient world = Minecraft.getMinecraft().theWorld;
            if (world == null) {
                return;
            }
            boolean sky = !world.provider.getHasNoSky();
            if (packet instanceof S21PacketChunkData) {
                S21PacketChunkData s21 = (S21PacketChunkData) packet;
                byte[] data = s21.getExtractedDataBytes();
                store(data, s21.getExtractedSize(), s21.func_149274_i(), sky);
            } else {
                S26PacketMapChunkBulk s26 = (S26PacketMapChunkBulk) packet;
                int n = s26.getChunkCount();
                for (int i = 0; i < n; i++) {
                    store(s26.getChunkBytes(i), s26.getChunkSize(i), true, sky);
                }
            }
        } catch (Throwable e) {
            // No preparation: the game's fillChunk does everything on the game thread, as always.
        }
    }

    /** The original fillChunk's walk, into new sections. */
    private static void store(byte[] data, int mask, boolean full, boolean sky) {
        Prepared p = new Prepared(mask, full, sky);
        int offset = 0;
        for (int i = 0; i < SECTIONS; i++) {
            if ((mask & (1 << i)) != 0) {
                ExtendedBlockStorage s = new ExtendedBlockStorage(i << 4, sky);
                char[] blocks = s.getData();
                for (int j = 0; j < BLOCKS; j++) {
                    blocks[j] = (char) ((data[offset + 1] & 0xFF) << 8 | data[offset] & 0xFF);
                    offset += 2;
                }
                p.sections[i] = s;
            }
        }
        offset = copyNibbles(data, offset, p.sections, false);
        if (sky) {
            offset = copyNibbles(data, offset, p.sections, true);
        }
        if (full) {
            p.biomes = new byte[BIOMES];
            System.arraycopy(data, offset, p.biomes, 0, BIOMES);
        }
        for (ExtendedBlockStorage s : p.sections) {
            if (s != null) {
                s.removeInvalidBlocks();
            }
        }
        READY.put(data, p);
    }

    /**
     * Copies the next light nibbles (sky light, or block light) of each present section from the
     * data, and returns the new offset.
     */
    private static int copyNibbles(
            byte[] data, int offset, ExtendedBlockStorage[] sections, boolean sky) {
        for (ExtendedBlockStorage section : sections) {
            if (section != null) {
                NibbleArray light = sky ? section.getSkylightArray() : section.getBlocklightArray();
                System.arraycopy(data, offset, light.getData(), 0, NIBBLES);
                offset += NIBBLES;
            }
        }
        return offset;
    }

    /**
     * Does the current world have a sky? The preparation read this on the network thread; if the
     * world changed midway (dimension change), the byte layout changes and the preparation is
     * discarded.
     */
    private static boolean hasSky() {
        try {
            WorldClient world = Minecraft.getMinecraft().theWorld;
            return world != null && !world.provider.getHasNoSky();
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * In place of fillChunk in the chunk handlers: with preparation, only the swap and the end of
     * the original.
     */
    public static void fill(Object chunkObj, Object bytes, int mask, boolean full) {
        Chunk chunk = (Chunk) chunkObj;
        byte[] data = (byte[]) bytes;
        Prepared p = VANILLA ? null : READY.remove(data);
        ExtendedBlockStorage[] sections = p == null ? null : chunk.storageArrays;
        if (p == null
                || p.mask != mask
                || p.full != full
                || sections.length != SECTIONS
                || p.sky != hasSky()) {
            chunk.fillChunk(data, mask, full);
            ChunkKeep.filled(chunk, full);
            return;
        }
        for (int i = 0; i < SECTIONS; i++) {
            if ((mask & (1 << i)) != 0) {
                sections[i] = p.sections[i];
            } else if (full) {
                sections[i] = null;
            }
        }
        if (full) {
            System.arraycopy(p.biomes, 0, chunk.blockBiomeArray, 0, BIOMES);
        }
        chunk.isLightPopulated = true;
        chunk.isTerrainPopulated = true;
        chunk.generateHeightMap();
        for (TileEntity te : chunk.chunkTileEntityMap.values()) {
            te.updateContainingBlockInfo();
        }
    }
}
