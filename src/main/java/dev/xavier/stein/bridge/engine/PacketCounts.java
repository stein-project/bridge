/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.server.S13PacketDestroyEntities;
import net.minecraft.network.play.server.S22PacketMultiBlockChange;
import net.minecraft.network.play.server.S34PacketMaps;
import net.minecraft.util.Vec4b;
import net.minecraft.world.ChunkCoordIntPair;

import java.io.IOException;

/**
 * Fix for packets that allocate whatever count the server writes: the count read from the packet is
 * checked against the bytes the packet still has before anything is allocated.
 *
 * <p>Explosion (S27, the affected blocks), map chunk bulk (S26, the chunk list), multi block change
 * (S22), destroy entities (S13), maps (S34, the icons) and PacketBuffer.readByteArray read a count
 * and allocate an array or list of that size at once; the reads that would fail on the missing
 * bytes only come after. A packet of a few bytes that announces 2^31 elements made the network
 * thread allocate gigabytes (an OutOfMemoryError that takes the client down, or a long GC freeze).
 * Every element takes at least {@code minBytes} bytes of the packet (for example 3 for a block of
 * the explosion: three signed bytes), so a count above {@code readableBytes / minBytes} can never
 * be read whole: it now fails right away with an IOException, the same way a short packet already
 * fails (the connection closes with the decoding error). Packets the vanilla server sends always
 * hold their elements, so they read exactly as before. A negative count is left alone: the game's
 * allocation already throws on it.
 *
 * <p>S27 and S26 read their count once in their readPacketData and get a call-site rule; S22, S13
 * and S34 read other varints with the same call in the same method, so their readPacketData body is
 * this class's copy of the game's reading, with the check on the count only.
 */
public final class PacketCounts {

    /** S27: x, y and z offsets, one byte each. */
    private static final int EXPLOSION_BLOCK_BYTES = 3;

    /** S26: chunk x and z (ints) and the section mask (a short) per chunk. */
    private static final int BULK_CHUNK_BYTES = 10;

    /** S22: the packed position (a short) and the block state (a varint, at least one byte). */
    private static final int MULTI_BLOCK_BYTES = 3;

    /** S34: icon type and rotation (one byte) and x, z (one byte each). */
    private static final int MAP_ICON_BYTES = 3;

    private PacketCounts() {}

    /** PacketBuffer.readInt of S27PacketExplosion.readPacketData: the count of affected blocks. */
    public static int explosionBlocks(Object buffer) throws IOException {
        PacketBuffer buf = (PacketBuffer) buffer;
        return checked(buf, buf.readInt(), EXPLOSION_BLOCK_BYTES, "explosion blocks");
    }

    /** PacketBuffer.readVarIntFromBuffer of S26PacketMapChunkBulk.readPacketData: the chunks. */
    public static int bulkChunks(Object buffer) throws IOException {
        PacketBuffer buf = (PacketBuffer) buffer;
        return checked(buf, buf.readVarIntFromBuffer(), BULK_CHUNK_BYTES, "chunks");
    }

    /**
     * PacketBuffer.readVarIntFromBuffer of PacketBuffer.readByteArray: the array's length. It
     * throws what the game's readBytes would have thrown a moment later on the same packet
     * (readByteArray declares no IOException).
     */
    public static int byteArrayLength(Object buffer) {
        PacketBuffer buf = (PacketBuffer) buffer;
        int length = buf.readVarIntFromBuffer();
        if (length > buf.readableBytes()) {
            throw new IndexOutOfBoundsException(
                    "byte array of " + length + " bytes, " + buf.readableBytes() + " left");
        }
        return length;
    }

    /** Body of S22PacketMultiBlockChange.readPacketData, as the game reads it. */
    public static void multiBlockChange(Object packet, Object buffer) throws IOException {
        S22PacketMultiBlockChange p = (S22PacketMultiBlockChange) packet;
        PacketBuffer buf = (PacketBuffer) buffer;
        p.chunkPosCoord = new ChunkCoordIntPair(buf.readInt(), buf.readInt());
        int count = checked(buf, buf.readVarIntFromBuffer(), MULTI_BLOCK_BYTES, "block changes");
        p.changedBlocks = new S22PacketMultiBlockChange.BlockUpdateData[count];
        for (int i = 0; i < p.changedBlocks.length; i++) {
            short position = buf.readShort();
            IBlockState state = Block.BLOCK_STATE_IDS.getByValue(buf.readVarIntFromBuffer());
            p.changedBlocks[i] = p.new BlockUpdateData(position, state);
        }
    }

    /** Body of S13PacketDestroyEntities.readPacketData, as the game reads it. */
    public static void destroyEntities(Object packet, Object buffer) throws IOException {
        S13PacketDestroyEntities p = (S13PacketDestroyEntities) packet;
        PacketBuffer buf = (PacketBuffer) buffer;
        p.entityIDs = new int[checked(buf, buf.readVarIntFromBuffer(), 1, "entities")];
        for (int i = 0; i < p.entityIDs.length; i++) {
            p.entityIDs[i] = buf.readVarIntFromBuffer();
        }
    }

    /** Body of S34PacketMaps.readPacketData, as the game reads it. */
    public static void maps(Object packet, Object buffer) throws IOException {
        S34PacketMaps p = (S34PacketMaps) packet;
        PacketBuffer buf = (PacketBuffer) buffer;
        p.mapId = buf.readVarIntFromBuffer();
        p.mapScale = buf.readByte();
        int icons = checked(buf, buf.readVarIntFromBuffer(), MAP_ICON_BYTES, "map icons");
        p.mapVisiblePlayersVec4b = new Vec4b[icons];
        for (int i = 0; i < p.mapVisiblePlayersVec4b.length; i++) {
            short typeAndRotation = buf.readByte();
            byte type = (byte) (typeAndRotation >> 4 & 15);
            byte x = buf.readByte();
            byte z = buf.readByte();
            p.mapVisiblePlayersVec4b[i] = new Vec4b(type, x, z, (byte) (typeAndRotation & 15));
        }
        p.mapMaxX = buf.readUnsignedByte();
        if (p.mapMaxX > 0) {
            p.mapMaxY = buf.readUnsignedByte();
            p.mapMinX = buf.readUnsignedByte();
            p.mapMinY = buf.readUnsignedByte();
            p.mapDataBytes = buf.readByteArray();
        }
    }

    /** The count, or an IOException when the packet cannot hold that many elements. */
    private static int checked(PacketBuffer buf, int count, int minBytes, String what)
            throws IOException {
        if ((long) count * minBytes > buf.readableBytes()) {
            throw new IOException(
                    count
                            + " "
                            + what
                            + " announced, the packet has "
                            + buf.readableBytes()
                            + " bytes left");
        }
        return count;
    }
}
