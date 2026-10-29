/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import dev.xavier.stein.bridge.core.Log;
import dev.xavier.stein.gfx.vk.SteinVkRay;

import java.util.ArrayList;
import java.util.List;

/**
 * The world as the Lens's rays see it (Vulkan with ray tracing): every opaque terrain slice gets a
 * bottom-level acceleration structure when it is uploaded (a few dozen a frame, so a fresh world
 * spreads over its first frames), dropped when the slice goes; each frame a top level gathers the
 * slices near the camera, placed relative to it. The terrain's LENS pixel traces it for the
 * reflections (docs/stein-mode-plan.md).
 *
 * <p>Off (no ray tracing on the device) nothing is made: the slices' hooks return at once.
 * Pipeline: Stein GFX on Vulkan.
 */
public final class SteinRayScene {

    /** Bottom levels built a frame at most (each slice is a few hundred quads). */
    private static final int BUILDS_PER_FRAME = 48;

    /** Slices farther than this from the camera stay out of the top level (blocks). */
    private static final double REACH = 96.0;

    /** The instance's custom index holds the slice's first vertex in 24 bits. */
    private static final int MAX_FIRST = (1 << 24) - 1;

    private static final List<SteinTerrainSlice> WAITING = new ArrayList<>();
    private static final List<SteinTerrainSlice> BUILT = new ArrayList<>();

    private static boolean failed;
    private static long statsAt;
    private static long buildNanos;
    private static int builds;

    private SteinRayScene() {}

    private static boolean active() {
        return !failed && SteinVkRay.available();
    }

    /** A terrain slice was uploaded: its bottom level waits for the next frames. */
    static void added(SteinTerrainSlice s) {
        if (!active() || s.count < 4) {
            return;
        }
        s.ray = new SteinVkRay.Bottom();
        s.rayIndex = -1;
        WAITING.add(s);
    }

    /** A terrain slice was released: its bottom level goes after the frames that may read it. */
    static void removed(SteinTerrainSlice s) {
        if (s.ray == null) {
            return;
        }
        SteinVkRay.release(s.ray);
        s.ray = null;
        int i = s.rayIndex;
        if (i >= 0 && i < BUILT.size() && BUILT.get(i) == s) {
            SteinTerrainSlice last = BUILT.remove(BUILT.size() - 1);
            if (last != s) {
                BUILT.set(i, last);
                last.rayIndex = i;
            }
        }
        s.rayIndex = -1;
        // (a slice still waiting is skipped when its turn comes: its bottom is gone)
    }

    /**
     * Once per world frame, before the terrain draws (camera at {@code cx, cy, cz}): builds the
     * waiting bottom levels and this frame's top level.
     */
    static void frame(double cx, double cy, double cz) {
        if (!active()) {
            return;
        }
        try {
            long t0 = System.nanoTime();
            SteinBuffer terrain = SteinTerrain.oneBuffer();
            SteinBuffer indices = SteinTerrain.indices();
            if (terrain != null && indices != null && !WAITING.isEmpty()) {
                long base = SteinVkRay.address(terrain);
                int asked = 0;
                int keep = 0;
                for (int i = 0; i < WAITING.size(); i++) {
                    SteinTerrainSlice s = WAITING.get(i);
                    if (s.ray == null || s.region.buffer != terrain) {
                        continue; // released meanwhile (or not in the one buffer)
                    }
                    if (asked < BUILDS_PER_FRAME
                            && SteinVkRay.requestBottom(s.ray, base + s.first * 16L, s.count)) {
                        asked++;
                        s.rayIndex = BUILT.size();
                        BUILT.add(s);
                    } else {
                        WAITING.set(keep++, s);
                    }
                }
                WAITING.subList(keep, WAITING.size()).clear();
                SteinVkRay.buildPending(SteinVkRay.address(indices));
                builds += asked;
            }
            if (SteinVkRay.beginTop()) {
                double reach2 = REACH * REACH;
                for (int i = 0; i < BUILT.size(); i++) {
                    SteinTerrainSlice s = BUILT.get(i);
                    double dx = s.x + 8 - cx;
                    double dy = s.y + 8 - cy;
                    double dz = s.z + 8 - cz;
                    if (dx * dx + dy * dy + dz * dz > reach2 || s.first > MAX_FIRST) {
                        continue; // far, or past what the instance's 24-bit index can name
                    }
                    if (!SteinVkRay.addInstance(
                            s.ray,
                            (float) (s.x - cx),
                            (float) (s.y - cy),
                            (float) (s.z - cz),
                            s.first)) {
                        break;
                    }
                }
                SteinVkRay.endTop();
            }
            buildNanos += System.nanoTime() - t0;
            stats();
        } catch (RuntimeException | LinkageError e) {
            failed = true;
            Log.warn("gfx", "ray tracing scene turned off for this session: " + e);
        }
    }

    /** Every five seconds: what the scene holds and what it cost the CPU. */
    private static void stats() {
        long now = System.nanoTime();
        if (statsAt == 0L) {
            statsAt = now;
            return;
        }
        if (now - statsAt < 5_000_000_000L) {
            return;
        }
        double sec = (now - statsAt) / 1e9;
        Log.info(
                "gfx",
                String.format(
                        "ray scene: %d slices built (+%d in %.0f s, %d waiting), %d in the top level,"
                                + " %.3f ms CPU a second",
                        BUILT.size(),
                        builds,
                        sec,
                        WAITING.size(),
                        SteinVkRay.topInstances(),
                        buildNanos / 1e6 / sec));
        statsAt = now;
        builds = 0;
        buildNanos = 0L;
    }
}
