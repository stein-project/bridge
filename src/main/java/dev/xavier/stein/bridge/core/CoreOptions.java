/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
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
}
