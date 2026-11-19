/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.sync;

/**
 * How a connection leaves ({@link SteinSyncQuic}): through a mod's route, direct, or over QUIC when
 * the server offers it. A mod that registered a route (the Connect mod always does) diverts a
 * connection only when its route gives an address for it (its SOCKS on); otherwise the join may
 * still go over QUIC. Plain values, tested without the game.
 */
final class SteinSyncRoute {

    /** The game's own TCP connection, untouched (a server list ping, or no join to set up). */
    static final int DIRECT = 0;

    /** Through the mod's route, over TCP. */
    static final int MOD = 1;

    /** A join: QUIC is tried when the server offers it, TCP otherwise. */
    static final int QUIC = 2;

    private SteinSyncRoute() {}

    /**
     * @param modAddress the address the mod's route gave for this connection, or null (no route, or
     *     it lets the connection go direct)
     * @param join whether the connection is a join set up for QUIC (not a server list ping)
     */
    static int choose(Object modAddress, boolean join) {
        if (modAddress != null) {
            return MOD;
        }
        return join ? QUIC : DIRECT;
    }
}
