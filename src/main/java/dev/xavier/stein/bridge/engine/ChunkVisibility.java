/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import net.minecraft.client.renderer.chunk.CompiledChunk;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.util.EnumFacing;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * A chunk's face-to-face visibility as 36 bits kept in the RenderChunk, for the visibility search.
 *
 * <p>The search asks CompiledChunk.isVisible(from, to) for nearly every chunk it visits, and the
 * answer sits three objects away (the CompiledChunk, its SetVisibility, the BitSet and its words),
 * each one somewhere else in memory: on the bench at distance 32 that line was 90% of the search,
 * waiting for memory. A compiled chunk never changes once it is set on the RenderChunk, so the 36
 * answers are read once per CompiledChunk (by the game's own isVisible, so the empty DUMMY answers
 * false as before) and kept in two fields the patcher adds to RenderChunk, next to the ones the
 * search already reads. Bit {@code from + to * 6}, by EnumFacing ordinal, as SetVisibility stores
 * them.
 */
final class ChunkVisibility {

    // Fields the patcher adds to RenderChunk (absent from the readable-name jar): by handle.
    private static final VarHandle OF; // RenderChunk.sbVisOf: the CompiledChunk the bits are from
    private static final VarHandle BITS; // RenderChunk.sbVis

    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            OF = l.findVarHandle(RenderChunk.class, "sbVisOf", Object.class);
            BITS = l.findVarHandle(RenderChunk.class, "sbVis", long.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private ChunkVisibility() {}

    /** The 36 bits of the chunk's current compilation (render thread). */
    static long of(RenderChunk rc) {
        CompiledChunk c = rc.getCompiledChunk();
        if (OF.get(rc) == c) {
            return (long) BITS.get(rc);
        }
        EnumFacing[] f = EnumFacing.values();
        long bits = 0;
        for (int to = 0; to < 6; to++) {
            for (int from = 0; from < 6; from++) {
                if (c.isVisible(f[from], f[to])) {
                    bits |= 1L << (from + to * 6);
                }
            }
        }
        BITS.set(rc, bits);
        OF.set(rc, c);
        return bits;
    }

    /** CompiledChunk.isVisible(from, to) from the bits. */
    static boolean visible(long bits, int from, int to) {
        return (bits >>> (from + to * 6) & 1L) != 0;
    }
}
