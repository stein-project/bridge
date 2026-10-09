/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import dev.xavier.stein.bridge.core.Log;

import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;

/**
 * Names on the GPU: the glyphs of the cached texts ({@link SteinTextCache}) live in a GPU buffer,
 * and each name per frame becomes just one record (the instance: label matrix turned to face the
 * camera, scale, color, lightmap, options) and the indirect commands that point to its runs. The
 * vertex shader ({@code entity_text.vert}) expands the record over the resident run; the render
 * thread no longer copies any glyph vertex.
 *
 * <p><b>The run.</b> An assembled text (a cache {@code Entry}: text, shadow, position and input
 * color) is uploaded once, on its second use (text that appears only once, like the F3 numbers, is
 * never uploaded), as the glyph vertices already in triangles in the 48-byte format used here:
 * position and texture as the assembly gave them, each glyph's color as floats (the same bits the
 * copy path would put in each batch's instance: the pixel is the same), the color as bytes and a
 * marker. Consecutive batches with the same texture become a single run (the color is in the
 * vertex); switching the font page (unicode) opens another. At draw time, each run is a command
 * (length, first vertex in this buffer, the record).
 *
 * <p><b>The record.</b> The instance of the usual dynamic draw ({@link
 * SteinEntityDeferred#commitText}), written once per label: the name's two passes (see-through,
 * without depth, and the normal one) and the text's shadow have the same matrix, color (white: the
 * real one is in the vertex), lightmap and options, so the second compares with the last write and
 * reuses the index. The same goes for the name's translucent background: its Tessellator quad
 * becomes a resident run (6 vertices with the "background" marker: no texture, byte color as the
 * Tessellator would give) and writes the record that the following text reuses. Sneaking and
 * see-through are pipeline state (depth), in the group key, as before: the draw order among the
 * groups stays the same as on the copy path.
 *
 * <p><b>GPU memory.</b> One ring per generation: the runs go one after another; when the end is
 * reached, it wraps to the start and the runs the write passes over lose their residency (the text
 * is uploaded again on the next use). Text that leaves the cache releases the run's owner (the
 * space comes back when the ring passes: nothing leaks). A run used by a draw that has not been
 * flushed yet is not overwritten: the new text stays on the copy path for that frame. A resource
 * reload (font and new pages) empties everything, along with the cache.
 *
 * <p>Only in the entity pass (names, signs), with indirect drawing and a persistent buffer (4.4+);
 * on 4.1 and with {@code -Dsteinbridge.gfx.gpuText=false} the cached text keeps copying the
 * vertices, as before.
 *
 * <p>Pipeline: Stein GFX only.
 */
public final class SteinTextGpu {

    public static final boolean REQUESTED = SteinGfxOptions.GPU_TEXT;

    /**
     * Also in the strict-order passes (HUD, screens, F3) when they draw in segments without
     * flushing ({@code strictSegments}): text that repeats between frames (compass, hotbar, status,
     * still coordinates) with no vertex copying. {@code -Dsteinbridge.gfx.gpuTextHud=false}: only
     * in the entity pass, as before.
     */
    private static final boolean HUD = SteinGfxOptions.GPU_TEXT_HUD;

    private static final boolean STATS = SteinGfxOptions.DEFERRED_STATS;

    /**
     * Vertex used here: position 3 floats, texture 2 floats, color 4 floats, color 4 ubytes, normal
     * 3 bytes + marker, padding.
     */
    static final int VERTEX_BYTES = 48;

    private static final int ARENA_BYTES = 16 << 20;

    private SteinTextGpu() {}

    private static boolean initDone;
    private static boolean ready;
    private static SteinBuffer arena;
    private static SteinShader vertex;
    private static SteinVertexLayout layout;

    /**
     * Turns on the first time a text or background asks (inside the pass, with the dynamic state
     * ready).
     */
    private static boolean ready() {
        if (initDone) {
            return ready;
        }
        initDone = true;
        if (!REQUESTED || !SteinTextCache.REQUESTED) {
            return false;
        }
        SteinDevice dev = SteinDevice.get();
        SteinCaps caps = dev.caps();
        if (!caps.multiDrawIndirect || !caps.persistentMapping) {
            Log.info(
                    "gfx",
                    "names on the GPU off: they need indirect draw and a persistent buffer"
                            + " (4.4+)");
            return false;
        }
        arena =
                dev.createBuffer(
                        new SteinBuffer.Desc(
                                "entities: resident glyphs",
                                ARENA_BYTES,
                                SteinBuffer.Memory.GPU,
                                SteinBuffer.Usage.VERTEX));
        vertex = SteinShader.load(SteinShader.Stage.VERTEX, "entity_text.vert");
        SteinVertexLayout.Builder b =
                SteinVertexLayout.builder()
                        .binding(0, VERTEX_BYTES, false)
                        .attribute(0, 0, SteinFormat.FLOAT3, 0)
                        .attribute(1, 0, SteinFormat.FLOAT2, 12)
                        .attribute(11, 0, SteinFormat.FLOAT4, 20)
                        .attribute(12, 0, SteinFormat.UBYTE4_NORM, 36)
                        .attribute(2, 0, SteinFormat.BYTE4_NORM, 40)
                        .binding(1, SteinEntityDeferred.POSE_INSTANCE_BYTES, true);
        for (int i = 0; i < 8; i++) {
            b.attribute(3 + i, 1, SteinFormat.FLOAT4, i * 16);
        }
        layout = b.build();
        if (!SteinEntityDeferred.textPipelineValid()) {
            Log.info("gfx", "names on the GPU off: the resident text shader did not compile");
            return false;
        }
        ready = true;
        Log.info("gfx", "names on the GPU: resident glyphs, one record per name");
        return true;
    }

    /** Resident text and background are valid now: entity pass recording, with everything ready. */
    static boolean active() {
        // Block being recorded for retention (SteinTileRetain): the text goes through the copy
        // path, which the retention stores and re-emits by itself; the resident run may lose its
        // residency when the ring passes, and the record reused from commitText does not belong to
        // the block.
        return (SteinEntityDeferred.inEntityPass() || HUD && SteinEntityDeferred.strictSegments())
                && !SteinTileRetain.recording
                && ready();
    }

    static SteinBuffer arena() {
        return arena;
    }

    static SteinShader vertexShader() {
        return vertex;
    }

    static SteinVertexLayout layout() {
        return layout;
    }

    // ---- the GPU ring ----

    /** A stretch of the ring: a run of a text (or of a background). */
    static final class Alloc {
        final long start;
        final long bytes;

        /**
         * The owner (the cache assembly or the background); null after it left (the stretch waits
         * for the ring to pass).
         */
        Object owner;

        /** False after the write passed here (or after a clear): the owner is uploaded again. */
        boolean valid = true;

        /** The flush in which it was last drawn ({@link SteinEntityDeferred#flushSerial}). */
        long drawn = -1;

        Alloc(long start, long bytes, Object owner) {
            this.start = start;
            this.bytes = bytes;
            this.owner = owner;
        }

        int firstVertex() {
            return (int) (start / VERTEX_BYTES);
        }
    }

    /** From oldest to newest; the addresses grow until the wrap. */
    private static final ArrayDeque<Alloc> fifo = new ArrayDeque<>();

    private static long head;
    private static long live;

    /**
     * A stretch of {@code bytes} (a multiple of {@link #VERTEX_BYTES}) with {@code data} already on
     * the GPU; null if it does not fit now (the ring would have to pass over a run that a pending
     * draw still reads).
     */
    static Alloc upload(ByteBuffer data, Object owner) {
        long bytes = data.remaining();
        if (bytes <= 0 || bytes > ARENA_BYTES) {
            return null;
        }
        long at = head;
        boolean wrap = at + bytes > ARENA_BYTES;
        if (wrap) {
            // The end of the buffer stays unused until the next wrap; what lives there (from an old
            // lap) leaves first, so the queue stays in address order from the start.
            while (!fifo.isEmpty() && fifo.peekFirst().start >= head) {
                if (!pop()) {
                    return refuse();
                }
            }
            at = 0;
        }
        while (!fifo.isEmpty()) {
            Alloc f = fifo.peekFirst();
            if (f.start < at + bytes && f.start + f.bytes > at) {
                if (!pop()) {
                    return refuse();
                }
            } else {
                break;
            }
        }
        arena.upload(at, data);
        Alloc a = new Alloc(at, bytes, owner);
        fifo.addLast(a);
        head = at + bytes;
        live += bytes;
        SteinTextGpuStats.uploads++;
        SteinTextGpuStats.uploadBytes += bytes;
        return a;
    }

    private static Alloc refuse() {
        SteinTextGpuStats.refused++;
        return null;
    }

    /**
     * Releases the oldest run; false if a pending draw still reads from it (it stays, and the
     * request does not fit now).
     */
    private static boolean pop() {
        Alloc f = fifo.peekFirst();
        if (f.drawn == SteinEntityDeferred.flushSerial()) {
            return false;
        }
        fifo.pollFirst();
        f.valid = false;
        f.owner = null;
        live -= f.bytes;
        return true;
    }

    /** The run was drawn now (valid until the next flush). */
    static void drawn(Alloc a) {
        a.drawn = SteinEntityDeferred.flushSerial();
    }

    /**
     * The owner left (the cache): the stretch stays until the ring passes, without holding the
     * owner.
     */
    static void release(Alloc a) {
        if (a != null) {
            a.owner = null;
        }
    }

    /**
     * Resource reload: nothing resident is valid anymore (outside the pass: no pending draw reads
     * from here).
     */
    static void reset() {
        for (Alloc a : fifo) {
            a.valid = false;
            a.owner = null;
        }
        fifo.clear();
        head = 0;
        live = 0;
        SteinTextBackgrounds.clear();
    }

    // ---- writing the runs ----

    private static ByteBuffer scratch =
            ByteBuffer.allocateDirect(64 * 1024).order(ByteOrder.nativeOrder());

    /** A scratch buffer with {@code bytes} free (zeroed), from position 0 up to the limit. */
    static ByteBuffer scratch(int bytes) {
        if (scratch.capacity() < bytes) {
            scratch =
                    ByteBuffer.allocateDirect(Math.max(bytes, scratch.capacity() * 2))
                            .order(ByteOrder.nativeOrder());
        }
        scratch.clear().limit(bytes);
        MemoryUtil.memSet(MemoryUtil.memAddress(scratch), 0, bytes);
        return scratch;
    }

    private static ByteBuffer quad =
            ByteBuffer.allocateDirect(6 * SteinTessCapture.VERTEX_BYTES)
                    .order(ByteOrder.nativeOrder());

    /**
     * Where {@link SteinTessCapture} converts the background quad (6 vertices of 32 bytes, zeroed:
     * the fields the format lacks stay 0 and the key comparison is exact). Separate from {@link
     * #scratch}, which the upload uses.
     */
    static long quadScratch() {
        long a = MemoryUtil.memAddress(quad);
        MemoryUtil.memSet(a, 0, quad.capacity());
        return a;
    }

    /**
     * A text vertex: from {@code src} ({@link SteinTessCapture} format, 32 bytes: position and
     * texture) to {@code dst} (48 bytes, already zeroed), with the batch's color as floats.
     */
    static void putGlyph(long src, long dst, float r, float g, float b, float a) {
        MemoryUtil.memCopy(src, dst, 20);
        MemoryUtil.memPutFloat(dst + 20, r);
        MemoryUtil.memPutFloat(dst + 24, g);
        MemoryUtil.memPutFloat(dst + 28, b);
        MemoryUtil.memPutFloat(dst + 32, a);
    }

    // ---- the name background ----

    /**
     * The Tessellator of a name's background: in the name drain, a quad with only position and
     * color (texture off). {@link SteinTessCapture} asks this before reserving the vertices in the
     * frame's ring.
     */
    static boolean wantsBackground(int mode, int count, int bits) {
        return mode == GL11.GL_QUADS
                && count == 4
                && bits == SteinTessCapture.HAS_COLOR
                && SteinEntityDeferred.drainingNames()
                && !SteinGameState.texture2D(0)
                && active();
    }

    /**
     * The background already converted to triangles in {@code tmp} ({@code verts} vertices of 32
     * bytes, absent fields zeroed), with {@code beginDynamic} done: records the command pointing to
     * the background's resident run. False = not resident yet (first use) or no room now: continues
     * on the copy path.
     */
    static boolean background(long tmp, int verts) {
        Alloc a = SteinTextBackgrounds.lookup(tmp, verts);
        if (a == null) {
            return false;
        }
        if (!SteinEntityDeferred.commitText(a.firstVertex(), verts, true)) {
            return false;
        }
        drawn(a);
        SteinTextGpuStats.backgrounds++;
        return true;
    }

    /** The direct label's background was drawn (busy in this flush). */
    static void backgroundDrawn(Alloc a) {
        drawn(a);
        SteinTextGpuStats.backgrounds++;
    }

    // ---- numbers ----

    /**
     * Together with the cache numbers (every 5 s, with {@code
     * -Dsteinbridge.gfx.deferredStats=true}).
     */
    static String stats() {
        if (!STATS || !ready) {
            return null;
        }
        return SteinTextGpuStats.report(live, fifo.size(), ARENA_BYTES);
    }
}
