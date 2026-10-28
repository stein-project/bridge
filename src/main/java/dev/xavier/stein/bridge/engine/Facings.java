/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import net.minecraft.util.EnumFacing;

/**
 * One shared EnumFacing.values() array for the game's hot loops that only read it.
 *
 * <p>values() clones the enum's array on every call: 10 to 16% of the allocation of the render
 * thread, the chunk build threads and the integrated server (light updates) on the benchmark. The
 * patcher sends the call here only in methods where the array is walked by a for-each loop or only
 * its length is read (checked in the bytecode: the result goes straight to a local that is only
 * indexed, or to arraylength), so nothing ever writes to or keeps the shared copy: the loops see
 * the same six values in the same order. The call sites are listed in redirects-opt.tsv. {@code
 * -Dsteinbridge.opt.vanillaFacings=true} hands out a fresh copy again.
 */
public final class Facings {

    private static final boolean VANILLA = EngineOptions.VANILLA_FACINGS;

    private static final EnumFacing[] VALUES = EnumFacing.values();

    private Facings() {}

    /** EnumFacing.values() at a call site that never writes to the array. */
    public static Object values() {
        return VANILLA ? EnumFacing.values() : VALUES;
    }
}
