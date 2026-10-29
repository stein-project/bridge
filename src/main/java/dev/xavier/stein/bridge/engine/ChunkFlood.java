/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import net.minecraft.client.renderer.chunk.VisGraph;
import net.minecraft.util.EnumFacing;

import java.util.BitSet;
import java.util.EnumSet;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Set;

/**
 * The body of VisGraph's flood fill (bhw.a(int)), with the queue in an int array instead of a
 * LinkedList and the six directions read from one array instead of EnumFacing.values().
 *
 * <p>The flood fill runs from every free block of a section when a chunk is built (the build
 * threads, and the render thread for the chunks it builds itself) and from the camera's block
 * whenever the camera's visible faces are looked up again (ChunkCull, each time the camera enters
 * another block). The game's version allocates, per block reached, a copy of the six directions and
 * a LinkedList node: the camera's lookup alone was 14% of the render thread's allocation in
 * singleplayer.
 *
 * <p>Same result: the same blocks visited in the same order (a FIFO queue fed in the order of
 * EnumFacing.values()), the same visited bits written and the same faces collected, through the
 * game's own neighbour index and edge test. Each block enters the queue at most once (it is marked
 * before it enters), so 4096 slots hold any section. {@code -Dsteinbridge.opt.vanillaCull=true}
 * keeps the game's queue.
 */
final class ChunkFlood {

    // Read here, not through ChunkGraph: the build threads may reach this class first.
    private static final boolean VANILLA = EngineOptions.VANILLA_CULL;

    /** The section's block count, the most a flood fill can queue. */
    private static final int SECTION_BLOCKS = 4096;

    private static final EnumFacing[] FACINGS = EnumFacing.values();

    /** One queue per thread: the build threads fill sections at the same time. */
    private static final ThreadLocal<int[]> QUEUE =
            ThreadLocal.withInitial(() -> new int[SECTION_BLOCKS]);

    private ChunkFlood() {}

    /** The six directions in declaration order; never written. */
    static EnumFacing[] facings() {
        return FACINGS;
    }

    /** VisGraph.floodFill(start): the faces of the section reached from the block {@code start}. */
    static Set<EnumFacing> fill(VisGraph graph, int start) {
        if (VANILLA) {
            return fillVanilla(graph, start);
        }
        Set<EnumFacing> faces = EnumSet.noneOf(EnumFacing.class);
        BitSet visited = graph.field_178612_d;
        int[] queue = QUEUE.get();
        int head = 0;
        int tail = 0;
        queue[tail++] = start;
        visited.set(start, true);
        while (head < tail) {
            int i = queue[head++];
            graph.func_178610_a(i, faces); // addEdges
            for (EnumFacing f : FACINGS) {
                int j = graph.func_178603_a(i, f); // getNeighborIndexAtFace
                if (j >= 0 && !visited.get(j)) {
                    visited.set(j, true);
                    queue[tail++] = j;
                }
            }
        }
        return faces;
    }

    /** The game's loop as it is (the A/B reference of the option). */
    private static Set<EnumFacing> fillVanilla(VisGraph graph, int start) {
        Set<EnumFacing> faces = EnumSet.noneOf(EnumFacing.class);
        Queue<Integer> queue = new LinkedList<>();
        queue.add(start);
        graph.field_178612_d.set(start, true);
        while (!queue.isEmpty()) {
            int i = queue.poll();
            graph.func_178610_a(i, faces);
            for (EnumFacing f : EnumFacing.values()) {
                int j = graph.func_178603_a(i, f);
                if (j >= 0 && !graph.field_178612_d.get(j)) {
                    graph.field_178612_d.set(j, true);
                    queue.add(j);
                }
            }
        }
        return faces;
    }
}
