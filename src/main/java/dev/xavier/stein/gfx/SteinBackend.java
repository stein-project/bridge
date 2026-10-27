/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import dev.xavier.stein.bridge.core.CoreOptions;
import dev.xavier.stein.bridge.core.Log;

import net.minecraft.client.Minecraft;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/**
 * The graphics API Stein GFX draws with: Vulkan (the default) or OpenGL. The player picks it in the
 * Stein Client, which writes it to {@code config/stein-graphics.properties} before starting the
 * game (the window is created for one API, so it is chosen before the game opens; the video options
 * only show it). {@code -Dsteinbridge.gfx.api=...} overrides the file for the session (the test
 * bench).
 *
 * <p>What was asked for and what is running can differ: when Vulkan does not start (no driver, no
 * GPU with Vulkan 1.2, the window refuses a surface) the game falls back to OpenGL and {@link
 * #fallback()} says why. Without a saved choice (a first start, or a launcher that writes none) it
 * is Vulkan: OpenGL only where Vulkan cannot run.
 *
 * <p>Pipeline: both — read before the window exists.
 */
public enum SteinBackend {
    OPENGL("opengl"),
    VULKAN("vulkan");

    /** The value in the file and in {@code -Dsteinbridge.gfx.api}. */
    public final String key;

    SteinBackend(String key) {
        this.key = key;
    }

    private static final String FILE = "config/stein-graphics.properties";
    private static final String KEY = "api";

    /**
     * Written when a start with Vulkan begins, removed once it has been presenting for a few
     * seconds: still there at the next start means the last one died on the way.
     */
    private static final String STARTING = "vulkanStarting";

    /**
     * Written when a start went back to OpenGL because the last one with Vulkan died: the Stein
     * Client reads it, switches its choice to OpenGL and tells the player.
     */
    private static final String CRASHED = "vulkanCrashed";

    /**
     * Written when Vulkan, asked for, cannot run on this machine (the {@link Fallback}'s name),
     * with OpenGL saved as the choice: the Stein Client reads it, switches to OpenGL and tells the
     * player, and the next starts do not try Vulkan again.
     */
    private static final String UNSUPPORTED = "vulkanUnsupported";

    /** A Vulkan start counts as good after this many frames over {@link #HEALTHY_NANOS}. */
    private static final int HEALTHY_FRAMES = 60;

    private static final long HEALTHY_NANOS = 5_000_000_000L;

    /** {@code -Dsteinbridge.gfx.api}: overrides the saved choice for the session. */
    private static final String FORCED = CoreOptions.GFX_API;

    /** Why Vulkan, asked for, did not start (the video options say it in the player's language). */
    public enum Fallback {
        /** No Vulkan loader or driver on the machine. */
        NO_DRIVER,
        /** The window system refused a window without OpenGL. */
        NO_WINDOW,
        /** No GPU with what Stein needs (Vulkan 1.2, dynamic rendering) that presents to it. */
        NO_DEVICE,
        /** A GPU whose driver is too old for Stein's Vulkan (no VK_EXT_depth_clip_control). */
        OLD_DRIVER
    }

    private static SteinBackend running = OPENGL;
    private static Fallback fallback;
    private static boolean crashedLastTime;
    private static boolean marked;
    private static int presented;
    private static long firstPresent;

    /**
     * The API asked for at this start: the option, else the saved choice, else Vulkan. A saved
     * Vulkan whose last start died before its first seconds goes back to OpenGL, saved too (the
     * video options say why), so a driver that crashes cannot keep the game from opening.
     */
    public static SteinBackend requested() {
        if (FORCED != null) {
            return parse(FORCED, VULKAN);
        }
        Properties p = load();
        SteinBackend api = parse(p.getProperty(KEY), VULKAN);
        if (api == VULKAN && Boolean.parseBoolean(p.getProperty(STARTING))) {
            Log.warn("gfx", "the last start with Vulkan did not get through: back to OpenGL");
            crashedLastTime = true;
            p.setProperty(KEY, OPENGL.key);
            p.remove(STARTING);
            p.setProperty(CRASHED, "true");
            store(p);
            return OPENGL;
        }
        return api;
    }

    /** The last start with Vulkan died on the way, and this one went back to OpenGL. */
    public static boolean crashedLastTime() {
        return crashedLastTime;
    }

    /** Display, before creating the Vulkan device: the start is marked until it proves good. */
    public static void vulkanStarting() {
        if (FORCED != null) {
            return; // a forced session (the test bench) leaves the player's file alone
        }
        Properties p = load();
        p.setProperty(STARTING, "true");
        store(p);
        marked = true;
    }

    /** Each frame presented with Vulkan: after a few seconds the start counts as good. */
    public static void vulkanPresented() {
        if (!marked) {
            return;
        }
        if (presented++ == 0) {
            firstPresent = System.nanoTime();
        }
        if (presented >= HEALTHY_FRAMES && System.nanoTime() - firstPresent >= HEALTHY_NANOS) {
            marked = false;
            Properties p = load();
            p.remove(STARTING);
            store(p);
        }
    }

    /** The Vulkan device could not be created: this start is not a crash. */
    public static void vulkanFailed() {
        if (marked) {
            marked = false;
            Properties p = load();
            p.remove(STARTING);
            store(p);
        }
    }

    private static Properties load() {
        Properties p = new Properties();
        File f = file();
        if (!f.isFile()) {
            return p;
        }
        try (InputStream in = Files.newInputStream(f.toPath())) {
            p.load(in);
        } catch (IOException e) {
            Log.warn("gfx", "could not read " + f + ": " + e);
        }
        return p;
    }

    private static void store(Properties p) {
        File f = file();
        try {
            File dir = f.getParentFile();
            if (dir != null) {
                Files.createDirectories(dir.toPath());
            }
            try (OutputStream out = Files.newOutputStream(f.toPath())) {
                p.store(out, "Stein graphics API, applied at the next start");
            }
        } catch (IOException e) {
            Log.warn("gfx", "could not save " + f + ": " + e);
        }
    }

    /** The API the window was created for. */
    public static SteinBackend running() {
        return running;
    }

    /**
     * Why the requested API is not the one running (Vulkan asked for and not available), or null.
     */
    public static Fallback fallback() {
        return fallback;
    }

    /** Vulkan did not start: {@code detail} goes to the log only. */
    public static void fellBack(Fallback why, String detail) {
        fallback = why;
        Log.warn("gfx", "Vulkan was requested, running OpenGL: " + detail);
        if (FORCED == null) {
            Properties p = load();
            p.setProperty(KEY, OPENGL.key);
            p.setProperty(UNSUPPORTED, why.name().toLowerCase(Locale.ROOT));
            store(p);
        }
    }

    /** Set by Display once the window exists. */
    public static void started(SteinBackend api) {
        running = api;
    }

    private static SteinBackend parse(String value, SteinBackend otherwise) {
        if (value == null) {
            return otherwise;
        }
        String v = value.trim().toLowerCase(Locale.ROOT);
        for (SteinBackend b : values()) {
            if (b.key.equals(v)) {
                return b;
            }
        }
        return otherwise;
    }

    /** Where the Vulkan backend keeps its compiled pipelines between sessions. */
    public static Path pipelineCacheFile() {
        return gameDir().toPath().resolve("cache").resolve("stein-vulkan-pipelines.bin");
    }

    private static File file() {
        return new File(gameDir(), FILE);
    }

    private static File gameDir() {
        File dir = null;
        try {
            Minecraft game = Minecraft.getMinecraft();
            dir = game == null ? null : game.mcDataDir;
        } catch (RuntimeException | LinkageError e) {
            // outside the game (tests): the working folder
        }
        return dir == null ? new File(".") : dir;
    }
}
