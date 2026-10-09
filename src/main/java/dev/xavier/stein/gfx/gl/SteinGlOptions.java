package dev.xavier.stein.gfx.gl;

import dev.xavier.stein.bridge.core.Opt;

/**
 * The system properties read by the OpenGL backend of Stein GFX (all diagnostic or test options).
 * The ones shared with the rest of Stein GFX are in {@code SteinGfxOptions}.
 *
 * <p>Pipeline: Stein GFX only.
 */
public final class SteinGlOptions {

    private SteinGlOptions() {}

    /**
     * {@code -Dsteinbridge.gfx.audit=true}: reports the draws that bypass Stein GFX (default: off).
     */
    static final boolean AUDIT = Opt.flag("steinbridge.gfx.audit");

    /**
     * {@code -Dsteinbridge.gfx.glDebug=true}: turns on the driver's debug output (default: off).
     */
    static final boolean GL_DEBUG = Opt.flag("steinbridge.gfx.glDebug");

    /**
     * {@code -Dsteinbridge.gfx.tier=41}: pretends to be a GL 4.1 device (default: unset, the real
     * device).
     */
    static final String TIER = Opt.text("steinbridge.gfx.tier", null);

    /**
     * {@code -Dsteinbridge.gfx.recordFrameCheck.every=N}: checks one recorded frame every N
     * (default: 300, minimum 1).
     */
    static final int RECORD_FRAME_CHECK_EVERY =
            Math.max(1, Opt.integer("steinbridge.gfx.recordFrameCheck.every", 300));

    /**
     * {@code -Dsteinbridge.gfx.recordFrameDump=N}: prints the recorded frame number N (default: -1,
     * never).
     */
    static final long RECORD_FRAME_DUMP = Opt.number("steinbridge.gfx.recordFrameDump", -1L);

    /**
     * {@code -Dsteinbridge.gfx.test.noGame=true}: runs without the game, for the out-of-game test
     * (default: off).
     */
    static final boolean TEST_NO_GAME = Opt.flag("steinbridge.gfx.test.noGame");

    /**
     * {@code -Dsteinbridge.gfx.gpuPassTiming.slots=N}: timer-query ring slots (default: 4096,
     * minimum 8).
     */
    static final int GPU_PASS_TIMING_SLOTS =
            Math.max(8, Opt.integer("steinbridge.gfx.gpuPassTiming.slots", 4096));

    /**
     * {@code -Dsteinbridge.gfx.gpuOpTiming=true}: measures GPU time per replayed command (default:
     * off).
     */
    static final boolean GPU_OP_TIMING = Opt.flag("steinbridge.gfx.gpuOpTiming");

    /**
     * {@code -Dsteinbridge.gfx.passTiming=true}: measures CPU time to open and close passes
     * (default: off).
     */
    static final boolean PASS_TIMING = Opt.flag("steinbridge.gfx.passTiming");
}
