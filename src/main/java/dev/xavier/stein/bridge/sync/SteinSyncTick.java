/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.sync;

/**
 * One Stein Sync Tick (type 7, stein-sync's docs/protocol.md section 5.5): the state of one client
 * tick and the attacks handled in it, and its encoding. Filled field by field and written into a
 * reused buffer, so a tick costs no allocation here. Plain values, no game classes, so it is tested
 * without the game.
 */
final class SteinSyncTick {

    /** The most attacks one Tick carries (section 5.5). */
    static final int MAX_ATTACKS = 4;

    /**
     * The longest Tick: 42 bytes of state, 37 per attack, the life and view bytes and the applied
     * count (a VarLong, 10 bytes at most), rounded up.
     */
    static final int MAX_BYTES = 64 + MAX_ATTACKS * 40;

    static final int ON_GROUND = 1;
    static final int SNEAKING = 1 << 1;
    static final int SPRINTING = 1 << 2;
    static final int JUMPING = 1 << 3;
    static final int USING_ITEM = 1 << 4;

    /**
     * The player rides a vehicle: the proxy forwards the look only (its C05; C0C stays vanilla).
     */
    static final int RIDING = 1 << 5;

    /**
     * The camera is on another entity (spectating): the vanilla client sends its sneak and sprint
     * actions but no movement packet, so the proxy forwards only those.
     */
    static final int CAMERA_AWAY = 1 << 6;

    /**
     * The sprint key is held: not the sprint state (which the client also starts on a double tap of
     * forward, and ends for reasons of its own), for the proxy's shadow physics.
     */
    static final int SPRINT_KEY = 1 << 7;

    double x;
    double y;
    double z;
    float yaw;
    float pitch;
    int flags;

    /** The movement keys of the tick: -1, 0 or 1 (W/S and A/D). */
    int forward;

    int strafe;

    /**
     * Which player entity of this connection the Tick belongs to: stepped (modulo 256) each time
     * the game builds a new one (a Respawn), so the proxy tells a Tick the old entity sent before
     * the client handled a Respawn from the new entity's first (section 5.5, appended field).
     */
    int life;

    /**
     * The view the player draws from (the game's third-person setting): 0 first person, 1 behind, 2
     * in front. The proxy's entity visibility checks the third-person camera only while it is not
     * 0.
     */
    int view;

    /**
     * How many of the server's packets (from Join Game, 1) the game had handled before this tick;
     * -1 when not known, and the field is left out.
     */
    long applied = -1;

    private int attacks;
    private final int[] targets = new int[MAX_ATTACKS];
    private final float[] attackYaw = new float[MAX_ATTACKS];
    private final float[] attackPitch = new float[MAX_ATTACKS];
    private final double[] seen = new double[MAX_ATTACKS * 3];

    /** Attacks of this tick, at most {@link #MAX_ATTACKS}. */
    int attacks() {
        return attacks;
    }

    /**
     * Adds an attack of this tick.
     *
     * @param target the target's entity id
     * @param yaw the yaw of the pick ray the click was handled with
     * @param pitch its pitch
     * @param sx the target's position whose box that pick tested (feet)
     * @return false when the Tick already holds {@link #MAX_ATTACKS} (the attack is not added)
     */
    boolean addAttack(int target, float yaw, float pitch, double sx, double sy, double sz) {
        if (attacks == MAX_ATTACKS) {
            return false;
        }
        int i = attacks++;
        targets[i] = target;
        attackYaw[i] = yaw;
        attackPitch[i] = pitch;
        seen[i * 3] = sx;
        seen[i * 3 + 1] = sy;
        seen[i * 3 + 2] = sz;
        return true;
    }

    /** Forgets the attacks (after they were written, or on a new connection). */
    void clearAttacks() {
        attacks = 0;
    }

    /** The sign of a movement input as a key: -1, 0 or 1. */
    static int key(float input) {
        return input > 0.0F ? 1 : input < 0.0F ? -1 : 0;
    }

    /**
     * Writes this Tick numbered {@code tick} into {@code out}, at least {@link #MAX_BYTES} long.
     *
     * @return the length written
     */
    int write(int tick, byte[] out) {
        out[0] = (byte) SteinSyncCodec.TICK;
        int p = SteinSyncCodec.putVarInt(out, 1, tick);
        p = putDouble(out, p, x);
        p = putDouble(out, p, y);
        p = putDouble(out, p, z);
        p = putInt(out, p, Float.floatToIntBits(yaw));
        p = putInt(out, p, Float.floatToIntBits(pitch));
        out[p++] = (byte) flags;
        out[p++] = (byte) forward;
        out[p++] = (byte) strafe;
        p = SteinSyncCodec.putVarInt(out, p, attacks);
        for (int i = 0; i < attacks; i++) {
            p = SteinSyncCodec.putVarInt(out, p, targets[i]);
            p = putInt(out, p, Float.floatToIntBits(attackYaw[i]));
            p = putInt(out, p, Float.floatToIntBits(attackPitch[i]));
            p = putDouble(out, p, seen[i * 3]);
            p = putDouble(out, p, seen[i * 3 + 1]);
            p = putDouble(out, p, seen[i * 3 + 2]);
        }
        out[p++] = (byte) life;
        out[p++] = (byte) view;
        if (applied >= 0) {
            p = putVarLong(out, p, applied);
        }
        return p;
    }

    private static int putVarLong(byte[] out, int p, long v) {
        while ((v & ~0x7FL) != 0) {
            out[p++] = (byte) ((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out[p++] = (byte) v;
        return p;
    }

    /** The longest Attack message: type, tick and one attack, rounded up. */
    static final int MAX_ATTACK_BYTES = 48;

    /**
     * Writes an Attack (type 10, section 5.1) into {@code out}, at least {@link #MAX_ATTACK_BYTES}
     * long: one attack of the Tick numbered {@code tick} (the next one the client sends), sent at
     * its place among the client's packets.
     *
     * @return the length written
     */
    static int writeAttack(
            int tick,
            int target,
            float yaw,
            float pitch,
            double sx,
            double sy,
            double sz,
            byte[] out) {
        out[0] = (byte) SteinSyncCodec.ATTACK;
        int p = SteinSyncCodec.putVarInt(out, 1, tick);
        p = SteinSyncCodec.putVarInt(out, p, target);
        p = putInt(out, p, Float.floatToIntBits(yaw));
        p = putInt(out, p, Float.floatToIntBits(pitch));
        p = putDouble(out, p, sx);
        p = putDouble(out, p, sy);
        return putDouble(out, p, sz);
    }

    private static int putDouble(byte[] out, int p, double v) {
        long bits = Double.doubleToLongBits(v);
        p = putInt(out, p, (int) (bits >>> 32));
        return putInt(out, p, (int) bits);
    }

    private static int putInt(byte[] out, int p, int v) {
        out[p] = (byte) (v >>> 24);
        out[p + 1] = (byte) (v >>> 16);
        out[p + 2] = (byte) (v >>> 8);
        out[p + 3] = (byte) v;
        return p + 4;
    }
}
