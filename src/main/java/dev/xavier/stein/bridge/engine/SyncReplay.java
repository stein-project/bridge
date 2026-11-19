/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.util.MovementInput;
import net.minecraft.world.World;

/**
 * Client reconciliation under a Stein Sync proxy's authoritative movement (stein-sync's
 * docs/protocol.md, milestone 7): the client keeps, per Tick number, the inputs each tick ran with,
 * what changed the player between ticks and the state it ended in ({@link SyncRing}); when the
 * proxy corrects tick N, the state of N is restored with the correction and the ticks after it run
 * again with the game's own movement code ({@link SyncReplayPlayer}), with their side effects held
 * back. The client is the game: running its own movement update again is not a copy of anything.
 *
 * <p>The Stein Loader turns it on ({@link #setActive}) only on a QUIC connection whose proxy
 * negotiated authoritative movement, records each Tick it sends ({@link #commit}) and hands it each
 * Correction ({@link #correct}). Off, every hook below does exactly what the game does and records
 * nothing: on any other connection nothing changes. The hooks are the patcher rules of
 * redirects-mods.tsv ("Stein Sync reconciliation").
 *
 * <p>Game thread only. Nothing allocates per tick; a replay allocates a box per tick it runs.
 */
public final class SyncReplay {

    /** The hit slowdown of EntityPlayer.attackTargetEntityWithCurrentItem, on X and Z. */
    static final double HIT_SLOWDOWN = 0.6;

    /** A correction that moves the player at least this far is smoothed (blocks). */
    static final double SMOOTH_MIN = 0.03;

    private static boolean active;

    /** Smooth the picture over the rest of the tick instead of snapping (the Loader's option). */
    private static boolean smooth = true;

    private static final SyncRing RING = new SyncRing();
    private static final SyncReplayPlayer PLAYER = new SyncReplayPlayer();

    /** The player entity the ring is for (identity only). */
    private static Object owner;

    /** The entry a replay is running, whose inputs the hooks hand the game; null otherwise. */
    private static SyncRing.Entry stepping;

    // The current tick's movement input, as the game's MovementInput produced it.
    private static boolean inputSeen;
    private static float inputForward;
    private static float inputStrafe;
    private static boolean inputJump;
    private static boolean inputSneak;

    // What changed the player since the last recorded tick: the server's velocity and the hits.
    private static boolean velocitySet;
    private static double velocityX;
    private static double velocityY;
    private static double velocityZ;
    private static int hits;

    /** The between-ticks changes of the tick about to run, worked out at its start. */
    private static final SyncRing.Entry PENDING = new SyncRing.Entry();

    private static boolean pendingSet;

    /** The changes since the last tick when a correction comes, put back after its replay. */
    private static final SyncRing.Entry NOW = new SyncRing.Entry();

    private static final SyncState BEFORE = new SyncState();
    private static final SyncState AFTER = new SyncState();

    // Counters, for the Loader's summary line.
    private static final int[] outcomes = new int[SyncReplayDriver.OUTCOMES.length];
    private static long ticksReplayed;
    private static double maxShift;
    private static long soundsHeld;
    private static long particlesHeld;

    /** The distance the last replayed correction moved the player (blocks). */
    private static double lastShift;

    private SyncReplay() {}

    // ---- the Loader ----

    /**
     * Turns the recording on for a connection with authoritative movement, or off. Either way the
     * ring starts empty.
     */
    public static void setActive(boolean on) {
        active = on;
        clear();
    }

    /** Whether the reconciliation is on. */
    public static boolean active() {
        return active;
    }

    /** The option: smooth the picture of a correction of a few centimetres or more (default). */
    public static void setSmooth(boolean on) {
        smooth = on;
    }

    /** Whether a replay is running right now (its side effects are being held back). */
    public static boolean replaying() {
        return stepping != null;
    }

    private static void clear() {
        RING.clear();
        owner = null;
        inputSeen = false;
        velocitySet = false;
        hits = 0;
        pendingSet = false;
        stepping = null;
    }

    /**
     * Records the tick that just ran as Tick {@code tick}: its input, the changes before it and the
     * state it ended in. Called right before the Tick is sent, after the player's update.
     *
     * @param replayable false for a tick the walking code did not run (riding, camera away)
     */
    public static void commit(int tick, boolean replayable) {
        if (!active) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP p = mc.thePlayer;
        if (p == null) {
            return;
        }
        if (p != owner) {
            // A new player entity (a Respawn): the history of the old one cannot be replayed on it.
            RING.clear();
            owner = p;
            pendingSet = false;
        }
        SyncRing.Entry e = RING.begin(tick);
        if (pendingSet) {
            e.copyChanges(PENDING);
        }
        pendingSet = false;
        velocitySet = false;
        hits = 0;
        MovementInput in = p.movementInput;
        if (inputSeen) {
            e.forward = inputForward;
            e.strafe = inputStrafe;
            e.jump = inputJump;
            e.sneak = inputSneak;
        } else if (in != null) {
            e.forward = in.moveForward;
            e.strafe = in.moveStrafe;
            e.jump = in.jump;
            e.sneak = in.sneak;
        }
        inputSeen = false;
        e.sprintKey = mc.gameSettings.keyBindSprint.isKeyDown();
        e.usingItem = p.isUsingItem();
        e.yaw = p.rotationYaw;
        e.pitch = p.rotationPitch;
        e.replayable = replayable && SyncReplayPlayer.walking(mc, p);
        SyncReplayPlayer.read(p, e.post);
    }

    /**
     * A Correction: restores its tick with it and runs the ticks after it again. On any outcome but
     * {@link SyncReplayDriver#OK} the player is left as it is.
     *
     * @return {@link SyncReplayDriver#OK} or the reason it was not replayed
     */
    public static int correct(SyncCorrection c) {
        if (!active) {
            return SyncReplayDriver.REFUSED;
        }
        int outcome = run(c);
        outcomes[outcome]++;
        return outcome;
    }

    private static int run(SyncCorrection c) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP p = mc.thePlayer;
        if (p == null || p != owner) {
            return SyncReplayDriver.TOO_OLD;
        }
        SyncRing.Entry newest = RING.newest();
        if (newest == null) {
            return SyncReplayDriver.TOO_OLD;
        }
        // What already changed the player after the newest tick (a velocity or a teleport handled
        // just before this message) goes back on after the replay.
        changesSince(newest.post, p, NOW);
        SyncReplayPlayer.read(p, BEFORE);
        boolean ready = PLAYER.begin();
        int outcome;
        try {
            outcome = SyncReplayDriver.replay(RING, c, ready ? PLAYER : NOT_READY);
        } finally {
            if (ready) {
                PLAYER.end();
            }
        }
        if (outcome != SyncReplayDriver.OK) {
            return outcome;
        }
        SyncReplayPlayer.applyChanges(p, NOW);
        SyncReplayPlayer.read(p, AFTER);
        ticksReplayed += newest.tick() - c.tick;
        lastShift = AFTER.distance(BEFORE);
        maxShift = Math.max(maxShift, lastShift);
        if (!smooth || lastShift < SMOOTH_MIN) {
            // The picture moves with the player at once: the interpolation of this tick starts as
            // far from the new position as it did from the old one.
            double dx = AFTER.x - BEFORE.x;
            double dy = AFTER.y - BEFORE.y;
            double dz = AFTER.z - BEFORE.z;
            p.lastTickPosX += dx;
            p.lastTickPosY += dy;
            p.lastTickPosZ += dz;
            p.prevPosX += dx;
            p.prevPosY += dy;
            p.prevPosZ += dz;
        }
        // Smoothed: the interpolation still starts where the old path was at the start of this
        // tick and ends on the new position, a lerp over the rest of the tick. Only what is drawn
        // follows it; the player's state is the corrected one already.
        return SyncReplayDriver.OK;
    }

    /** The state recorded at the end of Tick {@code tick} (not to be changed), or null. */
    public static SyncState recorded(int tick) {
        SyncRing.Entry e = RING.get(tick);
        return e == null ? null : e.post;
    }

    /** Reads the player's movement state now into {@code out} (unchanged without a player). */
    public static void read(SyncState out) {
        EntityPlayerSP p = Minecraft.getMinecraft().thePlayer;
        if (p != null) {
            SyncReplayPlayer.read(p, out);
        }
    }

    /**
     * Moves the player {@code dx} blocks along X at once, as a test of the reconciliation (a
     * divergence the proxy must correct). Called after the tick's movement, before it is recorded.
     */
    public static void nudge(double dx) {
        EntityPlayerSP p = Minecraft.getMinecraft().thePlayer;
        if (p != null) {
            p.setPosition(p.posX + dx, p.posY, p.posZ);
        }
    }

    /** The distance the last replayed correction moved the player (blocks). */
    public static double lastShift() {
        return lastShift;
    }

    /**
     * Works out what changed {@code p} since {@code prev} (the end of the last recorded tick) into
     * {@code out}'s between-ticks fields: a teleport, the server's velocity, the hits, any other
     * velocity change and a sprint change.
     */
    private static void changesSince(SyncState prev, EntityPlayerSP p, SyncRing.Entry out) {
        out.clearChanges();
        if (p.posX != prev.x || p.posY != prev.y || p.posZ != prev.z) {
            out.positionSet = true;
            SyncReplayPlayer.read(p, out.prePosition);
        }
        double mx = prev.motionX;
        double my = prev.motionY;
        double mz = prev.motionZ;
        if (velocitySet) {
            out.velocitySet = true;
            out.velocityX = mx = velocityX;
            out.velocityY = my = velocityY;
            out.velocityZ = mz = velocityZ;
        }
        boolean sprinting = prev.sprinting;
        out.hits = hits;
        for (int i = 0; i < hits; i++) {
            mx *= HIT_SLOWDOWN;
            mz *= HIT_SLOWDOWN;
            sprinting = false;
        }
        out.addX = p.motionX - mx;
        out.addY = p.motionY - my;
        out.addZ = p.motionZ - mz;
        if (p.isSprinting() != sprinting) {
            out.sprintSet = p.isSprinting() ? 1 : 0;
        }
    }

    private static final SyncReplayDriver.Stepper NOT_READY =
            new SyncReplayDriver.Stepper() {
                @Override
                public boolean ready() {
                    return false;
                }

                @Override
                public void restore(SyncState s, boolean sprintChanged) {}

                @Override
                public void changes(SyncRing.Entry e) {}

                @Override
                public void step(SyncRing.Entry e) {}

                @Override
                public void capture(SyncState out) {}
            };

    /** {@link SyncReplayPlayer}: the entry whose inputs the hooks give the game, or null. */
    static void stepping(SyncRing.Entry e) {
        stepping = e;
    }

    /**
     * One line with the counters, or null when no correction came: corrections by outcome, ticks
     * replayed, the largest shift and the sounds and particles held back.
     */
    public static String summary() {
        int total = 0;
        for (int n : outcomes) {
            total += n;
        }
        if (total == 0) {
            return null;
        }
        StringBuilder b = new StringBuilder("corrections ").append(total).append(" (");
        for (int i = 0; i < outcomes.length; i++) {
            if (outcomes[i] > 0) {
                b.append(SyncReplayDriver.OUTCOMES[i]).append(' ').append(outcomes[i]).append(", ");
            }
        }
        b.setLength(b.length() - 2);
        return b.append("), ticks replayed ")
                .append(ticksReplayed)
                .append(", largest shift ")
                .append(String.format("%.4f", maxShift))
                .append(" blocks, sounds held ")
                .append(soundsHeld)
                .append(", particles held ")
                .append(particlesHeld)
                .toString();
    }

    /** Clears the counters (the Loader, after logging them). */
    public static void resetCounters() {
        java.util.Arrays.fill(outcomes, 0);
        ticksReplayed = 0;
        maxShift = 0;
        soundsHeld = 0;
        particlesHeld = 0;
    }

    // ---- hooks (patcher/redirects-mods.tsv, "Stein Sync reconciliation") ----

    /**
     * Before the loaded-chunk check at the start of EntityPlayerSP.onUpdate: the player's tick is
     * about to run, and whatever changed it since the last recorded tick (the packets handled at
     * the start of the frame, the clicks of this tick) has happened.
     */
    public static void preTick() {
        if (!active || stepping != null) {
            return;
        }
        EntityPlayerSP p = Minecraft.getMinecraft().thePlayer;
        SyncRing.Entry newest = RING.newest();
        if (p == null || p != owner || newest == null) {
            velocitySet = false;
            hits = 0;
            return;
        }
        // Worked out again if the tick does not run (its column not loaded): the velocity and the
        // hits stay noted until a tick is recorded.
        changesSince(newest.post, p, PENDING);
        pendingSet = true;
    }

    /**
     * In place of MovementInput.updatePlayerMoveState in EntityPlayerSP.onLivingUpdate: in a replay
     * the tick's recorded input, otherwise the game's input, noted for the ring.
     */
    public static void moveState(Object input) {
        MovementInput in = (MovementInput) input;
        SyncRing.Entry e = stepping;
        if (e != null) {
            in.moveForward = e.forward;
            in.moveStrafe = e.strafe;
            in.jump = e.jump;
            in.sneak = e.sneak;
            return;
        }
        in.updatePlayerMoveState();
        if (active) {
            inputSeen = true;
            inputForward = in.moveForward;
            inputStrafe = in.moveStrafe;
            inputJump = in.jump;
            inputSneak = in.sneak;
        }
    }

    /**
     * In place of KeyBinding.isKeyDown in EntityPlayerSP.onLivingUpdate (the sprint key): in a
     * replay, whether it was held in the tick.
     */
    public static boolean sprintKey(Object key) {
        SyncRing.Entry e = stepping;
        if (e != null && key == Minecraft.getMinecraft().gameSettings.keyBindSprint) {
            return e.sprintKey;
        }
        return ((KeyBinding) key).isKeyDown();
    }

    /**
     * In place of World.playSound in EntityPlayerSP.playSound (the player's steps, swimming, splash
     * and fall): dropped in a replay, the tick already played them.
     */
    public static void playerSound(
            Object world,
            double x,
            double y,
            double z,
            Object name,
            float volume,
            float pitch,
            boolean delayed) {
        if (stepping != null) {
            soundsHeld++;
            return;
        }
        ((World) world).playSound(x, y, z, (String) name, volume, pitch, delayed);
    }

    /**
     * In place of World.spawnParticle in Entity.resetHeight (the splash into water): dropped in a
     * replay of the local player.
     */
    public static void splashParticle(
            Object world,
            Object type,
            double x,
            double y,
            double z,
            double dx,
            double dy,
            double dz,
            Object args) {
        if (stepping != null) {
            particlesHeld++;
            return;
        }
        ((World) world).spawnParticle((EnumParticleTypes) type, x, y, z, dx, dy, dz, (int[]) args);
    }

    /**
     * In place of Entity.setVelocity in NetHandlerPlayClient.handleEntityVelocity: the server's
     * velocity for the local player is noted, as a value set (not added), for the next tick.
     */
    public static void velocity(Object entity, double x, double y, double z) {
        Entity e = (Entity) entity;
        e.setVelocity(x, y, z);
        if (active && e == owner) {
            velocitySet = true;
            velocityX = e.motionX;
            velocityY = e.motionY;
            velocityZ = e.motionZ;
        }
    }

    /**
     * {@link Sprint#sprintOnHit}: the local player hit a player with knockback, which slows it to
     * 60% on X and Z and stops its sprint (the hit is in its tick's clicks, before its update).
     */
    static void hitSlowdown(Object player) {
        if (active && stepping == null && player == owner) {
            hits++;
        }
    }
}
