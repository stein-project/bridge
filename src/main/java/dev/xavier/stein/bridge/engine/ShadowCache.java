/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import dev.xavier.stein.bridge.core.Log;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.util.BlockPos;
import net.minecraft.world.World;

import java.util.Arrays;

/**
 * The world questions of entity shadows, answered once per block per entity pass.
 *
 * <p>Render.renderShadow walks the blocks under each entity and, for each one, asks the world for
 * the block below (getBlockState), its light (getLightFromNeighbors) and, in renderShadowBlock, its
 * brightness (getLightBrightness): each one a chunk lookup and a light read. Entities stand on the
 * same few blocks, so with a crowd almost every question was already asked in the same pass: with
 * 300 chickens in a 9 x 9 pit, those lookups were ~12% of the render thread.
 *
 * <p>Why it is exact: the answers only change when the world changes, and the world does not change
 * while RenderGlobal.renderEntities draws (blocks, light and chunks change in the tick and in the
 * network packets, both outside the frame's world pass). The table is valid only between the {@link
 * #begin} and {@link #end} the patcher puts around renderEntities in renderWorldPass, and each pass
 * starts empty (a generation number, so nothing is cleared). Outside it, on another thread, or in
 * another world, every question goes to the world as in the game.
 *
 * <p>{@code -Dsteinbridge.opt.vanillaShadows=true}: every question to the world. {@code
 * -Dsteinbridge.opt.checkShadows=true}: uses the table and compares every hit with the world.
 */
public final class ShadowCache {

    private static final boolean VANILLA = EngineOptions.VANILLA_SHADOWS;
    private static final boolean CHECK = !VANILLA && EngineOptions.CHECK_SHADOWS;

    /** Direct-mapped: a collision only replaces the older block, it never answers wrong. */
    private static final int BITS = 10;

    private static final int SLOTS = 1 << BITS;
    private static final int MASK = SLOTS - 1;

    private static final long[] stateKey = new long[SLOTS];
    private static final int[] stateGen = new int[SLOTS];
    private static final Object[] state = new Object[SLOTS];

    private static final long[] lightKey = new long[SLOTS];
    private static final int[] lightGen = new int[SLOTS];
    private static final int[] light = new int[SLOTS];

    private static final long[] brightKey = new long[SLOTS];
    private static final int[] brightGen = new int[SLOTS];
    private static final float[] bright = new float[SLOTS];

    /** The pass's generation; 0 outside a pass, so no slot (all start at 0) ever matches it. */
    private static int gen;

    private static int lastGen;
    private static World passWorld;
    private static Thread passThread;

    private static final CheckReport REPORT = new CheckReport(5_000_000_000L);

    static {
        if (VANILLA) {
            Log.info("shadows", "shadow blocks asked to the world every time (vanilla)");
        } else if (CHECK) {
            Log.info("shadows", "check: every cached shadow answer compared with the world");
        }
    }

    private ShadowCache() {}

    /** Before RenderGlobal.renderEntities in renderWorldPass. */
    public static void begin() {
        if (VANILLA) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        passWorld = mc.theWorld;
        passThread = Thread.currentThread();
        lastGen = lastGen == Integer.MAX_VALUE ? 1 : lastGen + 1;
        if (lastGen == 1) {
            Arrays.fill(stateGen, 0);
            Arrays.fill(lightGen, 0);
            Arrays.fill(brightGen, 0);
            Arrays.fill(state, null);
        }
        gen = lastGen;
    }

    /** After RenderGlobal.renderEntities: from here on, the world again. */
    public static void end() {
        gen = 0;
        passWorld = null;
    }

    private static boolean inPass(World world) {
        return gen != 0 && world == passWorld && Thread.currentThread() == passThread;
    }

    private static int slot(long key) {
        long h = key * 0x9E3779B97F4A7C15L;
        return (int) (h >>> (64 - BITS)) & MASK;
    }

    /** In place of World.getBlockState(pos.down()) in Render.renderShadow. */
    public static Object state(Object worldObj, Object posObj) {
        World world = (World) worldObj;
        BlockPos pos = (BlockPos) posObj;
        if (!inPass(world)) {
            return world.getBlockState(pos);
        }
        long key = pos.toLong();
        int i = slot(key);
        if (stateGen[i] == gen && stateKey[i] == key) {
            Object s = state[i];
            if (CHECK) {
                compare(s == world.getBlockState(pos), "state", pos);
            }
            return s;
        }
        IBlockState s = world.getBlockState(pos);
        stateKey[i] = key;
        stateGen[i] = gen;
        state[i] = s;
        return s;
    }

    /** In place of World.getLightFromNeighbors(pos) in Render.renderShadow. */
    public static int light(Object worldObj, Object posObj) {
        World world = (World) worldObj;
        BlockPos pos = (BlockPos) posObj;
        if (!inPass(world)) {
            return world.getLightFromNeighbors(pos);
        }
        long key = pos.toLong();
        int i = slot(key);
        if (lightGen[i] == gen && lightKey[i] == key) {
            int v = light[i];
            if (CHECK) {
                compare(v == world.getLightFromNeighbors(pos), "light", pos);
            }
            return v;
        }
        int v = world.getLightFromNeighbors(pos);
        lightKey[i] = key;
        lightGen[i] = gen;
        light[i] = v;
        return v;
    }

    /** In place of World.getLightBrightness(pos) in Render.renderShadowBlock. */
    public static float brightness(Object worldObj, Object posObj) {
        World world = (World) worldObj;
        BlockPos pos = (BlockPos) posObj;
        if (!inPass(world)) {
            return world.getLightBrightness(pos);
        }
        long key = pos.toLong();
        int i = slot(key);
        if (brightGen[i] == gen && brightKey[i] == key) {
            float v = bright[i];
            if (CHECK) {
                compare(
                        Float.floatToIntBits(v)
                                == Float.floatToIntBits(world.getLightBrightness(pos)),
                        "brightness",
                        pos);
            }
            return v;
        }
        float v = world.getLightBrightness(pos);
        brightKey[i] = key;
        brightGen[i] = gen;
        bright[i] = v;
        return v;
    }

    private static void compare(boolean same, String what, BlockPos pos) {
        if (REPORT.compared(same)) {
            Log.info("shadows", "DIVERGENCE: %s at %s", what, pos);
        }
        REPORT.maybeReport("shadows");
    }
}
