/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

package dev.xavier.stein.gfx;

import static dev.xavier.stein.gfx.SteinTessCapture.VERTEX_BYTES;

import dev.xavier.stein.bridge.engine.GLMatrix;

import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.VertexFormat;

import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.IdentityHashMap;

/**
 * Fixed geometry on the GPU for the Tessellator capture: item model display lists (ItemModelCache)
 * and the ranges owned by one object each (sky, stars, clouds), both in one buffer that the
 * recorded draws point to as instances instead of copying vertices every frame.
 *
 * <p>Pipeline: Stein GFX only.
 */
final class SteinStaticGeometry {
    private SteinStaticGeometry() {}

    private static final long STATIC_BYTES = 32L << 20;

    /** Item list being recorded (ItemModelCache): its vertices also go to the fixed buffer. */
    static int recordList;

    private static boolean staticInit;
    private static SteinBuffer staticGeometry;
    private static long staticUsed;

    /** Per display list: first vertex, how many (0 = not registered) and the format bits. */
    private static int[] listFirst = new int[1024];

    private static int[] listCount = new int[1024];
    private static int[] listBits = new int[1024];

    private static long recAddr;
    private static long recCap;
    private static int recVerts;
    private static int recBits;
    private static int recDraws;
    private static boolean recOk;

    private static boolean staticReady() {
        if (staticInit) {
            return staticGeometry != null;
        }
        staticInit = true;
        if (!SteinGfxOptions.ENABLED) {
            return false;
        }
        staticGeometry =
                SteinDevice.get()
                        .createBuffer(
                                new SteinBuffer.Desc(
                                        "entities: items",
                                        STATIC_BYTES,
                                        SteinBuffer.Memory.GPU,
                                        SteinBuffer.Usage.VERTEX));
        return true;
    }

    static SteinBuffer staticGeometry() {
        return staticGeometry;
    }

    /** Before recording the list {@code list} of an item model (inside glNewList). */
    static void listBegin(int list) {
        if (list <= 0 || !staticReady()) {
            return;
        }
        recordList = list;
        recVerts = 0;
        recBits = 0;
        recDraws = 0;
        recOk = true;
    }

    /** After recording the list: its vertices go to the fixed buffer. */
    static void listEnd() {
        int list = recordList;
        if (list == 0) {
            return;
        }
        recordList = 0;
        forget(list);
        long bytes = (long) recVerts * VERTEX_BYTES;
        if (!recOk || recVerts == 0 || staticUsed + bytes > STATIC_BYTES) {
            return;
        }
        staticGeometry.upload(staticUsed, MemoryUtil.memByteBuffer(recAddr, (int) bytes));
        if (list >= listFirst.length) {
            int n = Math.max(list + 1, listFirst.length * 2);
            listFirst = Arrays.copyOf(listFirst, n);
            listCount = Arrays.copyOf(listCount, n);
            listBits = Arrays.copyOf(listBits, n);
        }
        listFirst[list] = (int) (staticUsed / VERTEX_BYTES);
        listCount[list] = recVerts;
        listBits[list] = recBits;
        staticUsed += bytes;
    }

    private static void forget(int list) {
        if (list < listCount.length) {
            listCount[list] = 0;
        }
    }

    /**
     * The draw inside the list being recorded: converted for the copy (and drawn into the list
     * through the original path).
     */
    static void record(Object tess) {
        if (!recOk) {
            return;
        }
        WorldRenderer wr = ((Tessellator) tess).getWorldRenderer();
        int count = wr.getVertexCount();
        if (count == 0) {
            return;
        }
        int mode = wr.getDrawMode();
        int out = SteinVertexConverter.triangles(mode, count);
        SteinCaptureFormat f = SteinCaptureFormat.of(wr.getVertexFormat());
        if (out <= 0 || !f.ok || recDraws > 0 && f.bits != recBits) {
            recOk = false;
            return;
        }
        long need = (long) (recVerts + out) * VERTEX_BYTES;
        if (need > recCap) {
            long cap = Math.max(need, Math.max(64 * 1024, recCap * 2));
            recAddr = SteinNativeBuffer.grow(recAddr, cap);
            recCap = cap;
        }
        ByteBuffer buf = wr.getByteBuffer();
        // Without the vertex color that pulls in the plane (flat): item quads have the same color
        // on all four vertices.
        SteinVertexConverter.convert(
                MemoryUtil.memAddress(buf, 0),
                f,
                mode,
                count,
                recAddr + (long) recVerts * VERTEX_BYTES,
                false);
        recVerts += out;
        recBits = f.bits;
        recDraws++;
    }

    /**
     * In place of an item model's glCallList, with the current matrix: true = recorded as an
     * instance of the fixed geometry (do not call the list).
     */
    static boolean callList(int list) {
        if (!SteinEntityDeferred.capturingDynamic()
                && recordList == 0
                && !GLMatrix.compiling()
                && SteinEntityDeferred.oneShotBegin()) {
            // item outside the pass (the advancement toast): a one-draw pass for it alone
            try {
                return callList(list);
            } finally {
                SteinEntityDeferred.oneShotEnd();
            }
        }
        if (!SteinEntityDeferred.capturingDynamic() || GLMatrix.compiling()) {
            return SteinTessCapture.refuse(GLMatrix.compiling() ? "list" : "outside the pass");
        }
        if (list <= 0 || list >= listCount.length || listCount[list] == 0) {
            SteinGlTrack.forgetNormal(); // unknown item list: it may carry a normal
            SteinEntityDeferred.beforeImmediate();
            return SteinTessCapture.refuse("unknown list");
        }
        if ((listBits[list] & SteinTessCapture.HAS_NORMAL) != 0) {
            SteinGlTrack.forgetNormal();
        }
        try {
            if (SteinTessCapture.texGen()
                    || !SteinEntityDeferred.beginDynamic(listBits[list], false)) {
                SteinEntityDeferred.beforeImmediate();
                return SteinTessCapture.refuse(
                        SteinTessCapture.texGen() ? "texgen" : SteinEntityDeferred.rejectWhy);
            }
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
        if (!SteinEntityDeferred.commitStatic(listFirst[list], listCount[list])) {
            SteinEntityDeferred.beforeImmediate();
            return SteinTessCapture.refuse("static geometry full");
        }
        return true;
    }

    // ---- fixed geometry with an owner (sky, stars, clouds): one range per owner, rewritten when
    // it changes ----

    /** Per owner: {first vertex, capacity, vertices, format bits}. */
    private static final IdentityHashMap<Object, int[]> SLOTS = new IdentityHashMap<>();

    private static long slotAddr;
    private static long slotCap;

    /**
     * The geometry of owner {@code owner} ({@code count} vertices in game format {@code format},
     * mode {@code mode}, in {@code src}) converted into the fixed geometry on the GPU, in a range
     * of its own (reused while it fits). false = not suitable (a format or mode we do not record,
     * buffer full): the owner draws as usual.
     */
    static boolean slot(Object owner, long src, Object format, int mode, int count) {
        if (!staticReady()) {
            return false;
        }
        SteinCaptureFormat f = SteinCaptureFormat.of((VertexFormat) format);
        int out = SteinVertexConverter.triangles(mode, count);
        if (!f.ok || out <= 0) {
            SLOTS.remove(owner);
            return false;
        }
        long bytes = (long) out * VERTEX_BYTES;
        if (bytes > slotCap) {
            slotAddr = SteinNativeBuffer.grow(slotAddr, bytes);
            slotCap = bytes;
        }
        SteinVertexConverter.convert(src, f, mode, count, slotAddr, false);
        int[] s = SLOTS.get(owner);
        if (s == null || s[1] < out) {
            // Grew (clouds seen from another side): headroom for the next.
            int cap = s == null ? out : out * 2;
            if (staticUsed + (long) cap * VERTEX_BYTES > STATIC_BYTES) {
                SLOTS.remove(owner);
                return false;
            }
            s = new int[] {(int) (staticUsed / VERTEX_BYTES), cap, 0, 0};
            staticUsed += (long) cap * VERTEX_BYTES;
            SLOTS.put(owner, s);
        }
        // glBufferSubData on the range the GPU may still be reading from the previous frame: OpenGL
        // synchronizes by itself (rare: only when the owner changes its geometry). On Vulkan, a
        // double range.
        staticGeometry.upload(
                (long) s[0] * VERTEX_BYTES, MemoryUtil.memByteBuffer(slotAddr, (int) bytes));
        s[2] = out;
        s[3] = f.bits;
        return true;
    }

    /**
     * In place of the owner's draw, with the current state and matrix: true = recorded (do not
     * draw).
     */
    static boolean drawSlot(Object owner) {
        if (!SteinEntityDeferred.capturingDynamic() || GLMatrix.compiling()) {
            return SteinTessCapture.refuse(GLMatrix.compiling() ? "list" : "outside the pass");
        }
        int[] s = SLOTS.get(owner);
        if (s == null || s[2] == 0) {
            SteinEntityDeferred.beforeImmediate();
            return SteinTessCapture.refuse("vbo without a copy");
        }
        try {
            if (SteinTessCapture.texGen() || !SteinEntityDeferred.beginDynamic(s[3], false)) {
                SteinEntityDeferred.beforeImmediate();
                return SteinTessCapture.refuse(
                        SteinTessCapture.texGen() ? "texgen" : SteinEntityDeferred.rejectWhy);
            }
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
        if (!SteinEntityDeferred.commitStatic(s[0], s[2])) {
            SteinEntityDeferred.beforeImmediate();
            return false;
        }
        return true;
    }

    /** Resource reload: the item lists are deleted and rebuilt; the fixed buffer starts over. */
    static void clearLists() {
        Arrays.fill(listCount, 0);
        staticUsed = 0;
    }
}
