/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.core;

/**
 * The {@code -D} options of the window and core layer (read by {@code org.lwjgl.opengl.Display}).
 */
public final class CoreOptions {

    private CoreOptions() {}

    /**
     * {@code -Dsteinbridge.gfx=false}: the game draws with its own fixed-function pipeline instead
     * of Stein GFX (default: Stein GFX). Without an OpenGL 4.1 core context the fixed-function
     * pipeline is used anyway.
     */
    public static final boolean GFX = Opt.flag("steinbridge.gfx", true);

    /**
     * {@code -Dsteinbridge.gfx.api=opengl|vulkan}: the graphics API Stein GFX draws with for this
     * session, instead of the one saved in the video options (default: unset, the saved choice;
     * OpenGL when there is none). Without Vulkan on the machine the game falls back to OpenGL.
     */
    public static final String GFX_API = Opt.text("steinbridge.gfx.api", null);

    /**
     * {@code -Dsteinbridge.gfx.vkGlSink=false} (diagnostics): with Vulkan, no hidden OpenGL context
     * absorbs the OpenGL calls of mods that call it directly — such a call then aborts the game,
     * which is how to find one (default: the context exists).
     */
    public static final boolean VK_GL_SINK = Opt.flag("steinbridge.gfx.vkGlSink", true);

    /**
     * {@code -Dsteinbridge.opt.noVsyncLatencyFix=true}: goes back to the usual VSync instead of
     * adaptive VSync with a GPU fence per frame (default: off, the latency fix is on).
     */
    public static final boolean NO_VSYNC_LATENCY_FIX =
            Opt.flag("steinbridge.opt.noVsyncLatencyFix");

    /**
     * {@code -Dsteinbridge.diag.background=true}: test bench only; the window appears minimized and
     * without focus (default: off).
     */
    public static final boolean DIAG_BACKGROUND = Opt.flag("steinbridge.diag.background");

    /**
     * {@code -Dsteinbridge.gfx.glDebug=true}: asks GLFW for an OpenGL debug context (default: off).
     */
    public static final boolean GL_DEBUG = Opt.flag("steinbridge.gfx.glDebug");

    /**
     * {@code -Dsteinbridge.perf.timeSwap=true}: times the buffer swap and the event queue and
     * prints the worst case and average per second (default: off).
     */
    public static final boolean TIME_SWAP = Opt.flag("steinbridge.perf.timeSwap");

    /**
     * {@code -Dsteinbridge.perf.frameStats=true}: every 5 seconds, prints the frame count, the
     * average, p50 and p99 frame times and the 1% low (the average FPS of the slowest 1% of
     * frames), plus the average of the frames that ran a game tick and of the others, and how many
     * of the slowest frames had a tick, to compare the graphics APIs on the bench (default: off).
     */
    public static final boolean FRAME_STATS = Opt.flag("steinbridge.perf.frameStats");
}
