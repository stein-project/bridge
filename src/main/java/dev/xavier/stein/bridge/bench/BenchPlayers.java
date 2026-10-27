/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.bench;

import com.mojang.authlib.GameProfile;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumParticleTypes;

import java.util.UUID;

/**
 * The benchmark's crowd: 400 players fighting in 200 pairs. They are other players as the client
 * sees them on a server (the same class, model, armor, held item, name tag and pose as real ones),
 * but created only in this client's world, with ids no server uses, so nothing is sent and nothing
 * on the server changes.
 *
 * <p>Each pair circles around its middle facing each other, closes in and backs off, jumps for a
 * critical, and hits in turn: the swing, the hurt flash and a step back on the one hit, and crit
 * particles now and then. All of it follows the tick, so every machine draws the same fight. Skins
 * are the default ones (no download), chosen by the fixed UUID.
 */
final class BenchPlayers {

    private BenchPlayers() {}

    static final int COUNT = 400;
    private static final int PAIRS = COUNT / 2;
    private static final int COLUMNS = 20;
    private static final double SPACING = 5.0;
    private static final int FIRST_ID = -2_000_000;

    private static EntityOtherPlayerMP[] players;
    private static WorldClient world;
    private static double cx;
    private static double cy;
    private static double cz;

    /** Ticks since each player was last hit (for the step back). */
    private static final int[] SINCE_HIT = new int[COUNT];

    static boolean present() {
        return players != null;
    }

    /** Puts the fight around ({@code x}, {@code y}, {@code z}). */
    static void spawn(Minecraft mc, double x, double y, double z) {
        if (players != null && world == mc.theWorld) {
            return;
        }
        cx = x;
        cy = y;
        cz = z;
        world = mc.theWorld;
        players = new EntityOtherPlayerMP[COUNT];
        for (int i = 0; i < COUNT; i++) {
            GameProfile profile =
                    new GameProfile(new UUID(0x5731_4E00_0000_0000L, i), "Bench_" + i);
            EntityOtherPlayerMP p = new EntityOtherPlayerMP(world, profile);
            dress(p, i);
            place(i, 0);
            p.setLocationAndAngles(AT[0], AT[1], AT[2], (float) AT[3], 0F);
            p.prevRotationYawHead = p.rotationYawHead = (float) AT[3];
            world.addEntityToWorld(FIRST_ID - i, p);
            players[i] = p;
            SINCE_HIT[i] = 100;
        }
        Bench.event("players: " + COUNT + " fighting in " + PAIRS + " pairs");
    }

    /** Takes the crowd out of the world (also when the world changed under it). */
    static void remove(Minecraft mc) {
        if (players == null) {
            return;
        }
        if (mc.theWorld != null && mc.theWorld == world) {
            for (int i = 0; i < COUNT; i++) {
                mc.theWorld.removeEntityFromWorld(FIRST_ID - i);
            }
        }
        players = null;
        world = null;
    }

    /** Every client tick: the next step of the fight. */
    static void tick(Minecraft mc, int t) {
        if (players == null) {
            return;
        }
        if (mc.theWorld != world) {
            players = null; // another dimension: the old world took them with it
            world = null;
            return;
        }
        for (int i = 0; i < COUNT; i++) {
            SINCE_HIT[i]++;
        }
        for (int pair = 0; pair < PAIRS; pair++) {
            int a = 2 * pair;
            int b = a + 1;
            // they hit in turn, each pair at its own rhythm
            int period = 8 + pair % 5;
            int phase = (t + pair * 3) % period;
            if (phase == 0) {
                hit(mc, a, b, pair, t);
            } else if (phase == period / 2) {
                hit(mc, b, a, pair, t);
            }
        }
        for (int i = 0; i < COUNT; i++) {
            EntityOtherPlayerMP p = players[i];
            place(i, t);
            // three ticks of interpolation, like a server's movement packet
            p.setPositionAndRotation2(AT[0], AT[1], AT[2], (float) AT[3], (float) AT[4], 3, false);
            p.rotationYawHead = (float) AT[3];
            p.setSprinting(i % 3 != 0);
            p.setSneaking(i % 17 == 5 && (t / 30) % 3 == 0);
        }
    }

    /** {@code attacker} swings at {@code target}: the hurt flash and, now and then, crits. */
    private static void hit(Minecraft mc, int attacker, int target, int pair, int t) {
        players[attacker].swingItem();
        if ((t + attacker) % 3 != 0) {
            return; // a miss: only the swing
        }
        EntityOtherPlayerMP v = players[target];
        v.performHurtAnimation();
        SINCE_HIT[target] = 0;
        if ((pair + t) % 4 == 0 && mc.effectRenderer != null) {
            mc.effectRenderer.emitParticleAtEntity(v, EnumParticleTypes.CRIT);
        }
    }

    private static final double[] AT = new double[5];

    /**
     * Where player {@code i} is at tick {@code t}: x, y, z, yaw facing the opponent, pitch. The
     * pair circles its middle; the distance breathes, and the one hit last steps back.
     */
    private static void place(int i, int t) {
        int pair = i / 2;
        int side = i % 2;
        double mx = cx + (pair % COLUMNS - (COLUMNS - 1) / 2.0) * SPACING;
        double mz = cz + (pair / COLUMNS - (PAIRS / COLUMNS - 1) / 2.0) * SPACING;
        double dir = pair % 2 == 0 ? 1 : -1;
        double angle = pair * 0.7 + dir * t * (0.05 + 0.01 * (pair % 4)) + side * Math.PI;
        double knock = Math.max(0, 1 - SINCE_HIT[i] / 6.0) * 0.8;
        double d = 1.5 + 0.45 * Math.sin(t / 9.0 + pair) + knock;
        double x = mx + d * Math.cos(angle);
        double z = mz + d * Math.sin(angle);
        // jumping for a critical: a parabola of 8 ticks, every so often
        int j = (t + pair * 5 + side * 3) % 40;
        double y = cy + (j < 8 ? 0.42 * j - 0.0525 * j * j : 0);
        // facing the middle, so the two face each other
        AT[0] = x;
        AT[1] = y;
        AT[2] = z;
        AT[3] = Math.toDegrees(Math.atan2(-(mx - x), mz - z));
        AT[4] = 5;
    }

    private static final Item[][] ARMOR = {
        {
            Items.diamond_boots,
            Items.diamond_leggings,
            Items.diamond_chestplate,
            Items.diamond_helmet
        },
        {Items.iron_boots, Items.iron_leggings, Items.iron_chestplate, Items.iron_helmet},
        {
            Items.chainmail_boots,
            Items.chainmail_leggings,
            Items.chainmail_chestplate,
            Items.chainmail_helmet
        },
        {Items.golden_boots, Items.golden_leggings, Items.golden_chestplate, Items.golden_helmet},
        {
            Items.leather_boots,
            Items.leather_leggings,
            Items.leather_chestplate,
            Items.leather_helmet
        }
    };

    private static final Item[] HELD = {
        Items.diamond_sword,
        Items.iron_sword,
        Items.diamond_sword,
        Items.bow,
        Items.golden_apple,
        Items.fishing_rod,
        Items.diamond_sword,
        Items.stone_sword
    };

    /** A different kit per player, about half of it enchanted (the glint is drawn). */
    private static void dress(EntityOtherPlayerMP p, int i) {
        ItemStack held = new ItemStack(HELD[i % HELD.length]);
        if (i % 2 == 0 && held.getItem() == Items.diamond_sword) {
            held.addEnchantment(Enchantment.sharpness, 5);
        }
        p.setCurrentItemOrArmor(0, held);
        Item[] kit = ARMOR[(i / 3) % ARMOR.length];
        for (int s = 0; s < 4; s++) {
            if (i % 7 == 3 && s == 3) {
                continue; // some without a helmet: the skin's head shows
            }
            ItemStack piece = new ItemStack(kit[s]);
            if (i % 2 == 1) {
                piece.addEnchantment(Enchantment.protection, 4);
            }
            p.setCurrentItemOrArmor(s + 1, piece);
        }
    }
}
