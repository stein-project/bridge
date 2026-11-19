/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

/**
 * A Stein Sync Correction as the client applies it: the state the proxy's authoritative movement
 * computed for the end of the client's Tick {@link #tick}. The Loader's codec fills it from the
 * message; {@link #fields} says which parts the message carried, and the client's own recorded
 * state of that tick stands in for the others. Reused for every message.
 */
public final class SyncCorrection {

    /** {@link #state}'s feet position (the box is placed around it). */
    public static final int POSITION = 1;

    public static final int MOTION = 1 << 1;

    /** onGround, collidedHorizontally, collidedVertically. */
    public static final int GROUND = 1 << 2;

    public static final int FALL_DISTANCE = 1 << 3;
    public static final int JUMP_TICKS = 1 << 4;

    /** The sprint state and the client's sprint timeout. */
    public static final int SPRINT = 1 << 5;

    /** inWater and inWeb. */
    public static final int MEDIUM = 1 << 6;

    /** The double-tap-forward window of the sprint (EntityPlayerSP's sprintToggleTimer). */
    public static final int SPRINT_WINDOW = 1 << 7;

    /** The air control of the next tick (jumpMovementFactor). */
    public static final int AIR_CONTROL = 1 << 8;

    /** Every part a Correction message carries. */
    public static final int ALL = (1 << 9) - 1;

    /** The Tick number the state is the end of. */
    public int tick;

    /** Which parts of {@link #state} the message carried (the constants above). */
    public int fields;

    public final SyncState state = new SyncState();

    /**
     * Overwrites {@code into} with the parts this correction carries.
     *
     * @return whether it changed the sprint state (the next tick's ground speed then has to be
     *     taken from the movement speed with the new sprint modifier)
     */
    public boolean applyTo(SyncState into) {
        SyncState s = state;
        // The position is set as the game sets one, the box rebuilt around it, as the proxy does
        // to its own state (stein-sync docs/protocol.md 5.10).
        if ((fields & POSITION) != 0) {
            into.placeAt(s.x, s.y, s.z);
        }
        if ((fields & MOTION) != 0) {
            into.motionX = s.motionX;
            into.motionY = s.motionY;
            into.motionZ = s.motionZ;
        }
        if ((fields & GROUND) != 0) {
            into.onGround = s.onGround;
            into.collidedHorizontally = s.collidedHorizontally;
            into.collidedVertically = s.collidedVertically;
        }
        if ((fields & FALL_DISTANCE) != 0) {
            into.fallDistance = s.fallDistance;
        }
        if ((fields & JUMP_TICKS) != 0) {
            into.jumpTicks = s.jumpTicks;
        }
        boolean flipped = false;
        if ((fields & SPRINT) != 0) {
            flipped = into.sprinting != s.sprinting;
            into.sprinting = s.sprinting;
            into.sprintingTicksLeft = s.sprintingTicksLeft;
        }
        if ((fields & SPRINT_WINDOW) != 0) {
            into.sprintToggleTimer = s.sprintToggleTimer;
        }
        if ((fields & AIR_CONTROL) != 0) {
            into.jumpMovementFactor = s.jumpMovementFactor;
        }
        if ((fields & MEDIUM) != 0) {
            into.inWater = s.inWater;
            into.inWeb = s.inWeb;
        }
        return flipped;
    }
}
