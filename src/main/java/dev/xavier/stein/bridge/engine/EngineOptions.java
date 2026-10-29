/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import dev.xavier.stein.bridge.core.Opt;

/**
 * Every {@code -D} option of the engine package, read once through {@link Opt}. {@code vanilla*}
 * options turn one optimisation off (for A/B), {@code check*} options run the optimised and the
 * original path and compare them.
 */
public final class EngineOptions {

    private EngineOptions() {}

    /**
     * {@code -Dsteinbridge.opt.noIdleLimit=true}: no frame limit while the game is idle (out of
     * focus and title screen 30, Esc menu 60; default: limited).
     */
    public static final boolean NO_IDLE_LIMIT = Opt.flag("steinbridge.opt.noIdleLimit");

    /**
     * {@code -Dsteinbridge.diag.idleLimit=true}: keeps the idle frame limit on the test bench too,
     * where {@code -Dsteinbridge.diag.background} turns it off so measurements run at full speed
     * (default: off).
     */
    public static final boolean IDLE_LIMIT_IN_BENCH = Opt.flag("steinbridge.diag.idleLimit");

    /**
     * {@code -Dsteinbridge.diag.loadingShot=true}: the loading screen saves its frame, through the
     * game's own capture, to screenshots/loading.png when the opening's bar passes half (default:
     * off).
     */
    public static final boolean LOADING_SHOT = Opt.flag("steinbridge.diag.loadingShot");

    // ---- chunks / terrain ----

    /**
     * {@code -Dsteinbridge.opt.vanillaActiveChunks=true}: turns the active-chunks bookkeeping
     * optimisation off (default: on).
     */
    static final boolean VANILLA_ACTIVE_CHUNKS = Opt.flag("steinbridge.opt.vanillaActiveChunks");

    /**
     * {@code -Dsteinbridge.opt.vanillaActiveChunkFill=true}: keeps one player per chunk but lets
     * the game insert every chunk of their squares, overlaps included, instead of each chunk once
     * (default: once).
     */
    static final boolean VANILLA_ACTIVE_CHUNK_FILL =
            Opt.flag("steinbridge.opt.vanillaActiveChunkFill");

    /**
     * {@code -Dsteinbridge.opt.checkActiveChunks=true}: runs both active-chunk paths every tick and
     * compares the sets and the ambient countdown (default: off).
     */
    static final boolean CHECK_ACTIVE_CHUNKS = Opt.flag("steinbridge.opt.checkActiveChunks");

    /**
     * {@code -Dsteinbridge.opt.vanillaChunkAt=true}: does the chunk lookup every frame instead of
     * caching it per column (default: cached).
     */
    static final boolean VANILLA_CHUNK_AT = Opt.flag("steinbridge.opt.vanillaChunkAt");

    /**
     * {@code -Dsteinbridge.opt.vanillaClientChunks=true}: the client world's chunk lookup goes to
     * the chunk map every time instead of the table of recent columns (default: table).
     */
    static final boolean VANILLA_CLIENT_CHUNKS = Opt.flag("steinbridge.opt.vanillaClientChunks");

    /**
     * {@code -Dsteinbridge.opt.checkClientChunks=true}: uses the table of recent columns and
     * compares every answer with the chunk map (default: off).
     */
    static final boolean CHECK_CLIENT_CHUNKS = Opt.flag("steinbridge.opt.checkClientChunks");

    /**
     * {@code -Dsteinbridge.opt.vanillaActiveReuse=true}: rebuilds the client's active-chunk set
     * every tick even when the players stayed in the same chunks (default: kept).
     */
    static final boolean VANILLA_ACTIVE_REUSE = Opt.flag("steinbridge.opt.vanillaActiveReuse");

    /**
     * {@code -Dsteinbridge.opt.checkActiveReuse=true}: rebuilds the kept active-chunk set anyway
     * and compares it, in iteration order, with the kept one; also runs the skipped retainAll and
     * checks it removed nothing (default: off).
     */
    static final boolean CHECK_ACTIVE_REUSE = Opt.flag("steinbridge.opt.checkActiveReuse");

    /**
     * {@code -Dsteinbridge.opt.vanillaPlayerRange=true}: the closest-player and player-in-range
     * searches test the spectator mode before the distance, as in 1.8.9 (default: distance first).
     */
    static final boolean VANILLA_PLAYER_RANGE = Opt.flag("steinbridge.opt.vanillaPlayerRange");

    /**
     * {@code -Dsteinbridge.opt.vanillaCull=true}: uses the game's visibility search and chunk
     * culling (default: Stein's).
     */
    static final boolean VANILLA_CULL = Opt.flag("steinbridge.opt.vanillaCull");

    /**
     * {@code -Dsteinbridge.opt.checkCull=true}: computes the visibility graph both ways and
     * compares them (default: off).
     */
    static final boolean CHECK_CULL = Opt.flag("steinbridge.opt.checkCull");

    /**
     * {@code -Dsteinbridge.opt.vanillaChunkHash=true}: restores the original chunk hash (default:
     * Stein's).
     */
    static final boolean VANILLA_CHUNK_HASH = Opt.flag("steinbridge.opt.vanillaChunkHash");

    /**
     * {@code -Dsteinbridge.opt.vanillaChunkArena=true}: one buffer per chunk, as in the game,
     * instead of terrain per region; only matters without Stein GFX (default: regions).
     */
    static final boolean VANILLA_CHUNK_ARENA = Opt.flag("steinbridge.opt.vanillaChunkArena");

    /**
     * {@code -Dsteinbridge.opt.arenaPointers=true}: redoes the buffer bind and the 4 pointers on
     * each terrain run instead of using the region's VAO; only matters without Stein GFX (default:
     * the VAO).
     */
    static final boolean ARENA_POINTERS = Opt.flag("steinbridge.opt.arenaPointers");

    /**
     * {@code -Dsteinbridge.opt.vanillaInfoFilter=true}: loops over the whole render-info list
     * instead of the filtered copy (default: filtered copy).
     */
    static final boolean VANILLA_INFO_FILTER = Opt.flag("steinbridge.opt.vanillaInfoFilter");

    /**
     * {@code -Dsteinbridge.opt.checkInfoFilter=true}: rebuilds the filtered copy on every use and
     * compares it with the stored one (default: off).
     */
    static final boolean CHECK_INFO_FILTER = Opt.flag("steinbridge.opt.checkInfoFilter");

    /**
     * {@code -Dsteinbridge.visuals.keepChunks=N}: initial chunk keeping mode: 0 off, 1 memory, 2
     * disk (default: 0).
     */
    static final int KEEP_CHUNKS = Opt.integer("steinbridge.visuals.keepChunks", 0);

    /**
     * {@code -Dsteinbridge.visuals.keepChunksMB=N}: initial size limit of the kept chunks in
     * megabytes (default: 512).
     */
    static final int KEEP_CHUNKS_MB = Opt.integer("steinbridge.visuals.keepChunksMB", 512);

    /**
     * {@code -Dsteinbridge.visuals.keepDebug=true}: every 5 s, prints stored columns, bytes and
     * what the disk has (default: off).
     */
    static final boolean KEEP_DEBUG = Opt.flag("steinbridge.visuals.keepDebug");

    /**
     * {@code -Dsteinbridge.visuals.chunkUploads=N}: initial limit of chunk uploads per frame, 0 =
     * no limit (default: 0).
     */
    static final int CHUNK_UPLOADS = Opt.integer("steinbridge.visuals.chunkUploads", 0);

    /**
     * {@code -Dsteinbridge.visuals.waitNeighbors=true}: initially holds a chunk until its
     * neighbours have arrived (default: off).
     */
    static final boolean WAIT_NEIGHBORS = Opt.flag("steinbridge.visuals.waitNeighbors");

    /**
     * {@code -Dsteinbridge.opt.vanillaChunkPrepare=true}: skips the chunk preparation (default:
     * prepared).
     */
    static final boolean VANILLA_CHUNK_PREPARE = Opt.flag("steinbridge.opt.vanillaChunkPrepare");

    /**
     * {@code -Dsteinbridge.opt.vanillaBfs=true}: uses the game's breadth-first visibility search
     * (default: Stein's).
     */
    static final boolean VANILLA_BFS = Opt.flag("steinbridge.opt.vanillaBfs");

    /**
     * {@code -Dsteinbridge.opt.checkBfs=true}: runs both searches and compares them (default: off).
     */
    static final boolean CHECK_BFS = Opt.flag("steinbridge.opt.checkBfs");

    /**
     * {@code -Dsteinbridge.opt.vanillaNearBuild=true}: builds everything adjacent to the player
     * right away (default: Stein's order).
     */
    static final boolean VANILLA_NEAR_BUILD = Opt.flag("steinbridge.opt.vanillaNearBuild");

    /**
     * {@code -Dsteinbridge.opt.vanillaArrivalSearch=true}: repeats the search on chunk arrival as
     * the game does (default: Stein's).
     */
    static final boolean VANILLA_ARRIVAL_SEARCH = Opt.flag("steinbridge.opt.vanillaArrivalSearch");

    /**
     * {@code -Dsteinbridge.opt.vanillaChunkThreads=true}: uses 2 chunk build threads, as in the
     * game (default: off).
     */
    static final boolean VANILLA_CHUNK_THREADS = Opt.flag("steinbridge.opt.vanillaChunkThreads");

    /**
     * {@code -Dsteinbridge.opt.chunkThreads=N}: number of chunk build threads (default: half the
     * logical CPUs, between 2 and 8).
     */
    static final int CHUNK_THREADS =
            Opt.integer(
                    "steinbridge.opt.chunkThreads",
                    Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors() / 2)));

    /**
     * {@code -Dsteinbridge.diag.terrainWatch=true}: every 5 s, prints the state of the terrain
     * watchdog diagnostic (default: off).
     */
    static final boolean TERRAIN_WATCH = Opt.flag("steinbridge.diag.terrainWatch");

    // ---- armor / glint / skins ----

    /**
     * {@code -Dsteinbridge.opt.vanillaArmorGlint=true}: draws the armor glint piece by piece, as in
     * 1.8.9 (default: Stein's).
     */
    static final boolean VANILLA_ARMOR_GLINT = Opt.flag("steinbridge.opt.vanillaArmorGlint");

    /**
     * {@code -Dsteinbridge.gfx.debugGlint=true}: prints the armor glint replay diagnostic (default:
     * off).
     */
    static final boolean DEBUG_GLINT = Opt.flag("steinbridge.gfx.debugGlint");

    /**
     * {@code -Dsteinbridge.opt.vanillaArmorTexture=true}: computes the armor texture on every
     * piece, as the game does (default: cached).
     */
    static final boolean VANILLA_ARMOR_TEXTURE = Opt.flag("steinbridge.opt.vanillaArmorTexture");

    /**
     * {@code -Dsteinbridge.opt.vanillaSkinLoad=true}: loads skins as the original does (default:
     * asynchronous).
     */
    static final boolean VANILLA_SKIN_LOAD = Opt.flag("steinbridge.opt.vanillaSkinLoad");

    /**
     * {@code -Dsteinbridge.opt.noSharedSkin=true}: one texture per waiting skin, as before the
     * default skin was shared, for the A/B measurement (default: off, the waiting skins share one
     * texture).
     */
    static final boolean NO_SHARED_SKIN = Opt.flag("steinbridge.opt.noSharedSkin");

    // ---- visuals ----

    /**
     * {@code -Dsteinbridge.visuals.compactAngles=true}: initially uses the compact angle table
     * (default: off).
     */
    static final boolean COMPACT_ANGLES = Opt.flag("steinbridge.visuals.compactAngles");

    /**
     * {@code -Dsteinbridge.visuals.directRender=true}: initially renders directly to the window
     * (default: off).
     */
    static final boolean DIRECT_RENDER = Opt.flag("steinbridge.visuals.directRender");

    /**
     * {@code -Dsteinbridge.visuals.glint=N}: initial enchantment glint mode: 0 on, 1 near only, 2
     * off (default: 0).
     */
    static final int GLINT = Opt.integer("steinbridge.visuals.glint", 0);

    /**
     * {@code -Dsteinbridge.visuals.glintNear=N}: initial near-glint distance in blocks, parsed as a
     * float (default: 16).
     */
    static final String GLINT_NEAR = Opt.text("steinbridge.visuals.glintNear", "16");

    /**
     * {@code -Dsteinbridge.visuals.nametags=N}: initial maximum nametag distance in blocks, 0 = the
     * game's (default: 0).
     */
    static final int NAMETAGS = Opt.integer("steinbridge.visuals.nametags", 0);

    /**
     * {@code -Dsteinbridge.visuals.armor=false}: hides armor on entities; only the exact value
     * {@code false} turns it off (default: on, armor shown).
     */
    static final boolean ARMOR = Opt.unlessFalse("steinbridge.visuals.armor");

    /**
     * {@code -Dsteinbridge.visuals.fog=N}: initial fog mode: 0 normal, otherwise light or off
     * (default: 0).
     */
    static final int FOG = Opt.integer("steinbridge.visuals.fog", 0);

    /**
     * {@code -Dsteinbridge.visuals.cloudHeight=N}: initial cloud height offset in blocks (default:
     * 0).
     */
    static final int CLOUD_HEIGHT = Opt.integer("steinbridge.visuals.cloudHeight", 0);

    /**
     * {@code -Dsteinbridge.oldAnimations=false}: turns all the old 1.7 animations off; only the
     * exact value {@code false} does (default: on).
     */
    static final boolean OLD_ANIMATIONS = Opt.unlessFalse("steinbridge.oldAnimations");

    // ---- matrices / text / models ----

    /**
     * {@code -Dsteinbridge.opt.vanillaMatrix=true}: every matrix operation straight to the driver,
     * as in 1.8.9; only in the fixed-function fallback (default: matrices kept in Java).
     */
    static final boolean VANILLA_MATRIX = Opt.flag("steinbridge.opt.vanillaMatrix");

    /**
     * {@code -Dsteinbridge.opt.checkMatrix=true}: everything straight to the driver and the Java
     * stacks in parallel, compared every 64 draws; only in the fixed-function fallback (default:
     * off).
     */
    static final boolean CHECK_MATRIX = Opt.flag("steinbridge.opt.checkMatrix");

    /**
     * {@code -Dsteinbridge.opt.vanillaTextureMatrix=true}: TEXTURE straight to the driver, only
     * MODELVIEW in Java; only in the fixed-function fallback (default: both in Java).
     */
    static final boolean VANILLA_TEXTURE_MATRIX = Opt.flag("steinbridge.opt.vanillaTextureMatrix");

    /**
     * {@code -Dsteinbridge.opt.vanillaClouds=true}: rebuilds the clouds every frame, as in 1.8.9
     * (default: Stein's cache).
     */
    static final boolean VANILLA_CLOUDS = Opt.flag("steinbridge.opt.vanillaClouds");

    /**
     * {@code -Dsteinbridge.opt.checkClouds=true}: uses the cache and every second compares it with
     * the game's clouds (default: off).
     */
    static final boolean CHECK_CLOUDS = Opt.flag("steinbridge.opt.checkClouds");

    /**
     * {@code -Dsteinbridge.opt.vanillaCraftInit=true}: restores the crafting search call, for A/B
     * (default: skipped).
     */
    static final boolean VANILLA_CRAFT_INIT = Opt.flag("steinbridge.opt.vanillaCraftInit");

    /**
     * {@code -Dsteinbridge.opt.checkCraftInit=true}: runs the original crafting search and checks
     * the result slot (default: off).
     */
    static final boolean CHECK_CRAFT_INIT = Opt.flag("steinbridge.opt.checkCraftInit");

    /**
     * {@code -Dsteinbridge.opt.vanillaEnchCache=true}: always does the NBT enchantment lookup
     * (default: cached).
     */
    static final boolean VANILLA_ENCH_CACHE = Opt.flag("steinbridge.opt.vanillaEnchCache");

    /**
     * {@code -Dsteinbridge.opt.checkEnchCache=true}: uses the cache and checks every answer against
     * the NBT lookup (default: off).
     */
    static final boolean CHECK_ENCH_CACHE = Opt.flag("steinbridge.opt.checkEnchCache");

    /**
     * {@code -Dsteinbridge.opt.vanillaFont=true}: draws text in immediate mode, as in 1.8.9
     * (default: batched).
     */
    static final boolean VANILLA_FONT = Opt.flag("steinbridge.opt.vanillaFont");

    /**
     * {@code -Dsteinbridge.opt.vanillaFontIndex=true}: always uses String.indexOf for font glyphs
     * (default: indexed).
     */
    static final boolean VANILLA_FONT_INDEX = Opt.flag("steinbridge.opt.vanillaFontIndex");

    /**
     * {@code -Dsteinbridge.opt.vanillaHudBatch=true}: draws one draw call per status bar icon
     * (default: batched).
     */
    static final boolean VANILLA_HUD_BATCH = Opt.flag("steinbridge.opt.vanillaHudBatch");

    /**
     * {@code -Dsteinbridge.opt.checkHudBatch=true}: on each icon joined, checks that texture,
     * color, blend, alpha and depth are still those of the first (default: off).
     */
    static final boolean CHECK_HUD_BATCH = Opt.flag("steinbridge.opt.checkHudBatch");

    /**
     * {@code -Dsteinbridge.opt.vanillaItemModel=true}: always takes the original item model path
     * (default: cached).
     */
    static final boolean VANILLA_ITEM_MODEL = Opt.flag("steinbridge.opt.vanillaItemModel");

    /**
     * {@code -Dsteinbridge.opt.vanillaItemEdges=true}: generated items get edges only around fully
     * transparent pixels, as in the original (default: also around nearly transparent ones, see
     * ItemEdges).
     */
    static final boolean VANILLA_ITEM_EDGES = Opt.flag("steinbridge.opt.vanillaItemEdges");

    /**
     * {@code -Dsteinbridge.opt.vanillaModelBatch=true}: draws each model part with its own
     * glLoadMatrixf + glCallList in the fixed-function fallback (default: batched from a persistent
     * buffer).
     */
    static final boolean VANILLA_MODEL_BATCH = Opt.flag("steinbridge.opt.vanillaModelBatch");

    /**
     * {@code -Dsteinbridge.opt.vanillaItemTint=true}: a tinted item always takes the original path
     * (default: off).
     */
    static final boolean VANILLA_ITEM_TINT = Opt.flag("steinbridge.opt.vanillaItemTint");

    /**
     * {@code -Dsteinbridge.opt.checkItemModel=true}: takes the original path and compares the bytes
     * that would be sent (default: off).
     */
    static final boolean CHECK_ITEM_MODEL = Opt.flag("steinbridge.opt.checkItemModel");

    /**
     * {@code -Dsteinbridge.opt.vanillaText=true}: always uses the game's font renderer (default:
     * Stein's).
     */
    static final boolean VANILLA_TEXT = Opt.flag("steinbridge.opt.vanillaText");

    /**
     * {@code -Dsteinbridge.opt.vanillaSignText=true}: redoes the sign layout every frame (default:
     * cached).
     */
    static final boolean VANILLA_SIGN_TEXT = Opt.flag("steinbridge.opt.vanillaSignText");

    /**
     * {@code -Dsteinbridge.opt.checkSignText=true}: uses the cache and checks every answer against
     * the original layout (default: off).
     */
    static final boolean CHECK_SIGN_TEXT = Opt.flag("steinbridge.opt.checkSignText");

    // ---- entities / data watchers / light ----

    /**
     * {@code -Dsteinbridge.opt.vanillaDataWatcher=true}: reads data watchers with the lock, as in
     * 1.8.9, and turns off the mirrors (default: off).
     */
    static final boolean VANILLA_DATA_WATCHER = Opt.flag("steinbridge.opt.vanillaDataWatcher");

    /**
     * {@code -Dsteinbridge.opt.watchedMirror=false}: stops mirroring data watcher values; only the
     * exact value {@code false} does (default: on, the values are mirrored).
     */
    static final boolean WATCHED_MIRROR = Opt.unlessFalse("steinbridge.opt.watchedMirror");

    /**
     * {@code -Dsteinbridge.opt.checkWatchedMirror=true}: uses the mirror and checks every read
     * against the WatchableObject (default: off).
     */
    static final boolean CHECK_WATCHED_MIRROR = Opt.flag("steinbridge.opt.checkWatchedMirror");

    /**
     * {@code -Dsteinbridge.opt.entityFlags=false}: stops mirroring the entity flags; only the exact
     * value {@code false} does (default: on, the flags are mirrored).
     */
    static final boolean ENTITY_FLAGS = Opt.unlessFalse("steinbridge.opt.entityFlags");

    /**
     * {@code -Dsteinbridge.opt.checkEntityFlags=true}: uses the flags mirror and checks each read
     * against the game's chain (default: off).
     */
    static final boolean CHECK_ENTITY_FLAGS = Opt.flag("steinbridge.opt.checkEntityFlags");

    /**
     * {@code -Dsteinbridge.opt.vanillaLight=true}: computes entity light every frame (default:
     * stored).
     */
    static final boolean VANILLA_LIGHT = Opt.flag("steinbridge.opt.vanillaLight");

    /**
     * {@code -Dsteinbridge.opt.checkLight=true}: uses the stored light and checks it against the
     * game's computation (default: off).
     */
    static final boolean CHECK_LIGHT = Opt.flag("steinbridge.opt.checkLight");

    /**
     * {@code -Dsteinbridge.opt.vanillaNames=true}: sends the cached names through the original path
     * (default: cached).
     */
    static final boolean VANILLA_NAMES = Opt.flag("steinbridge.opt.vanillaNames");

    /**
     * {@code -Dsteinbridge.opt.vanillaParticleRemoval=true}: removes particles with the game's
     * removeAll (default: Stein's).
     */
    static final boolean VANILLA_PARTICLE_REMOVAL =
            Opt.flag("steinbridge.opt.vanillaParticleRemoval");

    // ---- client / network / watchdogs ----

    /**
     * {@code -Dsteinbridge.opt.vanillaClientSettings=true}: writes options like the game does, with
     * no deferral (default: deferred).
     */
    static final boolean VANILLA_CLIENT_SETTINGS =
            Opt.flag("steinbridge.opt.vanillaClientSettings");

    /**
     * {@code -Dsteinbridge.opt.noServerWatch=true}: turns the singleplayer server watch off
     * (default: on).
     */
    static final boolean NO_SERVER_WATCH = Opt.flag("steinbridge.opt.noServerWatch");

    /**
     * {@code -Dsteinbridge.opt.vanillaSearchRate=true}: the chunk visibility search runs whenever
     * the game asks, every frame while chunks wait to be built (default: the previous list is kept
     * when the camera did not move and no chunk finished; see {@link ChunkSearch}).
     */
    static final boolean VANILLA_SEARCH_RATE = Opt.flag("steinbridge.opt.vanillaSearchRate");

    /**
     * {@code -Dsteinbridge.opt.vanillaRebuildNear=true}: setupTerrain's rebuildNear loop walks the
     * whole visible list on every frame (default: only the chunks waiting; see {@link
     * RebuildNear}).
     */
    static final boolean VANILLA_REBUILD_NEAR = Opt.flag("steinbridge.opt.vanillaRebuildNear");

    /**
     * {@code -Dsteinbridge.opt.checkRebuildNear=true}: also walks the whole list on every frame and
     * compares (default: off).
     */
    static final boolean CHECK_REBUILD_NEAR = Opt.flag("steinbridge.opt.checkRebuildNear");

    /**
     * {@code -Dsteinbridge.opt.vanillaChunkLoading=true}: the integrated server loads and generates
     * every chunk of the view distance in the tick that asks for it, as the game does (default:
     * spread over the ticks, nearest first; see {@link LazyChunks}).
     */
    static final boolean VANILLA_CHUNK_LOADING = Opt.flag("steinbridge.opt.vanillaChunkLoading");

    /**
     * {@code -Dsteinbridge.opt.vanillaPendingTicks=true}: saving a chunk walks every scheduled
     * block update of the world, as the game does (default: one index per save; see {@link
     * PendingTicks}).
     */
    static final boolean VANILLA_PENDING_TICKS = Opt.flag("steinbridge.opt.vanillaPendingTicks");

    /**
     * {@code -Dsteinbridge.opt.checkPendingTicks=true}: answers every chunk of a save both ways and
     * compares the lists (default: off).
     */
    static final boolean CHECK_PENDING_TICKS = Opt.flag("steinbridge.opt.checkPendingTicks");

    /**
     * {@code -Dsteinbridge.opt.vanillaFacings=true}: the game's hot loops get a fresh copy of
     * EnumFacing.values() on every call, as in 1.8.9 (default: one shared array; see {@link
     * Facings}).
     */
    static final boolean VANILLA_FACINGS = Opt.flag("steinbridge.opt.vanillaFacings");

    /**
     * {@code -Dsteinbridge.opt.vanillaIntCache=true}: the world generator's IntCache drops one
     * spare array on every reset, as the game does (default: keeps them; see {@link IntCachePool}).
     */
    static final boolean VANILLA_INT_CACHE = Opt.flag("steinbridge.opt.vanillaIntCache");

    /**
     * {@code -Dsteinbridge.opt.vanillaBiomeColors=true}: every grass, foliage and water tint of a
     * chunk build reads the nine biomes around the block again (default: each position's colour
     * computed once per build; see {@link BiomeColors}).
     */
    static final boolean VANILLA_BIOME_COLORS = Opt.flag("steinbridge.opt.vanillaBiomeColors");

    /**
     * {@code -Dsteinbridge.opt.checkBiomeColors=true}: computes every tint both ways and compares
     * (default: off).
     */
    static final boolean CHECK_BIOME_COLORS = Opt.flag("steinbridge.opt.checkBiomeColors");

    /**
     * {@code -Dsteinbridge.opt.vanillaRawLight=true}: World.getRawLight allocates a BlockPos for
     * each of the six neighbours, as the game does (default: one reused position per thread; see
     * {@link NeighbourPos}).
     */
    static final boolean VANILLA_RAW_LIGHT = Opt.flag("steinbridge.opt.vanillaRawLight");

    /**
     * {@code -Dsteinbridge.opt.vanillaBlockNeighbours=true}: the block renderer allocates a
     * BlockPos for every side test and ambient-occlusion corner, as the game does (default: reused
     * positions per thread; see {@link NeighbourPos}).
     */
    static final boolean VANILLA_BLOCK_NEIGHBOURS =
            Opt.flag("steinbridge.opt.vanillaBlockNeighbours");

    /**
     * {@code -Dsteinbridge.opt.vanillaAoScratch=true}: every smooth-lit block allocates its quad
     * bounds, bound flags and AmbientOcclusionFace, as the game does (default: one set per thread;
     * see {@link AoScratch}).
     */
    static final boolean VANILLA_AO_SCRATCH = Opt.flag("steinbridge.opt.vanillaAoScratch");

    /**
     * {@code -Dsteinbridge.opt.vanillaRegionArrays=true}: every chunk build's RegionRenderCache
     * allocates its light and block-state arrays, as the game does (default: each build thread
     * reuses its pair; see {@link RegionArrays}).
     */
    static final boolean VANILLA_REGION_ARRAYS = Opt.flag("steinbridge.opt.vanillaRegionArrays");

    /**
     * {@code -Dsteinbridge.opt.vanillaClearUpdates=true}: setupTerrain's clearChunkUpdates takes
     * the waiting build tasks out one lock at a time, as the game does (default: all at once; see
     * {@link ChunkQueue}).
     */
    static final boolean VANILLA_CLEAR_UPDATES = Opt.flag("steinbridge.opt.vanillaClearUpdates");

    /**
     * {@code -Dsteinbridge.opt.vanillaPlayerUnload=true}: a chunk unloaded on the integrated server
     * also unloads a player still standing in it, as 1.8.9 does after a long teleport, leaving that
     * player frozen until a dimension change (default: the player stays; see {@link PlayerUnload}).
     */
    static final boolean VANILLA_PLAYER_UNLOAD = Opt.flag("steinbridge.opt.vanillaPlayerUnload");

    /** {@code -Dsteinbridge.opt.noWatchdog=true}: turns the freeze watchdog off (default: on). */
    static final boolean NO_WATCHDOG = Opt.flag("steinbridge.opt.noWatchdog");

    /**
     * {@code -Dsteinbridge.watchdog.silenceSec=N}: seconds of silence before the watchdog reports a
     * freeze (default: 20).
     */
    static final long WATCHDOG_SILENCE_SEC = Opt.number("steinbridge.watchdog.silenceSec", 20L);

    /**
     * {@code -Dsteinbridge.watchdog.tickSec=N}: seconds between the watchdog's checks (default:
     * 10).
     */
    static final long WATCHDOG_TICK_SEC = Opt.number("steinbridge.watchdog.tickSec", 10L);

    /**
     * {@code -Dsteinbridge.diag.sprintLog=true}: every second, prints the ground speed and whether
     * it is sprinting (default: off).
     */
    static final boolean SPRINT_LOG = Opt.flag("steinbridge.diag.sprintLog");
}
