/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import dev.xavier.stein.bridge.core.Log;

import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.chunk.RenderChunk;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * setupTerrain's "rebuildNear" loop over the visible chunks it can act on, in the list's order.
 *
 * <p>After the search, on every frame, the game walks the whole visible list and acts on a chunk
 * only if it is marked for a build or was already queued (chunksToUpdate); the queued ones go back
 * to the queue in the list's order, the ones next to the camera are built on the spot. At distance
 * 32 that is 10 to 14 thousand chunks walked on every frame for the few hundred waiting, 13% of the
 * render thread on the bench.
 *
 * <p>The loop now walks a list with those few hundred, in the same order: on a new visible list (a
 * search) it is made by walking it once, as the game does, writing each chunk's position in the
 * list into the chunk; on the other frames it is the previous one plus the chunks marked since
 * (ViewFrustum.markBlocksForUpdate, through {@link ChunkSync#markDirty}), put in by that position.
 * The game's loop body still tests each one, so a chunk that no longer waits is skipped as before.
 * A chunk only becomes marked there, when a build task is cancelled (which may happen on a build
 * thread: the next frame walks the whole list again) or when it is created (a new view frustum,
 * hence a new list). {@code -Dsteinbridge.opt.vanillaRebuildNear=true} turns it off; {@code
 * -Dsteinbridge.opt.checkRebuildNear=true} also walks the whole list and compares.
 */
public final class RebuildNear {

    private static final boolean OFF = EngineOptions.VANILLA_REBUILD_NEAR;
    private static final boolean CHECK = !OFF && EngineOptions.CHECK_REBUILD_NEAR;

    // Fields the patcher adds to RenderChunk (absent from the readable-name jar): by handle.
    private static final VarHandle SLOT; // RenderChunk.sbSlot: position in the list below
    private static final VarHandle SLOT_LIST; // RenderChunk.sbSlotList

    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            SLOT = l.findVarHandle(RenderChunk.class, "sbSlot", int.class);
            SLOT_LIST = l.findVarHandle(RenderChunk.class, "sbSlotList", Object.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    // Render thread.
    private static Thread renderThread;
    private static Set<?> queued;
    private static Object walkedList;
    private static int walkedSize;
    private static ArrayList<Object> candidates = new ArrayList<>();
    private static ArrayList<Object> spare = new ArrayList<>();
    private static final ArrayList<RenderChunk> MARKED = new ArrayList<>();
    private static volatile boolean markedElsewhere;

    private static final CheckReport REPORT = new CheckReport(5_000_000_000L);

    private RebuildNear() {}

    /**
     * Sets.newLinkedHashSet() in setupTerrain: the game is about to replace chunksToUpdate, and the
     * loop then reads the previous one.
     */
    public static Object newSet() {
        RenderGlobal rg = ChunkSearch.currentRg();
        queued = OFF || rg == null ? null : rg.chunksToUpdate;
        return new LinkedHashSet<>();
    }

    /** A chunk marked for a build on the render thread (ChunkSync.markDirty). */
    static void marked(Object chunk) {
        if (OFF) {
            return;
        }
        if (Thread.currentThread() == renderThread) {
            MARKED.add((RenderChunk) chunk);
        } else {
            markedElsewhere = true;
        }
    }

    /** RenderChunk.setNeedsUpdate in ChunkCompileTaskGenerator.finish (a cancelled build). */
    public static void markFromTask(Object chunk, boolean flag) {
        ((RenderChunk) chunk).setNeedsUpdate(flag);
        if (flag) {
            marked(chunk);
        }
    }

    /** List.iterator() of the rebuildNear loop over renderInfos. */
    public static Object iterator(Object listObj) {
        List<?> src = (List<?>) listObj;
        Set<?> old = queued;
        queued = null;
        if (old == null) {
            return src.iterator();
        }
        renderThread = Thread.currentThread();
        boolean elsewhere = markedElsewhere;
        markedElsewhere = false;
        if (src != walkedList || src.size() != walkedSize || elsewhere) {
            walk(src, old);
        } else {
            update(src, old);
        }
        MARKED.clear();
        if (CHECK) {
            compare(src, old);
        }
        return candidates.iterator();
    }

    /** The whole list, as the game's loop: the chunks it acts on, and each one's position. */
    private static void walk(List<?> src, Set<?> old) {
        ArrayList<Object> out = spare;
        out.clear();
        for (int i = 0, n = src.size(); i < n; i++) {
            Object info = src.get(i);
            RenderChunk rc = (RenderChunk) ChunkGraph.INFO_CHUNK.get(info);
            SLOT.set(rc, i);
            SLOT_LIST.set(rc, src);
            if (rc.isNeedsUpdate() || old.contains(rc)) {
                out.add(info);
            }
        }
        swap(out);
        walkedList = src;
        walkedSize = src.size();
    }

    /** The previous candidates still waiting, merged with the visible chunks marked since. */
    private static void update(List<?> src, Set<?> old) {
        ArrayList<Object> out = spare;
        out.clear();
        ArrayList<Object> prev = candidates;
        int m = MARKED.size();
        if (m > 1) {
            MARKED.sort(RebuildNear::bySlot);
        }
        int j = 0;
        int lastSlot = -1;
        for (int i = 0, n = prev.size(); i <= n; i++) {
            Object info = i < n ? prev.get(i) : null;
            int slot = info == null ? Integer.MAX_VALUE : slotOf(info);
            // the marked chunks in this list that come before the next previous candidate
            for (; j < m; j++) {
                RenderChunk rc = MARKED.get(j);
                if (SLOT_LIST.get(rc) != src) {
                    continue; // not visible: the game's loop does not see it either
                }
                int s = (int) SLOT.get(rc);
                if (s > slot) {
                    break;
                }
                if (s != lastSlot && s != slot && rc.isNeedsUpdate()) {
                    out.add(src.get(s));
                    lastSlot = s;
                }
            }
            if (info != null) {
                RenderChunk rc = (RenderChunk) ChunkGraph.INFO_CHUNK.get(info);
                if (slot != lastSlot && (rc.isNeedsUpdate() || old.contains(rc))) {
                    out.add(info);
                    lastSlot = slot;
                }
            }
        }
        swap(out);
    }

    private static void swap(ArrayList<Object> out) {
        spare = candidates;
        candidates = out;
    }

    private static int slotOf(Object info) {
        return (int) SLOT.get(ChunkGraph.INFO_CHUNK.get(info));
    }

    private static int bySlot(RenderChunk a, RenderChunk b) {
        return Integer.compare((int) SLOT.get(a), (int) SLOT.get(b));
    }

    /** The game's walk against the candidates, both filtered by the loop's own test. */
    private static void compare(List<?> src, Set<?> old) {
        ArrayList<Object> game = new ArrayList<>();
        for (Object info : src) {
            RenderChunk rc = (RenderChunk) ChunkGraph.INFO_CHUNK.get(info);
            if (rc.isNeedsUpdate() || old.contains(rc)) {
                game.add(info);
            }
        }
        ArrayList<Object> ours = new ArrayList<>();
        for (Object info : candidates) {
            RenderChunk rc = (RenderChunk) ChunkGraph.INFO_CHUNK.get(info);
            if (rc.isNeedsUpdate() || old.contains(rc)) {
                ours.add(info);
            }
        }
        if (REPORT.compared(game.equals(ours))) {
            Log.info(
                    "rebuildnear",
                    "DIVERGENCE: %d chunks here x %d in the game",
                    ours.size(),
                    game.size());
        }
        REPORT.maybeReport("rebuildnear");
    }
}
