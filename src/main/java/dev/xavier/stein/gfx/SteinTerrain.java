/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

package dev.xavier.stein.gfx;

import dev.xavier.stein.bridge.core.Log;
import dev.xavier.stein.bridge.engine.ChunkArena;
import dev.xavier.stein.bridge.engine.GLMatrix;
import dev.xavier.stein.gfx.gl.SteinCoreGl;

import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The world on Stein's graphics API (stage 3 of the renderer): the chunks' geometry in API buffers,
 * one indirect draw per region and layer, and a shader of ours in place of the fixed-function
 * pipeline. The hooks are those of {@link ChunkArena} (upload, draw, delete, end of frame); the
 * game keeps building the chunks, choosing which ones show up and in what order — from here on only
 * how the geometry lives on the GPU and is drawn changes.
 *
 * <ul>
 *   <li><b>16-byte vertex</b> (the game's is 28): position local to the chunk in 1/1024 of a block
 *       (3 ushort, from -16 to 48 blocks), the light (block and sky, one byte each), the color and
 *       the texture coordinate in 2 ushort (1/32768: the atlas texel edges come out exact). The
 *       conversion happens at upload, once per build;
 *   <li><b>no gaps</b>: the position in the shader is the local one plus the chunk origin minus the
 *       camera block, all integers or multiples of 1/1024 (exact sum in float); the camera's
 *       fractional part goes in the matrix. The same world point comes out equal from two
 *       neighboring chunks, without the game's 1.000001 scale;
 *   <li><b>indexed triangles</b> (a common index buffer, quad = 2 triangles) instead of GL_QUADS;
 *   <li><b>one indirect draw per layer</b>, with the whole terrain in a single buffer and the
 *       chunks in the game's order ({@code terrainOneBuffer=false}: one draw per 128-block region
 *       on the layers without blending — the order between regions does not change the image, depth
 *       — and, on the translucent one, one per run of consecutive chunks of the same region);
 *   <li><b>each layer's assembly kept</b> between frames (order, commands, origins), redone only
 *       when the game's list or the chunks' ranges change ({@code terrainCache=false}: every
 *       frame);
 *   <li><b>own shader</b>: texture, vertex color, lightmap, alpha test and fog read from the game's
 *       state (GlStateManager cache) once per layer. The pixel with alpha test is another shader
 *       (discard turns off the GPU's early depth test on the solid layer).
 * </ul>
 *
 * <p>Without indirect drawing (4.1, Mac) one indexed draw per chunk goes out, with the origin
 * through the base instance. Without Stein GFX (no core context, or {@code
 * -Dsteinbridge.gfx=false}) it does not start and ChunkArena's region terrain takes over. With this
 * on, Lens effects on the terrain do not run (Lens is redone on top of the API later).
 *
 * <p>Pipeline: Stein GFX only.
 */
public final class SteinTerrain {

    private static final boolean STATS = SteinGfxOptions.TERRAIN_STATS;

    /**
     * A single vertex buffer for the whole terrain ({@code
     * -Dsteinbridge.gfx.terrainOneBuffer=false} brings back the 128-block regions): each layer goes
     * out in a single indirect draw, in the game's order (near to far, the same as the
     * fixed-function pipeline: the GPU discards early what ends up behind), the translucent one
     * included. With regions it was ~37 draws and buffer switches per frame, and the solid layer
     * grouped by region (a far chunk of the first region before a near one of the second).
     */
    static final boolean ONE_BUFFER = SteinGfxOptions.TERRAIN_ONE_BUFFER;

    /**
     * The game's vertex (BLOCK format): position 3 float, color 4 byte, texture 2 float, light 2
     * short.
     */
    private static final int GAME_VERTEX = 28;

    /**
     * Ours: position 3 ushort and the light in a ushort, color 4 byte, texture 2 ushort in 1/32768.
     */
    static final int VERTEX = 16;

    private static final float POS_SCALE = 1024f;
    private static final float POS_BIAS = 16f;
    private static final int REGION_SHIFT = 7; // 128 blocks
    static final int INITIAL_VERTICES = 1 << 18; // 4 MB

    /** Frames until a released range is used again (the GPU may still be reading it). */
    private static final int FRAMES = 4;

    private static final int RING_REGIONS = 3;
    static final int COMMAND_BYTES = 20;
    static final int ORIGIN_BYTES = 16;

    /** Draws (chunks per layer) per ring region; the frame moves on to the next if it fills up. */
    private static final int RING_DRAWS = 64 * 1024;

    private static final int FRAME_BYTES = 192;

    // Byte offsets in the Frame uniform block (terrain.vert / terrain.frag); the projection is at
    // 0.
    private static final int FRAME_MODELVIEW = 64;
    private static final int FRAME_FOG_COLOR = 128;
    private static final int FRAME_FOG_MODE = 144;
    private static final int FRAME_FOG_DENSITY = 148;
    private static final int FRAME_FOG_START = 152;
    private static final int FRAME_FOG_END = 156;
    private static final int FRAME_ALPHA_REF = 160;
    private static final int FRAME_LIGHTMAP_ON = 164;
    private static final int FRAME_FOG_ON = 168;
    private static final int FRAME_TEXTURE_ON = 172;
    private static final int FRAME_TIME = 176;
    private static final int FRAME_FADE_SECONDS = 180;

    private static final Map<Long, SteinTerrainRegion> REGIONS = new HashMap<>();

    private static boolean initDone;
    private static boolean ready;
    private static boolean indirect;

    private static SteinBuffer indices;
    private static int indexVertices;
    private static SteinBuffer origins;
    private static SteinBuffer commands;
    private static SteinBuffer frameUbo;
    private static SteinTexture atlas;
    private static SteinTexture lightmap;

    private SteinTerrain() {}

    private static boolean ready() {
        if (initDone) {
            return ready;
        }
        initDone = true;
        if (!SteinGfxOptions.ENABLED) {
            return false;
        }
        SteinDevice dev = SteinDevice.get();
        indirect = dev.caps().multiDrawIndirect;
        origins =
                dev.createBuffer(
                                new SteinBuffer.Desc(
                                        "terrain: origins",
                                        (long) RING_DRAWS * ORIGIN_BYTES * RING_REGIONS,
                                        SteinBuffer.Memory.UPLOAD,
                                        SteinBuffer.Usage.VERTEX))
                        .ring(RING_REGIONS);
        commands =
                dev.createBuffer(
                                new SteinBuffer.Desc(
                                        "terrain: commands",
                                        (long) RING_DRAWS * COMMAND_BYTES * RING_REGIONS,
                                        SteinBuffer.Memory.UPLOAD,
                                        SteinBuffer.Usage.INDIRECT))
                        .ring(RING_REGIONS);
        frameUbo =
                dev.createBuffer(
                                new SteinBuffer.Desc(
                                        "terrain: frame",
                                        16384L * RING_REGIONS,
                                        SteinBuffer.Memory.UPLOAD,
                                        SteinBuffer.Usage.UNIFORM))
                        .ring(RING_REGIONS);
        SteinTerrainPipelines.create(dev);
        if (!origins.mapped() || !commands.mapped() || !frameUbo.mapped()) {
            Log.info("gfx", "Stein terrain off: buffer has no address (mapping failed)");
            return false;
        }
        // the solid layer's state and the cutout one's: without them, nothing changes
        if (!SteinTerrainPipelines.pipeline(SteinTerrainPipelines.DEFAULT_SOLID_KEY).valid()
                || !SteinTerrainPipelines.pipeline(SteinTerrainPipelines.DEFAULT_CUTOUT_KEY)
                        .valid()) {
            Log.info("gfx", "Stein terrain off: the shader did not compile");
            return false;
        }
        ensureIndices(INITIAL_VERTICES);
        ready = true;
        Log.info(
                "gfx",
                "Stein terrain: 16-byte vertex, "
                        + (!indirect
                                ? "one draw per chunk (no indirect draw)"
                                : ONE_BUFFER
                                        ? "one buffer and one indirect draw per layer"
                                        : "one indirect draw per region")
                        + (SteinTerrainLayers.CACHE ? ", assembly kept between frames" : "")
                        + (SteinTerrainLayers.CACHE_CHECK ? " (checked)" : "")
                        + ", own shader");
        return true;
    }

    /** Can take over the terrain (decided on the first call, with the graphics context current). */
    public static boolean enabled() {
        return ready();
    }

    // ---- indices: quad (0 1 2 3) = triangles (0 1 3) (1 2 3), the diagonal along which the driver
    // splits the game's GL_QUADS (with the other one, smooth lighting and corner shadow
    // interpolate differently and triangle-shaped blotches appear) ----

    private static void ensureIndices(int vertices) {
        if (vertices <= indexVertices) {
            return;
        }
        int n = Math.max(vertices, indexVertices * 2);
        n = (n + 3) & ~3;
        ByteBuffer b = MemoryUtil.memAlloc(n / 4 * 6 * 4);
        try {
            for (int q = 0; q < n; q += 4) {
                b.putInt(q).putInt(q + 1).putInt(q + 3).putInt(q + 1).putInt(q + 2).putInt(q + 3);
            }
            b.flip();
            SteinBuffer old = indices;
            indices =
                    SteinDevice.get()
                            .createBuffer(
                                    new SteinBuffer.Desc(
                                            "terrain: indices",
                                            b.remaining(),
                                            SteinBuffer.Memory.GPU,
                                            SteinBuffer.Usage.INDEX));
            indices.upload(0, b);
            if (old != null) {
                old.destroy(); // OpenGL keeps the old one while the GPU is still using it
            }
            indexVertices = n;
        } finally {
            MemoryUtil.memFree(b);
        }
    }

    private static ByteBuffer scratch = MemoryUtil.memAlloc(1 << 20);
    private static long clamped;

    /**
     * Upload of a chunk layer: {@code count} game vertices in {@code src} (BLOCK format, position
     * relative to the chunk origin at {@code x, y, z}). Returns what the chunk keeps for drawing
     * and for releasing later.
     */
    public static Object upload(long src, int count, int x, int y, int z) {
        long t0 = STATS ? System.nanoTime() : 0L;
        Object s;
        try {
            s = uploadSlice(src, count, x, y, z);
        } catch (RuntimeException | Error e) {
            // the upload runs in a game task that swallows the exception: without this the
            // chunk silently vanishes
            Log.error(
                    "gfx",
                    "terrain: upload failed ("
                            + count
                            + " vertices at "
                            + x
                            + " "
                            + y
                            + " "
                            + z
                            + ")",
                    e);
            throw e;
        }
        if (STATS) {
            uploadMax = Math.max(uploadMax, System.nanoTime() - t0);
        }
        return s;
    }

    private static Object uploadSlice(long src, int count, int x, int y, int z) {
        int rx = x >> REGION_SHIFT;
        int rz = z >> REGION_SHIFT;
        long key = ONE_BUFFER ? 0L : ((long) rx << 32) ^ (rz & 0xFFFFFFFFL);
        SteinTerrainRegion r = REGIONS.get(key);
        if (r == null) {
            r = new SteinTerrainRegion();
            REGIONS.put(key, r);
        }
        int bytes = count * VERTEX;
        if (scratch.capacity() < bytes) {
            MemoryUtil.memFree(scratch);
            scratch = MemoryUtil.memAlloc(Math.max(bytes, scratch.capacity() * 2));
        }
        scratch.clear();
        scratch.limit(bytes);
        convert(src, MemoryUtil.memAddress(scratch), count);
        int first = r.alloc(count);
        r.buffer.upload((long) first * VERTEX, scratch);
        if (count > indexVertices) {
            ensureIndices(count);
        }
        // The chunk will change range: the draws kept with the current list stop being valid.
        SteinTerrainLayers.epoch++;
        return new SteinTerrainSlice(r, first, count, x, y, z);
    }

    private static void convert(long src, long dst, int count) {
        for (int i = 0; i < count; i++) {
            long s = src + (long) i * GAME_VERTEX;
            long d = dst + (long) i * VERTEX;
            MemoryUtil.memPutShort(d, position(MemoryUtil.memGetFloat(s)));
            MemoryUtil.memPutShort(d + 2, position(MemoryUtil.memGetFloat(s + 4)));
            MemoryUtil.memPutShort(d + 4, position(MemoryUtil.memGetFloat(s + 8)));
            int block = Math.min(MemoryUtil.memGetShort(s + 24) & 0xFFFF, 255);
            int sky = Math.min(MemoryUtil.memGetShort(s + 26) & 0xFFFF, 255);
            MemoryUtil.memPutShort(d + 6, (short) (block | sky << 8));
            MemoryUtil.memPutInt(d + 8, MemoryUtil.memGetInt(s + 12));
            MemoryUtil.memPutShort(d + 12, unit(MemoryUtil.memGetFloat(s + 16)));
            MemoryUtil.memPutShort(d + 14, unit(MemoryUtil.memGetFloat(s + 20)));
        }
    }

    private static short position(float v) {
        int q = Math.round((v + POS_BIAS) * POS_SCALE);
        if (q < 0 || q > 0xFFFF) {
            clamped++;
            q = Math.max(0, Math.min(0xFFFF, q));
        }
        return (short) q;
    }

    private static short unit(float v) {
        return (short) Math.round(Math.max(0f, Math.min(1f, v)) * 32768f);
    }

    // ---- release (the range returns after the fence of the frame in which it was released) ----

    private static final class Pending {
        final SteinTerrainSlice slice;
        final long frame;

        Pending(SteinTerrainSlice slice, long frame) {
            this.slice = slice;
            this.frame = frame;
        }
    }

    private static final ArrayDeque<Pending> PENDING = new ArrayDeque<>();
    private static final ArrayDeque<Object[]> FENCES = new ArrayDeque<>();
    private static boolean releasedThisFrame;
    private static long frame;

    /** The chunk dropped the layer (new build, empty, or chunk deleted). */
    public static void release(Object handle) {
        PENDING.add(new Pending((SteinTerrainSlice) handle, frame));
        SteinChunkFade.departure(
                ((SteinTerrainSlice) handle).x,
                ((SteinTerrainSlice) handle).y,
                ((SteinTerrainSlice) handle).z);
        releasedThisFrame = true;
        SteinTerrainLayers.epoch++;
    }

    /** End of frame (Display.update): fence, return of the ranges and the rings' next region. */
    public static void endFrame() {
        if (!ready) {
            return;
        }
        SteinChunkFade.forgetGone();
        if (releasedThisFrame) {
            releasedThisFrame = false;
            FENCES.add(new Object[] {frame, SteinDevice.get().fence()});
        }
        boolean returned = false;
        while (!FENCES.isEmpty() && (long) FENCES.peek()[0] <= frame - FRAMES) {
            Object[] f = FENCES.poll();
            SteinFence fence = (SteinFence) f[1];
            fence.waitFor(50_000_000L);
            fence.destroy();
            while (!PENDING.isEmpty() && PENDING.peek().frame <= (long) f[0]) {
                SteinTerrainSlice s = PENDING.poll().slice;
                if (s.region.buffer != null) {
                    s.region.release(s.first, s.count);
                }
            }
            returned = true;
        }
        if (returned) {
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
        origins.nextRegion();
        commands.nextRegion();
        frameUbo.nextRegion();
        frame++;
        if (STATS) {
            stats();
        }
    }

    // ---- drawing a layer (the assembly and its cache are in SteinTerrainLayers) ----

    /**
     * Start of layer {@code ordinal} with the game's RenderChunk list. Returns whether the list
     * needs to be read (each chunk's ranges through {@link #add}); false: the kept assembly holds
     * (level 1) and {@link #draw} comes straight away.
     */
    public static boolean begin(int ordinal, List<?> chunks) {
        if (STATS) {
            beginAt = System.nanoTime();
        }
        return SteinTerrainLayers.begin(ordinal, chunks);
    }

    public static void add(Object handle) {
        SteinTerrainLayers.add(handle);
    }

    /**
     * Draws the layer with the game's current state; {@code vx, vy, vz}: the chunk list's camera.
     */
    public static void draw(double vx, double vy, double vz) {
        long t0 = beginAt;
        SteinTerrainLayers.Layer d = SteinTerrainLayers.layer;
        SteinTerrainLayers.assemble(d, SteinGameState.blend());
        listed += d.handleCount;
        missing += d.missing;
        int count = d.count;
        if (count == 0) {
            return;
        }
        if (count > RING_DRAWS) {
            count = RING_DRAWS; // never seen: more chunks in a layer than a whole ring
        }
        SteinDevice dev = SteinDevice.get();
        if (!reserveRings(dev, count)) {
            return;
        }
        writeRings(d, count, vx, vy, vz);
        int textures = importGameTextures(dev);
        int key = SteinTerrainPipelines.stateKey();
        SteinPipeline pipeline;
        if (d.pipe != null && d.pipeKey == key) {
            pipeline = d.pipe;
        } else {
            pipeline = SteinTerrainPipelines.pipeline(key);
            d.pipe = pipeline;
            d.pipeKey = key;
        }
        long t1 = STATS ? System.nanoTime() : 0L;
        SteinCommands cmd = dev.beginPass(SteinRenderPass.keep("terrain", dev.gameTarget()));
        long t2 = STATS ? System.nanoTime() : 0L;
        try {
            recordDraws(cmd, d, count, pipeline, textures);
        } finally {
            cmd.end();
        }
        if (STATS) {
            long t3 = System.nanoTime();
            prepNs += t1 - t0;
            prepMax = Math.max(prepMax, t1 - t0);
            openMax = Math.max(openMax, t2 - t1);
            drawMax = Math.max(drawMax, t3 - t2);
            openNs += t2 - t1;
            drawNs += t3 - t2;
        }
        sections += count;
    }

    /** The layer's offsets in the three rings, reserved by {@link #reserveRings}. */
    private static long originAt;

    private static long commandAt;
    private static long frameAt;

    /** Reserves the layer's origins, commands and Frame block in their rings; false = no room. */
    private static boolean reserveRings(SteinDevice dev, int count) {
        originAt = reserve(origins, (long) count * ORIGIN_BYTES, ORIGIN_BYTES);
        commandAt = indirect ? reserve(commands, (long) count * COMMAND_BYTES, 4) : 0;
        frameAt = reserve(frameUbo, FRAME_BYTES, dev.caps().uniformAlignment);
        return originAt >= 0 && commandAt >= 0 && frameAt >= 0;
    }

    /** Writes the layer's origins, commands and Frame block at the reserved offsets. */
    private static void writeRings(
            SteinTerrainLayers.Layer d, int count, double vx, double vy, double vz) {
        int camX = (int) Math.floor(vx);
        int camY = (int) Math.floor(vy);
        int camZ = (int) Math.floor(vz);
        if (!d.originValid || d.originX != camX || d.originY != camY || d.originZ != camZ) {
            SteinTerrainLayers.writeOrigins(d, camX, camY, camZ);
        }
        MemoryUtil.memCopy(
                MemoryUtil.memAddress(d.originBytes),
                origins.address(originAt),
                (long) count * ORIGIN_BYTES);
        if (indirect) {
            MemoryUtil.memCopy(
                    MemoryUtil.memAddress(d.commandBytes),
                    commands.address(commandAt),
                    (long) count * COMMAND_BYTES);
        }
        writeFrame(
                frameUbo.address(frameAt),
                (float) (vx - camX),
                (float) (vy - camY),
                (float) (vz - camZ));
        origins.commitRing();
        commands.commitRing();
        frameUbo.commitRing();
    }

    private static final int ATLAS_BOUND = 1;
    private static final int LIGHTMAP_BOUND = 2;

    /**
     * Wraps the game's bound atlas and lightmap textures for the draw; returns which of the two are
     * bound ({@link #ATLAS_BOUND}, {@link #LIGHTMAP_BOUND}).
     */
    private static int importGameTextures(SteinDevice dev) {
        int bound = 0;
        boolean textured = SteinGameState.texture2D(0);
        if (textured) {
            bound |= ATLAS_BOUND;
            int id = SteinGameState.textureName(0);
            if (atlas == null || atlas.handle() != id) {
                atlas =
                        dev.importTexture(
                                SteinTexture.Desc.texture2D(
                                        "terrain: atlas", SteinFormat.RGBA8, 1, 1, 1),
                                id);
            }
        }
        boolean lit = SteinGameState.texture2D(1);
        if (lit) {
            bound |= LIGHTMAP_BOUND;
            int id = SteinGameState.textureName(1);
            if (lightmap == null || lightmap.handle() != id) {
                lightmap =
                        dev.importTexture(
                                SteinTexture.Desc.texture2D(
                                        "terrain: lightmap", SteinFormat.RGBA8, 16, 16, 1),
                                id);
            }
        }
        return bound;
    }

    /** Binds the layer's state and issues its draws, one per run of chunks of the same region. */
    private static void recordDraws(
            SteinCommands cmd,
            SteinTerrainLayers.Layer d,
            int count,
            SteinPipeline pipeline,
            int textures) {
        cmd.bindPipeline(pipeline);
        cmd.bindIndexBuffer(indices, SteinCommands.IndexType.UINT32);
        // the layer's origins start at its offset in the ring: the commands' base instance is
        // relative to it, so the kept commands hold at any position in the ring
        cmd.bindVertexBuffer(1, origins, originAt);
        cmd.bindUniforms(0, frameUbo, frameAt, FRAME_BYTES);
        // the game's textures with their parameters (the atlas switches the mipmap filter
        // between layers)
        if ((textures & ATLAS_BOUND) != 0) {
            cmd.bindTexture(0, atlas, null);
        }
        if ((textures & LIGHTMAP_BOUND) != 0) {
            cmd.bindTexture(1, lightmap, null);
        }
        SteinBuffer bound = null;
        int start = 0;
        for (int r = 0; r < d.runs && start < count; r++) {
            int end = Math.min(d.runEnd[r], count);
            SteinBuffer vb = d.runRegion[r].buffer;
            if (vb == null) {
                // Deleted region: does not happen with the key up to date (releasing raises
                // the version).
                start = end;
                continue;
            }
            if (vb != bound) {
                cmd.bindVertexBuffer(0, vb, 0);
                bound = vb;
            }
            if (indirect) {
                cmd.drawIndexedIndirect(
                        commands, commandAt + (long) start * COMMAND_BYTES, end - start);
            } else {
                SteinTerrainSlice[] order = d.order;
                for (int k = start; k < end; k++) {
                    cmd.drawIndexed(order[k].count / 4 * 6, 1, 0, order[k].first, k);
                }
            }
            runs++;
            start = end;
        }
    }

    private static long reserve(SteinBuffer ring, long bytes, int alignment) {
        long at = ring.reserve(bytes, alignment);
        if (at == -2) {
            ring.nextRegion();
            at = ring.reserve(bytes, alignment);
        }
        return at;
    }

    /**
     * The Frame block: projection, the game's matrix with the camera's fractional part, fog, alpha,
     * units.
     */
    private static void writeFrame(long p, float fx, float fy, float fz) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer b = stack.mallocFloat(16);
            SteinCoreGl.glGetFloatv(GL11.GL_PROJECTION_MATRIX, b);
            for (int i = 0; i < 16; i++) {
                MemoryUtil.memPutFloat(p + i * 4L, b.get(i));
            }
            float[] m = GLMatrix.current();
            // m * translate(-f): only the translation column changes
            for (int i = 0; i < 12; i++) {
                MemoryUtil.memPutFloat(p + FRAME_MODELVIEW + i * 4L, m[i]);
            }
            for (int r = 0; r < 4; r++) {
                float t = m[12 + r] - (m[r] * fx + m[4 + r] * fy + m[8 + r] * fz);
                MemoryUtil.memPutFloat(p + FRAME_MODELVIEW + (12 + r) * 4L, t);
            }
            boolean fog = SteinGameState.fog();
            // always the fog color: the fading-in chunk comes out of it, even without fog turned
            // on in the layer
            SteinCoreGl.glGetFloatv(GL11.GL_FOG_COLOR, b);
            for (int k = 0; k < 4; k++) {
                MemoryUtil.memPutFloat(p + FRAME_FOG_COLOR + k * 4L, b.get(k));
            }
            int mode = SteinGameState.fogMode();
            MemoryUtil.memPutFloat(
                    p + FRAME_FOG_MODE, mode == GL11.GL_EXP ? 1f : mode == GL11.GL_EXP2 ? 2f : 0f);
            MemoryUtil.memPutFloat(p + FRAME_FOG_DENSITY, SteinGameState.fogDensity());
            MemoryUtil.memPutFloat(p + FRAME_FOG_START, SteinGameState.fogStart());
            MemoryUtil.memPutFloat(p + FRAME_FOG_END, SteinGameState.fogEnd());
            MemoryUtil.memPutFloat(
                    p + FRAME_ALPHA_REF,
                    SteinTerrainPipelines.alphaTest() ? SteinGameState.alphaRef() : -1f);
            MemoryUtil.memPutFloat(p + FRAME_LIGHTMAP_ON, SteinGameState.texture2D(1) ? 1f : 0f);
            MemoryUtil.memPutFloat(p + FRAME_FOG_ON, fog ? 1f : 0f);
            MemoryUtil.memPutFloat(p + FRAME_TEXTURE_ON, SteinGameState.texture2D(0) ? 1f : 0f);
            MemoryUtil.memPutFloat(p + FRAME_TIME, SteinChunkFade.now());
            MemoryUtil.memPutFloat(p + FRAME_FADE_SECONDS, SteinChunkFade.FADE_SECONDS);
        }
    }

    // ---- numbers ----

    private static long uploadMax;
    private static long prepMax;
    private static long openMax;
    private static long drawMax;
    private static long prepNs;
    private static long openNs;
    private static long drawNs;
    private static long listed;
    private static long missing;
    private static long sections;
    private static long runs;

    /** Start of the layer ({@link #begin}): the preparation also counts the list reading. */
    private static long beginAt;

    private static long statsAt = System.nanoTime();
    private static long statsFrames;

    private static void stats() {
        statsFrames++;
        long now = System.nanoTime();
        if (now - statsAt < 5_000_000_000L) {
            return;
        }
        long vertices = 0;
        long capacity = 0;
        for (SteinTerrainRegion r : REGIONS.values()) {
            vertices += r.used;
            capacity += r.capacity;
        }
        Log.info(
                "gfx",
                "terrain: game list %.0f (%.0f without geometry), %.0f chunks and %.1f draws per"
                        + " frame, %d regions, %.1f of %.1f MB%s | CPU per frame: prepare (with the"
                        + " list) %.3f ms (worst %.2f), open pass %.3f ms (worst %.2f), draw and close"
                        + " %.3f ms (worst %.2f), worst upload %.2f ms | cache: %d layers without"
                        + " reading the list, %d with the same ranges, %d reassembled",
                (double) listed / statsFrames,
                (double) missing / statsFrames,
                (double) sections / statsFrames,
                (double) runs / statsFrames,
                REGIONS.size(),
                vertices * VERTEX / 1048576.0,
                capacity * (double) VERTEX / 1048576.0,
                clamped > 0 ? ", " + clamped + " vertices out of range" : "",
                prepNs / 1e6 / statsFrames,
                prepMax / 1e6,
                openNs / 1e6 / statsFrames,
                openMax / 1e6,
                drawNs / 1e6 / statsFrames,
                drawMax / 1e6,
                uploadMax / 1e6,
                SteinTerrainLayers.levelOneHits,
                SteinTerrainLayers.levelTwoHits,
                SteinTerrainLayers.builds);
        SteinTerrainLayers.levelOneHits = 0;
        SteinTerrainLayers.levelTwoHits = 0;
        SteinTerrainLayers.builds = 0;
        uploadMax = 0;
        prepMax = 0;
        openMax = 0;
        drawMax = 0;
        prepNs = 0;
        openNs = 0;
        drawNs = 0;
        sections = 0;
        listed = 0;
        missing = 0;
        runs = 0;
        statsFrames = 0;
        statsAt = now;
    }
}
