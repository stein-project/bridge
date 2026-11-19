/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.diag;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.BlockPos;
import net.minecraft.world.World;

import java.io.IOException;
import java.io.Writer;

/**
 * The block region of a physics trace scene: the dump written when the scene starts (the {@code B}
 * record) and, on every tick boundary, the cells that changed since the last look ({@code X}
 * records). A replay that starts from the dump and applies the changes at the same boundaries sees
 * the blocks the client saw, so scenes where blocks change (pistons, {@code /setblock} during a
 * scene, columns arriving or leaving) can be replayed. Only built while the recorder is on.
 */
final class PhysicsTraceBlocks {

    private final int x0;
    private final int y0;
    private final int z0;
    private final int sx;
    private final int sy;
    private final int sz;

    /** The raw state of every cell as last seen, in y, z, x order. */
    private final int[] cells;

    /** Reused for every lookup: the scan runs over the whole region twice per tick. */
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

    PhysicsTraceBlocks(int x0, int y0, int z0, int x1, int y1, int z1) {
        this.x0 = x0;
        this.y0 = y0;
        this.z0 = z0;
        this.sx = x1 - x0 + 1;
        this.sy = y1 - y0 + 1;
        this.sz = z1 - z0 + 1;
        this.cells = new int[sx * sy * sz];
    }

    /**
     * The raw state of a cell: id << 4 | metadata, as the trace format writes it (an unloaded
     * column and cells outside 0..255 read as air, 0, as the client sees them).
     */
    private int raw(World w, int x, int y, int z) {
        IBlockState s = w.getBlockState(pos.set(x, y, z));
        Block k = s.getBlock();
        return (Block.getIdFromBlock(k) << 4) | (k.getMetaFromState(s) & 15);
    }

    /**
     * Reads the whole region and writes it as "B x0 y0 z0 sx sy sz" followed by run-length pairs
     * "count*raw" (raw in hex) in y, z, x order.
     */
    void dump(World w, Writer out) throws IOException {
        StringBuilder b = new StringBuilder(4096);
        b.append("B ").append(x0).append(' ').append(y0).append(' ').append(z0).append(' ');
        b.append(sx).append(' ').append(sy).append(' ').append(sz);
        int run = 0;
        int last = -1;
        int i = 0;
        for (int y = y0; y < y0 + sy; y++) {
            for (int z = z0; z < z0 + sz; z++) {
                for (int x = x0; x < x0 + sx; x++) {
                    int raw = raw(w, x, y, z);
                    cells[i++] = raw;
                    if (raw == last) {
                        run++;
                        continue;
                    }
                    if (run > 0) {
                        b.append(' ').append(run).append('*').append(Integer.toHexString(last));
                    }
                    last = raw;
                    run = 1;
                }
            }
        }
        if (run > 0) {
            b.append(' ').append(run).append('*').append(Integer.toHexString(last));
        }
        b.append('\n');
        out.write(b.toString());
    }

    /**
     * Writes one "X tick when x y z raw" record per cell that changed since the last look, and
     * remembers the new states. {@code when} is {@code b} for changes found before the tick (what
     * the packets applied since the previous tick) and {@code a} for changes found after it (what
     * the client's own world tick did, such as a piston finishing its move).
     */
    void diff(World w, Writer out, long tick, char when) throws IOException {
        int i = 0;
        for (int y = y0; y < y0 + sy; y++) {
            for (int z = z0; z < z0 + sz; z++) {
                for (int x = x0; x < x0 + sx; x++) {
                    int raw = raw(w, x, y, z);
                    if (raw != cells[i]) {
                        cells[i] = raw;
                        out.write(
                                "X "
                                        + tick
                                        + ' '
                                        + when
                                        + ' '
                                        + x
                                        + ' '
                                        + y
                                        + ' '
                                        + z
                                        + ' '
                                        + Integer.toHexString(raw)
                                        + '\n');
                    }
                    i++;
                }
            }
        }
    }
}
