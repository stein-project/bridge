/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.world.pathfinder.NodeProcessor;

import java.io.DataInputStream;
import java.io.IOException;

/**
 * Two leaks of the integrated server: the stream of every chunk read from disk, and the area each
 * mob's path finder searched last.
 *
 * <ul>
 *   <li>AnvilChunkLoader.loadChunk reads the chunk's NBT from the region file's stream and never
 *       closes it. The stream inflates through a native zlib Inflater, whose memory outside the
 *       heap goes back only when the collector happens to clean the stream up; walking through new
 *       chunks kept thousands of them waiting. The stream is closed once the NBT is read (the
 *       region file itself stays open in RegionFileCache, as before).
 *   <li>A path search hands the node processor a ChunkCache of the area (the world and an array of
 *       its chunks) and postProcess leaves it there: every mob with a path finder kept the chunks
 *       around its last search alive, also after they were unloaded. The reference is let go after
 *       postProcess; the next search sets its own, so the search itself is unchanged.
 * </ul>
 *
 * Nothing the game computes changes: the chunk read and the path found are the same. {@code
 * -Dsteinbridge.opt.vanillaWorldLeaks=true} brings back 1.8.9's behaviour.
 */
public final class WorldLeaks {

    private WorldLeaks() {}

    private static final boolean VANILLA = NetFixOptions.VANILLA_WORLD_LEAKS;

    /** CompressedStreamTools.read(stream) in AnvilChunkLoader.loadChunk. */
    public static Object readChunk(Object stream) throws IOException {
        DataInputStream in = (DataInputStream) stream;
        if (VANILLA) {
            return CompressedStreamTools.read(in);
        }
        try (DataInputStream closing = in) {
            return CompressedStreamTools.read(closing);
        }
    }

    /** NodeProcessor.postProcess() at the end of PathFinder's search. */
    public static void searchDone(Object processor) {
        NodeProcessor p = (NodeProcessor) processor;
        p.postProcess();
        if (!VANILLA) {
            p.blockaccess = null;
        }
    }
}
