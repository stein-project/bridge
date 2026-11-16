/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import net.minecraft.nbt.NBTSizeTracker;
import net.minecraft.nbt.NBTTagByteArray;
import net.minecraft.nbt.NBTTagIntArray;

import java.io.DataInput;
import java.io.IOException;

/**
 * Fix for the NBT array size check that overflows: the bodies of NBTTagIntArray.read and
 * NBTTagByteArray.read, with the size charged to the NBTSizeTracker computed in long.
 *
 * <p>1.8.9 charges {@code 32 * length} bits for an int array and {@code 8 * length} for a byte
 * array as an int product, converted to long only afterwards. A length such as 2^27 ints makes the
 * product wrap to 0 (or to a small or negative number), the tracker lets it through, and the game
 * allocates 512 MB for an array that a packet of a few bytes announced: an item's NBT in any slot
 * packet is enough to freeze or kill the client with an OutOfMemoryError. Here the product is a
 * long ({@code 32L * length}), so the tracker sees the real size and throws its usual "Tried to
 * read NBT tag that was too big" before the allocation. Mojang's later versions charge the size in
 * long (around 1.14).
 *
 * <p>For every length whose product fits in an int (all real data: a packet's tracker allows
 * 2097152 bits) the two computations are equal, so the reads, the charges and the results are the
 * same as the game's. A negative length still fails on the array allocation, as it did.
 */
public final class NbtArraySize {

    private NbtArraySize() {}

    /** Body of NBTTagIntArray.read(input, depth, sizeTracker). */
    public static void readIntArray(Object tag, Object input, int depth, Object tracker)
            throws IOException {
        NBTSizeTracker sizes = (NBTSizeTracker) tracker;
        DataInput in = (DataInput) input;
        sizes.read(192L);
        int length = in.readInt();
        sizes.read(32L * length);
        int[] values = new int[length];
        ((NBTTagIntArray) tag).intArray = values;
        for (int i = 0; i < length; i++) {
            values[i] = in.readInt();
        }
    }

    /** Body of NBTTagByteArray.read(input, depth, sizeTracker). */
    public static void readByteArray(Object tag, Object input, int depth, Object tracker)
            throws IOException {
        NBTSizeTracker sizes = (NBTSizeTracker) tracker;
        DataInput in = (DataInput) input;
        sizes.read(192L);
        int length = in.readInt();
        sizes.read(8L * length);
        NBTTagByteArray array = (NBTTagByteArray) tag;
        array.data = new byte[length];
        in.readFully(array.data);
    }
}
