/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.bench;

/**
 * The places of a frame the benchmark times on the CPU: the game's own phases (by patcher rules
 * around their call sites), the mods' events and the Bridge's frame handoff. Each zone sums the
 * time between its begin and its end within the frame, so a zone entered four times (the terrain
 * layers) reports the four together; a zone includes the zones under it ({@link #PARENT}).
 *
 * <p>Without {@code -Dsteinbridge.bench} every hook returns on a static final flag, which the JIT
 * removes; with it, a zone costs two {@code System.nanoTime}. On the render thread.
 */
public final class BenchZones {

    private BenchZones() {}

    public static final int TICK = 0;
    public static final int MODS_TICK = 1;
    public static final int FRAME_START = 2;
    public static final int RENDER = 3;
    public static final int WORLD = 4;
    public static final int LIGHTMAP = 5;
    public static final int TERRAIN_SETUP = 6;
    public static final int CHUNK_UPLOAD = 7;
    public static final int TERRAIN = 8;
    public static final int SKY = 9;
    public static final int CLOUDS = 10;
    public static final int ENTITIES = 11;
    public static final int PARTICLES = 12;
    public static final int WEATHER = 13;
    public static final int MODS_WORLD = 14;
    public static final int HAND = 15;
    public static final int HUD = 16;
    public static final int MODS_HUD = 17;
    public static final int SCREEN = 18;
    public static final int MODS_SCREEN = 19;
    public static final int BLIT = 20;
    public static final int SUBMIT = 21;
    public static final int SWAP = 22;
    public static final int EVENTS = 23;
    public static final int AWAIT = 24;
    public static final int LIMITER = 25;
    public static final int IDLE = 26;
    public static final int BENCH = 27;

    /** TextureManager.tick in runTick: the animated textures (TextureMap.updateAnimations). */
    public static final int TICK_TEXTURES = 28;

    /**
     * WorldClient.updateEntities in runTick: every entity and tile entity of the client's world.
     */
    public static final int TICK_ENTITIES = 29;

    /** WorldClient.tick in runTick: the client world's own tick (chunks, sky, weather). */
    public static final int TICK_WORLD = 30;

    /** EffectRenderer.updateEffects in runTick: the particles. */
    public static final int TICK_PARTICLES = 31;

    /**
     * The rest of the tick: {@link #TICK} minus the zones under it, worked out when the frame's row
     * is taken (it has no hooks of its own).
     */
    public static final int TICK_OTHER = 32;

    public static final int COUNT = 33;

    /** The column names in the report, by zone. */
    static final String[] NAMES = {
        "tick",
        "mods_tick",
        "frame_start",
        "render",
        "world",
        "lightmap",
        "terrain_setup",
        "chunk_upload",
        "terrain",
        "sky",
        "clouds",
        "entities",
        "particles",
        "weather",
        "mods_world",
        "hand",
        "hud",
        "mods_hud",
        "screen",
        "mods_screen",
        "blit",
        "submit",
        "swap",
        "events",
        "await_gpu",
        "fps_limiter",
        "idle_limiter",
        "bench",
        "tick_textures",
        "tick_entities",
        "tick_world",
        "tick_particles",
        "tick_other"
    };

    /** The zone each one is inside of (-1: directly in the frame). */
    static final int[] PARENT = {
        -1, TICK, -1, -1, RENDER, WORLD, WORLD, WORLD, WORLD, WORLD, WORLD, WORLD, WORLD, WORLD,
        WORLD, WORLD, RENDER, HUD, RENDER, SCREEN, -1, -1, -1, -1, -1, -1, -1, -1, TICK, TICK, TICK,
        TICK, TICK
    };

    private static final boolean ON = BenchOptions.ON;

    private static final long[] START = new long[COUNT];

    /** Time spent in each zone in the current frame (ns). */
    static final long[] SPENT = new long[COUNT];

    /** How many times each zone was entered in the current frame. */
    static final int[] CALLS = new int[COUNT];

    public static void begin(int zone) {
        if (ON) {
            START[zone] = System.nanoTime();
        }
    }

    public static void end(int zone) {
        if (ON) {
            long s = START[zone];
            if (s != 0L) {
                SPENT[zone] += System.nanoTime() - s;
                CALLS[zone]++;
                START[zone] = 0L;
            }
        }
    }

    /** The tick's time outside its timed sub-zones, before the frame's row is taken. */
    static void tickRest() {
        long rest =
                SPENT[TICK]
                        - SPENT[MODS_TICK]
                        - SPENT[TICK_TEXTURES]
                        - SPENT[TICK_ENTITIES]
                        - SPENT[TICK_WORLD]
                        - SPENT[TICK_PARTICLES];
        SPENT[TICK_OTHER] = Math.max(0L, rest);
    }

    /** At the end of the frame, after the frame's row has been taken. */
    static void clear() {
        for (int i = 0; i < COUNT; i++) {
            SPENT[i] = 0L;
            CALLS[i] = 0;
        }
    }

    // ---- the patcher's hooks (static, no arguments: redirects-opt.tsv and redirects-mods.tsv)
    // ----

    public static void tickBegin() {
        begin(TICK);
    }

    public static void tickEnd() {
        end(TICK);
    }

    public static void tickTexturesBegin() {
        begin(TICK_TEXTURES);
    }

    public static void tickTexturesEnd() {
        end(TICK_TEXTURES);
    }

    public static void tickEntitiesBegin() {
        begin(TICK_ENTITIES);
    }

    public static void tickEntitiesEnd() {
        end(TICK_ENTITIES);
    }

    public static void tickWorldBegin() {
        begin(TICK_WORLD);
    }

    public static void tickWorldEnd() {
        end(TICK_WORLD);
    }

    public static void tickParticlesBegin() {
        begin(TICK_PARTICLES);
    }

    public static void tickParticlesEnd() {
        end(TICK_PARTICLES);
    }

    /**
     * The game loop's frame start: the main framebuffer cleared and bound (GlStateManager.clear and
     * Framebuffer.bindFramebuffer in runGameLoop), before updateCameraAndRender, which no other
     * zone covers.
     */
    public static void frameStartBegin() {
        begin(FRAME_START);
    }

    public static void frameStartEnd() {
        end(FRAME_START);
    }

    /**
     * The main framebuffer drawn to the window (framebufferRender in runGameLoop), after the render
     * zone and before the handoff.
     */
    public static void blitBegin() {
        begin(BLIT);
    }

    public static void blitEnd() {
        end(BLIT);
    }

    public static void renderBegin() {
        begin(RENDER);
    }

    public static void renderEnd() {
        end(RENDER);
    }

    public static void worldBegin() {
        begin(WORLD);
    }

    public static void worldEnd() {
        end(WORLD);
    }

    public static void lightmapBegin() {
        begin(LIGHTMAP);
    }

    public static void lightmapEnd() {
        end(LIGHTMAP);
    }

    public static void terrainSetupBegin() {
        begin(TERRAIN_SETUP);
    }

    public static void terrainSetupEnd() {
        end(TERRAIN_SETUP);
    }

    public static void chunkUploadBegin() {
        begin(CHUNK_UPLOAD);
    }

    public static void chunkUploadEnd() {
        end(CHUNK_UPLOAD);
    }

    public static void terrainBegin() {
        begin(TERRAIN);
    }

    public static void terrainEnd() {
        end(TERRAIN);
    }

    public static void skyBegin() {
        begin(SKY);
    }

    public static void skyEnd() {
        end(SKY);
    }

    public static void cloudsBegin() {
        begin(CLOUDS);
    }

    public static void cloudsEnd() {
        end(CLOUDS);
    }

    public static void entitiesBegin() {
        begin(ENTITIES);
    }

    public static void entitiesEnd() {
        end(ENTITIES);
    }

    public static void particlesBegin() {
        begin(PARTICLES);
    }

    public static void particlesEnd() {
        end(PARTICLES);
    }

    public static void weatherBegin() {
        begin(WEATHER);
    }

    public static void weatherEnd() {
        end(WEATHER);
    }

    public static void handBegin() {
        begin(HAND);
    }

    public static void handEnd() {
        end(HAND);
    }

    public static void hudBegin() {
        begin(HUD);
    }

    public static void hudEnd() {
        end(HUD);
    }

    public static void screenBegin() {
        begin(SCREEN);
    }

    public static void screenEnd() {
        end(SCREEN);
    }
}
