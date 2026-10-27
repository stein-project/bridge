/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.bench;

import dev.xavier.stein.bridge.core.Log;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiControls;
import net.minecraft.client.gui.GuiCustomizeSkin;
import net.minecraft.client.gui.GuiIngameMenu;
import net.minecraft.client.gui.GuiLanguage;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiOptions;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiScreenOptionsSounds;
import net.minecraft.client.gui.GuiScreenResourcePacks;
import net.minecraft.client.gui.GuiSelectWorld;
import net.minecraft.client.gui.GuiVideoSettings;
import net.minecraft.client.gui.achievement.GuiAchievements;
import net.minecraft.client.gui.inventory.GuiContainerCreative;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The benchmark's world and route: a fresh world with a fixed seed (deleted and created again on
 * every run, so every machine generates the same land), the same scenery built by commands on the
 * integrated server, 400 players fighting that exist only on this client ({@link BenchPlayers}),
 * every block of the game, rain, a thunderstorm with lightning, every biome the seed has near the
 * spawn ({@link BenchTerrain}), the Nether, the End and the game's menus, with a camera that never
 * stops, following the same path tick by tick. The passes are shot in spectator mode, like a film
 * camera (through blocks, no hand); the first-person part, the inventory and the menus are in
 * creative, with the hand and the HUD. The route is a list of {@link Part}s; each one sets its
 * scene up, waits for its dimension and its chunks, and is then measured for a fixed number of
 * ticks.
 *
 * <p>The game's logic is untouched: the scenery is ordinary blocks and mobs placed with the game's
 * own commands, and the dimension changes are the game's own, in a world made only for this. On the
 * client thread.
 */
final class BenchScene {

    static final String WORLD = "stein-benchmark";
    static final long SEED = 20261027L;

    /** Height of the floating arena where the fight, the mobs and the blocks are. */
    static final int ARENA_Y = 150;

    /** Height the camera circles over the land around the spawn. */
    private static final double SKY_Y = 110.0;

    /** A part that runs in any dimension, or with no world at all (the title menus). */
    static final int ANY_DIMENSION = Integer.MIN_VALUE;

    /**
     * A part of the route, with what the report checks it showed: the least entities drawn, tile
     * entities and particles on average (0: not checked). {@code maxWaitTicks}: how long past the
     * warm-up it waits for its chunks at most.
     */
    record Part(
            String name,
            int warmupTicks,
            int measureTicks,
            boolean waitChunks,
            int maxWaitTicks,
            Kind kind,
            int dimension,
            boolean spectator,
            int minEntities,
            int minTiles,
            int minParticles) {}

    enum Kind {
        TERRAIN,
        MENUS_INGAME,
        MOBS,
        FIGHT_FAR,
        FIGHT_CLOSE,
        FIGHT_FIRST_PERSON,
        FIGHT_RAIN,
        STORM,
        PARTICLES,
        TILES,
        TRANSLUCENT,
        BLOCKS,
        INVENTORY,
        EVERYTHING,
        CAVES,
        FLIGHT,
        BIOMES,
        BIOME,
        NETHER,
        END,
        MENUS_TITLE
    }

    /** The whole route, in order, with the measured ticks scaled by {@code bench.scale}. */
    static List<Part> route() {
        List<Part> all = new ArrayList<>();
        all.add(part("spawn_flyover", 15, 15, true, Kind.TERRAIN, 0, true, 0, 0, 0));
        all.add(part("menus_ingame", 2, 20, false, Kind.MENUS_INGAME, 0, false, 0, 0, 0));
        all.add(part("mobs", 5, 15, true, Kind.MOBS, 0, true, 100, 0, 0));
        all.add(part("fight_far", 4, 20, false, Kind.FIGHT_FAR, 0, true, 300, 0, 0));
        all.add(part("fight_close", 2, 20, false, Kind.FIGHT_CLOSE, 0, true, 40, 0, 0));
        all.add(
                part(
                        "fight_first_person",
                        3,
                        15,
                        false,
                        Kind.FIGHT_FIRST_PERSON,
                        0,
                        false,
                        40,
                        0,
                        0));
        all.add(part("fight_rain", 3, 15, false, Kind.FIGHT_RAIN, 0, true, 300, 0, 0));
        all.add(part("storm", 3, 20, false, Kind.STORM, 0, true, 300, 0, 0));
        all.add(part("particles", 4, 15, false, Kind.PARTICLES, 0, true, 40, 0, 150));
        all.add(part("tiles", 4, 15, true, Kind.TILES, 0, true, 0, 900, 0));
        all.add(part("translucent", 3, 15, true, Kind.TRANSLUCENT, 0, true, 100, 0, 0));
        all.add(part("blocks", 4, 25, true, Kind.BLOCKS, 0, true, 0, 0, 0));
        all.add(part("inventory", 3, 20, false, Kind.INVENTORY, 0, false, 100, 0, 0));
        all.add(part("everything", 5, 20, true, Kind.EVERYTHING, 0, true, 300, 900, 20));
        all.add(part("caves", 5, 15, true, Kind.CAVES, 0, true, 0, 0, 0));
        all.add(part("flight", 3, 25, false, Kind.FLIGHT, 0, true, 0, 0, 0));
        all.add(part("biomes", 0, 1, false, Kind.BIOMES, 0, true, 0, 0, 0));
        all.add(part("nether", 5, 20, true, Kind.NETHER, -1, true, 0, 0, 0));
        all.add(part("end", 5, 20, true, Kind.END, 1, true, 3, 0, 0));
        all.add(part("menus_title", 2, 20, false, Kind.MENUS_TITLE, ANY_DIMENSION, false, 0, 0, 0));
        String only = BenchOptions.ONLY.trim();
        if (only.isEmpty()) {
            return all;
        }
        List<String> names = List.of(only.toLowerCase(Locale.ROOT).split("\\s*,\\s*"));
        List<Part> some = new ArrayList<>();
        for (Part p : all) {
            if (names.contains(p.name())) {
                some.add(p);
            }
        }
        return some.isEmpty() ? all : some;
    }

    private static Part part(
            String name,
            int warmupSec,
            int measureSec,
            boolean wait,
            Kind k,
            int dimension,
            boolean spectator,
            int minEntities,
            int minTiles,
            int minParticles) {
        int measure = (int) Math.max(20, Math.round(measureSec * 20 * BenchOptions.SCALE));
        return new Part(
                name,
                warmupSec * 20,
                measure,
                wait,
                600,
                k,
                dimension,
                spectator,
                minEntities,
                minTiles,
                minParticles);
    }

    /**
     * The biome parts the {@code biomes} part turns into once the search is done: one per biome
     * found, in the order of the shortest trip, each waiting at most 10 s past its warm-up for the
     * new land.
     */
    static List<Part> biomeParts() {
        List<Part> parts = new ArrayList<>();
        int measure = (int) Math.max(20, Math.round(8 * 20 * BenchOptions.SCALE));
        for (String name : BenchTerrain.BIOMES.keySet()) {
            if (BenchOptions.MAX_BIOMES > 0 && parts.size() == BenchOptions.MAX_BIOMES) {
                break;
            }
            parts.add(new Part(name, 60, measure, true, 200, Kind.BIOME, 0, true, 0, 0, 0));
        }
        return parts;
    }

    private BenchScene() {}

    // ---- the world ----

    /** Deletes the last run's world and opens a new one with the fixed seed. */
    static void openWorld(Minecraft mc) {
        WorldSettings settings =
                new WorldSettings(
                                SEED,
                                WorldSettings.GameType.CREATIVE,
                                true,
                                false,
                                WorldType.DEFAULT)
                        .enableCommands();
        mc.addScheduledTask(
                (Runnable)
                        () -> {
                            try {
                                mc.getSaveLoader().flushCache();
                                mc.getSaveLoader().deleteWorldDirectory(WORLD);
                                mc.launchIntegratedServer(WORLD, WORLD, settings);
                            } catch (RuntimeException e) {
                                Log.warn("bench", "could not open the world: " + e);
                            }
                        });
    }

    /** The world's spawn, the anchor of the overworld route (set once the world is open). */
    private static int sx;

    private static int sz;

    /** The anchor of a part that starts where the player arrives (the Nether). */
    private static volatile double ax;

    private static volatile double ay;
    private static volatile double az;

    /** The camera height of the land-following passes, smoothed from tick to tick. */
    private static double followY = Double.NaN;

    /** The commands that make the world still: same time, same weather, no spawning. */
    static void prepare(Minecraft mc) {
        BlockPos s = mc.theWorld.getSpawnPoint();
        sx = s.getX();
        sz = s.getZ();
        run(
                mc,
                "gamerule logAdminCommands false",
                "gamerule sendCommandFeedback false",
                "gamerule commandBlockOutput false",
                "gamerule doDaylightCycle false",
                "gamerule doMobSpawning false",
                "gamerule doFireTick false",
                "gamerule doTileDrops false",
                "gamerule mobGriefing false",
                "gamerule randomTickSpeed 0",
                "time set 6000",
                "weather clear 1000000",
                "clear @p",
                "give @p diamond_sword 1 0 {ench:[{id:16s,lvl:5s}]}",
                "replaceitem entity @p slot.armor.head diamond_helmet 1 0 {ench:[{id:0s,lvl:4s}]}",
                "replaceitem entity @p slot.armor.chest diamond_chestplate 1 0"
                        + " {ench:[{id:0s,lvl:4s}]}",
                "replaceitem entity @p slot.armor.legs diamond_leggings 1 0 {ench:[{id:0s,lvl:4s}]}",
                "replaceitem entity @p slot.armor.feet diamond_boots 1 0 {ench:[{id:0s,lvl:4s}]}");
        EntityPlayerSP p = mc.thePlayer;
        p.inventory.currentItem = 0;
        // the arena's floor and the block gallery, built now: placing that many blocks makes the
        // server relight for seconds, which must not fall inside a measured part
        int y = ARENA_Y;
        run(mc, fill(sx - 80, y - 1, sz - 80, sx + 79, y - 1, sz + 79, "stone"));
        run(mc, fill(sx - 42, y - 1, galleryZ(), sx + 41, y - 1, galleryZ() + 75, "stone"));
        run(mc, gallery());
        BenchTerrain.search(mc, sx, sz);
    }

    private static int tilesX() {
        return sx + 60;
    }

    /** The north edge of the block gallery (it lies north of the arena, on its own floor). */
    private static int galleryZ() {
        // inside the spawn's chunks, which the server always keeps (fill and setblock refuse
        // blocks in chunks that are not loaded)
        return sz - 186;
    }

    /** The part's own scenery and mode, once, when it starts. */
    static void setUp(Minecraft mc, Part part) {
        int y = ARENA_Y;
        if (part.kind() != Kind.MENUS_TITLE) {
            run(mc, part.spectator() ? "gamemode 3 @p" : "gamemode 1 @p");
        }
        followY = Double.NaN;
        screenIndex = -1;
        switch (part.kind()) {
            case MOBS -> run(mc, mobs());
            case FIGHT_RAIN -> run(mc, "weather rain 1000000");
            case STORM -> run(mc, "weather thunder 1000000");
            case PARTICLES -> run(mc, "weather clear 1000000");
            case TILES -> run(mc, tiles());
            case TRANSLUCENT -> run(mc, glass());
            case INVENTORY -> mc.displayGuiScreen(new GuiInventory(mc.thePlayer));
            case EVERYTHING -> {
                run(mc, mobs());
                run(mc, "weather thunder 1000000");
            }
            case NETHER -> toDimension(mc, -1);
            case END -> toDimension(mc, 1);
            case MENUS_TITLE -> leaveWorld(mc);
            default -> {}
        }
        if (fight(part)) {
            BenchPlayers.spawn(mc, sx + 0.5, y, sz + 0.5);
        }
    }

    private static boolean fight(Part part) {
        return switch (part.kind()) {
            case FIGHT_FAR,
                    FIGHT_CLOSE,
                    FIGHT_FIRST_PERSON,
                    FIGHT_RAIN,
                    STORM,
                    PARTICLES,
                    TILES,
                    TRANSLUCENT,
                    INVENTORY,
                    EVERYTHING ->
                    true;
            default -> false;
        };
    }

    /** Whether the part shows a screen of its own (the bench closes any other). */
    static boolean ownsScreen(Part part) {
        return part.kind() == Kind.INVENTORY
                || part.kind() == Kind.MENUS_INGAME
                || part.kind() == Kind.MENUS_TITLE;
    }

    /** The anchor came from the server's dimension change (the client's position is not used). */
    private static volatile boolean serverAnchor;

    /**
     * Once the player is in the part's dimension: where a part that starts there is anchored. False
     * while a dimension change's anchor has not come from the server yet (its packets reach the
     * client before the server thread is done with the change).
     */
    static boolean arrived(Minecraft mc, Part part) {
        boolean changes = part.kind() == Kind.NETHER || part.kind() == Kind.END;
        if (changes) {
            if (!serverAnchor) {
                return false;
            }
            serverAnchor = false;
            Bench.event(
                    String.format(
                            Locale.ROOT, "%s: arrived at %.1f %.1f %.1f", part.name(), ax, ay, az));
            return true;
        }
        if (mc.thePlayer != null) {
            ax = mc.thePlayer.posX;
            ay = mc.thePlayer.posY;
            az = mc.thePlayer.posZ;
        }
        return true;
    }

    /** The screen shown last in a menus part (-1 before the first). */
    private static int screenIndex;

    /**
     * What the part does every tick on its own: the particles and the lightning keep coming, the
     * player swings, the menus and the creative tabs change.
     */
    static void everyTick(Minecraft mc, Part part, int tick) {
        switch (part.kind()) {
            case PARTICLES, EVERYTHING -> particles(mc);
            case FIGHT_FIRST_PERSON -> {
                if (tick % 7 == 0) {
                    mc.thePlayer.swingItem();
                }
            }
            case INVENTORY -> creativeTabs(mc, tick);
            case MENUS_INGAME -> screens(mc, tick, false);
            case MENUS_TITLE -> screens(mc, tick, true);
            default -> {}
        }
        if ((part.kind() == Kind.STORM || part.kind() == Kind.EVERYTHING) && tick % 12 == 0) {
            // lightning on the fight, at places fixed by the tick
            int k = tick / 12;
            int x = sx + (k * 37 % 90) - 45;
            int z = sz + (k * 23 % 40) - 20;
            run(mc, "summon LightningBolt " + x + " " + ARENA_Y + " " + z);
        }
    }

    private static void particles(Minecraft mc) {
        String at = sx + " " + (ARENA_Y + 3) + " " + sz;
        run(
                mc,
                "particle flame " + at + " 10 2 6 0.01 60",
                "particle largesmoke " + at + " 10 2 6 0.01 40",
                "particle spell " + at + " 10 2 6 0.5 60",
                "particle reddust " + at + " 10 2 6 0 60",
                "particle crit " + at + " 10 2 6 0.3 40");
    }

    /** Every 30 ticks, the creative inventory's next tab: every item's texture goes by. */
    private static void creativeTabs(Minecraft mc, int tick) {
        if (tick % 30 != 0 || !(mc.currentScreen instanceof GuiContainerCreative c)) {
            return;
        }
        CreativeTabs[] tabs = CreativeTabs.creativeTabArray;
        c.setCurrentCreativeTab(tabs[(tick / 30) % tabs.length]);
    }

    /** Every 40 ticks, the next of the game's menus. */
    private static void screens(Minecraft mc, int tick, boolean title) {
        int i = tick / 40;
        if (i == screenIndex) {
            return;
        }
        screenIndex = i;
        List<GuiScreen> list = title ? titleScreens(mc) : worldScreens(mc);
        mc.displayGuiScreen(list.get(i % list.size()));
    }

    /** The menus over the world (the multiplayer list is left out: it would ping servers). */
    private static List<GuiScreen> worldScreens(Minecraft mc) {
        GuiScreen pause = new GuiIngameMenu();
        GuiScreen options = new GuiOptions(pause, mc.gameSettings);
        List<GuiScreen> l = new ArrayList<>();
        l.add(pause);
        l.add(options);
        l.add(new GuiVideoSettings(options, mc.gameSettings));
        l.add(new GuiControls(options, mc.gameSettings));
        l.add(new GuiScreenOptionsSounds(options, mc.gameSettings));
        l.add(new GuiCustomizeSkin(options));
        l.add(new GuiLanguage(options, mc.gameSettings, mc.getLanguageManager()));
        l.add(new GuiScreenResourcePacks(options));
        if (mc.thePlayer != null) {
            l.add(new GuiAchievements(pause, mc.thePlayer.getStatFileWriter()));
        }
        l.add(new GuiChat("Stein benchmark"));
        return l;
    }

    /** The menus from the title screen, without a world behind. */
    private static List<GuiScreen> titleScreens(Minecraft mc) {
        GuiScreen menu = new GuiMainMenu();
        GuiScreen options = new GuiOptions(menu, mc.gameSettings);
        List<GuiScreen> l = new ArrayList<>();
        l.add(menu);
        l.add(options);
        l.add(new GuiVideoSettings(options, mc.gameSettings));
        l.add(new GuiSelectWorld(menu));
        l.add(new GuiControls(options, mc.gameSettings));
        l.add(new GuiScreenResourcePacks(options));
        l.add(new GuiLanguage(options, mc.gameSettings, mc.getLanguageManager()));
        l.add(new GuiMainMenu());
        return l;
    }

    /** Takes the part's scenery away when it ends, where the next part needs it gone. */
    static void tearDown(Minecraft mc, Part part) {
        switch (part.kind()) {
            case MOBS -> run(mc, "kill @e[type=!Player]");
            case FIGHT_RAIN, STORM -> run(mc, "weather clear 1000000");
            case INVENTORY, MENUS_INGAME -> mc.displayGuiScreen(null);
            case BLOCKS -> run(mc, "kill @e[type=!Player]");
            case EVERYTHING -> {
                run(mc, "kill @e[type=!Player]", "weather clear 1000000");
                BenchPlayers.remove(mc);
            }
            default -> {}
        }
    }

    /** The game's own dimension change, on the server thread (a portal's, without the portal). */
    private static void toDimension(Minecraft mc, int dimension) {
        MinecraftServer server = mc.getIntegratedServer();
        if (server == null) {
            return;
        }
        server.addScheduledTask(
                (Runnable)
                        () -> {
                            List<EntityPlayerMP> players =
                                    server.getConfigurationManager().playerEntityList;
                            if (!players.isEmpty() && players.get(0).dimension != dimension) {
                                EntityPlayerMP p = players.get(0);
                                if (dimension == -1) {
                                    // the Nether's teleporter places the player by the portal
                                    // they came through: as if they stepped into one here
                                    p.lastPortalPos = new BlockPos(p);
                                    p.lastPortalVec = new net.minecraft.util.Vec3(0.5, 0.5, 0.0);
                                    p.teleportDirection = net.minecraft.util.EnumFacing.NORTH;
                                }
                                try {
                                    server.getConfigurationManager()
                                            .transferPlayerToDimension(p, dimension);
                                } catch (RuntimeException e) {
                                    // the task queue would swallow it
                                    Log.error("bench", "dimension change failed", e);
                                }
                                // where the game put the player (the client learns it a moment
                                // after the new world appears)
                                ax = p.posX;
                                ay = p.posY;
                                az = p.posZ;
                                serverAnchor = true;
                            }
                        });
        Bench.event("to dimension " + dimension);
    }

    /** Back to the title screen the way the pause menu's "Save and Quit" does. */
    private static void leaveWorld(Minecraft mc) {
        BenchPlayers.remove(mc);
        if (mc.theWorld != null) {
            mc.theWorld.sendQuittingDisconnectingPacket();
            mc.loadWorld((WorldClient) null);
        }
        mc.displayGuiScreen(new GuiMainMenu());
        Bench.event("left the world");
    }

    // ---- the camera: always moving ----

    /**
     * The camera at {@code tick} of the part (ticks since the part started, warm-up included): x,
     * y, z, yaw, pitch.
     */
    static void camera(Minecraft mc, Part part, int tick, double[] out) {
        double t = tick;
        double y0 = ARENA_Y;
        double cx = sx + 0.5;
        double cz = sz + 0.5;
        switch (part.kind()) {
            case TERRAIN, MENUS_INGAME, MENUS_TITLE -> {
                double a = t / 300.0;
                double x = cx + 48 * Math.cos(a);
                double z = cz + 48 * Math.sin(a);
                set(
                        out,
                        x,
                        SKY_Y + 4 * Math.sin(t / 90.0),
                        z,
                        Math.toDegrees(a) + 35,
                        18 + 6 * Math.sin(t / 70.0));
            }
            case MOBS, EVERYTHING, FIGHT_RAIN, STORM, INVENTORY ->
                    orbit(
                            out,
                            cx,
                            y0,
                            cz,
                            55 + 10 * Math.sin(t / 110.0),
                            18 + 5 * Math.sin(t / 80.0),
                            t / 360.0);
            case FIGHT_FAR ->
                    orbit(
                            out,
                            cx,
                            y0,
                            cz,
                            80 + 12 * Math.sin(t / 130.0),
                            32 + 6 * Math.sin(t / 90.0),
                            t / 420.0);
            case FIGHT_CLOSE -> {
                // weaving through the pairs at head height, looking where it goes
                double[] now = closePath(t, cx, y0, cz);
                double[] ahead = closePath(t + 25, cx, y0, cz);
                lookAt(out, now[0], now[1], now[2], ahead[0], ahead[1] - 1.2, ahead[2]);
            }
            case FIGHT_FIRST_PERSON -> {
                // walking inside the fight, in creative: the hand, the sword and the HUD
                double a = t / 160.0;
                lookAt(
                        out,
                        cx + 9 * Math.cos(a),
                        y0,
                        cz + 6 * Math.sin(a),
                        cx + 9 * Math.cos(a + 0.6),
                        y0 + 1.3,
                        cz + 6 * Math.sin(a + 0.6));
            }
            case PARTICLES -> orbit(out, cx, y0 + 2, cz, 18 + 3 * Math.sin(t / 60.0), 6, t / 220.0);
            case TILES ->
                    orbit(out, tilesX() + 0.5, y0, cz, 28 + 4 * Math.sin(t / 90.0), 12, t / 300.0);
            case TRANSLUCENT -> {
                double x = cx + 18 * Math.sin(t / 110.0);
                double z = cz + 60 + 6 * Math.sin(t / 70.0);
                lookAt(out, x, y0 + 7 + 2 * Math.sin(t / 50.0), z, cx, y0 + 2, cz);
            }
            case BLOCKS -> {
                // low over the gallery, row after row
                double[] now = galleryPath(t, cx, y0);
                double[] ahead = galleryPath(t + 15, cx, y0);
                lookAt(out, now[0], now[1], now[2], ahead[0], y0, ahead[2]);
            }
            case CAVES -> {
                // underground, through the stone: the caves the chunks' visibility lets through
                set(
                        out,
                        cx + 10 * Math.sin(t / 70.0),
                        28 + 4 * Math.sin(t / 50.0),
                        cz + 0.3 * t,
                        25 * Math.sin(t / 90.0),
                        5);
            }
            case FLIGHT ->
                    set(
                            out,
                            cx + t,
                            SKY_Y,
                            cz + 12 * Math.sin(t / 80.0),
                            -90 + 15 * Math.sin(t / 60.0),
                            12);
            case BIOME, BIOMES -> {
                BlockPos at = BenchTerrain.BIOMES.get(part.name());
                follow(mc, out, at == null ? new BlockPos(sx, 0, sz) : at, t, 12, 0.4);
            }
            case NETHER -> {
                // from the open space where the player arrived, turning to look around the
                // cavern (the netherrack around would swallow a wider circle)
                double turn = t * 0.6;
                set(
                        out,
                        ax + 1.5 * Math.sin(t / 60.0),
                        ay + 1.5 + Math.sin(t / 50.0),
                        az + 1.5 * Math.cos(t / 60.0),
                        turn,
                        10 * Math.sin(t / 40.0));
            }
            case END ->
                    orbitAround(
                            out,
                            0.5,
                            82 + 6 * Math.sin(t / 80.0),
                            0.5,
                            50 + 10 * Math.sin(t / 120.0),
                            t / 400.0,
                            65);
        }
    }

    /** The weaving path through the fight: back and forth along the rows. */
    private static double[] closePath(double t, double cx, double y0, double cz) {
        return new double[] {
            cx + 46 * Math.sin(t / 200.0),
            y0 + 2.4 + 0.6 * Math.sin(t / 40.0),
            cz + 12 * Math.sin(t / 90.0)
        };
    }

    /** Over the gallery: across and back while moving down the rows. */
    private static double[] galleryPath(double t, double cx, double y0) {
        return new double[] {cx + 34 * Math.sin(t / 45.0), y0 + 6, galleryZ() + 2 + 0.12 * t};
    }

    /**
     * Low over the land from {@code place}, heading east at {@code speed} blocks per tick, at
     * {@code above} blocks over the highest ground of the next stretch (the chunks the client has),
     * smoothed so the camera glides instead of jumping.
     */
    private static void follow(
            Minecraft mc, double[] out, BlockPos place, double t, double above, double speed) {
        double x = place.getX() - 40 + speed * t;
        double z = place.getZ() + 20 * Math.sin(t / 120.0);
        int ground = 0;
        if (mc.theWorld != null) {
            for (int k = 0; k <= 24; k += 4) {
                ground = Math.max(ground, mc.theWorld.getHeight(new BlockPos(x + k, 0, z)).getY());
            }
        }
        double target = ground > 0 ? ground + above : (Double.isNaN(followY) ? 120 : followY);
        followY = Double.isNaN(followY) ? target : followY + (target - followY) * 0.08;
        set(out, x, followY, z, -90 + 25 * Math.sin(t / 100.0), 18 + 6 * Math.sin(t / 60.0));
    }

    /** On a circle of radius {@code r}, {@code h} above the centre, looking at it. */
    private static void orbit(
            double[] out, double cx, double cy, double cz, double r, double h, double angle) {
        double x = cx + r * Math.sin(angle);
        double z = cz + r * Math.cos(angle);
        lookAt(out, x, cy + h, z, cx, cy + 1, cz);
    }

    /** At height {@code y} on a circle around (cx, cz), looking at its centre at {@code ty}. */
    private static void orbitAround(
            double[] out, double cx, double y, double cz, double r, double angle, double ty) {
        double x = cx + r * Math.sin(angle);
        double z = cz + r * Math.cos(angle);
        lookAt(out, x, y, z, cx, ty, cz);
    }

    /** At (x, y, z) looking at (tx, ty, tz). */
    private static void lookAt(
            double[] out, double x, double y, double z, double tx, double ty, double tz) {
        double dx = tx - x;
        double dy = ty - y;
        double dz = tz - z;
        // yaw 0 looks at +z, 90 at -x; pitch is positive looking down
        double yaw = Math.toDegrees(Math.atan2(-dx, dz));
        double pitch = Math.toDegrees(-Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        set(out, x, y, z, yaw, pitch);
    }

    private static void set(double[] out, double x, double y, double z, double yaw, double pitch) {
        out[0] = x;
        out[1] = y;
        out[2] = z;
        out[3] = yaw;
        out[4] = pitch;
    }

    // ---- scenery ----

    private static final String[] MOBS = {
        "Zombie", "Skeleton", "Creeper", "Spider", "Enderman", "PigZombie", "Witch", "Villager",
        "VillagerGolem", "Pig", "Cow", "Sheep", "Chicken", "Wolf", "EntityHorse", "Ozelot"
    };

    /** 304 mobs of 16 kinds on a grid, without AI (they stay where they are put). */
    private static String[] mobs() {
        List<String> c = new ArrayList<>();
        String ench = ",tag:{ench:[{id:0s,lvl:4s}]}";
        String gear =
                ",Equipment:[{id:\"minecraft:iron_sword\",Count:1b},{id:\"minecraft:iron_boots\","
                        + "Count:1b"
                        + ench
                        + "},{id:\"minecraft:iron_leggings\",Count:1b"
                        + ench
                        + "},{id:\"minecraft:iron_chestplate\",Count:1b"
                        + ench
                        + "},{id:\"minecraft:iron_helmet\",Count:1b"
                        + ench
                        + "}]";
        int n = 0;
        for (int i = 0; i < 19; i++) {
            for (int j = 0; j < 16; j++) {
                String kind = MOBS[(i + j) % MOBS.length];
                boolean armed =
                        kind.equals("Zombie")
                                || kind.equals("Skeleton")
                                || kind.equals("PigZombie");
                c.add(
                        "summon "
                                + kind
                                + " "
                                + (sx - 36 + 4 * i)
                                + " "
                                + ARENA_Y
                                + " "
                                + (sz - 30 + 4 * j)
                                + " {NoAI:1b,Silent:1b,PersistenceRequired:1b,CustomNameVisible:1b,"
                                + "CustomName:\"Mob_"
                                + n++
                                + "\""
                                + (armed ? gear : "")
                                + "}");
            }
        }
        return c.toArray(new String[0]);
    }

    /** 450 chests, 450 signs with text and a row of enchanting tables, beside the fight. */
    private static String[] tiles() {
        List<String> c = new ArrayList<>();
        int x0 = tilesX() - 15;
        int y = ARENA_Y;
        for (int i = 0; i < 15; i++) {
            int x = x0 + 2 * i;
            c.add(fill(x, y, sz - 15, x, y, sz + 14, "chest"));
            c.add(
                    fill(x + 1, y, sz - 15, x + 1, y, sz + 14, "standing_sign")
                            + " 0 replace {Text1:\"{\\\"text\\\":\\\"Stein\\\"}\","
                            + "Text2:\"{\\\"text\\\":\\\"bench\\\"}\"}");
        }
        c.add(fill(x0, y, sz + 16, x0 + 29, y, sz + 16, "enchanting_table"));
        return c.toArray(new String[0]);
    }

    /** Stained glass walls and ice between the camera and the fight. */
    private static String[] glass() {
        List<String> c = new ArrayList<>();
        int y = ARENA_Y;
        for (int k = 0; k < 4; k++) {
            c.add(
                    fill(
                                    sx - 20,
                                    y + 4 * k,
                                    sz + 40,
                                    sx + 20,
                                    y + 4 * k + 3,
                                    sz + 41,
                                    "stained_glass")
                            + " "
                            + (k * 4 + 1));
        }
        c.add(fill(sx - 20, y, sz + 44, sx + 20, y + 15, sz + 44, "ice"));
        return c.toArray(new String[0]);
    }

    /** Blocks the gallery leaves out: they only exist while something moves or as half a block. */
    private static final Set<String> NOT_IN_GALLERY =
            Set.of("minecraft:air", "minecraft:piston_extension", "minecraft:piston_head");

    /**
     * Every block of the game in every variant, one per spot two blocks apart, on the gallery's
     * floor; liquids sit in the floor, so they stay where they are. With no random ticks, no fire
     * spread and no drops, nothing changes once placed.
     */
    private static String[] gallery() {
        List<String> c = new ArrayList<>();
        int i = 0;
        int y = ARENA_Y;
        for (Block b : Block.blockRegistry) {
            ResourceLocation id = Block.blockRegistry.getNameForObject(b);
            if (id == null || NOT_IN_GALLERY.contains(id.toString())) {
                continue;
            }
            Set<Integer> metas = new HashSet<>();
            for (IBlockState s : b.getBlockState().getValidStates()) {
                int meta = b.getMetaFromState(s);
                if (!metas.add(meta)) {
                    continue;
                }
                int x = sx - 40 + (i % 40) * 2;
                int z = galleryZ() + 2 + (i / 40) * 2;
                boolean liquid =
                        b.getMaterial() == Material.water || b.getMaterial() == Material.lava;
                c.add(
                        "setblock "
                                + x
                                + " "
                                + (liquid ? y - 1 : y)
                                + " "
                                + z
                                + " "
                                + id
                                + " "
                                + meta);
                i++;
            }
        }
        Bench.event("blocks: " + i + " block states in the gallery");
        return c.toArray(new String[0]);
    }

    private static String fill(int x1, int y1, int z1, int x2, int y2, int z2, String block) {
        return "fill " + x1 + " " + y1 + " " + z1 + " " + x2 + " " + y2 + " " + z2 + " " + block;
    }

    /** Runs the commands on the integrated server's thread, as the server (operator level). */
    static void run(Minecraft mc, String... commands) {
        MinecraftServer server = mc.getIntegratedServer();
        if (server == null) {
            return;
        }
        server.addScheduledTask(
                (Runnable)
                        () -> {
                            for (String c : commands) {
                                try {
                                    server.getCommandManager().executeCommand(server, c);
                                } catch (RuntimeException e) {
                                    Log.warn("bench", "command failed: " + c + ": " + e);
                                }
                            }
                        });
    }
}
