/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import java.lang.management.ManagementFactory;

/**
 * Checks of the Stein Sync reconciliation that need no game: the ring ({@link SyncRing}), the
 * replay order and its refusals ({@link SyncReplayDriver}) against a small model player, and the
 * correction's fields ({@link SyncCorrection}). The game's own tick under replay is checked in the
 * game ({@code -Dsteinbridge.diag.syncReplayCheck}).
 *
 * <p>Usage: {@code ./gradlew syncReplayTest} (part of {@code ./gradlew check}).
 */
public final class SyncReplayTest {

    private static int failures;

    public static void main(String[] args) {
        ringKeepsTheLastTicks();
        ringDropsHistoryOnAGap();
        ringRecordingAllocatesNothing();
        replayOfTheOwnStateIsExact();
        replayFollowsTheCorrection();
        replayKeepsTheChangesBetweenTicks();
        refusalsTouchNothing();
        correctionFields();
        if (failures > 0) {
            throw new AssertionError(failures + " SyncReplay checks failed");
        }
        System.out.println("SyncReplayTest: ok");
    }

    private static void check(String what, boolean ok) {
        if (!ok) {
            failures++;
            System.out.println("FAILED: " + what);
        }
    }

    // ---- a model player: one axis of the game's walking (friction, input, velocity changes) ----

    /** A player whose tick is x += motion, motion = (motion + input) * friction. */
    static final class Model implements SyncReplayDriver.Stepper {
        final SyncState s = new SyncState();
        boolean ready = true;
        int steps;

        void tick(SyncRing.Entry e) {
            changes(e);
            step(e);
        }

        @Override
        public boolean ready() {
            return ready;
        }

        @Override
        public void restore(SyncState from, boolean sprintChanged) {
            s.copyFrom(from);
        }

        @Override
        public void changes(SyncRing.Entry e) {
            if (e.positionSet) {
                s.placeAt(e.prePosition.x, e.prePosition.y, e.prePosition.z);
            }
            if (e.velocitySet) {
                s.motionX = e.velocityX;
            }
            for (int i = 0; i < e.hits; i++) {
                s.motionX *= SyncReplay.HIT_SLOWDOWN;
            }
            s.motionX += e.addX;
        }

        @Override
        public void step(SyncRing.Entry e) {
            steps++;
            s.motionX = (s.motionX + e.forward * 0.1) * 0.546;
            s.placeAt(s.x + s.motionX, s.y, s.z);
            s.onGround = true;
            s.jumpTicks = Math.max(0, s.jumpTicks - 1);
        }

        @Override
        public void capture(SyncState out) {
            out.copyFrom(s);
        }
    }

    /** Runs the model for ticks 1..n, recording each into the ring as the client does. */
    private static Model record(SyncRing ring, int n, int velocityAt) {
        Model m = new Model();
        m.s.placeAt(100.5, 64, -20.5);
        for (int t = 1; t <= n; t++) {
            SyncRing.Entry e = ring.begin(t);
            e.forward = t % 7 == 0 ? 0 : 1;
            e.replayable = true;
            if (t == velocityAt) {
                e.velocitySet = true;
                e.velocityX = 0.4;
                e.hits = 1;
            }
            m.tick(e);
            m.capture(e.post);
        }
        return m;
    }

    // ---- the ring ----

    private static void ringKeepsTheLastTicks() {
        SyncRing ring = new SyncRing();
        check("empty ring has no newest", ring.newest() == null && ring.last() == 0);
        int n = SyncRing.CAPACITY * 3 + 5;
        for (int t = 1; t <= n; t++) {
            ring.begin(t).post.x = t;
        }
        check("last is the newest number", ring.last() == n && ring.newest().tick() == n);
        check("the newest is kept", ring.get(n) != null && ring.get(n).post.x == n);
        int oldest = n - SyncRing.CAPACITY + 1;
        check("the oldest kept is CAPACITY back", ring.get(oldest) != null);
        check("one before it is gone", ring.get(oldest - 1) == null);
        check("a future tick is not there", ring.get(n + 1) == null);
        check("tick 0 is never there", ring.get(0) == null);
        boolean all = true;
        for (int t = oldest; t <= n; t++) {
            SyncRing.Entry e = ring.get(t);
            all &= e != null && e.tick() == t && e.post.x == t;
        }
        check("every kept tick holds its own state", all);
        ring.clear();
        check("cleared", ring.last() == 0 && ring.get(n) == null);
    }

    private static void ringDropsHistoryOnAGap() {
        SyncRing ring = new SyncRing();
        for (int t = 1; t <= 10; t++) {
            ring.begin(t);
        }
        ring.begin(12);
        check("a gap drops the older ticks", ring.get(10) == null && ring.get(12) != null);
        ring.begin(3);
        check("a smaller number starts over", ring.get(12) == null && ring.get(3) != null);
        SyncRing.Entry e = ring.begin(4);
        e.hits = 2;
        e.velocitySet = true;
        SyncRing.Entry again = ring.begin(5);
        check("a new entry starts without changes", !again.anyChange());
        try {
            ring.begin(0);
            check("tick 0 is refused", false);
        } catch (IllegalArgumentException expected) {
            // refused
        }
    }

    private static void ringRecordingAllocatesNothing() {
        SyncRing ring = new SyncRing();
        Model m = new Model();
        SyncRing.Entry pending = new SyncRing.Entry();
        // Warm up (class loading, JIT), then measure.
        for (int t = 1; t <= 20_000; t++) {
            recordOne(ring, m, pending, t);
        }
        com.sun.management.ThreadMXBean bean =
                (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().threadId();
        long before = bean.getThreadAllocatedBytes(tid);
        for (int t = 20_001; t <= 120_000; t++) {
            recordOne(ring, m, pending, t);
        }
        long allocated = bean.getThreadAllocatedBytes(tid) - before;
        // The measuring itself allocates a little; 100 000 ticks of a single object each would be
        // megabytes.
        check("recording 100000 ticks allocated " + allocated + " bytes", allocated < 4096);
    }

    private static void recordOne(SyncRing ring, Model m, SyncRing.Entry pending, int t) {
        SyncRing.Entry e = ring.begin(t);
        e.copyChanges(pending);
        e.forward = 1;
        e.replayable = true;
        m.tick(e);
        m.capture(e.post);
    }

    // ---- the replay ----

    private static void replayOfTheOwnStateIsExact() {
        SyncRing ring = new SyncRing();
        Model m = record(ring, 40, 25);
        SyncState[] original = copies(ring, 40);
        SyncCorrection c = new SyncCorrection();
        c.tick = 20;
        c.fields = 0;
        int outcome = SyncReplayDriver.replay(ring, c, m);
        check("own state replays", outcome == SyncReplayDriver.OK);
        boolean exact = true;
        for (int t = 21; t <= 40; t++) {
            exact &= same(ring.get(t).post, original[t]);
        }
        check("a replay from the own state gives back every tick exactly", exact);
        check("the player ends where it was", same(m.s, original[40]));
        check("one step per tick after the corrected one", m.steps == 40 + 20);
    }

    private static void replayFollowsTheCorrection() {
        SyncRing ring = new SyncRing();
        Model m = record(ring, 30, -1);
        SyncCorrection c = new SyncCorrection();
        c.tick = 22;
        c.fields = SyncCorrection.POSITION | SyncCorrection.MOTION;
        c.state.copyFrom(ring.get(22).post);
        c.state.x -= 0.5;
        c.state.motionX = 0;
        // The truth: the model run from the corrected state with the same inputs.
        Model truth = new Model();
        truth.s.copyFrom(ring.get(22).post);
        truth.s.placeAt(c.state.x, truth.s.y, truth.s.z);
        truth.s.motionX = 0;
        SyncState[] expected = new SyncState[31];
        for (int t = 23; t <= 30; t++) {
            truth.tick(ring.get(t));
            expected[t] = new SyncState();
            truth.capture(expected[t]);
        }
        int outcome = SyncReplayDriver.replay(ring, c, m);
        check("correction replays", outcome == SyncReplayDriver.OK);
        check("the corrected tick holds the correction", ring.get(22).post.x == c.state.x);
        boolean all = true;
        for (int t = 23; t <= 30; t++) {
            all &= same(ring.get(t).post, expected[t]);
        }
        check("every later tick is the correction run forward", all);
        check("the player is at the corrected path's end", same(m.s, expected[30]));
    }

    private static void replayKeepsTheChangesBetweenTicks() {
        SyncRing ring = new SyncRing();
        Model m = record(ring, 30, 26);
        SyncState[] original = copies(ring, 30);
        SyncCorrection c = new SyncCorrection();
        c.tick = 24;
        c.fields = SyncCorrection.MOTION;
        c.state.motionX = 0.25;
        int outcome = SyncReplayDriver.replay(ring, c, m);
        check("replays across a velocity change", outcome == SyncReplayDriver.OK);
        // The server set the velocity before tick 26: from there the history is what it was.
        check(
                "the velocity set before tick 26 wins over the correction's",
                ring.get(26).post.motionX == original[26].motionX);
        check(
                "tick 25 differs (it ran from the corrected motion)",
                !same(ring.get(25).post, original[25]));
    }

    private static void refusalsTouchNothing() {
        SyncRing ring = new SyncRing();
        Model m = record(ring, 200, -1);
        SyncState[] original = copies(ring, 200);
        SyncCorrection c = new SyncCorrection();
        c.fields = SyncCorrection.POSITION;
        c.state.placeAt(0, 0, 0);
        c.tick = 201;
        check("a future tick", SyncReplayDriver.replay(ring, c, m) == SyncReplayDriver.FUTURE);
        c.tick = 200 - SyncRing.CAPACITY;
        check(
                "a tick older than the ring",
                SyncReplayDriver.replay(ring, c, m) == SyncReplayDriver.TOO_OLD);
        c.tick = 190;
        ring.get(195).replayable = false;
        check(
                "a tick that cannot be run again",
                SyncReplayDriver.replay(ring, c, m) == SyncReplayDriver.NOT_REPLAYABLE);
        ring.get(195).replayable = true;
        m.ready = false;
        check(
                "a player that cannot be replayed",
                SyncReplayDriver.replay(ring, c, m) == SyncReplayDriver.REFUSED);
        boolean untouched = true;
        for (int t = 200 - SyncRing.CAPACITY + 1; t <= 200; t++) {
            untouched &= same(ring.get(t).post, original[t]);
        }
        check("a refused correction changes no tick", untouched);
        check("nor the player", same(m.s, original[200]));
        m.ready = true;
        c.tick = 200;
        check(
                "the newest tick replays nothing after it",
                SyncReplayDriver.replay(ring, c, m) == SyncReplayDriver.OK);
        check(
                "and puts the correction on the player",
                m.s.x == 0 && m.s.minX == -SyncState.HALF_WIDTH);
    }

    private static void correctionFields() {
        SyncState own = new SyncState();
        own.placeAt(10.3, 64, 5.7);
        own.minX = 10.000000000000002; // a box the game moved, not placed around the position
        own.motionX = 0.1;
        own.onGround = true;
        own.fallDistance = 2;
        own.jumpTicks = 4;
        own.sprinting = true;
        own.sprintingTicksLeft = 300;
        SyncCorrection c = new SyncCorrection();
        c.state.placeAt(10.3, 64, 5.7);
        c.state.motionX = -1;
        c.state.onGround = false;
        c.state.fallDistance = 0;
        c.state.jumpTicks = 0;
        c.state.sprinting = false;
        c.state.sprintingTicksLeft = 0;
        SyncState a = new SyncState();
        a.copyFrom(own);
        c.fields = SyncCorrection.POSITION;
        check("a position alone changes no sprint", !c.applyTo(a));
        check(
                "a position rebuilds the box as the game's setPosition does",
                a.minX == 10.3 - SyncState.HALF_WIDTH);
        c.state.placeAt(11, 65, 6);
        c.applyTo(a);
        check(
                "a new position places the box",
                a.x == 11 && a.minY == 65 && a.maxY == 65 + SyncState.HEIGHT);
        check("only the position", a.motionX == 0.1 && a.onGround && a.jumpTicks == 4);
        c.fields =
                SyncCorrection.MOTION
                        | SyncCorrection.GROUND
                        | SyncCorrection.FALL_DISTANCE
                        | SyncCorrection.JUMP_TICKS
                        | SyncCorrection.SPRINT
                        | SyncCorrection.SPRINT_WINDOW
                        | SyncCorrection.AIR_CONTROL;
        c.state.sprintToggleTimer = 5;
        c.state.jumpMovementFactor = 0.026F;
        check("turning the sprint off is a sprint change", c.applyTo(a));
        check(
                "every other field",
                a.motionX == -1
                        && !a.onGround
                        && a.fallDistance == 0
                        && a.jumpTicks == 0
                        && !a.sprinting
                        && a.sprintingTicksLeft == 0
                        && a.sprintToggleTimer == 5
                        && a.jumpMovementFactor == 0.026F);
        check("the same sprint again is no change", !c.applyTo(a));
    }

    // ---- helpers ----

    private static SyncState[] copies(SyncRing ring, int n) {
        SyncState[] out = new SyncState[n + 1];
        for (int t = 1; t <= n; t++) {
            SyncRing.Entry e = ring.get(t);
            if (e != null) {
                out[t] = new SyncState();
                out[t].copyFrom(e.post);
            }
        }
        return out;
    }

    private static boolean same(SyncState a, SyncState b) {
        return a.x == b.x
                && a.y == b.y
                && a.z == b.z
                && a.minX == b.minX
                && a.maxX == b.maxX
                && a.motionX == b.motionX
                && a.motionY == b.motionY
                && a.motionZ == b.motionZ
                && a.onGround == b.onGround
                && a.jumpTicks == b.jumpTicks
                && a.fallDistance == b.fallDistance
                && a.sprinting == b.sprinting;
    }
}
