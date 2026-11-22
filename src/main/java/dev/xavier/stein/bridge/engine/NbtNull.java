/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import dev.xavier.stein.bridge.core.Log;

import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;

/**
 * Fix for a null put in an NBT compound: it is left out, and the log says who put it.
 *
 * <p>NBTTagCompound.setTag(key, null) stored the null, and nothing complained until the compound
 * was written: then write threw a NullPointerException from inside the NBT code, far from the code
 * that caused it (a mod, or a game path with a missing item), and in single player the chunk or
 * player being saved was lost with it. Here the null is not stored (reading the key gives null
 * either way, as a missing key does); the first time, the log gets the key and the stack trace of
 * the caller, and later ones only a collapsed line with the key. Later versions refuse the null
 * outright with an exception. {@code -Dsteinbridge.opt.vanillaNbtNull=true} stores it, as 1.8.9.
 */
public final class NbtNull {

    private NbtNull() {}

    private static final boolean VANILLA = NetFixOptions.VANILLA_NBT_NULL;

    private static final RepeatLog REPEATS = new RepeatLog("nbt", 10);

    private static boolean traced;

    /** The body of NBTTagCompound.setTag(key, value). */
    public static void setTag(Object compound, Object key, Object value) {
        NBTTagCompound nbt = (NBTTagCompound) compound;
        if (value == null && !VANILLA) {
            nullValue((String) key);
            return;
        }
        nbt.tagMap.put((String) key, (NBTBase) value);
    }

    private static void nullValue(String key) {
        synchronized (NbtNull.class) {
            if (!traced) {
                traced = true;
                Log.error(
                        "nbt",
                        "null value for NBT key \"" + key + "\" left out",
                        new IllegalArgumentException("null NBT value"));
                return;
            }
        }
        REPEATS.note("null value for NBT key \"" + key + "\" left out");
    }
}
