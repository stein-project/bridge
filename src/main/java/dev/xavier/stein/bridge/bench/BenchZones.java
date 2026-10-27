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
    public static final int RENDER = 2;
    public static final int WORLD = 3;
    public static final int LIGHTMAP = 4;
    public static final int TERRAIN_SETUP = 5;
    public static final int CHUNK_UPLOAD = 6;
    public static final int TERRAIN = 7;
    public static final int SKY = 8;
    public static final int CLOUDS = 9;
    public static final int ENTITIES = 10;
    public static final int PARTICLES = 11;
    public static final int WEATHER = 12;
    public static final int MODS_WORLD = 13;
    public static final int HAND = 14;
    public static final int HUD = 15;
    public static final int MODS_HUD = 16;
    public static final int SCREEN = 17;
    public static final int MODS_SCREEN = 18;
    public static final int SUBMIT = 19;
    public static final int SWAP = 20;
    public static final int EVENTS = 21;
    public static final int AWAIT = 22;
    public static final int LIMITER = 23;
    public static final int IDLE = 24;
    public static final int BENCH = 25;

    public static final int COUNT = 26;

    /** The column names in the report, by zone. */
    static final String[] NAMES = {
        "tick",
        "mods_tick",
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
        "submit",
        "swap",
        "events",
        "await_gpu",
        "fps_limiter",
        "idle_limiter",
        "bench"
    };

    /** The zone each one is inside of (-1: directly in the frame). */
    static final int[] PARENT = {
        -1, TICK, -1, RENDER, WORLD, WORLD, WORLD, WORLD, WORLD, WORLD, WORLD, WORLD, WORLD, WORLD,
        WORLD, RENDER, HUD, RENDER, SCREEN, -1, -1, -1, -1, -1, -1, -1
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
