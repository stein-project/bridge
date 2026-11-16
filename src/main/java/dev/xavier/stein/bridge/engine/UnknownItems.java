/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.PacketBuffer;

import java.io.IOException;

/**
 * Fix for item stacks with an unknown item id: PacketBuffer.readItemStackFromBuffer returns no
 * stack (an empty slot) instead of a stack whose item is null.
 *
 * <p>1.8.9 builds {@code new ItemStack(Item.getItemById(id), ...)} for any non-negative id, and an
 * id the client does not know (a newer server through a protocol translator, a plugin's custom id,
 * or a crafted packet) gives a stack with a null item. Everything that looks at the stack
 * afterwards calls its item (the tooltip, hasEffect, getDisplayName, the inventory renderer), so
 * the game thread crashed with a NullPointerException as soon as the slot was drawn or hovered.
 * Here the same bytes are read (id, count, damage and the NBT), so the rest of the packet stays
 * aligned, and an unknown item gives null, which every slot already takes as empty. Mojang's 1.11
 * does the same (an unknown item reads as the empty stack). Known items are built exactly as
 * before.
 */
public final class UnknownItems {

    private static final RepeatLog LOG = new RepeatLog("items", 10);

    private UnknownItems() {}

    /** Body of PacketBuffer.readItemStackFromBuffer. */
    public static Object readItemStack(Object buffer) throws IOException {
        PacketBuffer buf = (PacketBuffer) buffer;
        short id = buf.readShort();
        if (id < 0) {
            return null;
        }
        byte count = buf.readByte();
        short damage = buf.readShort();
        Item item = Item.getItemById(id);
        if (item == null) {
            buf.readNBTTagCompoundFromBuffer(); // the stack's bytes, to keep the packet aligned
            LOG.note("item id " + id + " is unknown to 1.8.9: the slot reads as empty");
            return null;
        }
        ItemStack stack = new ItemStack(item, count, damage);
        NBTTagCompound tag = buf.readNBTTagCompoundFromBuffer();
        stack.setTagCompound(tag);
        return stack;
    }
}
