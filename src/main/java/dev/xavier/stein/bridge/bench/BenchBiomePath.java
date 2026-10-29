/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.bench;

import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.BlockPos;
import net.minecraft.world.WorldServer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The camera's path over one biome of the tour, worked out on the integrated server's thread from
 * the world itself, so it is the same on every machine and in every graphics mode: a straight glide
 * low over the land, with the start, the direction and the speed chosen so the camera is over the
 * biome at every measured tick (checked about every block along the path, swing included), and the
 * height of every tick taken from the server's own land (the chunks along the path are generated
 * and decorated first). The place the biome search found is only a hint (it comes from the
 * generator's coarse layer, often on an edge): the starts are the points of a grid around it that
 * really are in the biome, nearest first. A narrow biome (a river, a beach) that no fast glide
 * stays over gets a slower, shorter one. The client's chunks never steer it, so the camera does not
 * depend on how fast they arrive.
 */
final class BenchBiomePath {

    private BenchBiomePath() {}

    /** Blocks per tick, fastest first: the first speed with a path wholly over the biome wins. */
    private static final double[] SPEEDS = {0.4, 0.2, 0.1, 0.05};

    /** The sideways swing, in blocks per block-per-tick of speed (10 blocks at 0.4). */
    private static final double SWING_PER_SPEED = 25;

    /** How high over the highest ground of the next stretch. */
    private static final double ABOVE = 12;

    /** How far ahead the camera looks at the ground, in blocks (the tie-break between paths). */
    private static final int LOOK = 30;

    /** The grid of starts around the search's place: half its side and its step, in blocks. */
    private static final int REACH = 256;

    private static final int STEP = 16;

    /** At most this many starts (the nearest ones in the biome) are tried per speed. */
    private static final int MAX_STARTS = 160;

    /** The directions tried from each start. */
    private static final int DIRECTIONS = 16;

    /** One planned path: where it starts, where it heads, and the camera's height at each tick. */
    record Path(
            String part,
            int biome,
            double x0,
            double z0,
            double dirX,
            double dirZ,
            double speed,
            double[] heights,
            double share) {

        /** The camera at scene tick {@code t}: x, y, z, yaw, pitch. */
        void camera(double t, double[] out) {
            double along = speed * t;
            double side = SWING_PER_SPEED * speed * Math.sin(t / 120.0);
            out[0] = x0 + dirX * along - dirZ * side;
            out[2] = z0 + dirZ * along + dirX * side;
            int i = (int) Math.max(0, Math.min(heights.length - 1, Math.round(t)));
            out[1] = heights[i];
            out[3] = Math.toDegrees(Math.atan2(-dirX, dirZ)) + 20 * Math.sin(t / 100.0);
            out[4] = 18 + 6 * Math.sin(t / 60.0);
        }
    }

    private static volatile Path planned;

    /** The path of {@code part}, once planned (null before). */
    static Path of(BenchScene.Part part) {
        Path p = planned;
        return p != null && p.part().equals(part.name()) ? p : null;
    }

    /**
     * Plans the path of a biome part on the server's thread: {@code from} is the place the search
     * found, {@code ticks} the scene ticks it must cover, {@code measureFrom} the first measured.
     */
    static void plan(
            Minecraft mc,
            BenchScene.Part part,
            BlockPos from,
            int biome,
            int ticks,
            int measureFrom) {
        planned = null;
        BenchCommands.onServer(
                mc,
                () -> {
                    MinecraftServer server = mc.getIntegratedServer();
                    WorldServer w = server == null ? null : server.worldServerForDimension(0);
                    if (w == null) {
                        return;
                    }
                    long t0 = System.nanoTime();
                    Path p = choose(w, part.name(), from, biome, ticks, measureFrom);
                    planned = p;
                    Bench.event(
                            String.format(
                                    Locale.ROOT,
                                    "%s: path from %.0f %.0f heading %.0f deg at %.2f blocks/tick,"
                                            + " %.0f%% over the biome (%.1f s)",
                                    part.name(),
                                    p.x0(),
                                    p.z0(),
                                    Math.toDegrees(Math.atan2(-p.dirX(), p.dirZ())),
                                    p.speed(),
                                    p.share() * 100,
                                    (System.nanoTime() - t0) / 1e9));
                });
    }

    /**
     * The path: for each speed, fastest first, every start in the biome (nearest first) and every
     * direction; the first speed with a path wholly over the biome wins, and among those the one
     * whose look-ahead ground is most in the biome (the first one wins a tie). Without any, the
     * slowest path with the most of it over the biome.
     */
    private static Path choose(
            WorldServer w, String part, BlockPos from, int biome, int ticks, int measureFrom) {
        List<int[]> starts = starts(w, from, biome);
        // x0, z0, dx, dz, speed, camera share, look share
        double[] best = null;
        for (int s = 0; s < SPEEDS.length; s++) {
            double speed = SPEEDS[s];
            boolean slowest = s == SPEEDS.length - 1;
            double half = speed * ticks / 2;
            search:
            for (int[] at : starts) {
                for (int k = 0; k < DIRECTIONS; k++) {
                    double a = 2 * Math.PI * k / DIRECTIONS;
                    double dx = Math.cos(a);
                    double dz = Math.sin(a);
                    double x0 = at[0] + 0.5 - dx * half;
                    double z0 = at[1] + 0.5 - dz * half;
                    Path p = new Path("", biome, x0, z0, dx, dz, speed, new double[] {0}, 0);
                    // the faster speeds only take a whole path: stop at its first miss
                    double cam = cameraShare(w, p, ticks, measureFrom, !slowest);
                    if ((cam < 1 && !slowest) || (best != null && cam < best[5] - 1e-9)) {
                        continue;
                    }
                    double look = lookShare(w, p, ticks, measureFrom);
                    if (best == null || cam > best[5] + 1e-9 || look > best[6] + 1e-9) {
                        best = new double[] {x0, z0, dx, dz, speed, cam, look};
                    }
                    if (best[5] >= 1 && best[6] >= 1) {
                        break search; // nothing can beat it (and the nearest start keeps it)
                    }
                }
            }
            if (best != null && best[5] >= 1) {
                break;
            }
        }
        if (best == null) {
            // no point of the grid in the biome: the search's place, as it was
            best = new double[] {from.getX(), from.getZ(), 1, 0, SPEEDS[0], 0, 0};
        }
        Path flat =
                new Path(
                        part,
                        biome,
                        best[0],
                        best[1],
                        best[2],
                        best[3],
                        best[4],
                        new double[] {0},
                        0);
        return new Path(
                part,
                biome,
                best[0],
                best[1],
                best[2],
                best[3],
                best[4],
                heights(w, flat, ticks),
                best[5]);
    }

    /**
     * The points of the grid around {@code from} that are in the biome, nearest first (then by x,
     * then by z: the same order everywhere), at most {@link #MAX_STARTS}.
     */
    private static List<int[]> starts(WorldServer w, BlockPos from, int biome) {
        List<int[]> in = new ArrayList<>();
        for (int ox = -REACH; ox <= REACH; ox += STEP) {
            for (int oz = -REACH; oz <= REACH; oz += STEP) {
                int x = from.getX() + ox;
                int z = from.getZ() + oz;
                if (biomeAt(w, x + 0.5, z + 0.5) == biome) {
                    in.add(new int[] {x, z, ox * ox + oz * oz});
                }
            }
        }
        in.sort(
                Comparator.<int[]>comparingInt(p -> p[2])
                        .thenComparingInt(p -> p[0])
                        .thenComparingInt(p -> p[1]));
        return in.size() > MAX_STARTS ? in.subList(0, MAX_STARTS) : in;
    }

    /**
     * The share of the measured ticks the camera is over the biome, sampled about every block of
     * the path and at the first and last measured tick; with {@code stopAtMiss}, 0 at the first
     * miss.
     */
    private static double cameraShare(
            WorldServer w, Path p, int ticks, int measureFrom, boolean stopAtMiss) {
        double[] c = new double[5];
        int step = Math.max(1, (int) Math.round(1 / p.speed()));
        int hits = 0;
        int n = 0;
        for (int t = measureFrom; ; t = Math.min(ticks, t + step)) {
            p.camera(t, c);
            boolean in = biomeAt(w, c[0], c[2]) == p.biome();
            if (!in && stopAtMiss) {
                return 0;
            }
            hits += in ? 1 : 0;
            n++;
            if (t >= ticks) {
                break;
            }
        }
        return (double) hits / n;
    }

    /** The share of the measured ticks the ground the camera looks at is in the biome. */
    private static double lookShare(WorldServer w, Path p, int ticks, int measureFrom) {
        double[] c = new double[5];
        int hits = 0;
        int n = 0;
        for (int t = measureFrom; t <= ticks; t += 10) {
            p.camera(t, c);
            double x = c[0] + p.dirX() * LOOK;
            double z = c[2] + p.dirZ() * LOOK;
            hits += biomeAt(w, x, z) == p.biome() ? 1 : 0;
            n++;
        }
        return n == 0 ? 0 : (double) hits / n;
    }

    private static int biomeAt(WorldServer w, double x, double z) {
        return w.getBiomeGenForCoords(new BlockPos(x, 64, z)).biomeID;
    }

    /**
     * The camera's height at every tick: {@link #ABOVE} over the highest ground of the next 24
     * blocks, smoothed so it glides. The chunks the path crosses, and their neighbours, are loaded
     * first (generated and decorated, trees included), as the player's arrival would.
     */
    private static double[] heights(WorldServer w, Path p, int ticks) {
        double[] c = new double[5];
        Set<Long> chunks = new HashSet<>();
        for (int t = 0; t <= ticks; t += 4) {
            p.camera(t, c);
            for (int k = 0; k <= LOOK; k += 4) {
                int cx = (int) Math.floor((c[0] + p.dirX() * k) / 16);
                int cz = (int) Math.floor((c[2] + p.dirZ() * k) / 16);
                for (int i = -1; i <= 1; i++) {
                    for (int j = -1; j <= 1; j++) {
                        chunks.add(((long) (cx + i) << 32) ^ ((cz + j) & 0xffffffffL));
                    }
                }
            }
        }
        chunks.stream()
                .sorted()
                .forEach(k -> w.theChunkProviderServer.loadChunk((int) (k >> 32), (int) (long) k));
        double[] y = new double[ticks + 1];
        double follow = Double.NaN;
        for (int t = 0; t <= ticks; t++) {
            p.camera(t, c);
            int ground = 0;
            for (int k = 0; k <= 24; k += 4) {
                BlockPos at = new BlockPos(c[0] + p.dirX() * k, 0, c[2] + p.dirZ() * k);
                ground = Math.max(ground, w.getHeight(at).getY());
            }
            double target = ground + ABOVE;
            follow = Double.isNaN(follow) ? target : follow + (target - follow) * 0.08;
            y[t] = follow;
        }
        return y;
    }
}
