/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import dev.xavier.stein.bridge.core.Log;
import dev.xavier.stein.gfx.SteinGfxOptions;
import dev.xavier.stein.gfx.SteinTerrain;
import dev.xavier.stein.gfx.gl.SteinGlFrame;

import net.minecraft.client.renderer.ChunkRenderContainer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.VboRenderList;
import net.minecraft.client.renderer.VertexBufferUploader;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.client.renderer.vertex.VertexBuffer;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.util.EnumWorldBlockLayer;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL44;
import org.lwjgl.system.MemoryUtil;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Terrain per region: the chunks' geometry goes into large buffers, one per region of 8 x 8 chunk
 * columns (128 x 256 x 128 blocks), and each layer of a region goes out in one glMultiDrawArrays.
 *
 * <p>In the game (VBO path), each chunk has one buffer per layer: on every build, a glBufferData on
 * the render thread (the driver allocates and copies), and, every frame, for each chunk: push,
 * translate, multMatrix, bind, 4 pointers, draw, pop — thousands of calls at a high render
 * distance.
 *
 * <p>Here:
 *
 * <ul>
 *   <li>upload: the region buffers live in GPU memory (glBufferStorage, GL 4.4) and each upload is
 *       a glBufferSubData into a fresh range — no buffer is reallocated, unlike the game's
 *       glBufferData. The vertices are rewritten relative to the region origin, already with the
 *       matrix the game applies to each chunk (the 1.000001 scale that closes the gaps between
 *       chunks), with the same arithmetic;
 *   <li>drawing: the chunks of the game's list, in the game's order, become runs of consecutive
 *       chunks of the same region; each run is a translate, a bind, the pointers and a
 *       glMultiDrawArrays. The order is kept — the translucent layer depends on it;
 *   <li>memory: each upload gets a fresh range; the old one is only reused after the fence of the
 *       frame in which the GPU could still read it. A full region grows with a copy on the GPU
 *       itself; an empty region is deleted.
 * </ul>
 *
 * With this on, the VBO path always applies (the display list one draws the same image); the game's
 * option stays as the player left it.
 *
 * <p>{@code -Dsteinbridge.opt.vanillaChunkArena=true}: one buffer per chunk, as in the game.
 *
 * <p>Pipeline: both — regions in one multi-draw per layer; Stein GFX uses SteinTerrain for the
 * geometry and the drawing.
 */
public final class ChunkArena {

    private static final boolean REQUESTED = !EngineOptions.VANILLA_CHUNK_ARENA;

    /**
     * The game's BLOCK format: position (3 float), color (4 byte), texture (2 float), light (2
     * short).
     */
    private static final int VERTEX = 28;

    private static final int REGION_SHIFT = 7; // 128 blocks
    private static final int INITIAL_VERTICES = 1 << 18; // 7 MB
    private static final float CHUNK_SCALE = 1.000001F;

    /**
     * The chunk matrix in the game: translate(-8) . scale(CHUNK_SCALE) . translate(8), that is, v'
     * = CHUNK_SCALE*v + 8(CHUNK_SCALE - 1).
     */
    private static final float CHUNK_SCALE_OFFSET = 8.0F * CHUNK_SCALE - 8.0F;

    private static final int FENCE_DELAY_FRAMES = 4;

    // Modes of the terrain path (see "state").
    private static final int MODE_VANILLA = 0;
    private static final int MODE_REGIONS = 1;
    private static final int MODE_STEIN_GFX = 2;

    /**
     * Pointers redone on each run (buffer bind and 4 pointers), instead of the region's VAO.
     * Walking over terrain, those pointers cost ~60% of the multidraw itself. {@code
     * -Dsteinbridge.opt.arenaPointers=true}.
     */
    private static final boolean POINTERS_PER_RUN = EngineOptions.ARENA_POINTERS;

    // The fields the patcher adds to VertexBuffer do not exist in the readable-name jar: by handle.
    private static final VarHandle SB_REGION; // VertexBuffer.sbRegion
    private static final VarHandle SB_FIRST; // VertexBuffer.sbFirst
    private static final VarHandle SB_COUNT; // VertexBuffer.sbCount

    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            SB_REGION = l.findVarHandle(VertexBuffer.class, "sbRegion", Object.class);
            SB_FIRST = l.findVarHandle(VertexBuffer.class, "sbFirst", int.class);
            SB_COUNT = l.findVarHandle(VertexBuffer.class, "sbCount", int.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * -1 = not yet decided (needs the GL context), 0 = game, 1 = regions, 2 = the terrain on
     * Stein's graphics API ({@link SteinTerrain}, with Stein GFX on, the default): the same hooks,
     * its geometry and its drawing.
     */
    private static int state = -1;

    private static boolean active() {
        if (state < 0) {
            if (SteinGfxOptions.ENABLED) {
                state = SteinTerrain.enabled() ? MODE_STEIN_GFX : MODE_VANILLA;
                if (state == MODE_VANILLA) {
                    Log.info("chunks", "Stein terrain not available: the game's own path");
                }
            } else {
                state = REQUESTED && GL.getCapabilities().OpenGL44 ? MODE_REGIONS : MODE_VANILLA;
                Log.info("chunks", "terrain by region: " + (state == MODE_REGIONS ? "on" : "off"));
            }
        }
        return state != MODE_VANILLA;
    }

    private ChunkArena() {}

    // ---- regions ----

    /** GPU buffer of one 128 x 128 region, with its free list. */
    private static final class Region {
        final int x;
        final int z;
        int buffer;

        /** VAO with the buffer and the BLOCK format pointers: one bind per run when drawing. */
        int vao;

        int capacity;
        int used;

        /** Free ranges: start -> size, coalesced. */
        final TreeMap<Integer, Integer> free = new TreeMap<>();

        Region(int x, int z) {
            this.x = x;
            this.z = z;
            allocate(INITIAL_VERTICES);
            free.put(0, capacity);
        }

        /**
         * Immutable buffer in GPU memory (not mapped: if mapped, it would go to system memory and
         * the GPU would read the geometry from there every frame). Written only by glBufferSubData,
         * in ranges the GPU is not using.
         */
        private void allocate(int vertices) {
            buffer = GL15.glGenBuffers();
            GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, buffer);
            GL44.glBufferStorage(
                    GL31.GL_COPY_WRITE_BUFFER,
                    (long) vertices * VERTEX,
                    GL44.GL_DYNAMIC_STORAGE_BIT);
            GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, 0);
            capacity = vertices;
            if (!POINTERS_PER_RUN) {
                buildVao();
            }
        }

        /**
         * The same pointers and arrays that drawing binds (those of the game's VboRenderList),
         * recorded in the region's VAO. Outside drawing the bound VAO is always 0, the game's — the
         * game does not use a VAO —, and its state is not touched.
         */
        private void buildVao() {
            if (vao == 0) {
                vao = GL30.glGenVertexArrays();
            }
            GL30.glBindVertexArray(vao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
            GL11.glVertexPointer(3, GL11.GL_FLOAT, VERTEX, 0L);
            GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
            GL11.glColorPointer(4, GL11.GL_UNSIGNED_BYTE, VERTEX, 12L);
            GL11.glEnableClientState(GL11.GL_COLOR_ARRAY);
            GL13.glClientActiveTexture(GL13.GL_TEXTURE0);
            GL11.glTexCoordPointer(2, GL11.GL_FLOAT, VERTEX, 16L);
            GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
            GL13.glClientActiveTexture(GL13.GL_TEXTURE1);
            GL11.glTexCoordPointer(2, GL11.GL_SHORT, VERTEX, 24L);
            GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
            GL13.glClientActiveTexture(GL13.GL_TEXTURE0);
            GL30.glBindVertexArray(0);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        }

        /** First free range that fits; with no space, the buffer doubles. */
        int alloc(int count) {
            for (Map.Entry<Integer, Integer> e : free.entrySet()) {
                int size = e.getValue();
                if (size >= count) {
                    // Key and size before the remove: the TreeMap reuses the removed node (copies
                    // the successor into it), and the entry would start showing the neighboring
                    // range.
                    int first = e.getKey();
                    free.remove(first);
                    if (size > count) {
                        free.put(first + count, size - count);
                    }
                    used += count;
                    return first;
                }
            }
            grow(count);
            return alloc(count);
        }

        /**
         * Larger buffer, with the geometry copied on the GPU; the old one goes away (the driver
         * waits for its use).
         */
        private void grow(int needed) {
            int oldBuffer = buffer;
            int oldCapacity = capacity;
            int vertices = Math.max(oldCapacity * 2, oldCapacity + needed);
            allocate(vertices);
            GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, oldBuffer);
            GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, buffer);
            GL31.glCopyBufferSubData(
                    GL31.GL_COPY_READ_BUFFER,
                    GL31.GL_COPY_WRITE_BUFFER,
                    0,
                    0,
                    (long) oldCapacity * VERTEX);
            GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, 0);
            GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, 0);
            GL15.glDeleteBuffers(oldBuffer);
            addFree(oldCapacity, vertices - oldCapacity);
        }

        /** Returns a range that was in use. */
        void release(int first, int count) {
            used -= count;
            addFree(first, count);
        }

        /** Free range, merging with the free neighbors. */
        private void addFree(int first, int count) {
            Map.Entry<Integer, Integer> before = free.floorEntry(first);
            if (before != null && before.getKey() + before.getValue() == first) {
                first = before.getKey();
                count += before.getValue();
                free.remove(first);
            }
            Integer after = free.get(first + count);
            if (after != null) {
                free.remove(first + count);
                count += after;
            }
            free.put(first, count);
        }

        void delete() {
            GL15.glDeleteBuffers(buffer);
            buffer = 0;
            if (vao != 0) {
                GL30.glDeleteVertexArrays(vao);
                vao = 0;
            }
        }
    }

    private static final Map<Long, Region> REGIONS = new HashMap<>();

    private static Region region(int blockX, int blockZ) {
        int rx = blockX >> REGION_SHIFT;
        int rz = blockZ >> REGION_SHIFT;
        long key = ((long) rx << 32) ^ (rz & 0xFFFFFFFFL);
        Region r = REGIONS.get(key);
        if (r == null) {
            r = new Region(rx << REGION_SHIFT, rz << REGION_SHIFT);
            REGIONS.put(key, r);
        }
        return r;
    }

    /**
     * Released range, to be returned when the fence of the frame in which it was released passes.
     */
    private static final class Pending {
        final Region region;
        final int first;
        final int count;
        final long frame;

        Pending(Region region, int first, int count, long frame) {
            this.region = region;
            this.first = first;
            this.count = count;
            this.frame = frame;
        }
    }

    private static final ArrayDeque<Pending> PENDING = new ArrayDeque<>();

    /**
     * Fences of the frames that released a range: {frame, sync}. Only those need a fence — with no
     * pending range there is nothing to protect, and a fence with a wait every frame cost ~25% of
     * the frame when looking at the sky (few chunks per frame).
     */
    private static final ArrayDeque<long[]> FENCES = new ArrayDeque<>();

    private static boolean releasedThisFrame;
    private static long frame;

    /** Releases the chunk buffer's current range (if any) to return after the fence. */
    private static void release(Object vb) {
        // (Object) before the cast: VarHandle.get has a polymorphic signature, and the cast type
        // would go into the call descriptor; with Object it names none of our classes.
        Object handle = (Object) SB_REGION.get(vb);
        if (state == MODE_STEIN_GFX) {
            if (handle != null) {
                SteinTerrain.release(handle);
            }
            SB_REGION.set(vb, (Object) null);
            SB_COUNT.set(vb, 0);
            return;
        }
        Region r = (Region) handle;
        int count = (int) SB_COUNT.get(vb);
        if (r != null && count > 0) {
            PENDING.add(new Pending(r, (int) SB_FIRST.get(vb), count, frame));
            releasedThisFrame = true;
        }
        SB_REGION.set(vb, (Object) null);
        SB_COUNT.set(vb, 0);
    }

    /**
     * End of frame (Display.update): the frame's fence, if it released a range, and the return of
     * ranges released FENCE_DELAY_FRAMES frames ago or more, whose fence has already passed.
     */
    public static void endFrame() {
        if (ChunkWatch.ENABLED) {
            ChunkWatch.endFrame(state);
        }
        if (state == MODE_STEIN_GFX) {
            SteinTerrain.endFrame();
            return;
        }
        if (state != MODE_REGIONS) {
            return;
        }
        if (releasedThisFrame) {
            releasedThisFrame = false;
            FENCES.add(new long[] {frame, GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)});
        }
        boolean returned = false;
        while (!FENCES.isEmpty() && FENCES.peek()[0] <= frame - FENCE_DELAY_FRAMES) {
            long[] f = FENCES.poll();
            // FENCE_DELAY_FRAMES frames ago: in practice it has already passed; if not, wait a
            // little.
            GL32.glClientWaitSync(f[1], 0, 50_000_000L);
            GL32.glDeleteSync(f[1]);
            while (!PENDING.isEmpty() && PENDING.peek().frame <= f[0]) {
                Pending p = PENDING.poll();
                if (p.region.buffer != 0) {
                    p.region.release(p.first, p.count);
                }
            }
            returned = true;
        }
        if (returned) {
            deleteEmptyRegions();
        }
        frame++;
    }

    /**
     * Region with nothing in use: no chunk points to it and none of its ranges is pending (pending
     * ones count as in use until returned).
     */
    private static void deleteEmptyRegions() {
        REGIONS.values()
                .removeIf(
                        r -> {
                            if (r.used <= 0) {
                                r.delete();
                                return true;
                            }
                            return false;
                        });
    }

    // ---- hooks ----

    /**
     * In place of OpenGlHelper.useVbo(): with Stein's terrain or regions, the VBO path always
     * applies.
     */
    public static boolean useVbo() {
        return active() || OpenGlHelper.useVbo();
    }

    /**
     * In place of VertexBufferUploader.draw(worldRenderer) in uploadVertexBuffer: the geometry goes
     * to the chunk's region, relative to its origin.
     */
    public static void upload(Object uploaderObj, Object rendererObj) {
        VertexBufferUploader uploader = (VertexBufferUploader) uploaderObj;
        WorldRenderer renderer = (WorldRenderer) rendererObj;
        if (!active()) {
            uploader.draw(renderer);
            return;
        }
        Object vb = uploader.vertexBuffer;
        ChunkWatch.uploads++;
        renderer.reset(); // like the original, before reading the buffer
        // Renderer that never began (no format): there is no geometry in it. Happens on a world
        // change with a chunk still being built — the server that takes you from the lobby to the
        // world right after /login —, and reading the format size crashed the game ("Rendering
        // chunk", NullPointerException). It is an empty layer: the old range goes away.
        VertexFormat format = renderer.getVertexFormat();
        if (format == null) {
            release(vb);
            return;
        }
        if (format.getNextOffset() != VERTEX) {
            uploader.draw(renderer);
            return;
        }
        ByteBuffer data = renderer.getByteBuffer();
        int count = data.remaining() / VERTEX;
        release(vb);
        if (count == 0) {
            return;
        }
        // The build uses translation = -chunk position.
        int cx = (int) Math.round(-renderer.xOffset);
        int cy = (int) Math.round(-renderer.yOffset);
        int cz = (int) Math.round(-renderer.zOffset);
        if (state == MODE_STEIN_GFX) {
            SB_REGION.set(vb, SteinTerrain.upload(MemoryUtil.memAddress(data), count, cx, cy, cz));
            SB_COUNT.set(vb, count);
            return;
        }
        Region r = region(cx, cz);
        int first = r.alloc(count);
        ByteBuffer out = scratch(count * VERTEX);
        write(
                MemoryUtil.memAddress(data),
                MemoryUtil.memAddress(out),
                count,
                cx - r.x,
                cy,
                cz - r.z);
        // Fresh range (the old one stays pending until the fence): the GPU is not reading this now.
        GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, r.buffer);
        GL15.glBufferSubData(GL31.GL_COPY_WRITE_BUFFER, (long) first * VERTEX, out);
        GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, 0);
        SB_REGION.set(vb, (Object) r);
        SB_FIRST.set(vb, first);
        SB_COUNT.set(vb, count);
    }

    private static ByteBuffer scratch = MemoryUtil.memAlloc(1 << 20);

    /** Work buffer with exact bytes (position 0, limit bytes), growing when needed. */
    private static ByteBuffer scratch(int bytes) {
        if (scratch.capacity() < bytes) {
            MemoryUtil.memFree(scratch);
            scratch = MemoryUtil.memAlloc(Math.max(bytes, scratch.capacity() * 2));
        }
        scratch.clear();
        scratch.limit(bytes);
        return scratch;
    }

    /**
     * Copies the vertices with the position relative to the region: CHUNK_SCALE*v +
     * CHUNK_SCALE_OFFSET + chunk offset.
     */
    private static void write(long src, long dst, int count, float ox, float oy, float oz) {
        for (int i = 0; i < count; i++) {
            long s = src + (long) i * VERTEX;
            long d = dst + (long) i * VERTEX;
            MemoryUtil.memPutFloat(
                    d, CHUNK_SCALE * MemoryUtil.memGetFloat(s) + CHUNK_SCALE_OFFSET + ox);
            MemoryUtil.memPutFloat(
                    d + 4, CHUNK_SCALE * MemoryUtil.memGetFloat(s + 4) + CHUNK_SCALE_OFFSET + oy);
            MemoryUtil.memPutFloat(
                    d + 8, CHUNK_SCALE * MemoryUtil.memGetFloat(s + 8) + CHUNK_SCALE_OFFSET + oz);
            MemoryUtil.memCopy(s + 12, d + 12, VERTEX - 12);
        }
    }

    /** In place of VertexBuffer.deleteGlBuffers() in RenderChunk.deleteGlResources. */
    public static void delete(Object vb) {
        if (state >= MODE_REGIONS) {
            release(vb);
        }
        ((VertexBuffer) vb).deleteGlBuffers();
    }

    private static IntBuffer firsts = MemoryUtil.memAllocInt(1024);
    private static IntBuffer counts = MemoryUtil.memAllocInt(1024);

    /** In place of renderContainer.renderChunkLayer(layer) in RenderGlobal.renderBlockLayer. */
    public static void render(Object containerObj, Object layerObj) {
        ChunkRenderContainer container = (ChunkRenderContainer) containerObj;
        EnumWorldBlockLayer layer = (EnumWorldBlockLayer) layerObj;
        if (!active() || !(container instanceof VboRenderList)) {
            container.renderChunkLayer(layer);
            return;
        }
        if (!container.initialized) {
            return;
        }
        List<RenderChunk> chunks = container.renderChunks;
        double vx = container.viewEntityX;
        double vy = container.viewEntityY;
        double vz = container.viewEntityZ;
        int ordinal = layer.ordinal();
        if (ChunkWatch.ENABLED && ordinal == 0) {
            ChunkWatch.layerListed(chunks.size(), vx, vz);
        }
        if (state == MODE_STEIN_GFX) {
            // List equal to the saved build one and no range swapped since: each chunk's range is
            // not even read (it was three objects per chunk, each a cache miss: RenderChunk, buffer
            // vector, VertexBuffer).
            if (SteinTerrain.begin(ordinal, chunks)) {
                for (RenderChunk chunk : chunks) {
                    Object vb = chunk.getVertexBufferByLayer(ordinal);
                    Object handle = (Object) SB_REGION.get(vb);
                    SteinTerrain.add(handle); // null: no geometry (empty layer)
                }
            }
            SteinTerrain.draw(vx, vy, vz);
            GlStateManager.resetColor();
            chunks.clear();
            return;
        }
        // The model parts batch leaves its own VBO and pointers bound between batches.
        ModelBatch.restore();
        Region run = null;
        firsts.clear();
        counts.clear();
        for (RenderChunk chunk : chunks) {
            VertexBuffer vb = chunk.getVertexBufferByLayer(ordinal);
            Region r = (Region) (Object) SB_REGION.get(vb);
            int count = (int) SB_COUNT.get(vb);
            if (r == null || count == 0) {
                continue;
            }
            if (r != run) {
                drawRun(run, vx, vy, vz);
                run = r;
            }
            if (!firsts.hasRemaining()) {
                firsts = grow(firsts);
                counts = grow(counts);
            }
            firsts.put((int) SB_FIRST.get(vb));
            counts.put(count);
        }
        drawRun(run, vx, vy, vz);
        if (!POINTERS_PER_RUN) {
            GL30.glBindVertexArray(0);
        }
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GlStateManager.resetColor();
        chunks.clear();
    }

    private static IntBuffer grow(IntBuffer b) {
        IntBuffer n = MemoryUtil.memAllocInt(b.capacity() * 2);
        b.flip();
        n.put(b);
        MemoryUtil.memFree(b);
        return n;
    }

    /** A run of consecutive chunks of the same region: translate, bind, pointers, multidraw. */
    private static void drawRun(Region r, double vx, double vy, double vz) {
        if (r == null || firsts.position() == 0) {
            return;
        }
        firsts.flip();
        counts.flip();
        GLMatrix.pushMatrix();
        GLMatrix.translate((float) (r.x - vx), (float) (-vy), (float) (r.z - vz));
        GLMatrix.flush();
        // the recorded frame goes before binding buffer and VAO: the replay changes those bindings
        SteinGlFrame.sync("direct draw of the old terrain");
        if (POINTERS_PER_RUN) {
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, r.buffer);
            // The same pointers as the game's VboRenderList.setupArrayPointers.
            GL11.glVertexPointer(3, GL11.GL_FLOAT, VERTEX, 0L);
            GL11.glColorPointer(4, GL11.GL_UNSIGNED_BYTE, VERTEX, 12L);
            GL11.glTexCoordPointer(2, GL11.GL_FLOAT, VERTEX, 16L);
            GL13.glClientActiveTexture(GL13.GL_TEXTURE1);
            GL11.glTexCoordPointer(2, GL11.GL_SHORT, VERTEX, 24L);
            GL13.glClientActiveTexture(GL13.GL_TEXTURE0);
        } else {
            GL30.glBindVertexArray(r.vao);
        }
        GL14.glMultiDrawArrays(GL11.GL_QUADS, firsts, counts);
        GLMatrix.popMatrix();
        firsts.clear();
        counts.clear();
    }
}
