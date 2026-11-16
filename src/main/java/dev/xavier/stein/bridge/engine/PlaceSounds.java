/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.world.World;

/**
 * The block placing sound plays as the player places the block, as from 1.9 on, instead of a
 * network round trip later.
 *
 * <p>In 1.8.9 the client already places the block itself (ItemBlock.onItemUse runs on the client
 * world before the server answers), but the sound it plays there goes nowhere — the client world's
 * listener ignores it — and the player only hears the copy the server sends back
 * (S29PacketSoundEffect): with 100 ms of ping, the sound comes 100 ms after the block. Here the
 * client's call is played at once and remembered; the server's copy of that same sound, at that
 * same block, is then dropped once, so it does not play twice. Other players' sounds are never held
 * back (one echo per sound played here, and only within {@link #ECHO_NANOS}). As in later versions,
 * a placement the server refuses still made its sound. {@code
 * -Dsteinbridge.opt.vanillaPlaceSound=true} brings back 1.8.9's behaviour.
 */
public final class PlaceSounds {
    private PlaceSounds() {}

    private static final boolean VANILLA = EngineOptions.VANILLA_PLACE_SOUND;

    /** How long the server's copy is waited for (generous: the round trip of a bad connection). */
    private static final long ECHO_NANOS = 3_000_000_000L;

    /** The server sends the position in eighths of a block. */
    private static final double SAME_PLACE = 0.25;

    private static final int SLOTS = 32;
    private static final String[] NAMES = new String[SLOTS];
    private static final double[] X = new double[SLOTS];
    private static final double[] Y = new double[SLOTS];
    private static final double[] Z = new double[SLOTS];
    private static final long[] AT = new long[SLOTS];
    private static int next;

    /**
     * In place of world.playSoundEffect(...) in the placing items (ItemBlock, ItemSlab, ItemReed,
     * ItemSnow): on the client world, also played at once and remembered.
     */
    public static void placed(
            Object world, double x, double y, double z, Object sound, float volume, float pitch) {
        String name = (String) sound; // the rewritten call passes references as Object
        World w = (World) world;
        w.playSoundEffect(x, y, z, name, volume, pitch);
        if (VANILLA || !(w instanceof WorldClient client)) {
            return; // the server's world: its sound goes out to the players, as always
        }
        client.playSound(x, y, z, name, volume, pitch, false);
        int i = next;
        next = (next + 1) % SLOTS;
        NAMES[i] = name;
        X[i] = x;
        Y[i] = y;
        Z[i] = z;
        AT[i] = System.nanoTime();
    }

    /**
     * In place of world.playSound(...) in NetHandlerPlayClient.handleSoundEffect: a sound this
     * client already played (same name, same block, recent) is skipped once.
     */
    public static void received(
            Object world,
            double x,
            double y,
            double z,
            Object sound,
            float volume,
            float pitch,
            boolean distanceDelay) {
        String name = (String) sound;
        if (!VANILLA && echo(x, y, z, name)) {
            return;
        }
        ((WorldClient) world).playSound(x, y, z, name, volume, pitch, distanceDelay);
    }

    private static boolean echo(double x, double y, double z, String name) {
        long now = System.nanoTime();
        for (int i = 0; i < SLOTS; i++) {
            String n = NAMES[i];
            if (n != null
                    && now - AT[i] <= ECHO_NANOS
                    && n.equals(name)
                    && Math.abs(X[i] - x) <= SAME_PLACE
                    && Math.abs(Y[i] - y) <= SAME_PLACE
                    && Math.abs(Z[i] - z) <= SAME_PLACE) {
                NAMES[i] = null;
                return true;
            }
        }
        return false;
    }
}
