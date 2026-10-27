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

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Whether each RenderChunk's compilation has tile entities, kept in the RenderChunk, and a version
 * that goes up only when that changes for some chunk.
 *
 * <p>The entity loops' filtered copy ({@link ChunkInfoFilter}) keeps the chunks with an entity or
 * with a tile entity in the compilation, and was rebuilt whenever any chunk finished compiling — on
 * almost every frame while the world loads at distance 32, walking 10 to 14 thousand chunks. A
 * compilation changes only in RenderChunk.setCompiledChunk (on the build threads) and
 * stopCompileTask (which puts the empty one back); both calls now also queue the chunk, and the
 * render thread, before using the copy, reads each queued chunk's tile entities once and raises the
 * version only if the answer changed. A chunk compiled after that read joins on the next frame.
 */
public final class TileChunks {

    private static final VarHandle HAS; // RenderChunk.sbTiles (a field the patcher adds)

    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            HAS = l.findVarHandle(RenderChunk.class, "sbTiles", boolean.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static final ConcurrentLinkedQueue<RenderChunk> CHANGED = new ConcurrentLinkedQueue<>();

    /** Render thread: goes up when some chunk gained or lost its tile entities. */
    static int version;

    private TileChunks() {}

    /** RenderChunk.setCompiledChunk (ChunkRenderWorker, on a build thread). */
    public static void setCompiled(Object chunk, Object compiled) {
        RenderChunk rc = (RenderChunk) chunk;
        rc.setCompiledChunk((CompiledChunk) compiled);
        CHANGED.add(rc);
    }

    /** RenderChunk.stopCompileTask (setPosition, deleteGlResources). */
    public static void stopCompile(Object chunk) {
        RenderChunk rc = (RenderChunk) chunk;
        rc.stopCompileTask();
        CHANGED.add(rc);
    }

    /** Reads the chunks compiled since the last call (render thread). */
    static void update() {
        RenderChunk rc;
        while ((rc = CHANGED.poll()) != null) {
            boolean has = !rc.getCompiledChunk().getTileEntities().isEmpty();
            if (has != (boolean) HAS.get(rc)) {
                HAS.set(rc, has);
                version++;
            }
        }
    }

    /** The chunk's answer as of the last {@link #update}. */
    static boolean has(RenderChunk rc) {
        return (boolean) HAS.get(rc);
    }
}
