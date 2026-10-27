/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.util.BlockPos;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * A RenderChunk's corner (its position) as three ints kept in the RenderChunk, for the visibility
 * search.
 *
 * <p>The search reads, for every chunk it visits, the position (a BlockPos) and, for every
 * neighbour it tests, the bounding box (an AxisAlignedBB): two more objects somewhere else in
 * memory per chunk. RenderChunk.setPosition sets both together, the box from the position to the
 * position plus 16 on each axis, so the ints are enough for both. They are kept with the BlockPos
 * they came from and read again only when the chunk got another one.
 */
final class ChunkCorner {

    // Fields the patcher adds to RenderChunk (absent from the readable-name jar): by handle.
    private static final VarHandle OF; // RenderChunk.sbPosOf: the BlockPos the ints are from
    private static final VarHandle X; // RenderChunk.sbX
    private static final VarHandle Y; // RenderChunk.sbY
    private static final VarHandle Z; // RenderChunk.sbZ

    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            OF = l.findVarHandle(RenderChunk.class, "sbPosOf", Object.class);
            X = l.findVarHandle(RenderChunk.class, "sbX", int.class);
            Y = l.findVarHandle(RenderChunk.class, "sbY", int.class);
            Z = l.findVarHandle(RenderChunk.class, "sbZ", int.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private ChunkCorner() {}

    /** Brings the ints up to the chunk's current position (render thread). */
    static void sync(RenderChunk rc) {
        BlockPos p = rc.position;
        if (OF.get(rc) != p) {
            X.set(rc, p.x);
            Y.set(rc, p.y);
            Z.set(rc, p.z);
            OF.set(rc, p);
        }
    }

    static int x(RenderChunk rc) {
        return (int) X.get(rc);
    }

    static int y(RenderChunk rc) {
        return (int) Y.get(rc);
    }

    static int z(RenderChunk rc) {
        return (int) Z.get(rc);
    }
}
