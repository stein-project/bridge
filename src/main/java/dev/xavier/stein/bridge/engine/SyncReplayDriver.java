/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

/**
 * The order of a replay, apart from the game: which corrections can be replayed, and the restore,
 * between-ticks changes, step and capture of each tick. {@link SyncReplay} gives it the game's
 * player as a {@link Stepper}; the tests give it a model. No allocation.
 */
public final class SyncReplayDriver {

    /** Replayed. */
    public static final int OK = 0;

    /** The corrected tick is not recorded any more (older than the ring), or never was. */
    public static final int TOO_OLD = 1;

    /** The corrected tick is newer than the last tick the client ran. */
    public static final int FUTURE = 2;

    /**
     * A tick between the corrected one and now did not run with the walking code (riding, flying,
     * spectating, camera away): the game's own movement cannot run it again.
     */
    public static final int NOT_REPLAYABLE = 3;

    /** The player cannot be replayed right now (the Stepper said so). */
    public static final int REFUSED = 4;

    /** Names of the outcomes, for the logs and the counters. */
    public static final String[] OUTCOMES = {
        "replayed", "too old", "future", "not replayable", "refused"
    };

    private SyncReplayDriver() {}

    /** The player the replay runs on. */
    public interface Stepper {

        /** Whether the player can be replayed now at all. */
        boolean ready();

        /**
         * Puts {@code s} on the player.
         *
         * @param sprintChanged the correction changed the sprint state: the ground speed of the
         *     next tick comes from the movement speed with the new sprint modifier, not from {@code
         *     s}
         */
        void restore(SyncState s, boolean sprintChanged);

        /** Applies what changed the player between the previous tick and {@code e}'s tick. */
        void changes(SyncRing.Entry e);

        /** Runs one tick with {@code e}'s inputs, without its side effects. */
        void step(SyncRing.Entry e);

        /** Reads the player's state into {@code out}. */
        void capture(SyncState out);
    }

    /**
     * Restores the state of {@code c.tick} with the correction applied, then runs every recorded
     * tick after it again, rewriting each one's state in the ring (so a later correction replays
     * from the corrected history). When the outcome is not {@link #OK} nothing is touched.
     *
     * @return {@link #OK} or the reason it was not replayed
     */
    public static int replay(SyncRing ring, SyncCorrection c, Stepper player) {
        int last = ring.last();
        if (c.tick > last) {
            return FUTURE;
        }
        SyncRing.Entry corrected = ring.get(c.tick);
        if (corrected == null) {
            return TOO_OLD;
        }
        for (int k = c.tick + 1; k <= last; k++) {
            SyncRing.Entry e = ring.get(k);
            if (e == null) {
                return TOO_OLD;
            }
            if (!e.replayable) {
                return NOT_REPLAYABLE;
            }
        }
        if (!player.ready()) {
            return REFUSED;
        }
        boolean sprintChanged = c.applyTo(corrected.post);
        player.restore(corrected.post, sprintChanged);
        for (int k = c.tick + 1; k <= last; k++) {
            SyncRing.Entry e = ring.get(k);
            player.changes(e);
            player.step(e);
            player.capture(e.post);
        }
        return OK;
    }
}
