/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

package dev.xavier.stein.gfx.gl;

import dev.xavier.stein.bridge.core.Log;
import dev.xavier.stein.gfx.SteinGfxOptions;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Submission counters of the recorded frame ({@link SteinGlFrame}): always counted (the out-of-game
 * test prints them) and, with {@code frameSubmitStats}, reported every 5 s with the reason of each
 * sync and the fence flushes.
 *
 * <p>Pipeline: Stein GFX only.
 */
final class SteinGlFrameStats {

    private SteinGlFrameStats() {}

    static final boolean ON = SteinGfxOptions.FRAME_SUBMIT_STATS;

    static long frames;
    static long submits;
    static long endSubmits;
    static long opsTotal;
    static long bytesTotal;
    static long statFrames;
    static long statSubmits;
    static long statEnd;
    static long statOps;
    static long statBytes;
    static final Map<String, long[]> REASONS = new LinkedHashMap<>();
    static long reportAt = System.nanoTime();

    /** glFenceSync calls (fences together) since the last report. */
    static long fenceFlushes;

    /** The pass fell in the middle of a sync and was split in two. */
    static void passSplit(String passName) {
        REASONS.computeIfAbsent("(pass split by sync: " + passName + ")", k -> new long[1])[0]++;
    }

    /**
     * The reason and the caller (the first three calls outside here and the detours), aggregated.
     */
    static void noteSync(String reason) {
        String who =
                StackWalker.getInstance()
                        .walk(
                                s ->
                                        s.map(f -> f.getClassName() + "." + f.getMethodName())
                                                .filter(n -> !isOwnFrame(n))
                                                .limit(3)
                                                .collect(
                                                        java.util.stream.Collectors.joining(
                                                                " <- ")));
        REASONS.computeIfAbsent(reason + " @ " + who, k -> new long[1])[0]++;
    }

    private static boolean isOwnFrame(String n) {
        return n.startsWith("dev.xavier.stein.gfx.gl.")
                || n.startsWith("dev.xavier.stein.gfx.SteinGlTrack")
                || n.startsWith("dev.xavier.stein.gfx.SteinLazyGl");
    }

    static void report() {
        long now = System.nanoTime();
        if (now - reportAt < 5_000_000_000L) {
            return;
        }
        long f = frames - statFrames;
        if (f > 0) {
            long sub = submits - statSubmits;
            long end = endSubmits - statEnd;
            StringBuilder b =
                    new StringBuilder(
                            String.format(
                                    "recorded frame: %.2f submissions per frame (end of frame"
                                            + " %.2f, sync %.2f), %.0f commands and %.1f KB of data"
                                            + " per frame (%d frames)",
                                    (double) sub / f,
                                    (double) end / f,
                                    (double) (sub - end) / f,
                                    (double) (opsTotal - statOps) / f,
                                    (bytesTotal - statBytes) / 1024.0 / f,
                                    f));
            b.append(
                    String.format(
                            ", %.2f glFenceSync per frame (fences together)",
                            (double) fenceFlushes / f));
            fenceFlushes = 0;
            if (!REASONS.isEmpty()) {
                b.append("; syncs:");
                for (Map.Entry<String, long[]> e : REASONS.entrySet()) {
                    b.append(
                            String.format(
                                    " %s %.3f/frame |", e.getKey(), (double) e.getValue()[0] / f));
                }
                REASONS.clear();
            }
            Log.info("gfx", b.toString());
        }
        statFrames = frames;
        statSubmits = submits;
        statEnd = endSubmits;
        statOps = opsTotal;
        statBytes = bytesTotal;
        reportAt = now;
    }
}
