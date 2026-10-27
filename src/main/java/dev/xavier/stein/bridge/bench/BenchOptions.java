/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.bench;

import dev.xavier.stein.bridge.core.Opt;

/**
 * The {@code -D} options of the built-in benchmark ({@link Bench}): a fixed scene played on its
 * own, with every frame measured and written to a report the player can send.
 */
public final class BenchOptions {

    private BenchOptions() {}

    /**
     * {@code -Dsteinbridge.bench=true}: runs the benchmark — opens a fresh world with a fixed seed,
     * plays a fixed route on its own with the player's input locked, and writes {@code
     * stein-bench/<date>.zip} in the game folder (default: off). It also turns on the GPU time per
     * pass.
     */
    public static final boolean ON = Opt.flag("steinbridge.bench");

    /**
     * {@code -Dsteinbridge.bench.settings=standard|player}: {@code standard} plays with the same
     * video settings on every machine (render distance 12, Fancy, smooth lighting, all particles,
     * fancy clouds, no frame limit, no VSync), so two machines compare; {@code player} keeps the
     * player's own (default: standard). Either way the player's settings come back at the end and
     * options.txt is never written.
     */
    public static final String SETTINGS = Opt.text("steinbridge.bench.settings", "standard");

    /**
     * {@code -Dsteinbridge.bench.scale=X}: multiplies how long each part of the route is measured
     * (default 1.0, about four minutes in all; 0.25 for a quick check).
     */
    public static final double SCALE = Math.max(0.05, Opt.decimal("steinbridge.bench.scale", 1.0));

    /** {@code -Dsteinbridge.bench.repeat=N}: plays the whole route N times (default 1). */
    public static final int REPEAT = Math.max(1, Opt.integer("steinbridge.bench.repeat", 1));

    /**
     * {@code -Dsteinbridge.bench.only=a,b}: plays only these parts of the route, by name (default:
     * all; the names are in the report and in docs/BENCHMARK.md).
     */
    public static final String ONLY = Opt.text("steinbridge.bench.only", "");

    /**
     * {@code -Dsteinbridge.bench.maxBiomes=N}: visits at most N biomes in the biome tour, the
     * nearest first (default 0: every biome the seed has within 2000 blocks of the spawn).
     */
    public static final int MAX_BIOMES = Math.max(0, Opt.integer("steinbridge.bench.maxBiomes", 0));

    /**
     * {@code -Dsteinbridge.bench.size=WxH}: sets the window to this size for the run, for two
     * machines to measure the same pixels (default: unset, the window as it is).
     */
    public static final String SIZE = Opt.text("steinbridge.bench.size", "");

    /**
     * {@code -Dsteinbridge.bench.quit=true}: closes the game when the report is written (default:
     * off, the game stays open and says where the file is).
     */
    public static final boolean QUIT = Opt.flag("steinbridge.bench.quit");

    /**
     * {@code -Dsteinbridge.bench.startAtSec=N}: seconds after startup before the benchmark opens
     * its world (default 5).
     */
    public static final int START_AT = Math.max(1, Opt.integer("steinbridge.bench.startAtSec", 5));

    /**
     * {@code -Dsteinbridge.bench.external=false}: no external tools (nvidia-smi and typeperf for
     * the GPU and processor clocks, reg and powercfg for the machine description; all read-only)
     * (default: they run, the clocks at one sample per second).
     */
    public static final boolean EXTERNAL = Opt.flag("steinbridge.bench.external", true);

    /**
     * {@code -Dsteinbridge.bench.gpu=false}: no GPU time per pass during the run, to measure what
     * the timestamps themselves cost (default: on; set in {@code core.Opt} before Stein GFX reads
     * it).
     */
    public static final boolean GPU = Opt.flag("steinbridge.bench.gpu", true);

    /**
     * {@code -Dsteinbridge.bench.modes=auto|current|vulkan,opengl,fixed}: the graphics modes to
     * run, one run each, the game reopening itself between them and joining the reports at the end:
     * {@code auto}, every mode this machine has (Stein GFX on Vulkan and on OpenGL, the
     * fixed-function pipeline); {@code current}, only the mode the game started in; or a list
     * (default: auto).
     */
    public static final String MODES = Opt.text("steinbridge.bench.modes", "auto");

    /**
     * {@code -Dsteinbridge.bench.jfr=false}: no flight recording in the report (default: on, when
     * the running Java has the jdk.jfr module).
     */
    public static final boolean JFR = Opt.flag("steinbridge.bench.jfr", true);

    /**
     * {@code -Dsteinbridge.bench.session=name}: set by the benchmark when it reopens the game, so
     * the runs of every mode go into one report (default: unset, a new session).
     */
    public static final String SESSION = Opt.text("steinbridge.bench.session", null);

    /**
     * {@code -Dsteinbridge.bench.modesLeft=a,b}: set by the benchmark when it reopens the game: the
     * modes still to run after this one (default: unset).
     */
    public static final String MODES_LEFT = Opt.text("steinbridge.bench.modesLeft", null);

    /**
     * {@code -Dsteinbridge.bench.dir=path}: where the report goes (default: {@code stein-bench} in
     * the game folder).
     */
    public static final String DIR = Opt.text("steinbridge.bench.dir", "stein-bench");
}
