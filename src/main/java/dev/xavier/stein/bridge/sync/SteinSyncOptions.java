/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.sync;

import dev.xavier.stein.bridge.core.Opt;

/**
 * The {@code -D} options of the Stein Sync client. Each one also answers to its {@code
 * steinloader.*} name, the one it had while the client lived in the Loader.
 */
final class SteinSyncOptions {

    private SteinSyncOptions() {}

    /**
     * {@code -Dsteinbridge.noSteinSync=true}: turns Stein Sync off on this client: no mark in the
     * server list, no marked login and no answer to a Stein Sync proxy's Hello, so every connection
     * stays in the vanilla mode; {@code -Dsteinloader.noSteinSync=true} does the same (default:
     * off, Stein Sync is on).
     */
    static final boolean NO_STEIN_SYNC = flag("noSteinSync");

    /**
     * {@code -Dsteinbridge.noQuic=true}: never joins over QUIC (stein-sync's docs/protocol.md
     * section 7); every connection is TCP, as before the transport existed (default: off, QUIC is
     * tried when a server offers it). The player's switch in the video settings does the same for
     * good.
     */
    static final boolean NO_QUIC = flag("noQuic");

    /**
     * {@code -Dsteinbridge.steinSyncSnap=true}: under a Stein Sync proxy's movement authority, a
     * correction moves the picture at once with the player (default: off, a correction of a few
     * centimetres or more is drawn as a short glide over the rest of the tick; the player's state
     * is corrected at once either way).
     */
    static final boolean STEIN_SYNC_SNAP = flag("steinSyncSnap");

    /**
     * {@code -Dsteinbridge.diag.syncNudge=D}: test, under a Stein Sync proxy's movement authority,
     * pushes the player D blocks along X once, after the movement of Tick {@code syncNudgeAtTick}:
     * a divergence the proxy must correct (default: 0, never).
     */
    static final double DIAG_SYNC_NUDGE =
            Opt.decimal("steinbridge.diag.syncNudge", Opt.decimal("steinloader.diag.syncNudge", 0));

    /**
     * {@code -Dsteinbridge.diag.syncNudgeAtTick=N}: the Tick of {@code syncNudge} (default 200: ten
     * seconds after the Accept).
     */
    static final int DIAG_SYNC_NUDGE_AT =
            Opt.integer(
                    "steinbridge.diag.syncNudgeAtTick",
                    Opt.integer("steinloader.diag.syncNudgeAtTick", 200));

    /**
     * {@code -Dsteinbridge.quicTransport=native|kwik}: forces the QUIC implementation a join over
     * QUIC uses (default: auto, the Bridge's native library, or Kwik in Java when the library
     * cannot be loaded or fails to start on this system).
     */
    static final String QUIC_TRANSPORT =
            Opt.text("steinbridge.quicTransport", Opt.text("steinloader.quicTransport", "auto"));

    /**
     * {@code -Dsteinbridge.quicRepeat=false}: the native QUIC transport stops sending each Tick
     * datagram a second time, 20 ms later, while the connection is losing packets (default: on; a
     * connection without loss never sends the copies).
     */
    static final boolean QUIC_REPEAT =
            Opt.flag("steinbridge.quicRepeat", Opt.flag("steinloader.quicRepeat", true));

    /**
     * {@code -Dsteinbridge.diag.quicRebindAtSec=N}: test, a QUIC connection moves to a new local
     * port after N seconds of Ticks, as an address change would (default: 0, never).
     */
    static final int DIAG_QUIC_REBIND_AT_SEC =
            Opt.integer(
                    "steinbridge.diag.quicRebindAtSec",
                    Opt.integer("steinloader.diag.quicRebindAtSec", 0));

    /**
     * {@code -Dsteinbridge.debugSteinSync=true}: logs the Stein Sync client messages as they go:
     * each attack put in a Tick, each BlockAction, and each block change held and released
     * (default: off).
     */
    static final boolean DEBUG_STEIN_SYNC = flag("debugSteinSync");

    /** A switch under its Bridge name or its old Loader name. */
    private static boolean flag(String name) {
        return Opt.flag("steinbridge." + name) || Opt.flag("steinloader." + name);
    }
}
