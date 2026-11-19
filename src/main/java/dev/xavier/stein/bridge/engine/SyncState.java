/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

/**
 * The local player's movement state after one tick: everything the game's movement code reads at
 * the start of the next tick, so that restoring it and running the next tick's inputs again gives
 * the same result as the first time ({@link SyncReplay}). Plain values, no game classes, reused:
 * the ring keeps one per slot, so recording a tick allocates nothing.
 *
 * <p>The box is kept besides the position: the game moves the box and derives the position from it,
 * and the two do not always round-trip through each other exactly.
 */
public final class SyncState {

    /** Feet position (the entity's posX, posY, posZ). */
    public double x;

    public double y;
    public double z;

    /** The collision box. */
    public double minX;

    public double minY;
    public double minZ;
    public double maxX;
    public double maxY;
    public double maxZ;

    /** Velocity, blocks per tick. */
    public double motionX;

    public double motionY;
    public double motionZ;

    public boolean onGround;
    public boolean collidedHorizontally;
    public boolean collidedVertically;

    /** Inside water after the tick (the next tick's friction and jump read it). */
    public boolean inWater;

    /** A cobweb touched during the tick: the next move is slowed, then the flag clears. */
    public boolean inWeb;

    public boolean sprinting;

    /** Set by a jump; the server-side movement checks read it. */
    public boolean airBorne;

    public float fallDistance;

    /** Ticks before holding jump jumps again. */
    public int jumpTicks;

    /** The client's own sprint timeout (EntityPlayerSP). */
    public int sprintingTicksLeft;

    /** The double-tap-forward window (EntityPlayerSP). */
    public int sprintToggleTimer;

    /** Air control of the next tick, from the sprint state at the end of this one. */
    public float jumpMovementFactor;

    /** Ground speed of the next tick, from the movement speed attribute at the end of this one. */
    public float aiMoveSpeed;

    /** Distance toward the next step sound (sound cadence only, kept so it matches). */
    public float distanceWalkedOnStep;

    public int nextStepDistance;

    /** Copies every field of {@code o}. */
    public void copyFrom(SyncState o) {
        x = o.x;
        y = o.y;
        z = o.z;
        minX = o.minX;
        minY = o.minY;
        minZ = o.minZ;
        maxX = o.maxX;
        maxY = o.maxY;
        maxZ = o.maxZ;
        motionX = o.motionX;
        motionY = o.motionY;
        motionZ = o.motionZ;
        onGround = o.onGround;
        collidedHorizontally = o.collidedHorizontally;
        collidedVertically = o.collidedVertically;
        inWater = o.inWater;
        inWeb = o.inWeb;
        sprinting = o.sprinting;
        airBorne = o.airBorne;
        fallDistance = o.fallDistance;
        jumpTicks = o.jumpTicks;
        sprintingTicksLeft = o.sprintingTicksLeft;
        sprintToggleTimer = o.sprintToggleTimer;
        jumpMovementFactor = o.jumpMovementFactor;
        aiMoveSpeed = o.aiMoveSpeed;
        distanceWalkedOnStep = o.distanceWalkedOnStep;
        nextStepDistance = o.nextStepDistance;
    }

    /**
     * Places the box around a feet position the way the game's setPosition does for a player (0.6
     * wide, 1.8 high): a correction carries the position, not the box.
     */
    public void placeAt(double px, double py, double pz) {
        x = px;
        y = py;
        z = pz;
        minX = px - HALF_WIDTH;
        minY = py;
        minZ = pz - HALF_WIDTH;
        maxX = px + HALF_WIDTH;
        maxY = py + HEIGHT;
        maxZ = pz + HALF_WIDTH;
    }

    /** The player's width over two and height, as the game's setPosition uses them (floats). */
    static final double HALF_WIDTH = 0.6F / 2.0F;

    static final double HEIGHT = 1.8F;

    /** Whether the feet position equals {@code o}'s exactly. */
    public boolean samePosition(SyncState o) {
        return x == o.x && y == o.y && z == o.z;
    }

    /** The largest per-axis distance between the two feet positions. */
    public double distance(SyncState o) {
        return Math.max(Math.abs(x - o.x), Math.max(Math.abs(y - o.y), Math.abs(z - o.z)));
    }
}
