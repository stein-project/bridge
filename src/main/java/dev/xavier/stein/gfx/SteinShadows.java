/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import dev.xavier.stein.bridge.core.Log;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.entity.RenderManager;

import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;

/**
 * The sun's shadows (the Lens option): what stands between a block and the sun darkens it.
 *
 * <p>The casters are the terrain's chunks around the camera — on the screen or not: a tree behind
 * the player still throws its shadow forward — kept as they are uploaded and released ({@link
 * #added}, {@link #removed}). Their depth seen from the sun goes into a 2048x2048 map covering a
 * level's radius around an anchor, bent so that most of its texels go near the camera ({@code
 * common/shadow_distort.glsl}) (the camera's block, in steps of {@link #ANCHOR_STEP}); it is drawn
 * again only when the anchor, the hour (a step of the sun) or the chunks change. Water does not
 * cast; leaves cast through their texture's holes, so the light comes through the canopy.
 *
 * <p>The terrain and the entities receive ({@code common/shadow.glsl}): where the sun reaches, its
 * direct light ({@link SteinWorldLight}) — a torch still lights what it lights — and faces turned
 * away from the sun get only the shade. It fades as the sun nears the horizon; at night and in the
 * rain there is none. Only while the world is drawn ({@link #worldEnd}). Entities and the player
 * cast into a map of their own ({@link SteinEntityShadows}).
 *
 * <p>Pipeline: Stein GFX on one terrain buffer (the default).
 */
public final class SteinShadows {

    /** The map's size in pixels and the blocks it covers around the anchor, each way, per level. */
    private static final int[] SIZES = {0, 1024, 2048, 4096};

    private static final float[] RADII = {0f, 64f, 128f, 160f};

    /** This session's map (the level's when it was made). */
    private static int size = 2048;

    private static float radius = 128f;

    /** How far the map reaches towards and away from the sun, in blocks. */
    private static final float DEPTH = 256f;

    /** The finest texel's share of the plain one, at the map's centre (shadow_distort.glsl). */
    private static final double SHADOW_NEAR = 0.15;

    /** The anchor moves in steps of this many blocks (a new map at each step). */
    private static final int ANCHOR_STEP = 4;

    /** Casters at most in one map (a chunk layer each). */
    private static final int MAX_CASTERS = 16384;

    /**
     * Bytes of the receivers' block: the shadow matrix, the sun in eye space, the settings, the
     * entities' and the world light's parts, and the previous map's matrix and fade.
     */
    public static final int BYTES =
            64
                    + 16
                    + 16
                    + SteinEntityShadows.BYTES
                    + SteinWorldLight.BYTES
                    + 64
                    + 16
                    + SteinClouds.BYTES;

    /**
     * The light leans this far to the south of the sun drawn in the sky: just enough that it is
     * never exactly straight down (the map's orientation needs a horizontal axis). It was 25
     * degrees, and the shadows and the light in the air came from somewhere else than the sun.
     */
    private static final double TILT = Math.toRadians(3.0);

    /**
     * The sun moves in steps of 1/SUN_STEPS of the day (twenty ticks, a second): a new map at each
     * step, faded in over {@link #FADE_NS} from the one before. Redrawn in place at every step, the
     * leaves' and the grass's shadows trembled (a texel's worth at each redraw).
     */
    private static final int SUN_STEPS = 1200;

    /** A new sun step's map takes over from the previous one in this long. */
    private static final long FADE_NS = 1_000_000_000L;

    /** The moon's shadows, a little weaker than the sun's. */
    private static final float MOON = 0.7f;

    /** The Lens level: 0 off, 1 low, 2 medium, 3 high (2 and up: entities cast too). */
    private static volatile int level;

    private static boolean failed;

    /** The live terrain slices (casters); each keeps its index here for removal in O(1). */
    private static final ArrayList<SteinTerrainSlice> SLICES = new ArrayList<>();

    /** Bumped when a slice comes or goes: the map is drawn again. */
    private static int slicesVersion;

    // ---- this frame (between the terrain's first layer and the end of the world) ----

    private static boolean active;
    private static float strength;
    private static final float[] SHADOW = new float[16];
    private static final float[] SUN_EYE = new float[3];

    // ---- the map ----

    private static SteinTexture map;
    private static SteinRenderTarget target;

    /** The two maps: the one drawn last ({@link #map}) and the one before, fading out. */
    private static final SteinTexture[] MAPS = new SteinTexture[2];

    private static final SteinRenderTarget[] TARGETS = new SteinRenderTarget[2];
    private static int current;

    /** What the previous map was drawn with: the light's view, its anchor and its snap. */
    private static final float[] PREV_VIEW = new float[9];

    private static final float[] DRAWN_VIEW = new float[9];
    private static int prevAnchorX;
    private static int prevAnchorY;
    private static int prevAnchorZ;
    private static double prevSnapX;
    private static double prevSnapY;
    private static double drawnSnapX;
    private static double drawnSnapY;

    /** When the last sun step's map was drawn (the fade's start), or MIN_VALUE: no fade. */
    private static long fadeStart = Long.MIN_VALUE;

    /** The previous map's share this frame (1 just after a step, 0 once faded). */
    private static float fade;

    private static final float[] PREV = new float[16];
    private static SteinTexture dummy;
    private static SteinSampler compare;
    private static SteinPipeline pipeline;
    private static SteinBuffer origins;
    private static SteinBuffer commands;
    private static SteinBuffer frame;

    /**
     * The map's centre moved off the anchor, in the light's plane (blocks), so that it falls on a
     * whole number of the finest texels: when the anchor steps, the texels near the camera land on
     * the same places and the shadows there stay still instead of sliding.
     */
    private static double snapX;

    private static double snapY;

    /** What the map was drawn for. */
    private static int mapAnchorX = Integer.MIN_VALUE;

    private static int mapAnchorY;
    private static int mapAnchorZ;
    private static long mapStep = Long.MIN_VALUE;
    private static int mapVersion = -1;
    private static boolean mapValid;
    private static float mapRadius;

    /** The sun's view (rows: right, up, back = towards the sun) and its ortho projection. */
    private static final float[] VIEW = new float[9];

    /** Scratch matrices (no allocation per map or frame). */
    private static final float[] PROJ = new float[16];

    private static final float[] INV = new float[16];

    /** The first-person hand is being drawn: its own projection, no shadows on it. */
    private static boolean hand;

    private SteinShadows() {}

    /** The Lens level (any thread). */
    public static void configure(int shadowLevel) {
        level = Math.max(0, Math.min(3, shadowLevel));
    }

    /**
     * In place of RenderManager.isRenderShadow() inside Render.doRenderShadowAndFire (patcher
     * rule): with the sun's shadows on, the game's round shadow under the entities goes away.
     */
    public static boolean roundShadow(Object renderManager) {
        return !(level > 0 && available()) && ((RenderManager) renderManager).isRenderShadow();
    }

    /** The Lens level (0 off). */
    public static int level() {
        return available() ? level : 0;
    }

    /** Whether the option can act: Stein GFX on one terrain buffer. */
    public static boolean available() {
        return SteinGfxOptions.ENABLED && SteinTerrain.ONE_BUFFER && !failed;
    }

    // ---- the casters ----

    /** A terrain slice was uploaded. */
    static void added(SteinTerrainSlice s) {
        s.shadowIndex = SLICES.size();
        SLICES.add(s);
        slicesVersion++;
    }

    /** A terrain slice was released. */
    static void removed(SteinTerrainSlice s) {
        int i = s.shadowIndex;
        if (i < 0 || i >= SLICES.size() || SLICES.get(i) != s) {
            return;
        }
        SteinTerrainSlice last = SLICES.remove(SLICES.size() - 1);
        if (last != s) {
            SLICES.set(i, last);
            last.shadowIndex = i;
        }
        s.shadowIndex = -1;
        slicesVersion++;
    }

    /** A slice seen in the translucent layer (water, stained glass): it does not cast. */
    static void translucent(Object handle) {
        SteinTerrainSlice s = (SteinTerrainSlice) handle;
        if (!s.translucent) {
            s.translucent = true;
            slicesVersion++;
        }
    }

    // ---- each frame ----

    /**
     * At the terrain's first layer: decides this frame's shadows, draws the map again if needed and
     * keeps the receivers' matrix. {@code m}: the camera's matrix (eye = m * (world - camera));
     * {@code cx, cy, cz}: the camera; {@code atlas}: the blocks' texture (the leaves' holes).
     */
    static void prepare(
            SteinDevice dev, float[] m, double cx, double cy, double cz, SteinTexture atlas) {
        if (active) {
            return;
        }
        active = true;
        strength = 0f;
        SteinEntityShadows.off();
        SteinGlow.frame();
        Minecraft mc = Minecraft.getMinecraft();
        WorldClient world = mc.theWorld;
        SteinMaterials.frame(world);
        // the rays' scene for this frame, at this camera (ray tracing on Vulkan only)
        SteinRayScene.frame(cx, cy, cz);
        if (world == null || !available()) {
            SteinWorldLight.none();
            return;
        }
        // the light: the sun (or the moon, opposite) by the hour, leaning TILT to the south; low at
        // the horizon and in the rain the shadows fade
        long step = (long) Math.floor(world.getCelestialAngle(0f) * SUN_STEPS);
        double a = step * (Math.PI * 2) / SUN_STEPS;
        double lx = -Math.sin(a);
        double ly = Math.cos(a) * Math.cos(TILT);
        double lz = Math.cos(a) * Math.sin(TILT);
        float k = 1f;
        if (ly < 0) {
            lx = -lx;
            ly = -ly;
            lz = -lz;
            k = MOON;
        }
        double len = Math.sqrt(lx * lx + ly * ly + lz * lz);
        lx /= len;
        ly /= len;
        lz /= len;
        float horizon = clamp01(((float) ly - 0.04f) / 0.18f);
        float rain = world.getRainStrength(mc.timer.renderPartialTicks);
        int lv = level;
        strength = k * horizon * horizon * (3f - 2f * horizon) * (1f - 0.85f * rain);
        if (strength <= 0.01f || lv == 0 || atlas == null || world.provider.getHasNoSky()) {
            strength = 0f;
        }
        SteinWorldLight.frame(world, strength, k < 1f, (float) ly, rain);
        if (strength == 0f) {
            SteinClouds.frame(dev, null, m, cx, cy, cz, lx, ly, lz);
            return;
        }
        SteinClouds.frame(dev, world, m, cx, cy, cz, lx, ly, lz);
        // the view: back = the light; right = up x back (never zero: the light leans south); up
        double rx = lz;
        double rz = -lx;
        double rl = Math.sqrt(rx * rx + rz * rz);
        rx /= rl;
        rz /= rl;
        VIEW[0] = (float) rx;
        VIEW[1] = 0f;
        VIEW[2] = (float) rz;
        VIEW[3] = (float) (ly * rz);
        VIEW[4] = (float) (lz * rx - lx * rz);
        VIEW[5] = (float) (-ly * rx);
        VIEW[6] = (float) lx;
        VIEW[7] = (float) ly;
        VIEW[8] = (float) lz;
        int ax = Math.floorDiv((int) Math.floor(cx), ANCHOR_STEP) * ANCHOR_STEP;
        int ay = Math.floorDiv((int) Math.floor(cy), ANCHOR_STEP) * ANCHOR_STEP;
        int az = Math.floorDiv((int) Math.floor(cz), ANCHOR_STEP) * ANCHOR_STEP;
        try {
            if (pipeline == null) {
                create(dev);
            }
            if (size != SIZES[lv]) {
                resize(dev, SIZES[lv]);
            }
            radius = RADII[lv];
            // the anchor in the light's plane, on the grid of the finest texel (near the centre)
            double texel = SHADOW_NEAR * 2.0 * radius / size;
            double lxA = VIEW[0] * (double) ax + VIEW[1] * (double) ay + VIEW[2] * (double) az;
            double lyA = VIEW[3] * (double) ax + VIEW[4] * (double) ay + VIEW[5] * (double) az;
            snapX = lxA - Math.floor(lxA / texel) * texel;
            snapY = lyA - Math.floor(lyA / texel) * texel;
            boolean same =
                    mapValid
                            && mapRadius == radius
                            && ax == mapAnchorX
                            && ay == mapAnchorY
                            && az == mapAnchorZ
                            && slicesVersion == mapVersion;
            if (!same || step != mapStep) {
                if (same) {
                    // only the sun moved: the drawn map stays as the previous one and the new one
                    // goes into the other, taking over in FADE_NS
                    System.arraycopy(DRAWN_VIEW, 0, PREV_VIEW, 0, 9);
                    prevAnchorX = mapAnchorX;
                    prevAnchorY = mapAnchorY;
                    prevAnchorZ = mapAnchorZ;
                    prevSnapX = drawnSnapX;
                    prevSnapY = drawnSnapY;
                    current ^= 1;
                    map = MAPS[current];
                    target = TARGETS[current];
                    fadeStart = System.nanoTime();
                } else {
                    // the anchor or the blocks changed: drawn again in place, no fade
                    fadeStart = Long.MIN_VALUE;
                }
                draw(dev, ax, ay, az, atlas);
                System.arraycopy(VIEW, 0, DRAWN_VIEW, 0, 9);
                drawnSnapX = snapX;
                drawnSnapY = snapY;
                mapAnchorX = ax;
                mapAnchorY = ay;
                mapAnchorZ = az;
                mapStep = step;
                mapVersion = slicesVersion;
                mapRadius = radius;
                mapValid = true;
            }
        } catch (RuntimeException | LinkageError e) {
            failed = true;
            strength = 0f;
            Log.warn("gfx", "shadows turned off for this session: " + e);
            return;
        }
        long sinceStep = System.nanoTime() - fadeStart;
        fade = fadeStart == Long.MIN_VALUE ? 0f : Math.max(0f, 1f - sinceStep / (float) FADE_NS);
        receivers(m, cx, cy, cz, ax, ay, az);
        if (lv >= 2) {
            SteinEntityShadows.frame(m, cx, cy, cz);
        }
    }

    /** The strength the world was drawn with, kept for the post-processing (SteinVolume). */
    private static float drawnStrength;

    private static float drawnFade;

    /** After the world's drawing: the HUD and the screens get no shadows. */
    public static void worldEnd() {
        drawnStrength = active && map != null && mapValid ? strength : 0f;
        drawnFade = fade;
        active = false;
        strength = 0f;
        hand = false;
    }

    /** Around the first-person hand (it has its own projection: no shadows on it). */
    static void hand(boolean drawing) {
        hand = drawing;
    }

    /** Writes the receivers' block at {@code p}: matrix, sun in eye space, settings. */
    static void write(long p) {
        float k = active && !hand ? strength : 0f;
        for (int i = 0; i < 16; i++) {
            MemoryUtil.memPutFloat(p + i * 4L, SHADOW[i]);
        }
        MemoryUtil.memPutFloat(p + 64, SUN_EYE[0]);
        MemoryUtil.memPutFloat(p + 68, SUN_EYE[1]);
        MemoryUtil.memPutFloat(p + 72, SUN_EYE[2]);
        MemoryUtil.memPutFloat(p + 76, 0f);
        MemoryUtil.memPutFloat(p + 80, k);
        MemoryUtil.memPutFloat(p + 84, 1f / size);
        MemoryUtil.memPutFloat(p + 88, 2f * radius / size); // a texel, in blocks
        // the edges open with the distance to the caster where the camera is enclosed (SteinVolume)
        MemoryUtil.memPutFloat(p + 92, SteinVolume.enclosed() > 0.35f ? 1f : 0f);
        SteinEntityShadows.write(p + 96, k > 0f);
        SteinWorldLight.write(p + 96 + SteinEntityShadows.BYTES, active);
        // the previous map's matrix and its share (0: no fade, the shaders skip it)
        long q = p + 96 + SteinEntityShadows.BYTES + SteinWorldLight.BYTES;
        float f = k > 0f ? fade : 0f;
        float[] prev = f > 0f ? PREV : SHADOW;
        for (int i = 0; i < 16; i++) {
            MemoryUtil.memPutFloat(q + i * 4L, prev[i]);
        }
        MemoryUtil.memPutFloat(q + 64, f);
        MemoryUtil.memPutFloat(q + 68, 0f);
        MemoryUtil.memPutFloat(q + 72, 0f);
        MemoryUtil.memPutFloat(q + 76, 0f);
        SteinClouds.write(q + 80, k > 0f);
        // cloudInfo.yzw: the sky's zenith for the reflections (SteinMaterials)
        SteinMaterials.write(q + 80 + 68, active);
    }

    /**
     * For the post-processing, after the world: the strength the world was drawn with (0: no map to
     * read), its eye -> map matrix ({@link #drawnMatrix}) and the map ({@link #drawnMap}).
     */
    static float drawnStrength() {
        return drawnStrength;
    }

    /** The receivers' matrix of the world just drawn (eye space -> the map's clip space). */
    static float[] drawnMatrix() {
        return SHADOW;
    }

    /** The previous map's share in the world just drawn (0: none) and its matrix. */
    static float drawnFade() {
        return drawnStrength > 0f ? drawnFade : 0f;
    }

    static float[] drawnPrevMatrix() {
        return PREV;
    }

    /** The previous map of the world just drawn (the current one when there is no fade). */
    static SteinTexture drawnPrevMap() {
        return drawnFade() > 0f ? MAPS[current ^ 1] : drawnMap();
    }

    /** The map of the world just drawn, or the stand-in when there is none. */
    static SteinTexture drawnMap() {
        return drawnStrength > 0f ? map : dummy;
    }

    /** The previous map to sample now (while it fades), else the same as {@link #texture}. */
    static SteinTexture previous() {
        return active && strength > 0f && fade > 0f && MAPS[current ^ 1] != null
                ? MAPS[current ^ 1]
                : texture();
    }

    /** The map to sample now: the shadow map, or a depth texture standing in. */
    static SteinTexture texture() {
        return active && strength > 0f && map != null ? map : dummy;
    }

    /** The light's view (rows: right, up, back), for the entities' map. */
    static float[] view() {
        return VIEW;
    }

    static float strength() {
        return active && !hand ? strength : 0f;
    }

    static SteinTexture dummy() {
        return dummy;
    }

    static SteinSampler sampler() {
        return compare;
    }

    /** Makes the stand-in early, so the shaders always have something to sample. */
    static void ensure(SteinDevice dev) {
        if (dummy == null) {
            dummy =
                    dev.createTexture(
                            SteinTexture.Desc.target("shadow: none", SteinFormat.DEPTH32F, 1, 1));
            compare =
                    dev.createSampler(
                            new SteinSampler.Desc(
                                    SteinSampler.Filter.LINEAR,
                                    SteinSampler.Filter.LINEAR,
                                    SteinSampler.Mip.NONE,
                                    SteinSampler.Wrap.CLAMP,
                                    SteinSampler.Wrap.CLAMP,
                                    1f,
                                    true));
        }
    }

    // ---- the map ----

    private static void create(SteinDevice dev) {
        ensure(dev);
        maps(dev);
        pipeline =
                dev.createPipeline(
                        SteinPipeline.builder("shadow map")
                                .vertex(
                                        SteinShader.load(
                                                SteinShader.Stage.VERTEX, "shadow_cast.vert"))
                                .fragment(
                                        SteinShader.load(
                                                SteinShader.Stage.FRAGMENT, "shadow_cast.frag"))
                                .layout(SteinTerrainPipelines.layout())
                                .texture("tex", 0)
                                .uniforms("Frame", 0)
                                .depth(SteinPipeline.Depth.LEQUAL, true)
                                .cull(SteinPipeline.Cull.NONE)
                                .build());
        if (!pipeline.valid()) {
            throw new IllegalStateException("the shadow map's pipeline did not link");
        }
        origins =
                upload(
                        dev,
                        "shadow: origins",
                        (long) MAX_CASTERS * SteinTerrain.ORIGIN_BYTES,
                        SteinBuffer.Usage.VERTEX);
        commands =
                upload(
                        dev,
                        "shadow: commands",
                        (long) MAX_CASTERS * SteinTerrain.COMMAND_BYTES,
                        SteinBuffer.Usage.INDIRECT);
        frame = upload(dev, "shadow: frame", 4096L, SteinBuffer.Usage.UNIFORM);
        Log.info("gfx", "shadows: " + size + "x" + size + " map");
    }

    /** A new level with another map size: the maps made again. */
    private static void resize(SteinDevice dev, int newSize) {
        for (int i = 0; i < 2; i++) {
            TARGETS[i].destroy();
            MAPS[i].destroy();
        }
        size = newSize;
        maps(dev);
        mapValid = false;
        fadeStart = Long.MIN_VALUE;
        Log.info("gfx", "shadows: " + size + "x" + size + " map");
    }

    /** The two maps at this session's size; the first is the current one. */
    private static void maps(SteinDevice dev) {
        for (int i = 0; i < 2; i++) {
            MAPS[i] =
                    dev.createTexture(
                            SteinTexture.Desc.target(
                                    "shadow map " + i, SteinFormat.DEPTH32F, size, size));
            TARGETS[i] = dev.createTarget(new SteinTexture[0], MAPS[i]);
        }
        current = 0;
        map = MAPS[0];
        target = TARGETS[0];
    }

    private static SteinBuffer upload(
            SteinDevice dev, String name, long bytes, SteinBuffer.Usage u) {
        return dev.createBuffer(new SteinBuffer.Desc(name, bytes * 3, SteinBuffer.Memory.UPLOAD, u))
                .ring(3);
    }

    /** Draws the casters' depth seen from the sun, around the anchor. */
    private static void draw(SteinDevice dev, int ax, int ay, int az, SteinTexture atlas) {
        SteinBuffer vb = SteinTerrain.oneBuffer();
        if (vb == null) {
            return;
        }
        long originAt = reserve(origins, (long) MAX_CASTERS * SteinTerrain.ORIGIN_BYTES, 16);
        long commandAt = reserve(commands, (long) MAX_CASTERS * SteinTerrain.COMMAND_BYTES, 4);
        long frameAt = reserve(frame, SteinTerrain.FRAME_BYTES, dev.caps().uniformAlignment);
        long o = origins.address(originAt);
        long q = commands.address(commandAt);
        float reach = radius + 24f;
        int n = 0;
        for (int i = 0, size = SLICES.size(); i < size && n < MAX_CASTERS; i++) {
            SteinTerrainSlice s = SLICES.get(i);
            if (s.translucent
                    || Math.abs(s.x + 8 - ax) > reach
                    || Math.abs(s.z + 8 - az) > reach
                    || Math.abs(s.y + 8 - ay) > DEPTH) {
                continue;
            }
            long po = o + (long) n * SteinTerrain.ORIGIN_BYTES;
            MemoryUtil.memPutFloat(po, s.x - ax);
            MemoryUtil.memPutFloat(po + 4, s.y - ay);
            MemoryUtil.memPutFloat(po + 8, s.z - az);
            MemoryUtil.memPutFloat(po + 12, 0f);
            long pq = q + (long) n * SteinTerrain.COMMAND_BYTES;
            MemoryUtil.memPutInt(pq, s.count / 4 * 6);
            MemoryUtil.memPutInt(pq + 4, 1);
            MemoryUtil.memPutInt(pq + 8, 0);
            MemoryUtil.memPutInt(pq + 12, s.first);
            MemoryUtil.memPutInt(pq + 16, n);
            n++;
        }
        writeFrame(frame.address(frameAt));
        origins.commitRing();
        commands.commitRing();
        frame.commitRing();
        SteinCommands c =
                dev.beginPass(SteinRenderPass.clear("shadow map", target, 0f, 0f, 0f, 0f));
        try {
            c.bindPipeline(pipeline);
            c.setDepthBias(true, 1.5f, 2f);
            c.bindIndexBuffer(SteinTerrain.indices(), SteinCommands.IndexType.UINT32);
            c.bindVertexBuffer(0, vb, 0);
            c.bindVertexBuffer(1, origins, originAt);
            c.bindUniforms(0, frame, frameAt, SteinTerrain.FRAME_BYTES);
            c.bindTexture(0, atlas, null);
            if (n > 0) {
                c.drawIndexedIndirect(commands, commandAt, n);
            }
            // off again: the bias is state that outlives the pass on some paths
            c.setDepthBias(false, 0f, 0f);
        } finally {
            c.end();
        }
    }

    /**
     * The shadow map's Frame block (the terrain's layout): the sun's ortho projection and view, the
     * alpha test for the leaves, no lightmap, fog or animation.
     */
    private static void writeFrame(long p) {
        MemoryUtil.memSet(p, 0, SteinTerrain.FRAME_BYTES);
        // ortho over [-RADIUS, RADIUS] x [-RADIUS, RADIUS], view z in [-DEPTH, DEPTH]
        float[] proj = PROJ;
        proj[0] = 1f / radius;
        proj[5] = 1f / radius;
        proj[12] = (float) (snapX / radius);
        proj[13] = (float) (snapY / radius);
        proj[10] = -1f / DEPTH;
        proj[15] = 1f;
        for (int i = 0; i < 16; i++) {
            MemoryUtil.memPutFloat(p + i * 4L, proj[i]);
        }
        // the view as a column-major matrix (rows right, up, back), no translation: the origins
        // are relative to the anchor
        for (int col = 0; col < 3; col++) {
            for (int row = 0; row < 3; row++) {
                MemoryUtil.memPutFloat(p + 64 + (col * 4 + row) * 4L, VIEW[row * 3 + col]);
            }
        }
        MemoryUtil.memPutFloat(p + 64 + 60, 1f);
        MemoryUtil.memPutFloat(p + 160, 0.5f); // alpha reference: the leaves' holes
        MemoryUtil.memPutFloat(p + 172, 1f); // textured
    }

    /** The receivers' matrices (the map's and, while it fades, the previous one's), the sun. */
    private static void receivers(
            float[] m, double cx, double cy, double cz, int ax, int ay, int az) {
        if (fade > 0f) {
            lightMatrix(
                    PREV,
                    m,
                    cx - prevAnchorX,
                    cy - prevAnchorY,
                    cz - prevAnchorZ,
                    radius,
                    DEPTH,
                    PREV_VIEW);
            PREV[12] += (float) (prevSnapX / radius);
            PREV[13] += (float) (prevSnapY / radius);
        }
        lightMatrix(SHADOW, m, cx - ax, cy - ay, cz - az, radius, DEPTH);
        SHADOW[12] += (float) (snapX / radius);
        SHADOW[13] += (float) (snapY / radius);
        // the sun (back) in eye space: R * back
        float bx = VIEW[6];
        float by = VIEW[7];
        float bz = VIEW[8];
        SUN_EYE[0] = m[0] * bx + m[4] * by + m[8] * bz;
        SUN_EYE[1] = m[1] * bx + m[5] * by + m[9] * bz;
        SUN_EYE[2] = m[2] * bx + m[6] * by + m[10] * bz;
    }

    /**
     * Eye space -> the clip space of a map seen from the light: eye = m * (world - camera), so
     * world - anchor = m^-1 * eye + (camera - anchor) ({@code d}); then the light's view and an
     * ortho over {@code radius} blocks and {@code depth} towards and away from the light.
     */
    static void lightMatrix(
            float[] out, float[] m, double dx, double dy, double dz, float radius, float depth) {
        lightMatrix(out, m, dx, dy, dz, radius, depth, VIEW);
    }

    /** {@link #lightMatrix} with the light's view {@code view} (rows right, up, back). */
    private static void lightMatrix(
            float[] out,
            float[] m,
            double dx,
            double dy,
            double dz,
            float radius,
            float depth,
            float[] view) {
        // m^-1 for a rigid matrix: R^T and -R^T t
        float[] inv = INV;
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                inv[c * 4 + r] = m[r * 4 + c];
            }
        }
        for (int r = 0; r < 3; r++) {
            inv[12 + r] = -(inv[r] * m[12] + inv[4 + r] * m[13] + inv[8 + r] * m[14]);
        }
        inv[12] += (float) dx;
        inv[13] += (float) dy;
        inv[14] += (float) dz;
        inv[15] = 1f;
        // view * inv (the view's rows), then the ortho's scale
        for (int c = 0; c < 4; c++) {
            for (int r = 0; r < 3; r++) {
                float v =
                        view[r * 3] * inv[c * 4]
                                + view[r * 3 + 1] * inv[c * 4 + 1]
                                + view[r * 3 + 2] * inv[c * 4 + 2];
                out[c * 4 + r] = v * (r == 2 ? -1f / depth : 1f / radius);
            }
            out[c * 4 + 3] = c == 3 ? 1f : 0f;
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

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
