/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.diag;

import dev.xavier.stein.bridge.core.Log;
import dev.xavier.stein.bridge.engine.SyncCorrection;
import dev.xavier.stein.bridge.engine.SyncReplay;
import dev.xavier.stein.bridge.engine.SyncReplayDriver;
import dev.xavier.stein.bridge.engine.SyncState;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.BlockPos;
import net.minecraft.world.WorldServer;

import java.util.List;

/**
 * {@code -Dsteinbridge.diag.syncReplayCheck=D} (with {@code -Dsteinbridge.diag.singleplayer}): the
 * Stein Sync reconciliation ({@link SyncReplay}) checked in the game without a proxy. The check
 * builds a lane in the sky (stone, a water strip, a one-block step), sprints the player along it
 * with jumps, and feeds the replay corrections of its own:
 *
 * <ul>
 *   <li>identity corrections (a tick's own recorded state) every few ticks, across walking,
 *       jumping, landing, water and the step: each replay must give back every later tick exactly,
 *       bit for bit (the replay runs the game's own movement with the recorded inputs);
 *   <li>one injected divergence: the player is pushed D blocks along X after one tick's movement
 *       (what the proxy would see as a client off its path), and six ticks later corrected to the
 *       state it had before the push: the replay must land D behind where the player was, and the
 *       speed after it must be the speed before (no rubber band).
 * </ul>
 *
 * <p>The sounds and particles the replays held back are in the summary line. Lines start with
 * {@code SyncReplayCheck:}; the last says PASS or FAIL.
 */
public final class SyncReplayCheck {

    /** Whether the check runs (the property is set). */
    public static final boolean ON = DiagOptions.SYNC_REPLAY_CHECK != null;

    private static final double NUDGE = parse(DiagOptions.SYNC_REPLAY_CHECK);

    /** Game ticks in the world before the lane is built, and before the check starts. */
    private static final int SETTLE = 100;

    private static final int BUILT_WAIT = 60;

    private static final int NUDGE_AT = 60;
    private static final int NUDGE_FIX_AFTER = 6;
    private static final int IDENTITY_FIRST = 40;
    private static final int PERIODIC_FROM = 80;
    private static final int PERIODIC_TO = 300;
    private static final int PERIODIC_EVERY = 5;
    private static final int END = 310;

    // The lane, relative to the player's spawn position.
    private static final int LANE_Y = 150;
    private static final int LANE_LENGTH = 180;
    private static final int LANE_HALF_WIDTH = 3;
    private static final int WATER_FROM = 40;
    private static final int WATER_TO = 42;
    private static final int STEP_AT = 75;

    private static int worldTicks;
    private static int tick;
    private static boolean built;
    private static int builtAt;
    private static boolean done;

    private static int failures;
    private static int identities;
    private static double identityWorst;

    private static final SyncState BEFORE_NUDGE = new SyncState();
    private static final SyncState[] ORIGINAL = new SyncState[NUDGE_FIX_AFTER + 1];
    private static double speedBefore;
    private static final SyncCorrection CORRECTION = new SyncCorrection();
    private static final SyncState[] SAVED = new SyncState[PERIODIC_EVERY + 2];

    static {
        for (int i = 0; i < ORIGINAL.length; i++) {
            ORIGINAL[i] = new SyncState();
        }
        for (int i = 0; i < SAVED.length; i++) {
            SAVED[i] = new SyncState();
        }
    }

    private SyncReplayCheck() {}

    private static double parse(String s) {
        try {
            return s == null ? 0 : Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0.5;
        }
    }

    /** After the player's walk (hooks/GameHooksSync), every game tick. */
    public static void walked() {
        if (done) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP p = mc.thePlayer;
        if (p == null || mc.theWorld == null) {
            return;
        }
        try {
            if (!built) {
                if (++worldTicks == SETTLE) {
                    build(mc, p);
                }
                return;
            }
            if (tick == 0 && worldTicks++ < builtAt + BUILT_WAIT) {
                return;
            }
            if (tick > 0 && p.getHealth() <= 0.0F) {
                failures++;
                Log.info(
                        "diag",
                        "SyncReplayCheck: FAIL, the player died at "
                                + p.posX
                                + " "
                                + p.posY
                                + " "
                                + p.posZ);
                finish(mc);
                return;
            }
            if (tick == 0) {
                SyncReplay.setActive(true);
                SyncReplay.resetCounters();
                Log.info(
                        "diag",
                        "SyncReplayCheck: start at " + p.posX + " " + p.posY + " " + p.posZ);
            }
            step(mc, p, ++tick);
        } catch (Throwable t) {
            failures++;
            Log.info("diag", "SyncReplayCheck: FAIL, " + t);
            finish(mc);
        }
    }

    private static void step(Minecraft mc, EntityPlayerSP p, int t) {
        keys(mc.gameSettings, t);
        if (t % 50 == 0) {
            Log.info(
                    "diag",
                    "SyncReplayCheck: tick %d at %.3f %.3f %.3f, on ground %s, in water %s",
                    t,
                    p.posX,
                    p.posY,
                    p.posZ,
                    p.onGround,
                    p.isInWater());
        }
        if (t == NUDGE_AT) {
            SyncReplay.read(BEFORE_NUDGE);
            speedBefore = speed(SyncReplay.recorded(t - 1), BEFORE_NUDGE);
            SyncReplay.nudge(NUDGE);
            // The divergence is in the tick's recorded end state, as a client off its path sends.
            SyncReplay.commit(t, true);
            return;
        }
        SyncReplay.commit(t, true);
        if (t == IDENTITY_FIRST
                || (t >= PERIODIC_FROM && t <= PERIODIC_TO && t % PERIODIC_EVERY == 0)) {
            identity(t - PERIODIC_EVERY, t);
        } else if (t == NUDGE_AT + NUDGE_FIX_AFTER) {
            fixNudge(t);
        } else if (t > NUDGE_AT + NUDGE_FIX_AFTER && t <= NUDGE_AT + NUDGE_FIX_AFTER + 6) {
            double v = speed(SyncReplay.recorded(t - 1), SyncReplay.recorded(t));
            boolean ok = Math.abs(v - speedBefore) < 0.02;
            if (!ok) {
                failures++;
            }
            Log.info(
                    "diag",
                    "SyncReplayCheck: after the correction, tick %d speed %.4f (before the push"
                            + " %.4f) %s",
                    t,
                    v,
                    speedBefore,
                    ok ? "ok" : "RUBBER BAND");
        }
        if (t == END) {
            finish(mc);
        }
    }

    /** Corrects tick {@code from} with its own state: every later tick must come back the same. */
    private static void identity(int from, int t) {
        for (int k = from; k <= t; k++) {
            SAVED[k - from].copyFrom(SyncReplay.recorded(k));
        }
        CORRECTION.tick = from;
        CORRECTION.fields = 0;
        int outcome = SyncReplay.correct(CORRECTION);
        double worst = 0;
        for (int k = from + 1; k <= t; k++) {
            worst = Math.max(worst, differ(SAVED[k - from], SyncReplay.recorded(k)));
        }
        identities++;
        identityWorst = Math.max(identityWorst, worst);
        boolean ok = outcome == SyncReplayDriver.OK && worst == 0;
        if (!ok) {
            failures++;
        }
        if (!ok || t == IDENTITY_FIRST) {
            Log.info(
                    "diag",
                    "SyncReplayCheck: identity replay of ticks %d..%d (at %.3f %.3f %.3f): %s,"
                            + " largest difference %s %s",
                    from + 1,
                    t,
                    SAVED[t - from].x,
                    SAVED[t - from].y,
                    SAVED[t - from].z,
                    SyncReplayDriver.OUTCOMES[outcome],
                    worst,
                    ok ? "ok" : "NOT EXACT");
        }
    }

    /** Corrects the pushed tick to the state it had before the push. */
    private static void fixNudge(int t) {
        for (int k = NUDGE_AT + 1; k <= t; k++) {
            ORIGINAL[k - NUDGE_AT].copyFrom(SyncReplay.recorded(k));
        }
        CORRECTION.tick = NUDGE_AT;
        CORRECTION.fields = SyncCorrection.POSITION | SyncCorrection.MOTION | SyncCorrection.GROUND;
        CORRECTION.state.copyFrom(BEFORE_NUDGE);
        int outcome = SyncReplay.correct(CORRECTION);
        double worst = 0;
        for (int k = NUDGE_AT + 1; k <= t; k++) {
            SyncState o = ORIGINAL[k - NUDGE_AT];
            SyncState r = SyncReplay.recorded(k);
            worst =
                    Math.max(
                            worst,
                            Math.max(
                                    Math.abs(r.x - (o.x - NUDGE)),
                                    Math.max(Math.abs(r.y - o.y), Math.abs(r.z - o.z))));
        }
        boolean ok = outcome == SyncReplayDriver.OK && worst < 1e-6;
        if (!ok) {
            failures++;
        }
        Log.info(
                "diag",
                "SyncReplayCheck: push of %s blocks at tick %d corrected at tick %d: %s, shift %.6f,"
                        + " largest distance from the unpushed path %s %s",
                NUDGE,
                NUDGE_AT,
                t,
                SyncReplayDriver.OUTCOMES[outcome],
                SyncReplay.lastShift(),
                worst,
                ok ? "ok" : "OFF PATH");
    }

    private static void finish(Minecraft mc) {
        done = true;
        GameSettings g = mc.gameSettings;
        KeyBinding.setKeyBindState(g.keyBindForward.getKeyCode(), false);
        KeyBinding.setKeyBindState(g.keyBindSprint.getKeyCode(), false);
        KeyBinding.setKeyBindState(g.keyBindJump.getKeyCode(), false);
        Log.info("diag", "SyncReplayCheck: " + SyncReplay.summary());
        Log.info(
                "diag",
                "SyncReplayCheck: %d identity replays, largest difference %s",
                identities,
                identityWorst);
        Log.info(
                "diag", "SyncReplayCheck: " + (failures == 0 ? "PASS" : "FAIL (" + failures + ")"));
        SyncReplay.setActive(false);
    }

    /** Forward and sprint held all along; jump held in three stretches (landings, the step). */
    private static void keys(GameSettings g, int t) {
        KeyBinding.setKeyBindState(g.keyBindForward.getKeyCode(), true);
        KeyBinding.setKeyBindState(g.keyBindSprint.getKeyCode(), true);
        boolean jump = (t >= 25 && t <= 35) || (t >= 110 && t <= 130) || (t >= 200 && t <= 260);
        KeyBinding.setKeyBindState(g.keyBindJump.getKeyCode(), jump);
    }

    /** Builds the lane on the integrated server and puts the player at its start, facing +X. */
    private static void build(Minecraft mc, EntityPlayerSP p) {
        MinecraftServer server = mc.getIntegratedServer();
        if (server == null) {
            Log.info("diag", "SyncReplayCheck: FAIL, needs -Dsteinbridge.diag.singleplayer");
            done = true;
            return;
        }
        int x0 = (int) Math.floor(p.posX);
        int z0 = (int) Math.floor(p.posZ);
        server.addScheduledTask(
                (Runnable)
                        () -> {
                            WorldServer w = server.worldServers[0];
                            for (int dx = -2; dx <= LANE_LENGTH; dx++) {
                                for (int dz = -LANE_HALF_WIDTH; dz <= LANE_HALF_WIDTH; dz++) {
                                    boolean water =
                                            dx >= WATER_FROM
                                                    && dx <= WATER_TO
                                                    && Math.abs(dz) < LANE_HALF_WIDTH;
                                    w.setBlockState(
                                            new BlockPos(x0 + dx, LANE_Y, z0 + dz),
                                            water
                                                    ? Blocks.water.getDefaultState()
                                                    : Blocks.stone.getDefaultState());
                                    // A floor under the water, so the lane goes on past it.
                                    w.setBlockState(
                                            new BlockPos(x0 + dx, LANE_Y - 1, z0 + dz),
                                            Blocks.stone.getDefaultState());
                                    for (int dy = 1; dy <= 4; dy++) {
                                        boolean wall =
                                                Math.abs(dz) == LANE_HALF_WIDTH
                                                        || dx == -2
                                                        || dx == LANE_LENGTH;
                                        boolean step = dx == STEP_AT && dy == 1;
                                        w.setBlockState(
                                                new BlockPos(x0 + dx, LANE_Y + dy, z0 + dz),
                                                step || wall
                                                        ? Blocks.stone.getDefaultState()
                                                        : Blocks.air.getDefaultState());
                                    }
                                }
                            }
                            List<EntityPlayerMP> players =
                                    server.getConfigurationManager().playerEntityList;
                            for (EntityPlayerMP mp : players) {
                                mp.playerNetServerHandler.setPlayerLocation(
                                        x0 + 0.5, LANE_Y + 1, z0 + 0.5, -90F, 0F);
                            }
                        });
        built = true;
        builtAt = worldTicks;
        Log.info("diag", "SyncReplayCheck: lane built at x " + x0 + ", y " + LANE_Y + ", z " + z0);
    }

    private static double speed(SyncState a, SyncState b) {
        double dx = b.x - a.x;
        double dz = b.z - a.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static double differ(SyncState a, SyncState b) {
        double d =
                Math.max(
                        Math.abs(a.x - b.x),
                        Math.max(
                                Math.abs(a.y - b.y),
                                Math.max(
                                        Math.abs(a.z - b.z),
                                        Math.max(
                                                Math.abs(a.motionX - b.motionX),
                                                Math.max(
                                                        Math.abs(a.motionY - b.motionY),
                                                        Math.abs(a.motionZ - b.motionZ))))));
        if (a.onGround != b.onGround
                || a.sprinting != b.sprinting
                || a.inWater != b.inWater
                || a.jumpTicks != b.jumpTicks
                || a.fallDistance != b.fallDistance
                || a.collidedHorizontally != b.collidedHorizontally) {
            d = Math.max(d, 1);
        }
        return d;
    }
}
