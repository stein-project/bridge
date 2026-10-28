/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.bench;

import dev.xavier.stein.bridge.core.Log;
import dev.xavier.stein.bridge.engine.TaskPost;

import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.biome.BiomeGenBase;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Every overworld biome the seed has near the spawn, for the route's biome tour: each one asked of
 * the world's own biome generator on the integrated server's thread (it is not thread-safe), with a
 * fixed random, so the same seed gives the same places on every machine. The places are put in the
 * order of a short trip (always the nearest not yet visited). A biome not found within the radius
 * is listed as missing in the report.
 */
final class BenchTerrain {

    private BenchTerrain() {}

    /** The radius searched around the spawn, in blocks. */
    private static final int RADIUS = 2000;

    /** Part name ("biome_jungle") to the place, in the order of the trip; set once, when done. */
    static volatile Map<String, BlockPos> BIOMES = Map.of();

    /** The biomes not found within the radius. */
    static volatile List<String> MISSING = List.of();

    static volatile boolean searched;

    /** Starts the search on the server thread; {@link #searched} turns true when it is done. */
    static void search(Minecraft mc, int sx, int sz) {
        MinecraftServer server = mc.getIntegratedServer();
        if (server == null) {
            searched = true;
            return;
        }
        TaskPost.toServer(
                server,
                (Runnable)
                        () -> {
                            try {
                                find(server.worldServerForDimension(0), sx, sz);
                            } catch (RuntimeException e) {
                                Log.warn("bench", "biome search: " + e);
                            } finally {
                                searched = true;
                            }
                        });
    }

    private static void find(WorldServer w, int sx, int sz) {
        long t0 = System.nanoTime();
        Map<String, BlockPos> found = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (BiomeGenBase b : BiomeGenBase.getBiomeGenArray()) {
            if (b == null || b == BiomeGenBase.hell || b == BiomeGenBase.sky) {
                continue;
            }
            String name = "biome_" + slug(b.biomeName);
            if (found.containsKey(name)) {
                continue;
            }
            BlockPos p =
                    w.getWorldChunkManager()
                            .findBiomePosition(sx, sz, RADIUS, List.of(b), new Random(b.biomeID));
            if (p != null) {
                found.put(name, p);
            } else {
                missing.add(b.biomeName);
            }
        }
        BIOMES = trip(found, sx, sz);
        MISSING = missing;
        Bench.event(
                String.format(
                        Locale.ROOT,
                        "biomes: %d found, %d missing within %d blocks (%.1f s)",
                        found.size(),
                        missing.size(),
                        RADIUS,
                        (System.nanoTime() - t0) / 1e9));
    }

    /** The places in the order of a short trip from the spawn (nearest neighbour first). */
    private static Map<String, BlockPos> trip(Map<String, BlockPos> places, int sx, int sz) {
        Map<String, BlockPos> left = new LinkedHashMap<>(places);
        Map<String, BlockPos> order = new LinkedHashMap<>();
        double x = sx;
        double z = sz;
        while (!left.isEmpty()) {
            String best = null;
            double bestD = Double.MAX_VALUE;
            for (Map.Entry<String, BlockPos> e : left.entrySet()) {
                double dx = e.getValue().getX() - x;
                double dz = e.getValue().getZ() - z;
                double d = dx * dx + dz * dz;
                if (d < bestD) {
                    bestD = d;
                    best = e.getKey();
                }
            }
            BlockPos p = left.remove(best);
            order.put(best, p);
            x = p.getX();
            z = p.getZ();
        }
        return order;
    }

    private static String slug(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
    }
}
