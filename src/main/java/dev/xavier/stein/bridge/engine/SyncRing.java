/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

/**
 * The last {@link #CAPACITY} ticks of the local player under Stein Sync authority, by Tick number:
 * for each one the inputs it ran with, what changed the player between the previous tick and this
 * one (the server's teleports, velocity and explosions, the client's own hits), and the state it
 * ended in. A correction for tick N restores N and runs N+1 onwards again from these ({@link
 * SyncReplayDriver}).
 *
 * <p>The entries are allocated once and reused: recording a tick only writes fields. Game thread
 * only; no game classes, so it is tested without the game.
 */
public final class SyncRing {

    /**
     * Ticks kept: 6.4 seconds, far more than a correction's round trip; a correction older than
     * that is refused. A power of two.
     */
    public static final int CAPACITY = 128;

    private static final int MASK = CAPACITY - 1;

    /** One recorded tick. */
    public static final class Entry {

        /** The Tick number, 0 while the slot holds nothing. */
        int tick;

        // ---- inputs ----

        /**
         * The movement input as the game's MovementInput produced it this tick (W/S and A/D, sneak
         * already applied), before the item-use slowdown, which the replay applies again itself.
         */
        public float forward;

        public float strafe;
        public boolean jump;
        public boolean sneak;
        public boolean sprintKey;

        /** An item was in use during the tick (sword block, bow, food). */
        public boolean usingItem;

        public float yaw;
        public float pitch;

        /**
         * The tick ran with the game's ordinary walking code: not riding, not flying, not a
         * spectator, the camera on the player. A replay never runs through a tick that is not.
         */
        public boolean replayable;

        // ---- what changed the player between the previous tick and this one ----

        /** A teleport: the position (and box) was set to {@link #prePosition}. */
        public boolean positionSet;

        public final SyncState prePosition = new SyncState();

        /** The server set the velocity (Entity Velocity) to {@link #velocityX} etc. */
        public boolean velocitySet;

        public double velocityX;
        public double velocityY;
        public double velocityZ;

        /** Hits on players this tick: each one slows the attacker down and stops its sprint. */
        public int hits;

        /** Velocity added by something else (an explosion), after the above. */
        public double addX;

        public double addY;
        public double addZ;

        /** The sprint state was set from outside (the server's metadata): -1 no, 0 off, 1 on. */
        public int sprintSet = -1;

        // ---- the state after the tick ----

        public final SyncState post = new SyncState();

        /** Clears the between-ticks changes (the inputs and the state are always written). */
        public void clearChanges() {
            positionSet = false;
            velocitySet = false;
            hits = 0;
            addX = 0;
            addY = 0;
            addZ = 0;
            sprintSet = -1;
        }

        /** Copies the between-ticks changes of {@code o}. */
        public void copyChanges(Entry o) {
            positionSet = o.positionSet;
            prePosition.copyFrom(o.prePosition);
            velocitySet = o.velocitySet;
            velocityX = o.velocityX;
            velocityY = o.velocityY;
            velocityZ = o.velocityZ;
            hits = o.hits;
            addX = o.addX;
            addY = o.addY;
            addZ = o.addZ;
            sprintSet = o.sprintSet;
        }

        /** Whether anything changed the player between the previous tick and this one. */
        public boolean anyChange() {
            return positionSet
                    || velocitySet
                    || hits != 0
                    || addX != 0
                    || addY != 0
                    || addZ != 0
                    || sprintSet >= 0;
        }

        /** The Tick number of this entry. */
        public int tick() {
            return tick;
        }
    }

    private final Entry[] entries = new Entry[CAPACITY];

    /** The newest Tick number recorded, 0 when empty. */
    private int last;

    public SyncRing() {
        for (int i = 0; i < CAPACITY; i++) {
            entries[i] = new Entry();
        }
    }

    /**
     * The slot for Tick {@code tick}, to be filled now: the newest. Ticks are numbered without gaps
     * from 1; a number that is not the next one (a gap, or a smaller one: a new numbering) drops
     * everything recorded before, so a replay never runs over a hole.
     */
    public Entry begin(int tick) {
        if (tick <= 0) {
            throw new IllegalArgumentException("tick " + tick);
        }
        if (last != 0 && tick != last + 1) {
            clear();
        }
        Entry e = entries[tick & MASK];
        e.tick = tick;
        e.clearChanges();
        last = tick;
        return e;
    }

    /** The entry of Tick {@code tick}, or null when it is not (or no longer) recorded. */
    public Entry get(int tick) {
        if (tick <= 0 || tick > last || last - tick >= CAPACITY) {
            return null;
        }
        Entry e = entries[tick & MASK];
        return e.tick == tick ? e : null;
    }

    /** The newest Tick number recorded, 0 when empty. */
    public int last() {
        return last;
    }

    /** The newest entry, or null when empty. */
    public Entry newest() {
        return last == 0 ? null : entries[last & MASK];
    }

    /** Forgets everything (a new connection, a new player entity, authority off). */
    public void clear() {
        for (Entry e : entries) {
            e.tick = 0;
        }
        last = 0;
    }
}
