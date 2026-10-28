/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.bench;

import com.sun.management.GarbageCollectionNotificationInfo;

import dev.xavier.stein.bridge.core.Log;
import dev.xavier.stein.bridge.core.Win;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ScreenShotHelper;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;

import java.io.File;
import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipOutputStream;

import javax.management.NotificationEmitter;
import javax.management.openmbean.CompositeData;

/**
 * The built-in benchmark ({@code -Dsteinbridge.bench=true}): plays {@link BenchScene}'s route on
 * its own and measures every frame — the game's phases and the mods' events ({@link BenchZones}),
 * what the renderer did ({@link BenchCounters}), the GPU time of each pass, the render thread's
 * allocation, the garbage collections — plus, once a second, the threads, the clocks, the chunks
 * and the integrated server ({@link BenchSampler}), the whole console ({@link BenchConsole}) and a
 * picture at the end of every part. Everything goes into one zip ({@link BenchReport}), with a
 * check of every part ({@link BenchChecks}), that the player sends.
 *
 * <p>While it runs, the player's keyboard and mouse never reach the game ({@link #inputLocked},
 * read by the window's input callbacks), the window cannot be resized, and a screen that opens on
 * its own (the pause menu when the window loses focus) is closed. Minimizing or switching windows
 * cannot be prevented: those frames are flagged, and the parts they fall in are marked in the
 * report. Closing the window stops the run and still writes the report, marked as stopped.
 *
 * <p>Called from Display.update (once per frame) and after each client tick, on the render thread.
 * Without the option every entry point returns on a static final flag.
 */
public final class Bench {

    public static final boolean ON = BenchOptions.ON;

    private Bench() {}

    private enum Phase {
        WAITING,
        OPENING,
        PREPARING,
        RUNNING,
        DONE
    }

    /** How long a part waits for its dimension before it is skipped (60 s). */
    private static final int DIMENSION_TIMEOUT_TICKS = 1200;

    private static Phase phase = Phase.WAITING;
    private static boolean inputLocked;

    private static final long BOOT = System.nanoTime();
    private static long start;
    private static long lastFrame;
    private static String stamp;
    private static File out;
    private static ZipOutputStream zip;
    private static BenchFrames frames;
    private static BenchSampler sampler;
    private static BenchExternal external;
    private static java.util.Map<String, Object> environment;
    private static final List<String> EVENTS = new ArrayList<>();
    private static final List<BenchStats> RESULTS = new ArrayList<>();

    /** The parts in the order they ran (a part's rows carry its index here). */
    private static final List<String> SEGMENTS = new CopyOnWriteArrayList<>();

    /** Parts left out, with the reason. */
    private static final List<String> SKIPPED = new ArrayList<>();

    private static List<BenchScene.Part> route;
    private static BenchSettings settings;
    private static String title;

    // the route
    private static int index;
    private static int round;
    private static int segment = -1;
    private static int tick;
    private static int waitTicks;
    private static int measured;

    /** The command mark of the preparation (-1 before it) and of the current part's set-up. */
    private static long prepareMark = -1;

    private static long partMark;

    /** The integrated server's tick counter at each of the last 20 client ticks. */
    private static final int[] SERVER_TICKS = new int[20];

    private static int serverSamples;

    /** Whether the server ran at least 18 ticks in the last 20 client ticks (or there is none). */
    private static boolean serverRecovered;

    private static void serverPace(Minecraft mc) {
        net.minecraft.server.MinecraftServer s = mc.getIntegratedServer();
        if (s == null) {
            serverRecovered = true;
            return;
        }
        int now = s.getTickCounter();
        int slot = serverSamples % SERVER_TICKS.length;
        int before = SERVER_TICKS[slot];
        SERVER_TICKS[slot] = now;
        serverSamples++;
        serverRecovered = serverSamples > SERVER_TICKS.length && now - before >= 18;
    }

    /** Ticks in a row with no chunk waiting to be built or uploaded: the land has settled. */
    private static int settledTicks;

    /**
     * The part's scene time: the camera, the fight and the part's own events follow it, not the
     * ticks, so every mode films the same thing. It runs through the warm-up, holds while the part
     * waits for its land and its server, and runs on while measured.
     */
    private static int scene;

    /** Ticks of the fight so far, counted only when a scene moves (the same in every mode). */
    private static int fightTicks;

    /** When the run began, the world opened and the preparation ended (for the report). */
    private static long worldOpenAt;

    private static int prepareTicks;
    private static boolean measuring;
    private static boolean setUp;
    private static boolean arrived;
    private static BenchStats current;

    /** The part whose picture is taken at the end of this frame, before it is taken down. */
    private static BenchStats pendingShot;

    private static BenchScene.Part pendingTearDown;

    private static final double[] CAM = new double[5];
    private static final double[] PREV = new double[5];
    private static boolean hasPrev;

    // per frame
    private static final com.sun.management.ThreadMXBean THREADS =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static long lastAlloc;
    private static final AtomicLong GC_EVENTS = new AtomicLong();
    private static final AtomicLong GC_PAUSE = new AtomicLong();
    private static long lastGcEvents;
    private static long lastGcPause;

    /** The window's input callbacks drop every event while this is true. */
    public static boolean inputLocked() {
        return inputLocked;
    }

    /** Whether the benchmark is driving the game (the idle frame limit stays off). */
    public static boolean running() {
        return ON && phase != Phase.WAITING && phase != Phase.DONE;
    }

    // ---- per frame (end of Display.update) ----

    public static void frameEnd() {
        if (!ON || phase == Phase.DONE) {
            return;
        }
        long now = System.nanoTime();
        try {
            if (phase == Phase.WAITING) {
                lastFrame = now;
                maybeStart(now);
            } else {
                // the bench's own work in the last frame, after its row was taken
                BenchZones.SPENT[BenchZones.BENCH] += carry;
                row(now);
                frame();
            }
        } catch (RuntimeException e) {
            Log.error("bench", "stopped by an error", e);
            event("error: " + e);
            finish(true);
        }
        BenchZones.clear();
        BenchCounters.clear();
        carry = System.nanoTime() - now;
    }

    /** The time this class spent at the end of the last frame (goes into the next row). */
    private static long carry;

    private static void maybeStart(long now) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || now - BOOT < BenchOptions.START_AT * 1_000_000_000L) {
            return;
        }
        if (!(mc.currentScreen instanceof GuiMainMenu) || mc.theWorld != null) {
            return;
        }
        begin(mc, now);
    }

    private static void begin(Minecraft mc, long now) {
        BenchConsole.install();
        route = new ArrayList<>(BenchScene.route());
        String mode = BenchModes.current();
        stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        session = BenchOptions.SESSION != null ? BenchOptions.SESSION : stamp;
        modes = BenchModes.detect();
        plan = BenchModes.plan(modes);
        String asked = askedMode();
        if (asked != null && !asked.equals(mode)) {
            // reopened for a mode that did not come up (Vulkan fell back to OpenGL, for one):
            // note it and go on to the next mode without measuring this one twice
            BenchSessions.note(
                    mc.mcDataDir,
                    session,
                    asked,
                    "the game came up in "
                            + mode
                            + " instead"
                            + (dev.xavier.stein.gfx.SteinBackend.fallback() == null
                                    ? ""
                                    : " (" + dev.xavier.stein.gfx.SteinBackend.fallback() + ")"));
            phase = Phase.DONE;
            nextMode(mc);
            return;
        }
        out = BenchSessions.modeZip(mc.mcDataDir, session, mode);
        File dir = out.getParentFile();
        try {
            Files.createDirectories(dir.toPath());
            zip = new ZipOutputStream(Files.newOutputStream(out.toPath()));
        } catch (IOException e) {
            Log.warn("bench", "cannot write " + out + ": " + e);
            message("steinbridge.bench.failed", e.toString());
            phase = Phase.DONE;
            return;
        }
        environment = BenchSystem.snapshot(mc);
        environment.put("mode", mode);
        environment.put("modes_on_this_machine", modes);
        environment.put("modes_still_to_run", plan);
        environment.put("session", session);
        if (BenchOptions.JFR && ModuleLayer.boot().findModule("jdk.jfr").isPresent()) {
            BenchJfr.start();
            jfr = true;
        } else {
            event("flight recording: " + (BenchOptions.JFR ? "this Java has no jdk.jfr" : "off"));
        }
        frames = new BenchFrames(zip, SEGMENTS);
        external = new BenchExternal();
        external.start();
        sampler =
                new BenchSampler(
                        Thread.currentThread().threadId(), Bench::segmentNow, SEGMENTS, external);
        watchCollections();
        Runtime.getRuntime().addShutdownHook(new Thread(Bench::onShutdown, "Stein Bench shutdown"));
        lockInput();
        title = Display.getTitle();
        Display.setTitle(I18n.format("steinbridge.bench.preparing"));
        BenchSettings.resize(BenchOptions.SIZE);
        start = now;
        lastFrame = now;
        lastAlloc = THREADS.getCurrentThreadAllocatedBytes();
        event(
                "started: "
                        + out.getName()
                        + ", "
                        + route.size()
                        + " parts x "
                        + BenchOptions.REPEAT);
        phase = Phase.OPENING;
        BenchScene.openWorld(mc);
    }

    private static void frame() {
        Minecraft mc = Minecraft.getMinecraft();
        BenchConsole.install(); // again, if the game swapped the standard streams
        if (pendingShot != null) {
            picture(mc, pendingShot);
            pendingShot = null;
        }
        if (pendingTearDown != null) {
            BenchScene.Part p = pendingTearDown;
            pendingTearDown = null;
            BenchScene.tearDown(mc, p);
            next(mc);
        }
        if (inputLocked) {
            // the pause menu when the window loses focus, a death screen, a chat: not part of the
            // route (the inventory and the menus parts open their own)
            boolean ownScreen =
                    phase == Phase.RUNNING && setUp && BenchScene.ownsScreen(route.get(index));
            if (mc.currentScreen != null && mc.theWorld != null && !ownScreen) {
                mc.displayGuiScreen(null);
            }
        }
        if (phase == Phase.OPENING
                && mc.theWorld != null
                && mc.thePlayer != null
                && mc.getIntegratedServer() != null) {
            event("world open");
            worldOpenAt = System.nanoTime();
            BenchScene.prepare(mc);
            settings = BenchSettings.apply(mc);
            prepareTicks = 0;
            phase = Phase.PREPARING;
        }
    }

    /**
     * The part's picture, through the game's own screenshot (it reads the frame on either API), and
     * the OpenGL errors the driver has queued (OpenGL only). Taken after the part was measured, so
     * the pause it causes is in no measured frame.
     */
    private static void picture(Minecraft mc, BenchStats s) {
        try {
            String name =
                    "stein-bench-"
                            + stamp
                            + "-"
                            + String.format(Locale.ROOT, "%02d", s.segment)
                            + "-"
                            + s.name.replace('#', '_')
                            + ".png";
            ScreenShotHelper.saveScreenshot(
                    mc.mcDataDir, name, mc.displayWidth, mc.displayHeight, mc.framebufferMc);
            s.shot = new File(new File(mc.mcDataDir, "screenshots"), name);
        } catch (RuntimeException e) {
            event(s.name + ": picture failed: " + e);
        }
        if (!Win.vulkan) {
            int errors = 0;
            while (errors < 64 && GL11.glGetError() != GL11.GL_NO_ERROR) {
                errors++;
            }
            s.glErrors = errors;
        }
    }

    /** The frame's row: everything measured since the last frame ended. */
    private static void row(long now) {
        long frame = now - lastFrame;
        lastFrame = now;
        int at = frames.row();
        if (at < 0) {
            return;
        }
        long[] v = frames.values();
        v[at + BenchFrames.T] = now - start;
        v[at + BenchFrames.SEGMENT] = segmentNow();
        // 2: measured, but it carried the GPU timestamps, so it stays out of the frame statistics
        boolean timed = dev.xavier.stein.gfx.SteinGpuPassTiming.takeTimed();
        v[at + BenchFrames.MEASURED] = measuring ? (timed ? 2 : 1) : 0;
        v[at + BenchFrames.FRAME] = frame;
        BenchZones.tickRest();
        for (int z = 0; z < BenchZones.COUNT; z++) {
            v[at + BenchFrames.ZONES + z] = BenchZones.SPENT[z];
        }
        int t = at + BenchFrames.TAIL_AT;
        v[t] = BenchZones.CALLS[BenchZones.TICK];
        v[t + 1] = BenchCounters.draws;
        v[t + 2] = BenchCounters.passes;
        v[t + 3] = BenchCounters.syncs;
        v[t + 4] = BenchCounters.renderBreaks;
        v[t + 5] = BenchCounters.ringWaits;
        v[t + 6] = BenchCounters.ringWaitNanos;
        v[t + 7] = BenchCounters.textureUploads;
        v[t + 8] = BenchCounters.chunkUploads;
        gameCounts(v, t + 9);
        long alloc = THREADS.getCurrentThreadAllocatedBytes();
        v[t + 14] = (alloc - lastAlloc) >> 10;
        lastAlloc = alloc;
        long events = GC_EVENTS.get();
        long pause = GC_PAUSE.get();
        v[t + 15] = events - lastGcEvents;
        v[t + 16] = pause - lastGcPause;
        lastGcEvents = events;
        lastGcPause = pause;
        v[t + 17] = Win.focused ? 1 : 0;
        v[t + 18] =
                Win.handle != 0L && GLFW.glfwGetWindowAttrib(Win.handle, GLFW.GLFW_ICONIFIED) != 0
                        ? 1
                        : 0;
        v[t + 19] = Display.getWidth();
        v[t + 20] = Display.getHeight();
        if (measuring && current != null && !timed) {
            current.add(v, at);
        }
        frames.commit();
    }

    /** Chunks drawn, entities drawn and in the world, tile entities, particles. */
    private static void gameCounts(long[] v, int at) {
        Minecraft mc = Minecraft.getMinecraft();
        RenderGlobal rg = mc.renderGlobal;
        if (rg == null || mc.theWorld == null) {
            for (int i = 0; i < 5; i++) {
                v[at + i] = 0;
            }
            return;
        }
        v[at] = rg.renderInfos == null ? 0 : rg.renderInfos.size();
        v[at + 1] = rg.countEntitiesRendered;
        v[at + 2] = rg.countEntitiesTotal;
        v[at + 3] = mc.theWorld.loadedTileEntityList.size();
        int particles = 0;
        if (mc.effectRenderer != null && mc.effectRenderer.fxLayers != null) {
            for (List<?>[] layer : mc.effectRenderer.fxLayers) {
                for (List<?> l : layer) {
                    particles += l.size();
                }
            }
        }
        v[at + 4] = particles;
    }

    /**
     * Chunks still to be built or uploaded (moving the camera keeps re-sorting the translucent
     * layer, so uploads alone never stop). Read by the sampler too (sizes only).
     */
    static int pendingChunks(Minecraft mc) {
        RenderGlobal rg = mc.renderGlobal;
        if (rg == null || mc.theWorld == null) {
            return 0;
        }
        int n = rg.chunksToUpdate.size();
        if (rg.renderDispatcher != null) {
            n += rg.renderDispatcher.queueChunkUpdates.size();
            n += rg.renderDispatcher.queueChunkUploads.size();
        }
        return n;
    }

    private static int segmentNow() {
        return phase == Phase.RUNNING ? segment : -1;
    }

    /** The part being measured right now, or -1 (warm-up, set-up, between parts). */
    static int measuringSegment() {
        return phase == Phase.RUNNING && measuring ? segment : -1;
    }

    // ---- per client tick (GameHooks.tickEnd) ----

    public static void tick() {
        if (!ON || (phase != Phase.PREPARING && phase != Phase.RUNNING)) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (phase == Phase.PREPARING) {
            // 10 s for the commands of the preparation and the relighting they cause
            if (prepareMark < 0) {
                prepareMark = BenchCommands.mark();
            }
            serverPace(mc);
            // the preparation's commands run and the server keeping its ticks again (a large fill
            // relights for seconds), at least 10 s, at most 2 min
            boolean ready = BenchCommands.ran(prepareMark) && serverRecovered;
            if (mc.thePlayer != null && ++prepareTicks >= 200 && (ready || prepareTicks >= 2400)) {
                if (!ready) {
                    event("the server was still busy after the longest preparation");
                }
                setupDone();
                phase = Phase.RUNNING;
                index = 0;
                round = 0;
                startPart(mc);
            }
            return;
        }
        if (pendingTearDown != null) {
            return; // the part ended in this frame: its picture and take-down come first
        }
        BenchScene.Part part = route.get(index);
        boolean noWorld = part.dimension() == BenchScene.ANY_DIMENSION;
        if (!noWorld && (mc.thePlayer == null || mc.theWorld == null)) {
            return;
        }
        if (!setUp) {
            if (part.kind() == BenchScene.Kind.BIOMES) {
                expandBiomes();
                return;
            }
            BenchScene.setUp(mc, part);
            partMark = BenchCommands.mark();
            setUp = true;
        }
        if (!noWorld && !inDimension(mc, part)) {
            if (++waitTicks > DIMENSION_TIMEOUT_TICKS) {
                skip(mc, part, "did not reach dimension " + part.dimension());
            }
            return;
        }
        if (!arrived) {
            arrived = BenchScene.arrived(mc, part);
            if (!arrived) {
                if (++waitTicks > DIMENSION_TIMEOUT_TICKS) {
                    skip(mc, part, "no arrival point after the dimension change or teleport");
                }
                return;
            }
            BenchGuard.reset();
        }
        String gone = noWorld ? null : BenchGuard.check(mc);
        if (gone != null) {
            abort(mc, part, gone);
            return;
        }
        int nextScene =
                measuring ? part.warmupTicks() + measured : Math.min(tick, part.warmupTicks());
        // what follows the scene runs only when it moves
        boolean moved = nextScene != scene || tick == 0;
        scene = nextScene;
        BenchScene.everyTick(mc, part, scene, moved);
        if (moved) {
            BenchPlayers.tick(mc, fightTicks++);
        }
        settledTicks = pendingChunks(mc) == 0 ? settledTicks + 1 : 0;
        if (mc.thePlayer != null) {
            place(mc, part);
        }
        tick++;
        if (measuring && measured % 10 == 0 && current.biome >= 0) {
            current.biomeSamples++;
            current.biomeHits += BenchGuard.biomeUnder(mc) == current.biome ? 1 : 0;
        }
        if (!measuring) {
            serverPace(mc);
            // the part's scenery built on the server and its ticks back to normal
            boolean warm =
                    tick >= part.warmupTicks()
                            && ((BenchCommands.ran(partMark) && serverRecovered)
                                    || tick >= part.warmupTicks() + part.maxWaitTicks());
            boolean waitedLong = tick >= part.warmupTicks() + part.maxWaitTicks();
            // the land around the camera arrived, built and uploaded, for a second in a row
            boolean landDone =
                    !part.waitChunks() || (settledTicks >= 20 && BenchGuard.landArrived(mc));
            if (warm && (landDone || waitedLong)) {
                int biome = BenchScene.biomeOf(part);
                int under = biome < 0 ? -1 : BenchGuard.biomeUnder(mc);
                measuring = true;
                measured = 0;
                current =
                        new BenchStats(
                                SEGMENTS.get(segment), round + 1, segment, part, System.nanoTime());
                current.dimension =
                        mc.theWorld == null
                                ? BenchScene.ANY_DIMENSION
                                : mc.theWorld.provider.getDimensionId();
                current.settled = landDone;
                current.biome = biome;
                if (!landDone) {
                    event(part.name() + ": chunks still loading after the longest warm-up");
                }
                if (biome >= 0 && under != biome) {
                    abort(
                            mc,
                            part,
                            "the camera is over "
                                    + BenchGuard.biomeName(under)
                                    + ", not "
                                    + BenchGuard.biomeName(biome));
                }
            }
            return;
        }
        if (++measured >= part.measureTicks()) {
            endPart(mc, part);
        }
    }

    /**
     * The part cannot film what it is meant to (the world is not there, the wrong biome): it ends
     * now, as a failed part with the reason, instead of measuring something else.
     */
    private static void abort(Minecraft mc, BenchScene.Part part, String why) {
        if (current == null) {
            current =
                    new BenchStats(
                            SEGMENTS.get(segment), round + 1, segment, part, System.nanoTime());
            current.dimension =
                    mc.theWorld == null
                            ? BenchScene.ANY_DIMENSION
                            : mc.theWorld.provider.getDimensionId();
        }
        current.aborted = why;
        event(part.name() + ": FAIL: " + why);
        endPart(mc, part);
    }

    /** The title menus part runs without the game's 30 fps cap on menus (Display.sync). */
    public static boolean uncappedMenus() {
        return ON
                && phase == Phase.RUNNING
                && route != null
                && index < route.size()
                && route.get(index).kind() == BenchScene.Kind.MENUS_TITLE;
    }

    /** How long the world took to open and the preparation to finish, into the report. */
    private static void setupDone() {
        long now = System.nanoTime();
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("total_s", BenchStats.round1((now - start) / 1e9));
        m.put("world_open_s", BenchStats.round1((worldOpenAt - start) / 1e9));
        m.put("preparation_s", BenchStats.round1((now - worldOpenAt) / 1e9));
        m.put("command_errors", BenchCommands.failures());
        environment.put("setup", m);
        event("setup done: " + m);
    }

    private static boolean inDimension(Minecraft mc, BenchScene.Part part) {
        return mc.theWorld != null && mc.theWorld.provider.getDimensionId() == part.dimension();
    }

    private static void endPart(Minecraft mc, BenchScene.Part part) {
        current.endNanos = System.nanoTime();
        RESULTS.add(current);
        pendingShot = current;
        event(
                current.name
                        + ": "
                        + current.count
                        + " frames in "
                        + String.format(Locale.ROOT, "%.1f", current.seconds())
                        + " s"
                        + (current.clean() ? "" : " (window out of focus, minimized or resized)"));
        current = null;
        measuring = false;
        // the picture is taken at the end of this frame, with the part still on screen; then the
        // part is taken down and the next one starts
        pendingTearDown = part;
    }

    private static void skip(Minecraft mc, BenchScene.Part part, String why) {
        SKIPPED.add(SEGMENTS.get(segment) + ": " + why);
        event(part.name() + " skipped: " + why);
        BenchScene.tearDown(mc, part);
        next(mc);
    }

    private static void next(Minecraft mc) {
        if (++index == route.size()) {
            index = 0;
            if (++round == BenchOptions.REPEAT) {
                finish(false);
                return;
            }
        }
        startPart(mc);
    }

    /** The {@code biomes} part becomes one part per biome found, once the search is done. */
    private static void expandBiomes() {
        if (!BenchTerrain.searched) {
            return; // the search is still running: wait
        }
        route.remove(index);
        List<BenchScene.Part> biomes = BenchScene.biomeParts();
        route.addAll(index, biomes);
        for (String m : BenchTerrain.MISSING) {
            SKIPPED.add("biome " + m + ": not within the search radius of this seed");
        }
        if (index >= route.size()) {
            index--;
            next(Minecraft.getMinecraft());
            return;
        }
        startPart(Minecraft.getMinecraft());
    }

    private static void startPart(Minecraft mc) {
        tick = 0;
        scene = 0;
        waitTicks = 0;
        settledTicks = 0;
        setUp = false;
        arrived = false;
        hasPrev = false;
        BenchScene.Part p = route.get(index);
        SEGMENTS.add(round == 0 ? p.name() : p.name() + "#" + (round + 1));
        segment = SEGMENTS.size() - 1;
        BenchConsole.segment = segment;
        Display.setTitle(
                I18n.format(
                        "steinbridge.bench.title", SEGMENTS.get(segment), index + 1, route.size()));
        event("part " + SEGMENTS.get(segment));
    }

    /**
     * Puts the camera where the route says, for this tick: the previous position becomes the one
     * the frames interpolate from, so the motion is smooth at any frame rate.
     */
    private static void place(Minecraft mc, BenchScene.Part part) {
        BenchScene.camera(part, scene, CAM);
        if (!hasPrev) {
            System.arraycopy(CAM, 0, PREV, 0, 5);
            hasPrev = true;
        }
        var p = mc.thePlayer;
        p.motionX = 0;
        p.motionY = 0;
        p.motionZ = 0;
        p.capabilities.isFlying = true;
        p.setPosition(CAM[0], CAM[1], CAM[2]);
        p.prevPosX = p.lastTickPosX = PREV[0];
        p.prevPosY = p.lastTickPosY = PREV[1];
        p.prevPosZ = p.lastTickPosZ = PREV[2];
        float yaw = (float) CAM[3];
        float prevYaw = (float) PREV[3];
        p.rotationYaw = yaw;
        p.prevRotationYaw = prevYaw;
        p.rotationYawHead = yaw;
        p.prevRotationYawHead = prevYaw;
        p.renderYawOffset = yaw;
        p.prevRenderYawOffset = prevYaw;
        p.rotationPitch = (float) CAM[4];
        p.prevRotationPitch = (float) PREV[4];
        System.arraycopy(CAM, 0, PREV, 0, 5);
    }

    // ---- the GPU (SteinGpuPassTiming, as each frame's timestamps arrive) ----

    /** A GPU frame: from the first pass's start to the last pass's end, the passes and the gaps. */
    public static void gpuFrame(long span, long busy, long gaps) {
        if (ON && current != null) {
            current.gpuFrame(span, busy, gaps);
        }
    }

    /** One pass of the GPU frame being read. */
    public static void gpuPass(String label, long nanos) {
        if (ON && current != null) {
            current.gpuPass(label, nanos);
        }
    }

    // ---- input ----

    private static void lockInput() {
        inputLocked = true;
        Win.resetInput();
        Display.setResizable(false);
    }

    private static void unlockInput() {
        inputLocked = false;
        Win.resetInput();
        Display.setResizable(true);
    }

    // ---- end ----

    private static void finish(boolean stopped) {
        if (phase == Phase.DONE) {
            return;
        }
        phase = Phase.DONE;
        BenchConsole.segment = -1;
        Minecraft mc = Minecraft.getMinecraft();
        if (current != null) {
            current.endNanos = System.nanoTime();
            event(current.name + ": stopped before the end of the part");
            current = null;
        }
        if (pendingShot != null && !shuttingDown) {
            picture(mc, pendingShot);
            pendingShot = null;
        }
        if (!shuttingDown) {
            BenchPlayers.remove(mc);
            unlockInput();
            if (settings != null) {
                settings.restore(mc);
            }
            if (title != null) {
                Display.setTitle(title);
            }
        }
        event(stopped ? "stopped" : "finished");
        File recording = null;
        if (jfr) {
            recording = new File(out.getParentFile(), "recording.jfr");
            if (!BenchJfr.stop(recording)) {
                recording = null;
            }
        }
        String error =
                BenchReport.write(
                        zip,
                        frames,
                        sampler,
                        external,
                        environment,
                        RESULTS,
                        SKIPPED,
                        SEGMENTS,
                        start,
                        stopped,
                        recording,
                        mc);
        if (recording != null) {
            recording.delete();
        }
        if (shuttingDown) {
            return;
        }
        if (error != null) {
            message("steinbridge.bench.failed", error);
        }
        for (BenchStats s : RESULTS) {
            if (!s.clean()) {
                message("steinbridge.bench.invalid");
                break;
            }
        }
        Log.info("bench", "report: " + out.getAbsolutePath());
        if (!stopped) {
            nextMode(mc);
        }
    }

    /** The session's name: its runs' reports go into one folder, joined at the end. */
    private static String session;

    private static java.util.Map<String, String> modes;
    private static List<String> plan = List.of();
    private static boolean jfr;

    /** The mode a reopened game was asked for (null at the first run). */
    private static String askedMode() {
        if (BenchOptions.SESSION == null) {
            return null;
        }
        if ("false".equals(System.getProperty("steinbridge.gfx"))) {
            return BenchModes.FIXED;
        }
        String api = System.getProperty("steinbridge.gfx.api");
        return api == null ? null : api.toLowerCase(Locale.ROOT);
    }

    /**
     * After a run: the game reopens in the next mode of the plan, or, with none left, the session
     * is joined into the zip to send and the game says where it is.
     */
    private static void nextMode(Minecraft mc) {
        if (!plan.isEmpty()) {
            String next = plan.get(0);
            List<String> after = plan.subList(1, plan.size());
            if (mc.theWorld != null) {
                mc.theWorld.sendQuittingDisconnectingPacket();
                mc.loadWorld((net.minecraft.client.multiplayer.WorldClient) null);
            }
            if (BenchModes.relaunch(next, session, after)) {
                event("reopening the game in " + next + (after.isEmpty() ? "" : ", then " + after));
                mc.shutdown();
                return;
            }
            for (String m : plan) {
                BenchSessions.note(
                        mc.mcDataDir,
                        session,
                        m,
                        "the game could not be reopened by itself: run it again with this mode");
            }
        }
        try {
            File done = BenchSessions.combine(mc.mcDataDir, session);
            if (done != null) {
                message("steinbridge.bench.done", done.getAbsolutePath());
                Log.info("bench", "session report: " + done.getAbsolutePath());
            }
        } catch (IOException e) {
            message("steinbridge.bench.failed", e.toString());
        }
        if (BenchOptions.QUIT) {
            mc.shutdown();
        }
    }

    private static volatile boolean shuttingDown;

    /** The window closed (or the game quit) mid-run: the report is still written. */
    private static void onShutdown() {
        if (phase == Phase.OPENING || phase == Phase.PREPARING || phase == Phase.RUNNING) {
            shuttingDown = true;
            finish(true);
        }
    }

    static synchronized void event(String e) {
        String line =
                String.format(Locale.ROOT, "%8.2f s  %s", (System.nanoTime() - BOOT) / 1e9, e);
        EVENTS.add(line);
        Log.info("bench", e);
    }

    static synchronized List<String> events() {
        return new ArrayList<>(EVENTS);
    }

    private static void message(String key, Object... args) {
        Minecraft mc = Minecraft.getMinecraft();
        String text = I18n.format(key, args);
        if (mc.ingameGUI != null && mc.theWorld != null) {
            mc.ingameGUI.getChatGUI().printChatMessage(new ChatComponentText(text));
        }
        Log.info("bench", text);
    }

    /** Counts the collections and sums their pauses (concurrent cycles are counted, not summed). */
    private static void watchCollections() {
        for (GarbageCollectorMXBean g : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (!(g instanceof NotificationEmitter emitter)) {
                continue;
            }
            boolean concurrent =
                    g.getName().contains("Cycles") || g.getName().contains("Concurrent");
            emitter.addNotificationListener(
                    (n, h) -> {
                        if (!GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION
                                .equals(n.getType())) {
                            return;
                        }
                        GarbageCollectionNotificationInfo info =
                                GarbageCollectionNotificationInfo.from(
                                        (CompositeData) n.getUserData());
                        GC_EVENTS.incrementAndGet();
                        if (!concurrent) {
                            GC_PAUSE.addAndGet(info.getGcInfo().getDuration() * 1_000_000L);
                        }
                    },
                    null,
                    null);
        }
    }
}
