/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.item.EntityTNTPrimed;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;

import org.lwjgl.system.MemoryUtil;

import java.util.List;

/**
 * Dynamic lights (the Lens option): what carries light lights what is around it as if the block
 * were there — a torch or a glowstone in anyone's hand, a light block dropped on the ground, a
 * burning entity, primed TNT. Only the picture: the world's light, the game and the server are
 * untouched, and no chunk is rebuilt.
 *
 * <p>Once per world drawing, at the terrain's first layer, the 16 nearest sources are gathered from
 * the loaded entities (no allocation) and put in eye space with the camera's matrix; the terrain
 * and the entities ({@code common/dynamic_light.glsl}) take, per pixel, the brightest of their own
 * block light and each source's level minus its distance, like the game's block light. Outside the
 * world's drawing (the HUD, the inventory) there are none ({@link #worldEnd}).
 *
 * <p>Pipeline: Stein GFX only.
 */
public final class SteinDynamicLights {

    /** Sources at most (the shaders' array). */
    public static final int MAX = 16;

    /** Bytes of the block the frames carry: the count, then the sources. */
    public static final int BYTES = 16 + MAX * 16;

    private static volatile boolean on;

    /** Sources gathered for this world drawing, in eye space: x, y, z, level. */
    private static final float[] EYE = new float[MAX * 4];

    /** World positions and squared distances of the nearest found, while gathering. */
    private static final double[] POS = new double[MAX * 3];

    private static final double[] DIST = new double[MAX];
    private static final int[] LEVEL = new int[MAX];

    private static int count;

    /** Between the terrain's first layer and the end of the world's drawing. */
    private static boolean active;

    /** Sources beyond this many blocks are left out (a level-15 light reaches 15). */
    private static final double REACH = 24.0;

    private SteinDynamicLights() {}

    /** The Lens option (any thread). */
    public static void configure(boolean enabled) {
        on = enabled;
    }

    /** Whether the Lens option is on. */
    static boolean enabled() {
        return on;
    }

    /**
     * At each terrain layer's Frame block, with the camera's matrix {@code m} (column major): the
     * first time in a world drawing, gathers the sources.
     */
    static void update(float[] m) {
        if (active) {
            return;
        }
        active = true;
        count = 0;
        if (!on || !SteinGfxOptions.ENABLED) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        WorldClient world = mc.theWorld;
        Entity view = mc.getRenderViewEntity();
        if (world == null || view == null) {
            return;
        }
        float pt = mc.timer.renderPartialTicks;
        double cx = view.lastTickPosX + (view.posX - view.lastTickPosX) * pt;
        double cy = view.lastTickPosY + (view.posY - view.lastTickPosY) * pt + view.getEyeHeight();
        double cz = view.lastTickPosZ + (view.posZ - view.lastTickPosZ) * pt;
        List<Entity> entities = world.loadedEntityList;
        for (int i = 0, n = entities.size(); i < n; i++) {
            Entity e = entities.get(i);
            int level = level(e);
            if (level <= 0) {
                continue;
            }
            double x = e.lastTickPosX + (e.posX - e.lastTickPosX) * pt;
            double y = e.lastTickPosY + (e.posY - e.lastTickPosY) * pt + e.height * 0.6;
            double z = e.lastTickPosZ + (e.posZ - e.lastTickPosZ) * pt;
            double d = (x - cx) * (x - cx) + (y - cy) * (y - cy) + (z - cz) * (z - cz);
            if (d > REACH * REACH) {
                continue;
            }
            keep(x, y, z, d, level);
        }
        // eye space: m * (position - camera); the camera's eye height is in m (it is the view)
        double ex = cx;
        double ey = cy - view.getEyeHeight();
        double ez = cz;
        for (int i = 0; i < count; i++) {
            float x = (float) (POS[i * 3] - ex);
            float y = (float) (POS[i * 3 + 1] - ey);
            float z = (float) (POS[i * 3 + 2] - ez);
            EYE[i * 4] = m[0] * x + m[4] * y + m[8] * z + m[12];
            EYE[i * 4 + 1] = m[1] * x + m[5] * y + m[9] * z + m[13];
            EYE[i * 4 + 2] = m[2] * x + m[6] * y + m[10] * z + m[14];
            EYE[i * 4 + 3] = LEVEL[i];
        }
    }

    /** Keeps the source among the MAX nearest (insertion by distance). */
    private static void keep(double x, double y, double z, double d, int level) {
        int at = count;
        if (count == MAX) {
            if (d >= DIST[MAX - 1]) {
                return;
            }
            at = MAX - 1;
        } else {
            count++;
        }
        while (at > 0 && DIST[at - 1] > d) {
            DIST[at] = DIST[at - 1];
            LEVEL[at] = LEVEL[at - 1];
            System.arraycopy(POS, (at - 1) * 3, POS, at * 3, 3);
            at--;
        }
        DIST[at] = d;
        LEVEL[at] = level;
        POS[at * 3] = x;
        POS[at * 3 + 1] = y;
        POS[at * 3 + 2] = z;
    }

    /** The light an entity carries: burning, primed TNT, a light item held or lying there. */
    private static int level(Entity e) {
        int level = e.isBurning() || e instanceof EntityTNTPrimed ? 15 : 0;
        if (e instanceof EntityItem item) {
            level = Math.max(level, itemLevel(item.getEntityItem()));
        } else if (e instanceof EntityLivingBase living) {
            level = Math.max(level, itemLevel(living.getHeldItem()));
        }
        return level;
    }

    private static int itemLevel(ItemStack stack) {
        if (stack == null) {
            return 0;
        }
        Item item = stack.getItem();
        if (item instanceof ItemBlock block) {
            Block b = block.getBlock();
            return b == null ? 0 : b.getLightValue();
        }
        if (item == Items.lava_bucket) {
            return 15;
        }
        if (item == Items.blaze_rod) {
            return 10;
        }
        if (item == Items.glowstone_dust || item == Items.blaze_powder) {
            return 8;
        }
        return 0;
    }

    /** After the world's drawing (the hand included): the HUD and the screens have none. */
    public static void worldEnd() {
        active = false;
        count = 0;
    }

    /** Writes the block at {@code p}: the count and the sources (eye space). */
    static void write(long p) {
        int n = active ? count : 0;
        MemoryUtil.memPutFloat(p, n);
        for (int i = 0; i < n * 4; i++) {
            MemoryUtil.memPutFloat(p + 16 + i * 4L, EYE[i]);
        }
    }
}
