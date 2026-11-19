/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.sync;

/**
 * Tells a Respawn of the Stein Sync proxy's own server from a server switch behind BungeeCord or
 * Velocity (stein-sync's docs/protocol.md section 5.9). A switch keeps the connection and its
 * NetHandlerPlayClient, so nothing else ends the Stein Sync mode when the next server has no Stein
 * Sync: without this the client would go on sending Ticks to a server that ignores them, and the
 * player could not move there.
 *
 * <p>The proxy sends a Still right behind every Respawn, and its Hello behind a new server's Join
 * Game (which a switch turns into Respawns); a switch to a server without Stein Sync brings
 * neither. The game builds a new player entity for each Respawn, so the entity the last Still or
 * Hello was read with is the confirmed one: while the current entity is another, the hooks send the
 * vanilla packets (a Stein Sync proxy drops those, for the moment until its Still is read), and
 * after {@link #DEADLINE_TICKS} game ticks without one the mode ends.
 *
 * <p>Identity only, game thread only, no allocation.
 */
final class SteinSyncStill {

    /**
     * Game ticks a new player entity may wait for its Still: the Still is right behind the Respawn
     * in the stream, so it is normally read in the same tick; a second covers a stalled stream.
     */
    static final int DEADLINE_TICKS = 40;

    /** The player entity the last Still or Hello was read with. */
    private Object confirmed;

    private int waited;

    /** A Hello or Still was read while {@code player} is the game's player entity. */
    void confirm(Object player) {
        confirmed = player;
        waited = 0;
    }

    /** May the Stein Sync packets go out for {@code player}? */
    boolean confirmed(Object player) {
        return player == confirmed;
    }

    /**
     * End of a game tick with {@code player} as the player entity (null between worlds, which is
     * not counted).
     *
     * @return true when the deadline passed without a Still: the mode must end
     */
    boolean tick(Object player) {
        if (player == confirmed) {
            waited = 0;
            return false;
        }
        if (player == null) {
            return false;
        }
        return ++waited >= DEADLINE_TICKS;
    }

    /** No Stein Sync mode: nothing is confirmed. */
    void clear() {
        confirmed = null;
        waited = 0;
    }
}
